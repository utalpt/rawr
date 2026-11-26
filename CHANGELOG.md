# Rawr 17U changelog

Fork of [Rawr](https://github.com/adityawarmanfw/rawr) for the Xiaomi 17 Ultra.
Releases follow Ed's version: `0.5.1-17u.N`. Test work lands on `yuv-probe`
first and moves to `xiaomi-17u` once verified.

## 0.5.1-17u.14

A film look read back from any photo taken with Rawr, ALL PARAMS reachable again and laid out like the film
sheet, Ukrainian, Polish, Turkish and Bengali, the-marox's video and build PRs, and Ed's #162 to #165, checked
on the Xiaomi 17 Ultra.

### Synced with Ed's main up to #165

- Ed's #162: Rexture's grain evening works on white-balanced values, so warm light no longer gives purple /
  yellow grain or tinted grey edges.
- Ed's #163: the AgX RAWR look keeps hue and half the purity as it brightens (orange no longer turns yellow, blue
  no longer cyan), mid-grey as RAWR NTRL, contrast 3.1, saturation 1.25.
- Ed's #164: "Correct per-position response" also works on 2x2 quad photo streams; vivo X200 Ultra Q35/Q85. Off
  on the 17U (its 200 MP lenses keep the fork's 4x4 fast remosaic; the 50 MP lenses get Bayer from the camera).
- Ed's #165: full-size bursts (a separate photo session, opened after the press) use their first frame as the
  base, the one closest to the shutter press.
- Checked on the 17U: a Rexture 17-frame shot on a white wall under warm and blue light (no tinted grain), AgX
  RAWR on saturated reds, oranges and blues (hues kept).

### Film

- ALL PARAMS has a way in again: an "ALL PARAMS ›" chip after the film sheet's tabs (since the LOOK button opens
  the sheet in place of FILM and PARAMS, it could not be reached).
- ALL PARAMS is laid out like the film sheet, in the same space above the exposure row: "‹" (back to the sheet) and
  the sections in one sliding row, the section's subsections in a second, and its parameters five across the full
  width. The floating card with FILM and PARAMS beside it, which covered half the viewfinder, is gone.
- ALL PARAMS shows "OFF" under the GRAIN, HALATION, DIFFUSION (and its CAMERA / PRINT tabs), SCANNER and FILTERS
  sections when their switch is off, and dims the values that do nothing until it is on, as the quick card
  already did. They stay editable. Idea from apos-fandango's Rawr X14U fork; the code is the fork's own.
- A photo's film look can be imported as a preset: the film sheet's PRESETS tab ends with "+ FROM PHOTO" (pick a
  JPEG or DNG taken with Rawr; its look is saved as a preset named after the shot's time and film, such as
  "21:32 Velvia 100", and put in use; a look a preset already has is not saved again, that preset is put in use).
  Settings → Look → Film Simulation → Save & manage → "Import look file" also takes such a photo.
- A DNG already carries the full processing recipe (Ed's provenance block); JPEGs now carry it too, in the EXIF
  MakerNote, and so do JPEGs exported from the Renderer, with the edited look. The DNG is unchanged. The idea of
  keeping each shot's recipe comes from apos-fandango's Rawr X14U fork (its StopBath recipe in the DNG); the code
  is the fork's own.
- The Renderer shows in Android's share and open-with list under the build's name ("Rawr 17U Renderer",
  "Rawr 17U Benchmark Renderer"), so several installed Rawr builds can be told apart.
- Checked on the 17U: the OFF marks and dimming per section, the three-row layout (no overlap with the exposure
  row), importing a JPEG-only shot, a DNG + JPEG shot and the same photo twice.

### Language

- Three more languages bundled: Ukrainian, Polish and Turkish, machine-made from the full list of texts.
- Spanish, French, German and Italian now have every text the other languages have (the longer help texts,
  plus words that stay the same, kept as they are so nothing falls back to English).
- Turkish: the community translation by @agabey_42 (Telegram) replaces the machine-made one (711 texts reworded,
  383 more filled, among them chip abbreviations and error messages).
- Bengali (বাংলা): the community translation by @arcticstellar (Telegram), bundled as sent (1869 texts); the
  newest texts stay in English until it is updated.
- A build test checks that every bundled language has every text Portuguese has, so a new text can't be translated
  in one language and forgotten in the others (a community file may have more).
- Checked on the 17U in Turkish and Bengali (settings, top sheet, WB strip, film sheet); no text cut.

### the-marox's PRs

- #7 and #8 (video): split YUV encoder drop counters and logs; the HEVC encoder prepared before the shutter. Both
  change behaviour only on the Xiaomi 15 (dada); on the 17U `debug.rawr.video_encoder_prewarm=1` turns the
  prewarm on.
- #9: the log names the sensor mode behind a lens's fps ceiling (1x DCG at 30); no change in what the 17U asks.
- #13: the native build also configures on a Windows host.
- #14: the GLES bridge unpacks RAW10 on the GPU when Vulkan cannot import the buffer; on by default only on the
  Xiaomi 15, `debug.rawr.gles_bridge=1` elsewhere. The 17U imports its RAW10 directly, so it does not use it.
- The debug build runs the-marox's host policy tests (#7, #8, #9) in one step, skipped with a warning when the
  runner has no host C++ compiler.
- Checked on the 17U: 4K30 (no frame dropped) and 4K60 (a few frames dropped only in the first 0.35 s).
- Not in this release: #6 (17U ToF), still on test with a fork fix for the 0.6x.

## 0.5.1-17u.13

The interface in six languages (with a language file to export, fix and import), the quick and eye panels as
one sheet at the top of the viewfinder, a white balance strip, and Ed's #159 and #160, checked on the Xiaomi 17
Ultra.

### Language

- The interface follows the phone's language when a translation is bundled, or the one chosen in Settings →
  System → Language. Bundled: Portuguese, Spanish, French, German, Italian and Russian, machine-made; anything
  without a translation stays in English (mostly the longer help texts, chip values and abbreviations).
- Export language file writes every text of the app (English as the key) with the current translation; fix or
  fill it and Import language file loads it, so corrections and new languages need no new build. An imported
  file sits on top of the bundled one of the same language (its `@code`), so a correction only needs the texts
  that change; it shows as "name · imported" in the menu. The list of texts is generated from the sources at
  build time, so texts Ed or the fork add later show up in the export by themselves.
- Covers the settings (rows, menus, subtitles, help, dialogs, lens editor), the search (it also finds by the
  translated name), the viewfinder's control help, the FILM / LOOK / top sheet chips and the PHOTO / VIDEO
  capsule.
- Checked on the 17U in Portuguese, Russian, German, Spanish and Italian (settings, lens editor, dialogs, sheets,
  import and export); no crash.

### Viewfinder top sheet

- The swipe-down quick panel and the eye panels (MONITORING, VF CONTROLS) are now one sheet docked at the top of
  the viewfinder, in the FILM / LOOK sheets' format: tabs (✕, EXPO, HIGHL, DETAIL, LENS, MONITOR, SCOPES, VF) and
  one row of chips. It takes about two rows instead of half the viewfinder.
- A swipe down opens it only when it starts in the top third of the viewfinder and travels 96 dp (it was 48 dp
  anywhere, so framing or focusing opened it by accident). It opens on the last tab used; the eye button opens it
  on MONITOR. ✕, a swipe up or a tap outside close it.
- In portrait the compact scopes move down below the sheet while it is open. In landscape the sheet runs along
  the right edge (the top as the phone is held), the swipe works from that edge, and the compact scope sits in
  the top-right corner as the phone is held; when a FILM / LOOK sheet opens there, the scope slides to just
  above it.
- White balance (portrait): a strip in the same format just above the exposure row - ✕, PICK, AUTO, MANUAL, the
  camera's presets, SENSOR, and the TEMP / TINT rails (Ed's rails) - instead of the panel in the middle of the
  viewfinder. The strip and the FILM / LOOK sheet never open together: the one opened last closes the other.
  Landscape keeps Ed's panel.
