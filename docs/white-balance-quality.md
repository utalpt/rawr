# White balance quality: gray card and DCP

How to tell a wrong white-balance seed from a wrong colour profile, and what to change when one of
them is wrong. This is the procedure behind the `+9 mired` correction in
`SensorWhiteBalanceCorrection` and the check that the DCPs shipped in the lens profiles do not
correct the same error twice.

Nothing here has been run on a Xiaomi 17 Ultra yet: the one measurement that exists is utalpt's
5900 K reading for a 5600 K reference (audit item 4). The table below is what fills in the rest.

## What decides the colour

Three independent pieces, and each fails differently:

1. **The seed.** The light sensor measures a CCT (`cctK`), `SensorWhiteBalanceCorrection` adds the
   device's mired offset, and `sensorWhiteBalanceK` puts it on the TEMP rail's 25 K steps. The
   reading taken on the SENSOR tile is what manual WB gets.
2. **The gains.** `WhiteBalanceMath.h` turns temp and tint into RGGB multipliers (the inverted
   Tanner Helland fit) and the DNG writes them as `AsShotNeutral`; the raw bytes are never
   white-balanced.
3. **The profile.** `ColorMatrix1/2` with `CalibrationIlluminant1/2` (plus camera calibration and
   forward matrices) decide how those camera values become colour; a lens profile can replace them
   with a DCP (`docs/camera-recipes.md`, `dcp`).

A seed error moves with the *light*: the same lens, same scene, different illuminant, cast changes
with the illuminant's CCT error in mired. A profile error stays when the light and the seed are
right, and repeats on every frame of that lens. A double correction shows when a DCP whose
calibration illuminants differ from the DNG's own pair replaces the matrices that produced the
neutral: the converter then interpolates a different matrix for the same illuminant.

## The bench

- Gray card (18 % or 12 %, matte) filling at least half the frame, at 0.5–1.0 m, square to the lens,
  no reflection of a window or a lamp in it.
- Lights: tungsten ~2700 K, warm LED ~3300 K, neutral/studio LED ~5600 K, daylight 6500 K, open sky
  in shadow ~7500–8000 K. A reference CCT from a colour meter or the light's own calibration sheet is
  what the measurement is compared with; without one, compare the three capture modes against each
  other and only the *shape* of the error is readable.
- Lux levels 50 / 500 / 2000 / 15000 per light, as the light meter shows (dimmer or distance, not
  aperture, because the scene must stay the same).
- Two rear lenses (23 mm and 75 mm): a sensor bias can differ per lens only if the sensor sits
  behind different glass, but the profile definitely does.
- Phone on a tripod, RAW+JPEG, histogram centred, nothing clipped.

## Per cell

1. `AUTO` capture: what the HAL's AWB does, the baseline.
2. `SENSOR` capture: tap the SENSOR tile, which locks manual WB at the corrected sensor CCT.
3. Rail capture at the source's nominal CCT: what the gains do with no sensor in the loop.

Record, per capture: the light meter's lux and CCT, the panel's WB, the source's reference CCT, the
lens, and the DNG's `AsShotNeutral` (the script below prints it).

## Reading the result

    uv run --script scripts/wb-quality capture.dng --reference 5600

(`scripts/wb-quality` is executable on POSIX; the header is a PEP 723 script, so `uv run --script`
works from PowerShell too.)

The script prints the DNG's `AsShotNeutral`, the matrices and illuminants it carries, whether a DCP
is embedded, and the CCT that the neutral point implies through the camera matrices (camera-space
illuminant → XYZ → xy → CCT). `--reference` turns that into a mired error. `--json` prints the same
as one object, for a spreadsheet. McCamy's approximation is good to a few mired near the Planckian
locus and only a pointer away from it, which is enough to compare a cell with its reference.

Two numbers per cell:

- **Seed error, in mired**: `mired(implied CCT) - mired(reference CCT)`, so it is negative when the
  capture is warmer than the reference and the correction is its negation. Under 4 mired in either
  direction is a good seed; the audit's 5900 K for a 5600 K reference is -9.1.
- **Patch neutrality**: the gray card's mean R/G/B in a converter that honours `AsShotNeutral`
  (RawTherapee, darktable, the app's own DNG develop), sampled on the card only. Pass when
  `|R-G|/G` and `|B-G|/G` are under 2 %. This is the number that a matrix error breaks even when the
  seed is right.

The script cannot do the second one: the payload is lossless JPEG (`DngCompression::LosslessJpeg`),
so a metadata reader that does not decode JPEG cannot reach the pixels. That is what the converter
is for.

It also prints the sensor's own reading (`rawr:AmbientColorTemperature`) next to the neutral. The two
errors answer different questions:

- sensor error ≈ 0 and neutral error ≈ 0: nothing to fix;
- sensor error ≠ 0 and neutral error ≈ 0: the seed was corrected and the gains followed it, the
  correction is doing its job;
- both ≠ 0: the gains did not follow the seed (tint, a rail clamp, or the HAL's own gains);
- card not neutral although both are ≈ 0: the profile, see the decision table.

Verified so far on a synthetic DNG built to carry these tags (`scripts/wb-quality` reads IFD0, the
next IFDs and the sub-IFDs); no phone capture yet.

## Decision rules

From the audit, applied to the mired errors collected above:

| What the table shows | What it means | What to change |
|---|---|---|
| Error roughly constant in mired across all sources | fixed sensor bias | one device constant (`NEZHA_MIRED`) |
| Error grows with CCT | bias plus a scale term | affine in mired, or a small LUT over the rail |
| Error changes with lux at the same source | sensor linearity, not offset | investigate the sensor's own calibration first; do not ship a lux table |
| Error differs between the two lenses with the same light | profile, not seed | the lens's DCP/matrices, not `NEZHA_MIRED` |
| Card not neutral although the implied CCT matches | matrix (or tint) error | profile work; a seed offset makes it worse |

## Applying a correction

- Device default: `SensorWhiteBalanceCorrection.NEZHA_MIRED`, matched by `ro.product.device` =
  `nezha`; every other device gets 0, so no other phone changes behaviour.
- Per session, on any device: `adb shell setprop debug.rawr.wb_sensor_mired 9` (or `0` to compare
  the uncorrected reading on the same scene). Values are clamped to ±60 mired.
- The offset is applied in mired on the TEMP rail's 25 K grid, so the SENSOR label and the WB it
  writes are the same number.
- The front camera's sensor is not calibrated and today shares the rear offset; the table above has
  a row for it before a separate value is worth adding.

## What is not covered

- Tint: the seed writes tint 0. A green/magenta bias shows up in the patch neutrality number but is
  not corrected by any of this yet.
- Auto WB: the HAL's own AWB is measured here only as a baseline; the corrections above never touch
  it.
- Per-lens DCP validation beyond the illuminant pair: a DCP that is right at D65 but wrong at
  tungsten needs its own chart captures, which is a profile job, not a white-balance one.
