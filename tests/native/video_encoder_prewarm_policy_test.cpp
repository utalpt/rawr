#include <cassert>
#include <iostream>
#include <string>

#include "video/VideoEncoderPrewarmPolicy.h"

namespace {
using rawrcam::video::hevcSpecText;
using rawrcam::video::hevcSpecUsable;
using rawrcam::video::HevcVideoEncoderSpec;
using rawrcam::video::kHevcPrewarmTtlNs;
using rawrcam::video::PreparedEncoder;
using rawrcam::video::PrewarmTake;
using rawrcam::video::prewarmEnabled;
using rawrcam::video::prewarmNeeded;
using rawrcam::video::prewarmTake;
using rawrcam::video::prewarmTakeText;

// 4K24 10-bit, the setting the prewarm was validated against.
HevcVideoEncoderSpec openGate() {
    HevcVideoEncoderSpec spec;
    spec.width = 4096;
    spec.height = 3072;
    spec.fps = 24;
    spec.bitrate = 120'000'000;
    spec.intraFrames = 12;
    spec.bitrateMode = 2;
    spec.maxBFrames = -1;
    spec.bitDepth = 10;
    return spec;
}

PreparedEncoder prepared(const HevcVideoEncoderSpec& spec, int64_t ageNs) {
    PreparedEncoder entry;
    entry.present = true;
    entry.spec = spec;
    entry.ageNs = ageNs;
    return entry;
}

void specValidation() {
    const HevcVideoEncoderSpec spec = openGate();
    assert(hevcSpecUsable(spec));

    // Everything the recorder's own constructor refuses must be refused here too,
    // otherwise a warm would prepare a codec the recording then rejects.
    HevcVideoEncoderSpec bad = spec;
    bad.width = 0;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.height = -2;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.width = 4095;  // odd dimensions cannot be encoded.
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.fps = 0;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.fps = 241;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.bitrate = 999'999;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.bitrate = 400'000'001;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.intraFrames = 0;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.intraFrames = 481;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.bitrateMode = 3;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.maxBFrames = 5;
    assert(!hevcSpecUsable(bad));
    bad = spec;
    bad.bitDepth = 9;
    assert(!hevcSpecUsable(bad));
    // -1 means "leave it to the codec" for both optional keys.
    bad = spec;
    bad.bitrateMode = -1;
    bad.maxBFrames = -1;
    assert(hevcSpecUsable(bad));
}

void specIdentity() {
    const HevcVideoEncoderSpec base = openGate();
    assert(base == openGate());

    // A prepared encoder is only reusable for exactly the same input: every field
    // that reaches AMediaCodec_configure has to match.
    HevcVideoEncoderSpec changed = base;
    changed.width = 3840;
    assert(changed != base);
    changed = base;
    changed.height = 2160;
    assert(changed != base);
    changed = base;
    changed.fps = 30;
    assert(changed != base);
    changed = base;
    changed.bitrate = 100'000'000;
    assert(changed != base);
    changed = base;
    changed.intraFrames = 1;  // all-intra
    assert(changed != base);
    changed = base;
    changed.bitrateMode = 1;  // VBR
    assert(changed != base);
    changed = base;
    changed.maxBFrames = 0;
    assert(changed != base);
    changed = base;
    changed.bitDepth = 8;  // Main, not Main10
    assert(changed != base);
}

void takeDecisions() {
    const HevcVideoEncoderSpec spec = openGate();

    const PreparedEncoder none;
    assert(prewarmTake(spec, none) == PrewarmTake::Empty);

    // A different record window cannot reuse the pooled encoder.
    HevcVideoEncoderSpec other = spec;
    other.width = 3840;
    other.height = 2160;
    assert(prewarmTake(spec, prepared(other, 0)) == PrewarmTake::Mismatch);
    other = spec;
    other.bitDepth = 8;
    assert(prewarmTake(spec, prepared(other, 0)) == PrewarmTake::Mismatch);

    // Fresh enough, and stale from the TTL on.
    assert(prewarmTake(spec, prepared(spec, 0)) == PrewarmTake::Reused);
    assert(prewarmTake(spec, prepared(spec, kHevcPrewarmTtlNs - 1)) == PrewarmTake::Reused);
    assert(prewarmTake(spec, prepared(spec, kHevcPrewarmTtlNs)) == PrewarmTake::Expired);
    assert(prewarmTake(spec, prepared(spec, kHevcPrewarmTtlNs * 2)) == PrewarmTake::Expired);

    // Expiry wins over a matching spec: an old codec may hold a reclaimed session.
    assert(prewarmTake(spec, prepared(spec, kHevcPrewarmTtlNs)) != PrewarmTake::Reused);

    assert(std::string(prewarmTakeText(PrewarmTake::Reused)) == "reused");
    assert(std::string(prewarmTakeText(PrewarmTake::Empty)) == "empty");
    assert(std::string(prewarmTakeText(PrewarmTake::Mismatch)) == "mismatch");
    assert(std::string(prewarmTakeText(PrewarmTake::Expired)) == "expired");
}

void warmDecisions() {
    const HevcVideoEncoderSpec spec = openGate();

    // Nothing pooled: warm.
    const PreparedEncoder none;
    assert(prewarmNeeded(spec, false, none));

    // Already ready for this recording: do not rebuild.
    assert(!prewarmNeeded(spec, false, prepared(spec, 0)));
    assert(!prewarmNeeded(spec, false, prepared(spec, kHevcPrewarmTtlNs - 1)));

    // Settings changed between clips: rebuild for the new input.
    HevcVideoEncoderSpec other = spec;
    other.fps = 30;
    assert(prewarmNeeded(spec, false, prepared(other, 0)));

    // Stale entry: rebuild.
    assert(prewarmNeeded(spec, false, prepared(spec, kHevcPrewarmTtlNs)));

    // Never while a recording owns an encoder: a second 4K session would compete
    // with the one producing frames.
    assert(!prewarmNeeded(spec, true, none));
    assert(!prewarmNeeded(spec, true, prepared(other, 0)));
    assert(!prewarmNeeded(spec, true, prepared(spec, kHevcPrewarmTtlNs)));

    // An invalid spec is never prepared; the recorder reports the real error instead.
    HevcVideoEncoderSpec bad = spec;
    bad.width = 0;
    assert(!prewarmNeeded(bad, false, none));
}

void specText() {
    const std::string text = hevcSpecText(openGate());
    assert(text.find("4096x3072@24f") != std::string::npos);
    assert(text.find("120Mbps") != std::string::npos);
    assert(text.find("depth10") != std::string::npos);
    // The log line still has to fit one logcat entry next to the codec name.
    assert(text.size() < 96);
}

void gateDecisions() {
    // On where the A/B was run, off everywhere else: the pool holds a hardware session
    // and an unmeasured device has no evidence it is worth it.
    assert(prewarmEnabled("", "dada"));
    assert(!prewarmEnabled("", "blazer"));

    // The property overrides the default both ways: kill switch in the field, and the
    // way to trial the pool on the next device.
    assert(!prewarmEnabled("0", "dada"));
    assert(prewarmEnabled("1", "blazer"));
    assert(prewarmEnabled("true", "blazer"));
    assert(!prewarmEnabled("false", "dada"));

    // An unrecognized value never flips it on somewhere unmeasured.
    assert(!prewarmEnabled("yes", "blazer"));
    assert(prewarmEnabled("yes", "dada"));
}
}  // namespace

int main() {
    specValidation();
    specIdentity();
    takeDecisions();
    warmDecisions();
    gateDecisions();
    specText();
    std::cout << "VIDEO_ENCODER_PREWARM_POLICY_PASS\n";
}
