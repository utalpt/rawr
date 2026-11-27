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
| `xiaomi.sensor.tof` | continuous, 10 Hz | [0] distance mm (about -5 when covered, max 4000), [2]/[3] range bounds mm |

In the dark the CCT reads about 662000 ("no measurement"); Rawr drops CCT outside 1000–25000 K and below 2 lux.

The SENSOR tile's white balance seed is this CCT with a per-device bias corrected in mired
(`SensorWhiteBalanceCorrection`): on the 17 Ultra (`ro.product.device` = `nezha`) +9 mired, which
takes 5900 K to 5600 K; every other device gets none, and `debug.rawr.wb_sensor_mired` (clamped to
+/-60) overrides it for one session. The bias moves the seed only: `rawr:AmbientColorTemperature` and
the DNG `AsShotNeutral` keep what was measured and what the gains came to. The front camera's sensor
shares the rear value until it is measured, and `docs/white-balance-quality.md` is the procedure that
decides whether one constant is enough.

Readings taken with the probe app, back of the phone towards the source:

| Scene | lux | CCT | flicker [0] |
|---|---|---|---|
| Lying on its back (sensor covered) | 0.1 | invalid | 7.81 |
| Window, dawn, overcast | 42 | 8290 K | wanders 15–586 Hz |
| Warm LED bulb, 50 Hz mains | 51 | 3330 K | 101.6 Hz, every reading |
| Wall at ~20 cm under the same lamp | 9.8 | 2720 K | 7.81 |

ToF [0]: 218 mm against a wall at about 20 cm, 529 mm and 3384 mm at farther surfaces, -5 with the phone lying
on its back.

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