- Checked on the 17U in portrait and landscape (swipe, eye button, scopes, WB strip with the sheets); no crash.

### Viewfinder

- A changed value in the film, AgX and tone cards shows a small dot in the corner, so it shows in every theme
  (in Mono the colour of a changed value is white, like the others).
- Lens buttons follow the theme: the variant picker marks the variant in use with the theme's soft accent, and
  the selected lens button's second line ("50MP", "Normal") is neutral on the theme colour (both were a fixed
  red / pink, also in the Mono, Steel and Sage themes).
- LOOK button with Film on and no preset in use: the stock's name (e.g. Gold 200) instead of "Custom", and the
  dot as soon as a film value is off its default (EV, push, ...).

### Synced with Ed's main up to #160

- Ed's #159: the burst noise fit no longer reads low, and HDR+ Quality's Wiener merge takes its noise from the
  burst noise model. Checked on the 17U: 17-frame merges in 2.7-3.0 s, the burst noise fit in use; at 100% a
  dark wall stays clean (no colour blotches) and moving people on a TV screen show no ghosts.
- Ed's #160: faster lens start after a device error, RAW-only lenses remembered across launches; OPPO X9U 2x, 6x
  and 20x in-sensor crop lenses. Checked on the 17U: every lens opens in 170-280 ms, no errors.

### Docs

- docs/aux-sensors.md: the rawr XMP fields (ambient light, ToF distance) go in the DNG; an Ultra HDR JPEG keeps
  only the EXIF, so it does not have them. Flicker is released after 16 readings (about two seconds), as the code
  does.

## 0.5.1-17u.12

A clean viewfinder (LOOK next to the shutter), restyled scopes, the settings grouped with a search, and Ed's
#156 and #158, checked on the Xiaomi 17 Ultra.

### Viewfinder

- Clean viewfinder: the LOOK and PARAMS chips left the picture. A LOOK button sits left of the shutter (gallery
  further out, mirroring multiframe and FILM), with the look in use on two lines ("RAWR" over "NTRL") at a fixed
  size and a dot when its parameters changed; a long press opens the Look settings (Film settings while Film is
  on). In video it is alone left of the record button.
- LOOK opens a sheet over the bottom of the viewfinder in the film sheet's format: the looks as swipeable cards
  and the PARAMS groups as tabs (TONE, COLOUR, OUTPUT; for AgX RANGE, CURVE, LOOK and ADV), each opening its
  controls above the sheet, with RESET/UNDO (5 s) for the look's parameters. While Film is on, LOOK opens the
  film sheet; turning Film on opens it. Ed's controls, new layout.
- PHOTO / VIDEO is a capsule with the active mode filled in red. The gallery thumbnail is 52 dp beside the
  other buttons.
- VF CONTROLS (eye panel) and Settings > Viewfinder & controls no longer have the LOOK switch. The look sheet
  still hides with the clean recording view.
- Scopes: translucent, borderless cards (the scope blended over the picture), the mode as a small corner label
  (LUMA, RGB, PARADE, VECTOR), dashed lines at 0, 50 and 100% and the top of the scale in the accent red. New
  waveform mode PARADE (R, G and B side by side); a long press on WAVE cycles LUMA, RGB, PARADE; RGB stays the
  default. The trace itself (Ed's image_scopes rendering) is unchanged.
- Scopes hide while a film card is held for a compare; the compare's left label names the stock in use when
  the tab has no card for it.
- The readout of a scrubbed control is a slim capsule near the top of the viewfinder (title and value on one
  line over a tick ruler).
- Checked on the 17U: looks switched from the cards, RESET/UNDO, AgX tabs and ADV, the film sheet from LOOK,
  the long presses, video; logs with no crash.

### Settings

- Home grouped by intent: Photo, Look, RAW development, Video, Viewfinder & controls, Lenses, System; the hubs
  only link to Ed's pages, so options added upstream show up with no change. Section titles and values are
  grey; the accent red stays for what is on or chosen.
- Search on the home: part of a setting's name (or of its page, e.g. "denoise strength"); a result opens its
  page with the setting highlighted. The index is generated at build time from the settings sources.
- Lens editor: the lens page keeps the basics (name, camera, mirror, capture, DCP profiles); sensor tuning
  (extended exposure, vendor mode and keys, levels, sensor layout, edge crop) is under Advanced, above Ed's
  automatic settings.
- The Look page's LUT Profile row opens the chosen LUT, or Tone & Color when none is chosen (same for a LUT
  search result); Back with no history goes to the page's hub.
- Checked on the 17U: every page reached from the home (a unit test checks it too), search results and the
  highlight, the lens editor saved unchanged.

### From upstream Rawr (Ed): #156 and #158

- #156 HDR+ Quality: a tile where a frame moved keeps merging each frequency bin within 2x its noise, so moving
  subjects are clean with no ghosts (before: the reference alone, with blotchy grain); alignment's upsampling
  correction in one pass, 22% faster in Ed's numbers. Merged without conflicts. Checked on the 17U: 17-frame
  merges of a TV with moving runners - solid subjects, no ghosts or blotches at 100%; merge 2.5-2.9 s. No
  pre-#156 shot of the scene, so the gain was not measured.
- #158 Rexture Native RGB without false colour: red and blue rebuilt from their own CFA sites, green re-estimated
  where few frames merged. Native RGB and Rexturation are on by default for new installs and reset settings;
  saved settings are kept. Merged without conflicts. Checked on the 17U: with Native RGB the JPEG input is
  ready in 0-13 ms against 440-550 ms for AMaZE in the same session; fine textures (wicker, engraving) show no
  false colour or moire at 100%.

## 0.5.1-17u.11

The film stocks as cards, with hold-to-compare and a grain loupe, and Ed's #155, checked on the Xiaomi 17
Ultra.

