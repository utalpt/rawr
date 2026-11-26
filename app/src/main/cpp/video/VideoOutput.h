#pragma once
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <jni.h>
#include <vulkan/vulkan.h>

#include <array>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "imaging/FrameLimits.h"
#include "rawr/vk/OwnedImage.h"
namespace rawrcam::vulkan {
class VulkanContext;
}
namespace rawrcam::video {
class VideoOutput final {
   public:
    explicit VideoOutput(vulkan::VulkanContext& context) : context_(context) {}
    ~VideoOutput();
    VideoOutput(const VideoOutput&) = delete;
    VideoOutput& operator=(const VideoOutput&) = delete;
    bool start(JNIEnv* env, jobject surface, uint32_t width, uint32_t height, uint32_t bitDepth);
    void stop() noexcept;
    bool ready() const noexcept { return swapchain_ != VK_NULL_HANDLE || yuv_; }
    // YUV mode: the encoder is fed P010/NV12 through an ImageWriter that the GPU
    // writes directly, so the platform never converts RGBA on the GPU.
    bool yuv() const noexcept { return yuv_; }
    // Layout the output images rest in between frames.
    VkImageLayout restingLayout() const noexcept {
        return yuv_ ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    }
    // YUV mode: converts output image imageIndex into its encoder buffer. Recorded
    // after the tonemap, with the image in GENERAL layout.
    void recordYuvPack(VkCommandBuffer command, uint32_t imageIndex);
    uint64_t yuvNoBufferDrops() const noexcept { return yuvNoBuffer_.load(std::memory_order_relaxed); }
    struct YuvStats {
        uint64_t noBuffer = 0;
        uint64_t noReady = 0;
        uint64_t noImage = 0;
        uint64_t acquires = 0;
        uint64_t dequeues = 0;
        uint64_t queued = 0;
        uint64_t fenceWaits = 0;
        double dequeueMs = 0.0;
        double dequeueMaxMs = 0.0;
        double fenceMs = 0.0;
        double fenceMaxMs = 0.0;
    };
    YuvStats yuvStats() const noexcept;
    VkExtent2D extent() const noexcept { return extent_; }
    VkFormat format() const noexcept { return outputFormat_; }
    VkFormat preferredFormat(uint32_t bitDepth) const;
    double swapchainMs() const noexcept { return swapchainMs_; }
    const std::string& offeredFormats() const noexcept { return offeredFormats_; }
    const char* outputFormatName() const noexcept {
        if (!ready()) return "none";
        if (yuv_) return outputFormat_ == VK_FORMAT_R8G8B8A8_UNORM ? "nv12" : "p010";
        if (outputFormat_ == VK_FORMAT_R8G8B8A8_UNORM) return "rgba8";
        return outputFormat_ == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ? "rgb10a2" : "rgba16f";
    }
    bool acquire(uint32_t frameSlot, uint32_t* imageIndex, uint64_t frameOrdinal = 0,
                 uint64_t timestampNs = 0);
    uint32_t encoderImageCount() const noexcept { return static_cast<uint32_t>(images_.size()); }
    // VK_NULL_HANDLE in YUV mode: the output image is free when acquire() returns.
    VkSemaphore available(uint32_t frameSlot) const { return yuv_ ? VK_NULL_HANDLE : available_[frameSlot]; }
    VkSemaphore rendered(uint32_t frameSlot) const { return rendered_[frameSlot]; }
    // presentTimeNs (CLOCK_MONOTONIC) becomes the encoded frame's PTS when the
    // driver supports display timing; 0 keeps the queue-time timestamp.
    VkResult present(VkQueue queue, uint32_t frameSlot, uint32_t imageIndex, uint64_t presentTimeNs);
    // Whether present() stamps frames with the caller's time.
    bool stampsPresentTime() const noexcept;
    // CLOCK_MONOTONIC at start(). Frames captured earlier predate the recording.
    int64_t startedAtNs() const noexcept { return startedAtNs_; }

