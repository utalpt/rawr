#include <cassert>
#include <iostream>

#include "video/VideoAcquirePolicy.h"

using rawrcam::video::videoAcquireWaitMs;

int main() {
    assert(videoAcquireWaitMs("dada", "") == 0);
    assert(videoAcquireWaitMs("dada", "0") == 0);
    assert(videoAcquireWaitMs("dada", "2") == 2);
    assert(videoAcquireWaitMs("dada", "5") == 5);
    assert(videoAcquireWaitMs("dada", "6") == 5);
    assert(videoAcquireWaitMs("dada", "999") == 5);
    assert(videoAcquireWaitMs("dada", "-1") == 0);
    assert(videoAcquireWaitMs("dada", "abc") == 0);
    assert(videoAcquireWaitMs("other", "2") == 0);
    assert(videoAcquireWaitMs("dada", "6x") == 0);
    assert(videoAcquireWaitMs("dada", "999x") == 0);
    assert(videoAcquireWaitMs("dada", " 2") == 0);
    assert(videoAcquireWaitMs("dada", "+2") == 0);
    assert(videoAcquireWaitMs("dada", "0002") == 2);
    assert(videoAcquireWaitMs("dada", "99999999999999999999999999999") == 5);
    for (const char* device : {"", "other", "dada_eea", "DADA"}) {
        assert(videoAcquireWaitMs(device, "2") == 0);
        assert(rawrcam::video::videoReadyBufferCount(device, "6") == 4);
    }
    assert(rawrcam::video::videoReadyBufferCount("dada", "6") == 6);
    for (const char* setting : {"", "0", "4", "7", "6x"})
        assert(rawrcam::video::videoReadyBufferCount("dada", setting) == 4);
    std::cout << "video_acquire_policy_test: ok\n";
    return 0;
}
