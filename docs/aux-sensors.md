# Auxiliary sensors: light, flicker, ToF, barometer, compass, gyro log

Some phones put a colour/flicker sensor beside the rear cameras. The stock camera uses it for white
balance and anti-banding; Camera2 apps never see it. On the Xiaomi 15 (HyperOS, Android 16) it is an
ams **TCS3408**, plus a Visionics **VI5300** time-of-flight sensor for laser AF. Both are exposed
through `SensorManager` as vendor sensor types with no required permission (`perm: n/a` in
`dumpsys sensorservice`), so any app can read them.

Rawr reads them in `AuxSensors` (found by string type; phones without them report nothing), together with
two standard sensors many phones have, the barometer (`TYPE_PRESSURE`) and the rotation vector
(`TYPE_ROTATION_VECTOR`, the compass). They are used for:

- **Anti-flicker, Auto**: when the flicker sensor confirms 100 Hz or 120 Hz flicker, AE antibanding is
  set to 50 Hz or 60 Hz; otherwise it stays on the camera's own AUTO. The explicit 50 Hz / 60 Hz / Off
  choices are unchanged.
- **Light meter** (Settings > Display & Control > Light meter): a viewfinder pill with lux, incident EV at ISO 100
  (`log2(lux / 2.5)`), colour temperature, flicker and the ToF distance, and a warning when the shutter time is
  not a whole number of flicker periods (`1/125 will band · try 1/100`).
- **Capture metadata**: XMP `rawr:AmbientLux`, `rawr:AmbientColorTemperature`, `rawr:LightFlicker`,
  `rawr:TofDistance` (metres) in the DNG and an SDR JPEG, and `com.rawrcam.ambient.*` / `com.rawrcam.tof.distance_mm`
  entries in the DNG provenance. An Ultra HDR JPEG keeps only the EXIF when it is assembled, so it has none of
  these XMP fields; the DNG has them. EXIF SubjectDistance keeps the lens focus distance.
- **Barometer**: EXIF 2.31 `Pressure` (0x9402, hPa) in the DNG and JPEG.
- **Compass**: EXIF `GPSImgDirection` / `GPSImgDirectionRef` (GPS tags 17/16) in the DNG and JPEG, the direction
  the lens pointed. It is part of the location, so it is written only with Location tagging on and a fix; the
  magnetic heading is turned to true north (`T`) with the declination at the fix (`GeomagneticField`). The
  heading of the rear lens axis does not depend on portrait/landscape; a front camera gets it turned by 180
  degrees. It is left out when the lens points within 15 degrees of straight up or down, or when the sensor
  rates its own heading unreliable (status UNRELIABLE or an estimated error above 30 degrees).
