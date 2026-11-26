# Video encoder buffer diagnostics

The video path reports two separate YUV input starvation counters:

- `encoderNoReady`: no imported encoder buffer was ready when a camera frame reached the video path.
- `encoderNoImage`: all six GPU output images were still in flight.

They are included in `videoStats()` and are also logged as `VIDEO_YUV_ACQUIRE_DROP` with `reason=noReady` or `reason=noImage`. The drop log now includes `frameOrdinal` and `timestampNs`, so it can be matched to the Camera2 frame and the file-side `encodedFrameGaps` statistic.

`videoStats()` also reports `encoderNoBuffer`, `encoderAcquires`, `encoderDequeues`, `encoderQueued`, `encoderDequeueMs`, `encoderDequeueMaxMs`, `encoderFenceMs` and `encoderFenceMaxMs`. These distinguish `ImageWriter`/encoder starvation from GPU fence latency.

On the validated Xiaomi 15 (`ro.product.device=dada`), a short acquire wait can be enabled for diagnosis:

```sh
adb shell su -c 'setprop debug.rawr.video_acquire_wait_ms 2'
```

The value is capped at 5 ms and is ignored on every other device. The default is zero, so the normal path remains non-blocking. Clear it with:

```sh
adb shell su -c 'setprop debug.rawr.video_acquire_wait_ms 0'
```

The YUV feeder keeps four encoder buffers dequeued ahead by default on every device. On `dada` only, `debug.rawr.video_yuv_ready=6` opts into six at the next recording start; unset, `0`, `4` or invalid values keep four. The writer limit remains 16. Four is the validated default: the A/B below found no drop reduction from six at 4K24, and six moved the startup loss inside the file instead of ahead of it.

For a controlled 24 fps run, collect:

```sh
adb logcat -c
adb logcat -v threadtime | grep -E 'VIDEO_YUV_ACQUIRE_DROP|VIDEO_YUV_FLOW|VIDEO_YUV_STATS|RAW_READER_STATS'
```

On Windows PowerShell, replace `grep -E` with `Select-String -Pattern`.

Interpretation:

- `encoderNoReady > 0` points to the Java `ImageWriter` feeder or encoder buffer availability.
- `encoderNoImage > 0` points to GPU output images remaining in flight.
- high `encoderDequeueMaxMs` points to the encoder holding its buffers; high `encoderFenceMaxMs` points to GPU completion latency.
- both zero with a file-side PTS gap does not locate the loss: inspect RAW sensor timestamps, ingress/pairer/slot losses, `present()` and encoder output. `encodedFrameGaps` counts gaps inferred from encoder timestamps, not proven missing camera frames.

## Device observation, 2026-11-25

Build `debug-9479b521` (PR head `5f263cd`), Xiaomi 15 `dada`, 23 mm Normal, 1920x1080, 24 fps. This build still used six ready buffers globally; the new opt-in pool policy is not in that installed APK.

| Wait | Encoded frames | First-to-last PTS span | Final encodedFrameGaps | Max sampled dropped/noReady/noImage |
|---|---:|---:|---:|---|
| 0 ms | 890 | 36.996624 s | 0 | 0/0/0 |
| 2 ms | 647 | 27.066362 s | 5 | 0/0/0 |

Both journals ended with `ok=true` and `published`. Final YUV log for the second run reported zero buffer drops. Its RAW reader log at 13:53:15.100 reported `sensorGaps=1` and `longestMs=234.049`. This is evidence against assuming all gaps originate in YUV buffer acquisition. The runs had unequal durations, were sequential, and were not thermally/scene controlled; do not infer a benefit or regression caused by the wait.

The wait property was disabled (`0`) after testing, and the debug app was stopped. No release app settings were modified.

Timing caveats: dequeue timing includes buffer import, not just ImageWriter blocking. Fence timing is the CPU duration of each wait call, not total GPU execution time. The revised mean divides by wait calls, including timeouts. Timing writers and snapshots now use the same mutex; the earlier APK did not fully synchronize these metrics.

## `sensorGaps` semantics

`RAW_READER_STATS` reports `sensorGaps` for a rolling window, and the comparison interval is the *shortest* frame interval observed in that window (`RawFrameIngress::recordReaderStats`, `RawFrameIngress.cpp:188`, field defined in `RawFrameIngress.h:54`). The counter therefore does not mean "frames the sensor did not deliver" in absolute terms.

This matters at mode changes. A window that covers the last Photo preview frames (96 fps, `frameMs=10.44`) and the first Video frames (24 fps, `frameMs=41.62`) measures every video frame against the 10.44 ms minimum, so all of them count as late: the 2026-11-25 Photo to Video transition reported `sensorGaps=119` with `longestMs=42.024` in a window where nothing was actually lost. The same run reported `sensorGaps=1` with `longestMs=228.845` and `244.946` in the following pure-video windows, which is the startup gap described below.

Rules of thumb:

- read `sensorGaps` only inside a window that stays on a single sensor mode;
- compare windows of the same mode, never the window that spans the transition;
- a value close to `windowDuration / frameMs` is a sign of a mixed-mode window, not of lost frames.

## A/B: `debug.rawr.video_yuv_ready` 4 vs 6, 2026-11-25

Same session, same scene, Xiaomi 15 `dada`, 23 mm Open Gate, 4096x3072, 24 fps, nominal 25 s per clip. The property is read at every `startYuv()` (`VideoOutput.cpp:465-466`), so the second clip needed no app restart. Final state: `debug.rawr.video_yuv_ready=4`, `debug.rawr.video_acquire_wait_ms=0`.

| Metric | target 4 | target 6 |
|---|---:|---:|
| `VIDEO_YUV_STATS buffers` | 8 | 10 |
| `VIDEO_YUV_FLOW` | `ready=4 outstanding=4` | `ready=6 outstanding=6` |
| `noReady/noImage/noBuffer` | 0/0/0 | 0/0/0 |
| `encodedFrames` | 832 | 783 |
| `encodedFrameGaps` | 0 | 5 |
| `targetFrameShortfall` | 7 | 11 |
| `currentFps` | 23.800381 | 23.800381 |
| first-to-last PTS span | 34.922 s | 33.055 s |
| `encoderLatencyMs` mean/max | 177.06/245.36 | 170.36/239.68 |
| `dequeueMax`/`fenceMax` ms | 5.81/4.57 | 7.58/5.55 |
| submitted/dropped/images | 811/0/6 | 762/0/6 |
| published | yes | yes |

Both runs recorded zero YUV acquisition drops, so the feeder was not starving in either configuration, and the frame rate was identical. Six buffers only add work at the start (higher dequeue and fence maxima) and they do not remove the startup loss: they relocate it. With four, the loss sits ahead of the first encoded frame and the file is simply shorter; with six, six frames are lost inside the file as `encodedFrameGaps=5`, which is a visible freeze of about 208 ms. Neither number proves a missing camera frame; `encodedFrameGaps` is inferred from encoder timestamps.

The startup loss measured separately is about 245 ms and starts before `CAMERA_RECORDING_FPS`, i.e. at the shutter tap, not in the YUV feeder. A four-buffer target with a non-blocking acquire is therefore the configuration to keep, and the remaining work belongs in the window between the tap and the first video frame.

Caveat: one clip per configuration, sequential, not thermally or scene controlled. The conclusion rests on identical frame rates and identical zero-drop counters, plus the placement of the loss in the file.
