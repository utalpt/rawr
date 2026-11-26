#pragma once
#include <memory>
#include <string>

#include "vulkan/RawAhbImporter.h"

namespace rawrcam::vulkan {
// RAW16 ingress for drivers whose Vulkan refuses camera RAW AHBs. The Xiaomi 15's Adreno 830 driver
// (7cd9369a93) fails vkAllocateMemory with VK_ERROR_INVALID_EXTERNAL_HANDLE for every RAW16/RAW10
// AHB, image or buffer, any usage or memory type, yet imports BLOB AHBs, and its GLES driver reads
// the RAW16 frame as an R16UI EGLImage bit-exactly. So GLES packs each frame into a BLOB AHB that
// Vulkan imports as a buffer, and the existing GPU copy (RawCpuUploadPool::gpuCopy) takes it from
// there: no CPU pixel copy. One BLOB per realtime frame slot; the camera frame is imported per frame.
// RAW10 uses the same BLOB but imports the camera plane as a storage buffer and decodes it on the GPU
// (packRaw10), because the packed MIPI layout cannot be read as an R16 image.
class GlesRawBridge {
   public:
    GlesRawBridge();
    ~GlesRawBridge();
    // Packs `camera` into the slot's BLOB and returns it as a gpuCopy source. Waits `fence` (not
    // owned) and finishes the GLES work before returning. The slot must be retired. Throws on any
    // failure, leaving the caller's CPU upload in charge.
    ImportedRaw pack(VkDevice device, PFN_vkGetAndroidHardwareBufferPropertiesANDROID props, uint32_t slot,
                     AHardwareBuffer* camera, int fence, uint32_t w, uint32_t h);
    // RAW10 has no R16 EGLImage to read: the plane is packed MIPI RAW10, four 10-bit values in five bytes,
    // which no sampler exposes. So the camera AHB is imported as a shader storage buffer and a compute
    // shader (ES 3.1) decodes each group into the slot BLOB exactly as imaging::unpackRaw10Row does,
    // leaving the same BLOB the RAW16 path produces for RawCpuUploadPool::gpuCopy. `rowStrideBytes` is the
    // plane stride reported by AImage_getPlaneRowStride; the decode addresses bytes, so any stride works.
    // `parity`, when not null, receives a one-line check of sampled rows against the CPU reference decode
    // (it needs a camera buffer the CPU may lock, i.e. a CPU-readable reader). Throws on any failure.
    ImportedRaw packRaw10(VkDevice device, PFN_vkGetAndroidHardwareBufferPropertiesANDROID props, uint32_t slot,
                          AHardwareBuffer* camera, int fence, uint32_t w, uint32_t h, uint32_t rowStrideBytes,
                          std::string* parity = nullptr);
    // Releases the EGL context and the BLOBs. Only with the device idle.
    void reset() noexcept;

   private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}  // namespace rawrcam::vulkan