   private:
    friend class VideoRecorder;
    struct EncoderBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        uint32_t yOffsetWords = 0, yStrideWords = 0, cOffsetWords = 0, cStrideWords = 0, swapUV = 0;
    };
    struct YuvFrame {
        jobject image = nullptr;  // global ref while dequeued
        EncoderBuffer* buffer = nullptr;
        int64_t timestampNs = 0;
        bool inUse = false;
    };
    struct ReadyImage {
        jobject image = nullptr;
        EncoderBuffer* buffer = nullptr;
    };
    bool startYuv(JNIEnv* env, jobject surface, uint32_t width, uint32_t height, uint32_t bitDepth);
    void stopYuv() noexcept;
    void feederLoop();
    EncoderBuffer* importEncoderBuffer(AHardwareBuffer* ahb);
    bool dequeueEncoderImage(JNIEnv* env);
    void queueEncoderImage(JNIEnv* env, uint32_t index);
    // Headroom for a slow (warm) GPU: frames in flight plus buffers already in
    // hand. 6 writer images ran dry at 4K60 (~60 drops per 12 s on a warm phone).
    static constexpr uint32_t kYuvImages = 6;       // output images (GPU side)
    static constexpr uint32_t kYuvWriterImages = 16;  // encoder buffers we may hold at once
    uint32_t yuvReadyTarget_ = 4;  // latched at start; six is opt-in on dada
    bool yuv_ = false;
    JavaVM* vm_ = nullptr;
    jobject writer_ = nullptr;
    jclass writerClass_ = nullptr, imageClass_ = nullptr, hardwareBufferClass_ = nullptr;
    jmethodID dequeue_ = nullptr, queue_ = nullptr, writerClose_ = nullptr, getHardwareBuffer_ = nullptr,
              setTimestamp_ = nullptr, setDataSpace_ = nullptr, imageClose_ = nullptr, hardwareBufferClose_ = nullptr;
    std::vector<rawr::vk::OwnedImage> ownedImages_;  // internal output images
    std::unordered_map<uint64_t, std::unique_ptr<EncoderBuffer>> encoderBuffers_;
    std::array<YuvFrame, kYuvImages> yuvFrames_{};
    std::array<VkFence, kYuvImages> yuvFences_{};
    std::deque<ReadyImage> readyImages_;
    std::deque<uint32_t> pendingImages_;
    uint32_t outstandingImages_ = 0;
    uint32_t nextYuvIndex_ = 0;
    mutable std::mutex yuvMutex_;
    std::condition_variable yuvCv_;
    bool yuvStopping_ = false;
    std::thread feeder_;
    std::atomic<uint64_t> yuvNoBuffer_{0};
    // Why a frame found no encoder buffer: none dequeued yet (encoder holding
    // them) or every GPU output image still in flight (GPU behind).
    uint64_t yuvNoReady_ = 0, yuvNoImage_ = 0, yuvAcquires_ = 0;  // yuvMutex_
    double yuvDequeueMs_ = 0, yuvDequeueMaxMs_ = 0, yuvFenceMs_ = 0, yuvFenceMaxMs_ = 0;  // feeder
    uint64_t yuvDequeues_ = 0, yuvQueued_ = 0, yuvFenceWaits_ = 0;  // yuvMutex_
    double yuvWriterMs_ = 0, yuvWriterMaxMs_ = 0, yuvImportMs_ = 0, yuvImportMaxMs_ = 0;  // feeder
    uint64_t yuvImports_ = 0;  // feeder
    VkDescriptorSetLayout packSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout packLayout_ = VK_NULL_HANDLE;
    VkPipeline packPipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool packPool_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, kYuvImages> packSets_{};
    static constexpr uint32_t kPreferredEncoderImages = 5;
    static constexpr uint64_t kEncoderAcquireWaitNs = 3'000'000;
    rawrcam::vulkan::VulkanContext& context_;
    ANativeWindow* window_ = nullptr;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkExtent2D extent_{};
    VkFormat outputFormat_ = VK_FORMAT_R16G16B16A16_SFLOAT;
    std::string offeredFormats_;
    double swapchainMs_ = 0;
    int64_t startedAtNs_ = 0;
    std::vector<VkImage> images_;
    std::vector<VkImageView> views_;
    std::vector<bool> initialized_;
    std::array<VkSemaphore, rawrcam::imaging::kRealtimeFramesInFlight> available_{};
    std::array<VkSemaphore, rawrcam::imaging::kRealtimeFramesInFlight> rendered_{};
};
}  // namespace rawrcam::video