### Film: stocks as cards

- Turning FILM on opens the film sheet in place of the FILM and PARAMS buttons: one row of tabs - presets,
  negative (Kodak and Fujifilm), cinema (Vision3 and Verita), slide, print paper (hidden for slide film,
  which is scanned directly), filter (none, Glimmerglass, Black Pro-Mist, Pro-Mist, CineBloom, strengths 1/8
  to 2), grain (off, light, medium, strong) and halation (off, 1, 2, 4) - over one row of cards, with the
  tab's own settings above (exposure, diffusion, grain, halation). ✕ closes it; a long press on a tab or a
  setting explains it. The layout follows the film browser of the fork's Klipp Lab.
- Tap a card to use it. Hold it to compare without changing the setting: the viewfinder splits down the
  middle, the look in use on the left and the held card on the right, with a white line and both names on
  top. The held card gets its own film engine, so the look in use is never rebuilt for a hold.
- Halation cards fill the whole viewfinder. Grain cards open a grain loupe: the middle of the frame enlarged
  (x4 at the usual viewfinder size) and rendered at about the pixel size of a 4096-wide photo, passed to the
  film model as an enlarger magnification. The normal viewfinder renders the film at a quarter of its size,
  where grain does not show.
- RESET in the tab row: back to the preset in use as saved (or to the default film look when no preset is in
  use); for 5 seconds the chip reads UNDO and brings the previous look back.
- The viewfinder keeps the last two film engines, so going back to a stock swaps at once instead of
  rebuilding.
- Checked on the 17U: a new stock builds in about 0.15-0.7 s (up to 1.4 s the first time), going back to one of the last two in 6-36 ms;
  the split with Portra 400 against Velvia 100 (card on the right); the grain loupe; RESET and UNDO. Strong
  grain in the JPEG roughly doubles the fine detail measured in a 400 px crop (5.5 -> 10.5).

### From upstream Rawr (Ed): #155

- Rexture (multiframe) chroma denoise on the white-balanced image, with colour thresholds from the noise
  model and a lower edge-stop, against green / magenta blotches in dark merges; the 50 MP strip merge JPEG
  develops the merge's 16-bit codes instead of rounding them back to sensor codes. Merged without
  conflicts. Checked on the 17U: two 17-frame Rexture merges at about 3 lux, ISO 525 - the merged DNGs are
  correct (16-bit scale, black and white levels, metadata) and the JPEG shadows are clean; the gain over
  17u.10 was not measured (Ed's numbers: colour noise left in dark synthetic merges 12-30% -> 3.4-5.3%).

### CI

- Test builds of the private test repository run on a self-hosted runner when its RUNNER variable is set;
  builds of this repository are unchanged.

## 0.5.1-17u.10

Ed's #152 (faster stills with less memory), checked on the Xiaomi 17 Ultra.

### From upstream Rawr (Ed): #152 and #136

- #152 still perf: still render memory down by about a third (Ed's
  numbers: 50 MP 2450 -> 1560 MB), faster UltraHDR gain map fit, gain map
  encoded 4:2:0 (luma stays full resolution), Rexture merge faster on large
  frames (the next frame loads while the GPU merges), faster wavelet
  denoise. Merged without conflicts; the fork's 3:2/16:9 crop of the JPEG
  and gain map is kept.
  Checked on the 17U against 17u.9: three 30-frame 1x 50 MP bursts in a row
  merged 30/30 in 13.0/11.0/10.3 s (was 18.3/15.8/18.0 s), and from the
  first press to the third photo saved 59 s (was 80 s, with the shots taken
  closer together this time). 200 MP single frame 7.2 s (was 7.6 s); 16:9
  crop of the JPEG and gain map unchanged. Android still closes background
  apps for memory during the three bursts (Rawr is not closed).
- #136 vivo X200 Ultra high-res (separate photo session, keepTemplateEntries);
  lenses without these fields, the 17U's included, run as before.

### CI

- Test builds from `yuv-probe` save the Gradle cache too, and the benchmark
  test build skips lint (test builds about 8 min instead of 12).

## 0.5.1-17u.9

Ed's #151 (full-size bursts keep their frames when the camera falls behind)
and 3:2/16:9 photos framed across the full screen width, checked on the
Xiaomi 17 Ultra.

### From upstream Rawr (Ed): #151

- Full-size bursts no longer lose the whole photo when the camera falls
  behind: frame N-1 is written to the journal while frame N is copied and
  remosaiced (two alternating buffers, about 100 MB more for a 50 MP burst;
  200 MP frames keep the one-buffer order). A burst that is flushed, fails or
  times out keeps the frames already delivered and merges them when there
  are two or more (FULL_SIZE_BURST_SALVAGED requested= kept=). Ed's check: a
  vivo where 28 and 30 frame 50 MP quad bursts failed now gets 30/30. On the
  17U this is the 50 MP multiframe (17 frames by default, up to 30). Merged
  without conflicts; capture_job_test passes here.
  Checked on the 17U: three 30-frame 1x 50 MP bursts in a row merged 30/30
  (28-60 s each; the third ran while the first two were still processing,
  and Android closed background apps for memory, not Rawr), a 17-frame one
  17/17, 200>50 6/6, and a 200 MP single frame saved.

### Photo aspect ratio

- 3:2 and 16:9 photos fill the screen width in the viewfinder, running
  behind the shutter cluster like 16:9 video, instead of a narrow box inside
  the 3:4 photo frame. 1:1 and 5:4 still fit the photo frame; 2.39 and XPan
  stay inside it (full width would run past the screen). The bottom controls
  float over it, as in 16:9 video (they hid the last quarter of the frame).
  Checked: JPEG 16:9 4096x2304 and 3:2 4096x2730 from the 4096x3072 frame,
  framing matches the viewfinder and tap-to-focus lands where tapped.

## 0.5.1-17u.8

Sync with Ed's main up to #150, checked on the Xiaomi 17 Ultra, and the
README's full list of what the fork adds.

### From upstream Rawr (Ed): #139 to #150

Ed took this fork's #140 (S23 Ultra), #141 (focus hold) and #142 (Manual WB)
and ported more of it himself: #143 (17 Ultra and 15 Ultra built-in profiles,
lost-companion workaround, 12-character lens names), #144 (200 MP reduced
bursts read RAW10 in place on the GPU), #150 (cropEdges, cropEdgesHighFps,
the-marox's mirrored-preview fix and the 17U front at 60 fps). Merged into
the fork, with Ed's own work since #138:

- #139 Quad Detail remosaic with block moments, in pipelined strips; the
  log shows the OIS mode a still was taken with (CAMERA_STILL_OIS).
- #145 2x2 photo lenses remosaic with Detail: on the 17U the 1x, 3.2x and
  4.3x ISZ lenses (were High).
- #134 (by Mateusz) the recovery of interrupted stills no longer deletes
  photos already published.
