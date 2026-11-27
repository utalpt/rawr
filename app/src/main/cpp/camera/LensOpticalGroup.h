#pragma once

#include <string>

namespace rawrcam::camera {

struct LensRoute;

// Groups capture-screen lenses by the physical optics they stream from: facing, focal length and
// sensor size, read from the camera's characteristics (the physical sub-camera's when one is set).
// A logical camera and the physical camera behind it (Xiaomi 0 and 2) therefore share a group.
// Falls back to the camera id when the characteristics can't be read. Results are cached.
[[nodiscard]] std::string lensOpticalGroup(const std::string& cameraId, const std::string& physicalCameraId);

// The same, split further by an optical zoom position the lens asks the HAL for: a periscope that moves
// its optics per request (Xiaomi 17 Ultra tele, xiaomi.zoomRatio.debug.setZoomRatioCode) is one camera id
// but two focal lengths (75 to 100 mm on the 4.3x lenses, TeleOpticalZoom on the Kotlin side).
[[nodiscard]] std::string lensOpticalGroup(const LensRoute& route);

}  // namespace rawrcam::camera