- **Front camera**: lux and colour temperature come from the light sensor beside the display (`xiaomi.sensor.front_cct_strm`
  [0] CCT K, [8] lux; `front_cct` [0] CCT K, [3] lux; any phone's `TYPE_LIGHT` for lux), the rear ToF is left out.
  Flicker, pressure and heading stay as they are. The facing comes from the selected lens's Camera2 `LENS_FACING`.
- **Gyroflow gyro log** (Settings > Video Encoder > Motion data, off by default): `TYPE_GYROSCOPE` and `TYPE_ACCELEROMETER`
  at 200 Hz (the most apps get without HIGH_SAMPLING_RATE_SENSORS) into a `.gcsv` v1.3 file named like the video:
  t in microseconds from the recording start, gyro in 1e-5 rad/s, acceleration in 1e-5 g, orientation `XYZ`
  (Android device axes, the convention Gyroflow's telemetry-parser uses for other Android loggers). MediaStore
  keeps DCIM for media, so videos saved there get the log in `Download/RAWR/Gyroflow`; in a picked document-tree
  folder it sits beside the video, where Gyroflow loads it by itself. When the recording ends the log is retimed
  to the video file: t = 0 at the first frame, and sensor time stretched by the ratio between the file's clock
  and real time (the recorder writes frames on the exact 1/fps grid, so a camera delivering 30.04 fps makes the
  file run 0.13 % behind; the recorder stats now report `lastFileVideoPtsUs` for this). Without the retiming,
  Gyroflow found offsets of 150, 159 and 173 ms across a 20 s clip. With OIS on, the lens moves on its own and
  stabilisation is less exact.

## Value layouts (measured)

| String type | Mode | Values |
|---|---|---|
| `xiaomi.sensor.back_cct_strm` | continuous, 10 Hz | [0] lux, [1] CCT K, [2..6] raw channels, [7] gain (256), [8] integration ms (50), [9] CCT K |
| `xiaomi.sensor.back_cct` | on change | [0] CCT K, [1] lux |
| `xiaomi.sensor.back_lux_strm` | continuous, 10 Hz | [0] lux, [1] lux, [2] gain, [3] integration ms, [4..7] raw channels |
| `xiaomi.sensor.back_lux` | on change | [0] lux |
| `xiaomi.sensor.flicker` | ~8 Hz | [0] dominant frequency, a multiple of 7.8125 Hz (1000/128); 7.8125 = no flicker |
| `xiaomi.sensor.tof` | continuous, 10 Hz | [0] distance mm (about -5 when covered, max 4000), [1] status (0 target, 255 none), [2]/[3] range bounds mm |

In the dark the CCT reads about 662000 ("no measurement"); Rawr drops CCT outside 1000–25000 K and below 2 lux.

Readings taken with the probe app, back of the phone towards the source:

| Scene | lux | CCT | flicker [0] |
|---|---|---|---|
| Lying on its back (sensor covered) | 0.1 | invalid | 7.81 |
| Window, dawn, overcast | 42 | 8290 K | wanders 15–586 Hz |
| Warm LED bulb, 50 Hz mains | 51 | 3330 K | 101.6 Hz, every reading |
| Wall at ~20 cm under the same lamp | 9.8 | 2720 K | 7.81 |

ToF [0]: 218 mm against a wall at about 20 cm, 529 mm and 3384 mm at farther surfaces, -5 with the phone lying
on its back. Aimed at an overcast sky [0] still gives 1.7–2.8 m, with status 255: only status 0 counts.

The Xiaomi 17 Ultra's `xiaomi.sensor.tof` is an ST "L8 TOF Sensor" with another layout: [0..7] four
distance/status pairs, one per horizontal band of the frame from the top (portrait), [8..15] unset. A flat wall
reads 993/1018/1040/1072 mm; a hand over the top half changes bands 0 and 1 only. Rawr picks the layout from the
sensor's name (`tofLayoutFor`) and focuses at the nearer of the two middle bands. In bright light the L8 reports
a made-up 0.4–0.65 m with status 0: at open sky every band reads 520–630 mm, and sunlit paving or a car at
1.2–1.9 m read the same. So `tofDistanceForFocus` drops the distance when the camera's AF confirms focus and the
ToF is more than max(0.5 D, half the AF diopters) nearer than it; with manual focus or AF not focused the
reading stays. The TOF cell, the light-meter pill and the capture metadata all take the distance from there.

The L8's status cannot tell either, so on that phone the distance is also filtered over time (`TofWindow`):
nothing is published until three readings agree, the median of the last five is what gets published, and a
window whose readings span more than max(50 mm, 10 % of the median) is emptied rather than averaged, because
a target that moves that far in half a second is not one target. A reading with no target (status invalid,
out of range) ends the window too. `debug.rawr.tof_stability 0` turns the filter off for one session on any
phone, which is how the same scene is compared with and without it.

Above a set light level the L8's distance is withheld altogether: `debug.rawr.tof_max_lux` overrides the
15 000 lux the phone uses by default (0 turns the limit off, 40 000 raises it). It is the low end of the
15 000-57 000 range where utalpt saw the invented distance, a starting point for the sweep and not a
calibration: targets at 0.3, 0.5, 1, 2 and 3 m at 0, 100, 1 000, 15 000 and 57 000 lux decide whether a
single ceiling is right, whether it has to be a ramp, and whether the sensor is simply unusable above it.
The window and the ceiling are off on every other phone, and the VI5300 keeps its status as the only guard.

The sweep has an instrument of its own: `debug.rawr.tof_log 1`, then `adb logcat -s RawrTof` prints a
`TOF_SAMPLE` line per reading (distance, the band statuses, lux, CCT, monotonic ms) and a `TOF_SUMMARY`
line every ten with the median, the median absolute deviation, min/max and how many readings had no
target, so each cell of the sweep is a number instead of an impression. The property is read again every
ten readings; nothing is logged while it is off, on any phone.

Flicker is confirmed after three readings in a row within one bin of 100 or 120 Hz and released after
16 readings in a row that are not, about two seconds (`FlickerClassifier`).

To check another phone: `adb shell dumpsys sensorservice | grep -iE "flicker|cct|lux|tof"`. If its sensors use
other string types or layouts, add them to `LIGHT_SOURCES` / `FLICKER_TYPE` in `AuxLightSensors`.

## Found but not used yet: `xiaomi.sensor.ois_sensor` ("ois_manager")

A continuous 500 Hz stream, readable without permission like the others. Compared with `TYPE_GYROSCOPE` while
shaking the phone (same min/max): [0] = -gyro X, [1] = gyro Y, [6] = gyro Z, all in deg/s; [12]/[13] follow
[0]/[1]; [2]..[5] are 12-bit OIS lens positions (two pairs, X and Y, roughly 400..3700 around 2000, probably
target and Hall reading); [8]..[10] acceleration in m/s² ([10] about -9.8 lying flat); [11] IMU temperature in
°C; [7] a hardware tick counter (about 410 000 per second). It streams with the camera closed too: then the
undriven lens swings freely when the phone is shaken and stops at the same travel limits (about 450 and 3650)
as with the camera open; at rest it sags off-centre (1490/2520 closed, about 1630/1700 with the camera open and
OIS holding it). The lens positions are what Gyroflow's protobuf
`LensOISData` (image-plane shift in nm per frame) needs to undo OIS; turning counts into nm needs a
calibration (for example against the gyro angle times the focal length). Until then, turn OIS off in
Settings > Capture when recording for Gyroflow.