- #146 a device kept open for the next lens (S23 Ultra) is closed when that
  lens never opens (closeKeptDevice), instead of staying open unused.
- #148 a lost first frame counts as one failure, not one per callback; a
  camera the service does not list yet (still closing) is retried at once.
- #149 the HEVC encoder asks for realtime priority at the recording's rate
  (already in the fork) and, if refused, is recreated before configuring
  again. Small realtime RAW readers (the 17U's binned lenses) now hold 8
  images with 3 waiting frames: this fork tried the same in 0.5.1-17u.4 and
  reverted it (more drops at the start of a 4K60 take on the 17U), so it is
  checked again in this round.
  First take on the 17U (4K60 10-bit, 1x, ~3 min): 9 RAW frames evicted in
  the first 0.3 s, none later, no encoder drops. Two takes with the fork's
  reader (6 images) before: 4 and 2 at the start, 3 and 2 later. Same total,
  moved to the start. `adb shell setprop debug.rawr.deep_ingress 0` keeps the
  usual reader (6 images, one waiting frame) for an A/B in one build;
  RAW_READER_CREATED_NATIVE shows maxImages=6 or 8.
  A/B on the 17U (4K60 10-bit, 1x, 2 takes of 2 min each, same scene):
  Ed's reader (8) lost no frames in either take; the usual reader (6) lost
  3 at the start, then 2 at the start and 1 later. No encoder drops in any.
  The 6-image takes ran second, about 2 °C warmer (41-43 against 40-41 °C);
  their drops were in the first 0.15 s, as in the fork's earlier takes. Ed's
  deeper reader stays (the default); the property stays for comparisons.
- #147 the host test suite builds again.

Checked on the 17U with the first sync build: 50 MP single and 17-frame
multiframe on 1x, 200>50 on 3.2x (the lost companion buffer ignored, photo
saved), 15 lens switches with no failed open, front 60 fps inside its
4096x2304 area. Seen once: 1x DCG lost 4 frames in a row (HAL ERROR_BUFFER)
about 1 s after opening; the session went on.

Kept from the fork where it does more: Open Gate high-fps sizes and the
recording aspect ratios, the-marox's 1.25x-2x video resample (#5), the
200 MP fast remosaic and JPEG from the blocks, two-plane gain maps, and the
viewfinder/scope throttles while recording. The 17U profile is now Ed's
(the fork's, plus ignoreLostCompanion on every photo-stream lens and Detail
on the ISZ lenses). Ed made the lost-companion rule a per-lens workaround
(ignoreLostCompanion); the fork keeps applying it to every lens, as before,
so lens profiles saved or edited before (without the workaround named) keep
their full-size stills.

Host tests here: everything builds; the merge's own tests pass (crop,
kept area, colour, start ladder, reader ladder, route fixture). The
failures left were there before the merge (camera_profile, camera_result_
state, and still_binned_fallback, which now builds: it expects the generic
remosaic on 4x4 blocks, where the fork takes its fast path).

### README

- "What this fork adds" lists everything the fork adds, also what Ed has
  taken into Rawr since, marked with his pull request.

### CI

- The shader SPIR-V upload (for the AOC analysis) no longer fails the
  build: in the private test repository the account's artifact quota was
  full, after the APK had already gone to the test-build prerelease.

## 0.5.1-17u.7

the-marox's Xiaomi 15 work (PRs #3-#5), checked on the 17U, and the 17U
front camera at 60 fps.

### Xiaomi 15 PRs by the-marox (#3, #4, #5)

Merged as is from the-marox's PRs to this fork; his work, checked by him
on a Xiaomi 15 (dada).

- #3 Xiaomi 15 60 fps only where a sensor mode delivers the RAW stream
  (sensor-mode table ceiling, on only for ro.product.device "dada",
  debug.rawr.sensor_mode_fps), and the preview framing of a mirrored lens
  whose crop window is off the frame centre (centred windows and
  unmirrored lenses unchanged).
- #4 Xiaomi 15 auxiliary sensors into capture: sensed PWM/mains flicker for
  rawr AE (dada only, debug.rawr.sensed_flicker), a TOF cell in the focus
  bar that sets manual focus to the laser distance, and a SENSOR button in
  the WB panel that sets manual WB to the light sensor's colour
  temperature. The TOF and SENSOR controls show on any phone that exposes
  those sensors.
- #5 1080p from the full width of a RAW 1.25x-2x its size (Lanczos-2
  resample), on only for "dada" (debug.rawr.video_resample).
- Checked on the 17U: the TOF cell shows (17U sensor: ST L8, value [0]
  read 1.73 m for a helmet at about 1.70 m, 34 cm for a screen where AF
  read 0.36 m) and the SENSOR WB button shows (rear tcs3448 CCT stream).
  The TOF cell: tap to focus, then long press on the AF target (a long
  press elsewhere starts the AE target). Lens switching across
  0.6x/1x/3.2x/4.3x/front in the same test session: no failed open, no
  errors in the log.

### 17U front camera at 60 fps

