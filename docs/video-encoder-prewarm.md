# HEVC encoder prewarm

A video recording pays its encoder setup at shutter press. On Xiaomi 15 (`dada`), 4K24
Open Gate: the video start marker is at 20.139 s, the platform HEVC encoder is
allocated at 20.164, its input Surface is up at 20.231, and the first frame reaches
`VIDEO_FAST_PATH` at 20.616. About 70 ms of those ~480 ms are the codec itself: create,
configure, input Surface. Everything before the marker (camera reconfigure) and
everything after it (Vulkan surface chain, YUV buffers) belongs to `VideoSession`'s
existing prewarm, which is measured separately.

This prewarm prepares one HEVC encoder ahead of the next recording, in Video mode,
while the user is still framing the shot. At shutter press `NativeAvRecorder`
**takes** the prepared codec instead of building one; otherwise it follows the
unchanged path and reports why.

## What it covers

- `AMediaCodec_createEncoderByType("video/hevc")` and its implementation check
  (software encoders are still rejected).
- `AMediaCodec_configure` with the recording's own format, including the realtime
  hints and the fallback without them on the same instance.
- `AMediaCodec_createInputSurface`.

`AMediaCodec_start` is **not** part of the warm: it runs in `take()`, because a codec
that was never started is freed without waiting for a surface drain, and because the
startup cost of each step has to stay measurable. `VIDEO_ENCODER_PREWARM reused`
reports both the entry's age and the start it paid.

Not covered: the Vulkan surface chain, YUV buffer allocation and the recording session
itself (that is `VideoSession`'s prewarm), the muxer, and the audio input.

## Gate

On by default where it was measured (`ro.product.device=dada`), off everywhere else until
that device repeats the A/B. The property overrides the default both ways, so it doubles
as the kill switch in the field:

```sh
adb shell setprop debug.rawr.video_encoder_prewarm 1     # force on
adb shell setprop debug.rawr.video_encoder_prewarm 0     # kill switch
adb shell setprop debug.rawr.video_encoder_prewarm ''    # back to the device default
```

`1`/`true`/`on` and `0`/`false`/`off` are the recognized values; anything else (including
unset) falls back to the device default. The property is read on every call, so it can be
flipped between recordings without restarting the app — but only the log line changes
immediately: with the gate off, an existing entry is dropped on the next
`take()`/`release()`, and the recorder reports `"encoderPrewarm":"off"` in its stats.

## How it behaves

- **One slot.** A prepared encoder holds a hardware session; two 4K HEVC instances
  compete for the same encoder and slow the one producing frames. The pool never
  builds a second one.
- **Exact spec match.** A pooled codec is only reused for the same frame window,
  frame rate, bitrate, GOP, rate-control mode, B-frame setting and bit depth. Any
  change (lens, aspect, resolution, fps, settings) rebuilds it, which is why the
  prewarm resolves the record window from the camera geometry exactly as the
  recording does.
- **90 s TTL.** An entry older than that is discarded rather than handed over: the
  device may have reclaimed the session, and the user may have moved on.
- **Never during a recording.** While a recording owns an encoder nothing is warmed.
- **Waits for the record window.** Open Gate sizes the encoder from the RAW geometry in the
  camera snapshot. A session that boots straight into Video has not published it yet, so
  the prewarm polls the snapshot (250 ms, up to ~6 s) instead of leaving the first take
  cold; leaving Video cancels the wait together with the pool.
- **Refill after a stop.** A successful recording posts a new warm for its own
  settings, so a back-to-back take reuses the encoder instead of paying setup again.
- **Released on leaving Video mode**, together with the rest of the prewarmed
  recording pipeline.

Diagnostics (`adb logcat -s RawrNativeVideo`, plus `video_jobs` journals):

```
VIDEO_ENCODER_PREWARM ready buildMs=… codec=… spec=…
VIDEO_ENCODER_PREWARM reused ageMs=… startMs=… codec=… spec=…
VIDEO_ENCODER_PREWARM miss reason=empty|mismatch|expired spec=…
VIDEO_ENCODER_PREWARM discard reason=mismatch|expired codec=… pooled=…
VIDEO_ENCODER_PREWARM failed reason=unavailable|configure|surface|exception|start …
VIDEO_ENCODER_PREWARM skip reason=recording spec=…
VIDEO_ENCODER_PREWARM release ageMs=… codec=…
```

The recorder's journal and stats carry `"encoderPrewarm":"reused"|"missed"|"off"`.

## Validation

The A/B is a pair of 4K24 Open Gate clips from the same session, gate off and on:
`encoderPrewarm` in the journal stats, plus the usual startup evidence
(`VIDEO_YUV_STATS`, `VIDEO_YUV_FLOW`, `currentFps`, `encodedFrameGaps`, drop counts)
unchanged. The prewarm is only worth keeping if `reused` appears, `startMs` is small,
and the first-frame time or drop counts improve; otherwise it goes back behind the gate
(`setprop debug.rawr.video_encoder_prewarm 0`).

## Dependency

The policy (`VideoEncoderPrewarmPolicy.h`, gate included) is host-tested by
`tests/native/video_encoder_prewarm_policy_test.cpp`. If PR #7 ("Video: diagnose and
cushion YUV encoder drops") is not merged yet, this change stacks on its branch: both
touch the video startup path and the host-test registration in `tests/CMakeLists.txt`.