- The front 60 fps mode fills only rows 0..2303 of the 4096x3072 RAW10
  (16:9), so the centred UHD window read 312 rows the camera never writes:
  a band on the left of the recording (magenta before #3, black after, with
  stale image in the first second). The Front Normal profile now drops the
  768 rows below at high fps (image.cropEdgesHighFps, the same mechanism
  as #3), so the UHD window sits inside the written rows.
- Checked on the 17U (front, 4K60 HEVC): no band, real image to the left
  edge from the first frame, no stale image at start.

## 0.5.1-17u.6

Galaxy S23 Ultra lens switching and launch (tested by two S23 Ultra users),
and the focus jump after a 1x 50 MP single-frame photo on the 17U.

### Lens switching on the Galaxy S23 Ultra (test)

- Two S23 Ultra users (SM-S918B, Android 17) on 0.5.1-17u.5: about half the
  lens switches took 2.3-4.5 s, the rest ~0.9 s (34 switches in one
  diagnostics bundle). Every rear lens there is logical camera 0; Rawr
  closed and reopened it on each switch, and the HAL refused the reopen
  (-10001) 13-30 times before letting it through. The open retry (#121)
  recovers every time, so no more 10 s switches, but the wait stays.
- Now, on the SM-S918B only, a switch between lenses of the same logical
  camera keeps the device open and only rebuilds the capture session
  (CAMERA_NDK_DEVICE_KEPT / CAMERA_NDK_DEVICE_REUSED in the log). Other
  phones, the 17U included, close and reopen as before.
  `debug.rawr.keep_device=1` opts another device in, `=0` opts out.
- Checked on an S23 Ultra by @NaveenRayala (diagnostics bundles, same
  phone, same kind of back-and-forth switching across 0.6x/1x/3x/10x):
  before 33 switches, median 2.6 s (0.9-4.5 s), 22 with refused reopens;
  with this build 38 switches, median 0.7 s (0.5-1.3 s), no refused reopen,
  every switch logged DEVICE_KEPT/DEVICE_REUSED, photos saved, no device
  errors. (Measured from the switch to the first RAW frame.) The capture
  failures at app launch, retried by the start ladder, were there before.

### App launch on the Galaxy S23 Ultra (test)

- Same bundle: every launch lost ~1.6 s. The RAW10 reader asked for GPU
  buffer usage, which the S23 Ultra's gralloc (before 4.1) can't allocate
  on RAW10, so the first requests failed, the usage ladder demoted to a
  CPU-read reader and the session restarted (first RAW frame 2.6 s after
  the camera start instead of ~0.9 s).
- The RAW10 reader now asks the allocator up front, as Ed's code already
  does for RAW16, and starts CPU-read when the GPU usage can't be
  allocated (RAW_READER_CPU_ONLY_USAGE format=RAW10
  reason=gpu_usage_unallocatable). Same end state as the ladder's, without
  the failed start. Phones that can allocate it are unchanged.
- Checked on the S23 Ultra by @NaveenRayala: from engine start to the
  first RAW frame 2.8-3.6 s before, 0.7-0.9 s now (5 launches, no failed
  first start); "opening in about 2 s, earlier 4 s" by feel. Lens switches
  in the same bundle: 45, median 0.6 s.
- Seen once in that bundle: right after a launch on 0.6x, the first switch
  to 1x on the kept device got a device error and its configure failed
  ("Broken pipe"). Rawr took it for the HAL rejecting the 1x companion
  stream and ran 1x RAW-only for the rest of that launch. Now a failed
  session on a reused device closes it and starts the lens again with a
  fresh open, without the RAW-only penalty (CAMERA_NDK_DEVICE_REUSE_FAILED).
  Seen by a second S23 Ultra user on this build: 3 of 27 reuses failed the
  same way (device error, "Broken pipe"), each time the first reuse after
  a fresh open, and the fresh-open retry then cost ~8 s: the dead device
  drops the camera id for a moment, so the restart was rejected at
  selection (characteristics -10001) and only the stall watchdog brought
  the camera back. The retry now repeats the start every 150 ms for up to
  3.5 s (CAMERA_NDK_DEVICE_REUSE_RECOVERED / _RETRY_GAVE_UP). With this
  build both S23 Ultra users report it as much better, including with
  HDR+Q on (by feel; no bundle of that build yet). Taking a photo and
  switching right away still waits for the HDR+Q processing, as before.

### Focus after a 1x 50 MP single-frame photo (test)

- Close up, the viewfinder jumped after a 1x 50 MP single-frame photo
  ("Restoring preview..."): FOC went 0.17 m -> 20 m -> 1.0 -> 0.29 m, the
  lens restarting from infinity and continuous AF hunting back (focus
  breathing makes it look like a framing jump). Also on 0.5.1-17u.5; not
  with Rexture (it merges the live frames, no sensor mode switch) nor with
  manual focus. The remosaic still switches the sensor mode and the HAL
  resets the lens when the preview mode comes back.
- Now, after a single full-size still in continuous AF, the viewfinder
  holds the lens at the pre-still distance for 20 frames, then hands back
  to continuous AF (CAMERA_FOCUS_HOLD / CAMERA_FOCUS_HOLD_RELEASED in the
  log). A tap to focus or manual focus take over as before.
- Checked on the 17U (1x 50 MP, ~0.2 m, screen recording): one preview frame
  still shows the reset lens (FOC 20 m, ~0.1 s) right as the preview comes
  back, then it is back at 0.20 m and stays there after the hold ends, no
  hunt. Before: ~1 s of hunting (20 m -> 1.0 -> 0.51 -> 0.31 -> 0.29 m).
- Why Rexture never showed it: its 50 MP burst is the same remosaic still
  (same request keys, 17 frames), but the viewfinder stays frozen through
  the countdown and "Restoring preview...", so the lens has settled before
  live frames return. The single-frame path now also keeps the first 4
  preview frames of the hold off the viewfinder, which hides the one frame
  with the reset lens. Checked on the 17U.

## 0.5.1-17u.5

Ed's upstream #137 and #138, then the white balance work. Checks are on a
Xiaomi 17 Ultra.

### From upstream Rawr (Ed): #137, #138

- #137 OPPO Find X8 Ultra (PKJ110) built-in profile, by Ed. Not used on the
  17U. Its refreshed route snapshot is merged into the fork's.
- #138 optional shared debug signing key, by Ed: the Gradle part and
  docs/build.md taken as is (no effect without the key). The fork keeps
  signing debug builds with its own fixed debug.keystore, applied after
  Ed's option, and its own debug.yml (no GitHub secret needed).

### White balance

- WB sampler: WB panel > PICK NEUTRAL, then tap a white or grey area in the
  viewfinder. Rawr measures that area (about 5% of the frame) in the scene
  meter, before the render, and switches to Manual with the temperature and
  tint that make it neutral; the sliders fine-tune from there. A second
  pick refines from the current Manual values. Too dark or clipped areas are
  ignored (logcat tag RawrWbPick). Asked for by Matt in the Rawr group.
- Why: in Auto, Rawr renders with the white balance gains the camera HAL
  gives third-party apps. Checked on a 17U DNG under warm indoor light: the
  HAL neutral (R 0.567, B 0.601) was cooler than the light on a white wall
  (R 0.634, B 0.531), so the wall came out cream/yellow. Matt measured the
  same on a vivo X300 Ultra: Rawr about 100-300 K warmer than the stock app.
- How: the scene meter (image_scopes scene_exposure shaders) also sums the
  RGB inside a sample rectangle; the session turns the measured ratios into
  a Manual temp/tint request on the camera controller.
- First check on the 17U (daylight and shade, 3 picks): the pick lands
  right in camera space - the DNGs read R/G 1.04 / B/G 1.00 and 1.00 / 1.01
  on the picked wall - but the JPEGs came out magenta (a white wall turned
  lilac). Cause, in the render (also in upstream): with AWB off the colour
  matrix took its white from the HAL's neutral point, which in Manual is
  the HAL's own guess, not the requested gains. A raw the Manual gains make
  neutral then rendered with the gap between the two as a cast. Manual WB
  now takes its white from the gains, the same rule the DNG's AsShotNeutral
  already follows (color/ColorCalibration.cpp; native test added).
- Checked on the 17U with the fix (white wall, daylight, Auto then pick):
  the wall read R/G 1.07 / B/G 0.97 in the Auto JPEG and 1.00 / 0.97 after
  the pick (linear, centre of the frame); DNG 0.99 / 0.99. No magenta.
- Warm indoor light (17U, lamps, white wall): Auto JPEG R/G 1.34 / B/G 0.78
  on the picked spot (the yellow cast), after the pick 1.01 / 0.96; DNG
  1.13 / 0.90 -> 1.00 / 0.98. In mixed light the rest of the wall follows
  its own light: a part lit from elsewhere read 0.92 / 1.02 after the pick,
  so it can look slightly blue.
- Picking a cream/beige wall made the picture too cool (the pick makes the
  tapped area grey; a white shelf in the same scene went 0.79 / 1.12). The
  hint now says to tap something truly white or grey. Picking the white
  shelf instead: 1.19 / 0.79 in Auto -> 0.95 / 1.00, still felt too cold
  under warm lamps.
- So a warm pick (one that cools the picture) corrects part of the way
  (in log space), keeping some of the light's warmth like stock cameras do;
  a second pick moves closer to neutral. Cool and tint-only picks stay
  full. Checked on the 17U, same scene: shelf 1.22 / 0.78 in Auto -> 1.01 /
  0.93 at 75%; still read too cold by eye, so now 50% (halfway, in log
  space, between Auto and neutral). Checked on the 17U: the shelf went from
  1.07 / 0.88 in Auto to 1.00 / 0.99; the gains moved about 65-75% of the
  way, not 50%, because Manual keeps only temperature and tint, which can't
  hold the pick's exact red/blue gains. Left as is for now (temporary);
  exact gains are in the TODO. A pick on a napkin changed little: with the
  napkin in frame the HAL's Auto already had it near neutral (1.01 / 0.96).

## 0.5.1-17u.4

Ed's upstream #135 first, then this fork's work since 0.5.1-17u.3. Each
item says how it was checked; checks are on a Xiaomi 17 Ultra unless noted.

### From upstream Rawr (Ed): #135

- #135 Bayer bin 22 MP: the smaller reduced size ("reduced25") on Bayer
  lenses now goes through a direct 3x bin (22 MP from 200 MP) instead of
  the 2x bin then resample; colour kernels refitted. The vivo X300 Ultra
  profile (not the 17U's) also sends vivo's pro-mode vendor key on its
  lenses.
- Rawr AE: a still's gain uses the exposure target in effect when the frame
  was taken (kept per frame timestamp), not the one when the burst is
  processed, which for 200 MP can be seconds later.
- On the 17U: its reduced lenses are 4x4 (block sums, not the Bayer bin),
  so the bin changes don't touch the default profile; the AE change applies
  to every photo. Merged without conflicts on top of this fork (#129 to
  #133 recorded as already merged by hand).
- Checked on the 17U after the merge: 50 MP singles and 17-frame
  multiframe on 1x and 3.2x; single and multiframe came out at matching
  brightness. Leaving the app and shooting right after coming back gave
  the same post gain (1.0) as a shot 3 s later.

### Video

- **Gyro stabilisation** (Settings › Video Encoder › Stabilisation: Off,
  Standard, Strong; off by default). From apos-fandango's Rawr X14U fork
  (github.com/apos-fandango/rawr-xiaomi14u, commit 9bd6bac): the gyro
  integrator with its smoothed path and the timestamped shake track. The
  rest of the stabilisation below (applying it in the fast path, OIS off,
  soft edge, the sub-pixel warp with roll, Level horizon) is this fork's
  own. First version: the shake was taken out by moving the crop window
  inside the sensor's spare area in the recording fast path, so it adds no
  GPU pass. Pitch and yaw only (no roll), 4K and 1080p (Open Gate has no
  spare area), fast path only. The crop moves in 2-pixel steps with
  hysteresis, so it doesn't flicker between steps on a steady hand.
  First test: the slow wander of a handheld shot went from ~70/50 px to
  ~6-16 px over 10 s, no dropped frames.
- Stabilisation turns the lens OIS off while it runs (video mode with
  Stabilisation on); the OIS setting itself is kept. Measured on the 17U:
  with OIS on, the lens already removes most of the hand tremor (3-12 Hz)
  that the gyro still sees, so correcting it again doubled the fine jitter
  (1.6 to 3.3 px). With OIS off the gyro matches the image (correlation
  0.89, gain 0.94, no frame lag) and Standard cut the fine jitter of the
  same take by about half. Not yet better than OIS alone: work in progress.
- Smoothing: Standard 1.0 s (was 0.45 s), Strong 2.0 s (was 1.0 s). Walking
  test on the 17U, same route, separate takes (x / y, px at 4K): fine jitter
  OIS alone 1.7 / 0.7, Strong (1.0 s) 1.0 / 0.7; slow sway 0.3-1 Hz OIS
  alone 4.2 / 2.8, Strong 1.4 / 1.8. The old Standard (0.45 s) was no
  better than OIS. Standing still, OIS alone still has less fine jitter
  (it also corrects within the frame). Motion blur without OIS not measured.
- Soft edge: past 60% of the crop margin the smoothed path is pulled
  towards the camera progressively, so a turn eases into the margin instead
  of hitting it (a walk with turns reached the hard limit in 15-18% of
  frames). Known limit: the crop moves in 2-pixel steps, which leaves small
  frame-to-frame jumps (~0.4-0.5 px median vs ~0.1 px with OIS alone);
  sub-pixel moves need resampling, the same work as horizon levelling.
- Fast path: lens shading looked up once per 2x2 cell (all four map
  channels at the cell centre) instead of three bilinear lookups per pixel.
  AOC (A840, P010): 2870 -> 2189 instructions, ALU 1188 -> 882, texture
  reads 57 -> 13, exposed memory latency 29% -> 7.6% of cycles; occupancy
  still 25%.
- Fast path: RAW tile cache in half precision (packed pairs), half the
  shared memory of the float cache (6.4 -> 3.2 KB per workgroup), to raise
  occupancy. AOC: occupancy 25% -> 31% (2453 instructions). 3-minute 4K60
  10-bit recording with both changes vs the morning build: average GPU
  clock 348 -> 286 MHz at similar load (busy x clock -21%), no dropped
  frames.
- Tried and dropped: sub-pixel stabilisation (resampling the frame in the
  fast pass instead of moving the crop in 2-pixel steps). On the 17U it cost
  about twice the GPU work of the regular pass in 3-minute 4K60 takes
  (busy x clock 401-440 vs 215, GPU 460-650 MHz vs 286) even after three
  rounds of optimisation, for ~0.3 px less frame-to-frame jitter.
  Standard/Strong keep the free 2-pixel crop steps of the regular pass.
- Level horizon (Settings › Video Encoder › Stabilisation): turns the
  frame by the tilt from the gravity sensor, keeping the horizon level in
  landscape; it fades out past 45 degrees (portrait). It zooms in 1.1x so
  the frame can turn about 10 degrees, and levels before taking out shake.
  It runs a turned, sub-pixel shader variant that costs about twice the
  GPU work of the regular pass (opt-in, said in the setting).
  First test (no zoom yet): the turn's direction is right (in the video the
  applied turn cancelled the camera's roll, -0.81 vs -0.83 per degree), but
  tilts of up to 15 degrees ran past the ~5 degrees the margin allowed.
  Measured with the 1.1x zoom (gyro log against the recorded horizon): tilts
  up to ~5 degrees came out level (a 4 degree tilt stayed at -0.25), a 21
  degree tilt was levelled by only ~5.5 (the horizon showed ~15-16
  degrees), and no frame had empty corners. That matches the 2304-row
  sensor mode's margin, where 1.1x leaves ~5.5 degrees (the ~10 of the
  first estimate assumed 2560 rows). The zoom now follows the recording's
  margins: the smallest of 1.1x and what a 9 degree turn needs, up to 1.25x
  (about 1.2x from 2304 rows, 1.1x from 2560 or more). More zoom upscales
  more (4K from ~3200 px wide at 1.2x). The STAB log line
  (`adb logcat -s RawrStab`) now shows the zoom and the turn room. The zoom
  is chosen on a take's first frame and kept for the take.
  Checked on the 17U with this build: that take ran 2560 rows (margins
  112/192 px, zoom 1.1x, turn room 9.7 degrees): tilts up to ~9 came out
  level, an 11 degree tilt was turned 9.7 (the horizon kept ~1-3). The 1.2x
  path (2304 rows) is not yet checked on a take.
  Release check, Stabilisation Standard, gyro log against the video and
  the RawrStab trace: on 1x (margins 120/192 px) and 3.2x, both at 1.1x
  with 9.7 degrees of turn room, tilts up to ~9 degrees came out level; at
  14 degrees the trace shows 9.74 applied of 14.2 wanted, so ~4.5 stays
  visible. Strong not checked (the turn room doesn't depend on it).
- Tried and reverted: holding the viewfinder on a lens switch until the
  new lens's first frames settle. It hid the HAL's default-exposure flash,
  but AE still searched at 800 ms on every switch, and in video mode Rawr
  AE's first manual requests switch the HAL to the 2560-row sensor mode
  (the viewfinder jumps sideways); waiting for that froze the viewfinder
  too long. Lens switching is as it was.
- Tried and reverted: a deeper realtime RAW reader (8 images) and ingress
  queue (3 frames) against the start-of-take drop, from apos-fandango's
  X14U fix (rawr-xiaomi14u, commits 39a49615 and b2d85abe). On the 17U a
  3-minute 4K60 take still dropped 7 frames at the start (2-4 before) and
  had 6 one-frame sensor gaps (2-3 before), at a higher GPU clock.

### Lenses (17U profile)

- New lenses 3.2x 200>25 and 4.3x 200>25, after the 200>50 ones: the same
  200 MP tele mode with the smaller reduced output (Ed's "reduced25": the
  50 MP result resampled to half its pixels, 25 MP), for smaller files.
  To see them: Settings > Lens > Reset to device default (or clear the
  app's data); it replaces lenses edited by hand.
- Checked on the 17U: after the reset both lenses sit right after their
  200>50, and they shoot and save.

### Renderer

- Sensor CFA (RAW tab): how the DNG's colour filter array is read, saved
  with that edit only. Layout Auto, Bayer, Quad Bayer 2x2 or 4x4 blocks;
  order as tagged or RGGB, GRBG, GBRG, BGGR. Some apps write a sensor's
  block mosaic tagged as 2x2 Bayer, which renders magenta: a 50 MP Xiaomi
  15 Ultra DNG from Unspektrawesome holds a Quad Bayer (2x2 sites equal,
  RRGG/RRGG/GGBB/GGBB in 4x4). Auto finds that from the data (Rawr's own
  DNGs are always Bayer) and remosaics it to Bayer once, into the raw
  cache, with the remosaic the camera already uses for block sensor modes
  (CfaRemosaic.h). Checked off-device on that DNG: detection picks 2x2 with
  a wide margin and the remosaiced raw renders with correct colour; a true
  Bayer raw stays Bayer.
- Large uncompressed DNGs: the Auto detection now runs on the full decode
  that builds the raw cache (named -auto<n>, so a later open doesn't decode
  again). Reading a region first made tinydng decode the whole strip inside
  its 128 MiB cap, and that 50 MP DNG failed to open ("memory cap
  exceeded"). Checked on the 17U: it opens, detected as Quad 2x2, with
  correct colour; the CFA section shows in the RAW tab.
- Optional Renderer icon in the app drawer (Renderer › options › Icon in
  app drawer), for launchers that don't show app shortcuts. Off by default.
  The Renderer now runs in its own task, so opening it from a launcher
  doesn't bring up the camera.
- Settings › Renderer: opens the Renderer and holds the same app drawer
  icon switch, so it can be found without a launcher shortcut or the long
  press on the gallery thumbnail.
- Checked on the 17U: Settings > Renderer opens it; the drawer icon opens
  the Renderer. Known: sharing a DNG from Google Photos doesn't offer the
  Renderer (it registers for the exact DNG types only).

### Exposure controls

- Manual ISO shows the standard numbers on the third stops: 80, 160, 320,
  640, 1250, 2500 instead of 79, 159, 317, 635, 1270, 2540 (the exact
  values rounded). The 1/24 EV fine steps between them stay; full and third
  stops keep their snapping. Fork change to Ed's scale
  (NativeCapabilityProjection.kt). Checked on the 17U.

### RAW ingress (PR #2 by the-marox)

- GLES bridge for RAW16 on the Xiaomi 15 (by the-marox, commits f3e8c96
  and 8e053d9): where the Vulkan driver refuses the camera's RAW16 buffers,
  the frame is read through a GLES pbuffer context into a buffer Vulkan
  imports, instead of the CPU upload. On only for ro.product.device "dada"
  (Xiaomi 15); `debug.rawr.gles_bridge=1` opts another device in, `=0` opts
  out. Off, the RAW16 ingress is the previous one: on the 17U nothing of
  the bridge runs (no probe, no EGL context); the app only links EGL and
  GLESv3. Merge into yuv-probe: the link line conflict resolved by hand.
- Checked on the 17U: viewfinder normal, a 200 MP tele photo (the case
  that reached the RAW16 fallback before), no GLES_BRIDGE line in the log.
  Tested on the Xiaomi 15 by the-marox.

## 0.5.1-17u.3

Goal of this round: 4K60 10-bit recording without dropped frames on the 17U.

Test conditions for the figures below: Xiaomi 17 Ultra, HEVC at 120 Mbps,
viewfinder quality High, 10-bit unless noted.

### How the GPU work was found

- The video shaders were compiled to SPIR-V by CI (artifact
  `shaders-spv-<sha>`) and analysed with Qualcomm's Adreno Offline
  Compiler (`aoc -arch=a840 -api=Vulkan -dump=stats_perf`). It showed the
  tonemap uber-shader at ~12k instructions with almost no fp16, and the
  quad demosaic at 25% occupancy.
- That is why the fast path replaces the per-pixel tonemap with a 65³ LUT
  baked once per frame and fuses demosaic, colour and YUV output into one
  dispatch. The demosaic occupancy is not addressed yet. AOC is an
  analysis tool only: nothing from Qualcomm ships in the app.

### Video pipeline

- **17U fast path (RAW to YUV in one GPU pass).** Demosaic, lens shading,
  white balance, the render look and the YUV conversion run in a single
  compute dispatch that writes the encoder buffer directly (P010 for 10-bit,
  NV12 for 8-bit). The render look is baked each frame into a 65³ LUT.
  - Measured on the 17U at 4K60 10-bit: 0 dropped frames over 3 minutes with
    the GPU at ~342 MHz; phone power ~4-5 W (earlier builds: ~8-12 W).
  - 4K60 8-bit (NV12) also measured: no encoder buffer drops over ~3 min.
  - Rechecked after this round's changes: 4K60 10-bit, ~3.5 min, ~12,960
    frames to the encoder, none missing.
  - Used only when the recording needs nothing the fast path doesn't do:
    YUV output, highlight recovery off, no denoise, no false-colour or
    defringe correction, no DCP profile, no distortion correction, no
    mirror, quad demosaic at sensor scale 1:1. Otherwise the previous path
    runs (no change in output, higher GPU load).
  - `debug.rawr.video.fast=0` turns it off.
- **Encoder feed.** Finished frames are handed to the encoder before
  waiting on the GPU, and the encoder keeps more buffers in reserve
  (16 writer images, 4 ready). This removed the systematic "no buffer"
  drops on a warm phone.
- Encoder input is P010/NV12 written by the GPU (ImageWriter), no platform
  RGBA conversion.
- Viewfinder refreshes every other frame while recording above 30 fps
  (the recorded video keeps every frame).
- On-screen monitor written at half resolution while recording.
- Scopes recompute at most every 300 ms per frame slot while recording
  (monitor stage ~3.0 ms to ~1.1 ms).
- Idle viewfinder runs at the recording fps above 30 so the sensor mode
  doesn't switch at record start.
- Hardware trilinear render LUT by default on Adreno 840.

### 60 fps sensor modes (blank rows)

- The 17U's 60 fps modes deliver 3072 rows of which only 2560 hold image.
  New per-lens setting `image.cropEdgesHighFps`; the 17U profile drops the
  512 blank bottom rows on 0.6x, 1x, 3.2x and 4.3x. Viewfinder, metering
  and recording use the same area, so there's no blank strip and no
  reframe at record start.
- **Open Gate above 30 fps records 3408x2560 (4:3)** (was 4096x3072,
  which the encoder refused). The 60 fps sensor mode only has 2560 image
  rows, so the frame keeps the 30 fps 4:3 aspect with a centred crop. The
  viewfinder keeps the same 4:3 box; switching 30 to
  60 fps looks like a ~1.2x zoom, and the recording matches what the
  viewfinder shows. At 30 fps Open Gate stays 4096x3072.

### Recording UI

- **Clean recording view:** while recording, the top bar, lens and mode
  selectors, look strip and exposure row fade out. A swipe in any
  direction on the viewfinder shows them for 3 s; a tap still focuses.
  While they're shown, swipes work as before (quick panel).
- Scopes keep their rounded corners while recording (the scope was
  letterboxed to the video aspect and the black bars hid the corners).
- **Do not disturb while recording** (Settings › Video Encoder › While
  recording, off by default). Turns on Do Not Disturb with the user's
  priority rules at record start and restores the previous mode at stop.
  Left alone if Do Not Disturb is already on or changed meanwhile; undone
  on the next launch if the app closed mid-recording. Needs Do Not Disturb
  access (asked the first time).
- Timing row hides SHORT/GPU/LAT/GOP while recording.

### From upstream Rawr (Ed): #129 to #133

- #129 multiframe follows the lens photo remosaic (Detail on quad lenses,
  High elsewhere); full-size quad remosaic runs on the GPU in strips.
- #130 Rexturation; #131 and #132 Rexture fixes around moving subjects
  (blotches, grain patches, moving water).
- #133 Bayer reduced output.
- Merged by hand into the 17U code: the 200 MP fast remosaic (single
  stills and bursts on 4x4 blocks) and the 200 MP JPEG from the blocks
  stay; the zero-copy reduced burst stays for 4x4 blocks, Bayer reduced
  frames take the upload path.
- Checked on the 17U after the merge: 50 MP singles and 17-frame
  multiframe on 1x and 3.2x (DNG and JPEG); 200 MP singles on the tele
  (fast remosaic ~0.8-0.9 s, JPEG ~8.5 s after the press); 4K60 10-bit
  recording with no encoder drops.

### Auxiliary sensors (PR #1 by the-marox)

- **Light meter** (Settings › Display › Light meter, off by default): lux,
  incident EV, colour temperature, mains flicker and laser AF distance from
  the sensors beside the rear cameras, with a warning when the shutter
  speed will band. Values checked on the 17U against another app. Hidden
  in the clean recording view, like the other viewfinder controls. Sits
  at the top centre as the phone is held, in portrait and landscape (in
  landscape it used to spill over the top bar).
- **Antibanding** in Auto follows the phone's flicker sensor when it has
  one.
- **Gyroflow gyro log** (Settings › Video Encoder › Motion data, off by
  default): gyroscope and accelerometer in a .gcsv named like the video
  (the 17U delivers ~238 Hz). Videos in DCIM get it in
  Download/RAWR/Gyroflow; in a picked folder it sits beside the video.
  Not tested in Gyroflow yet: the file format, timing and units were
  checked, the stabilisation itself was not.
- Sensor readings also go into the photo/video metadata (EXIF/XMP).

### Removed for now

- **120 fps** is out of the fps button until the video pipeline records it
  without drops. A saved 120 falls back to 30.

### Fixes

- **Tap-to-focus in the bottom third of the viewfinder.** The exposure /
  look strip swallowed taps across its whole width; now only its buttons
  take taps and the empty space focuses.
- **Scopes ignore the blank rows of the 60 fps modes.** The idle
  waveform/vectorscope sampled the whole frame, padding included, so in
  Open Gate at 60 fps the waveform showed an empty band. They now sample
  only the image area (bottom/right padding).

### Known issues

- One frame can still drop in the first second of a recording; one
  mid-recording drop was seen once in Open Gate at 60 fps.
- The app's main thread uses ~24% of a core while recording.

### Build

- CI "Debug build" workflow: `variant` (debug / benchmark) and
  `skip_tests` inputs; the benchmark variant installs as
  `x17u.rawr.camera.benchmark` ("Rawr 17U Benchmark") beside the debug
  app. Shader SPIR-V uploaded as an artifact.

## 0.5.1-17u.2

- Photo: aspect ratio crop of the JPEG (the DNG stays full), picker in the
  top bar; plane-aware gain map edge crop.
- RAW import cache: 32 entries; a full cache is transient.

## 0.5.1-17u.1

- First signed release of the fork.
