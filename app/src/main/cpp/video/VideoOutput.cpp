#include "video/VideoOutput.h"

#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>
#include <ctime>

#include <pthread.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include "video_yuv_pack_nv12.h"
#include "video_yuv_pack_p010.h"
#include "video/VideoAcquirePolicy.h"
#include "vulkan/ImageResources.h"
#include "vulkan/RawImportEvidence.h"
#include "vulkan/VulkanContext.h"

namespace rawrcam::video {
namespace {
void check(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(std::string(what) + " VkResult=" + std::to_string(r));
}
#define YLOG(...) __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo", __VA_ARGS__)
int64_t monotonicNs() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return int64_t(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
}

uint32_t acquireWaitMs() noexcept {
    char device[PROP_VALUE_MAX] = {};
    char setting[PROP_VALUE_MAX] = {};
    __system_property_get("ro.product.device", device);
    __system_property_get("debug.rawr.video_acquire_wait_ms", setting);
    return rawrcam::video::videoAcquireWaitMs(device, setting);
}
bool clearException(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}
}  // namespace
VideoOutput::~VideoOutput() { stop(); }
bool VideoOutput::start(JNIEnv* env, jobject javaSurface, uint32_t width, uint32_t height, uint32_t bitDepth) {
    stop();
    const auto startedAt = std::chrono::steady_clock::now();
    const auto sinceStartMs = [&] {
        return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - startedAt).count();
    };
    if (!javaSurface || !context_.device() || width == 0 || height == 0 || (bitDepth != 8 && bitDepth != 10))
        return false;
    // YUV input by default; debug.rawr.video.yuv=0 keeps the RGBA swapchain.
    char yuvProp[PROP_VALUE_MAX] = {};
    __system_property_get("debug.rawr.video.yuv", yuvProp);
    if (std::string(yuvProp) != "0") {
        try {
            if (startYuv(env, javaSurface, width, height, bitDepth)) {
                swapchainMs_ = sinceStartMs();
                return true;
            }
        } catch (const std::exception& error) {
            YLOG("VIDEO_YUV_OUTPUT fallback=rgba reason=%s", error.what());
        }
        stopYuv();
    }
    try {
        window_ = ANativeWindow_fromSurface(env, javaSurface);
        if (!window_) throw std::runtime_error("video ANativeWindow unavailable");
        VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = window_;
        check(vkCreateAndroidSurfaceKHR(context_.instance(), &surfaceInfo, nullptr, &surface_), "video surface");
        VkBool32 supported = VK_FALSE;
        check(vkGetPhysicalDeviceSurfaceSupportKHR(context_.physicalDevice(), context_.queueFamily(), surface_,
                                                   &supported),
              "video queue support");
        if (!supported) throw std::runtime_error("video queue cannot present to encoder Surface");
        VkSurfaceCapabilitiesKHR caps{};
        check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context_.physicalDevice(), surface_, &caps), "video caps");
        uint32_t count = 0;
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(context_.physicalDevice(), surface_, &count, nullptr),
              "video formats count");
        std::vector<VkSurfaceFormatKHR> formats(count);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(context_.physicalDevice(), surface_, &count, formats.data()),
              "video formats");
        offeredFormats_.clear();
        for (const auto& format : formats)
            offeredFormats_ += (offeredFormats_.empty() ? "" : ",") + std::to_string(format.format) + ":" +
                               std::to_string(format.colorSpace);
        const auto find = [&](VkFormat wanted) {
            return std::find_if(formats.begin(), formats.end(),
                                [wanted](const VkSurfaceFormatKHR& format) { return format.format == wanted; });
        };
        // Prefer the 4-byte formats: the encoder converts RGBA16F with extra
        // work that competes with recording at 4K and Open Gate.
        auto selected = bitDepth == 8                            ? find(VK_FORMAT_R8G8B8A8_UNORM)
                        : context_.storageImageExtendedFormats() ? find(VK_FORMAT_A2B10G10R10_UNORM_PACK32)
                                                                 : formats.end();
        VkFormatProperties packedProperties{};
        if (selected != formats.end()) {
            vkGetPhysicalDeviceFormatProperties(context_.physicalDevice(), selected->format, &packedProperties);
            if ((packedProperties.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0)
                selected = formats.end();
        }
        if (selected == formats.end()) selected = find(VK_FORMAT_R16G16B16A16_SFLOAT);
        if (selected == formats.end() || (caps.supportedUsageFlags & VK_IMAGE_USAGE_STORAGE_BIT) == 0)
            throw std::runtime_error("encoder Surface does not support RGBA16F storage");
        outputFormat_ = selected->format;
        extent_ = caps.currentExtent.width == UINT32_MAX ? VkExtent2D{width, height} : caps.currentExtent;
        if (extent_.width != width || extent_.height != height)
            throw std::runtime_error("encoder Surface extent differs from codec configuration");
        VkSwapchainCreateInfoKHR swapInfo{};
        swapInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        swapInfo.surface = surface_;
        if (caps.maxImageCount && std::max(3u, caps.minImageCount) > caps.maxImageCount)
            throw std::runtime_error("encoder Surface has too few swapchain images");
        swapInfo.imageFormat = selected->format;
        swapInfo.imageColorSpace = selected->colorSpace;
        swapInfo.imageExtent = extent_;
        swapInfo.imageArrayLayers = 1;
        swapInfo.imageUsage = VK_IMAGE_USAGE_STORAGE_BIT;
        swapInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapInfo.preTransform = caps.currentTransform;
        swapInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        swapInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        swapInfo.clipped = VK_TRUE;
        // Ask for spare images so the encoder can absorb a brief stall. On the
        // test phone the Surface's own minimum (11-18) is already higher; the
        // request matters where it is lower. Fall back if it is refused.
        VkResult created = VK_ERROR_INITIALIZATION_FAILED;
        for (uint32_t wanted : {kPreferredEncoderImages, 4u, 3u}) {
            uint32_t images = std::max(wanted, caps.minImageCount);
            if (caps.maxImageCount) images = std::min(images, caps.maxImageCount);
            swapInfo.minImageCount = images;
            created = vkCreateSwapchainKHR(context_.device(), &swapInfo, nullptr, &swapchain_);
            if (created == VK_SUCCESS) break;
            swapchain_ = VK_NULL_HANDLE;
        }
        check(created, "video swapchain");
        count = 0;
        check(vkGetSwapchainImagesKHR(context_.device(), swapchain_, &count, nullptr), "video images count");
        images_.resize(count);
        check(vkGetSwapchainImagesKHR(context_.device(), swapchain_, &count, images_.data()), "video images");
        views_.resize(count);
        initialized_.assign(count, false);
        for (uint32_t i = 0; i < count; ++i) {
            VkImageViewCreateInfo view{};
            view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            view.image = images_[i];
            view.viewType = VK_IMAGE_VIEW_TYPE_2D;
            view.format = outputFormat_;
            view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            check(vkCreateImageView(context_.device(), &view, nullptr, &views_[i]), "video image view");
        }
        VkSemaphoreCreateInfo semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        for (uint32_t i = 0; i < available_.size(); ++i) {
            check(vkCreateSemaphore(context_.device(), &semaphoreInfo, nullptr, &available_[i]),
                  "video available semaphore");
            check(vkCreateSemaphore(context_.device(), &semaphoreInfo, nullptr, &rendered_[i]),
                  "video rendered semaphore");
        }
        swapchainMs_ = sinceStartMs();
        timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        startedAtNs_ = int64_t(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
        return true;
    } catch (...) {
        stop();
        throw;
    }
}
VkFormat VideoOutput::preferredFormat(uint32_t bitDepth) const {
    const VkFormat format = bitDepth == 8                            ? VK_FORMAT_R8G8B8A8_UNORM
                            : context_.storageImageExtendedFormats() ? VK_FORMAT_A2B10G10R10_UNORM_PACK32
                                                                     : VK_FORMAT_R16G16B16A16_SFLOAT;
    VkFormatProperties properties{};
    vkGetPhysicalDeviceFormatProperties(context_.physicalDevice(), format, &properties);
    return (properties.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0
               ? format
               : VK_FORMAT_R16G16B16A16_SFLOAT;
}
void VideoOutput::stop() noexcept {
    stopYuv();
    const VkDevice device = context_.device();
    if (device) {
        if (swapchain_) (void)context_.waitIdle();
        for (auto& view : views_)
            if (view) vkDestroyImageView(device, view, nullptr);
        for (auto& semaphore : available_)
            if (semaphore) vkDestroySemaphore(device, semaphore, nullptr);
        for (auto& semaphore : rendered_)
            if (semaphore) vkDestroySemaphore(device, semaphore, nullptr);
        if (swapchain_) vkDestroySwapchainKHR(device, swapchain_, nullptr);
    }
    views_.clear();
    images_.clear();
    initialized_.clear();
    available_.fill(VK_NULL_HANDLE);
    rendered_.fill(VK_NULL_HANDLE);
    swapchain_ = VK_NULL_HANDLE;
    if (surface_ && context_.instance()) vkDestroySurfaceKHR(context_.instance(), surface_, nullptr);
    surface_ = VK_NULL_HANDLE;
    if (window_) ANativeWindow_release(window_);
    window_ = nullptr;
    extent_ = {};
    startedAtNs_ = 0;
}
VideoOutput::YuvStats VideoOutput::yuvStats() const noexcept {
    std::lock_guard<std::mutex> lock(yuvMutex_);
    return {yuvNoBuffer_.load(std::memory_order_relaxed), yuvNoReady_, yuvNoImage_, yuvAcquires_,
            yuvDequeues_, yuvQueued_, yuvFenceWaits_, yuvDequeues_ ? yuvDequeueMs_ / double(yuvDequeues_) : 0.0,
            yuvDequeueMaxMs_, yuvFenceWaits_ ? yuvFenceMs_ / double(yuvFenceWaits_) : 0.0, yuvFenceMaxMs_};
}

bool VideoOutput::acquire(uint32_t frameSlot, uint32_t* imageIndex, uint64_t frameOrdinal,
                           uint64_t timestampNs) {
    if (!ready() || frameSlot >= available_.size()) return false;
    if (yuv_) {
        std::unique_lock<std::mutex> lock(yuvMutex_);
        const auto findFree = [this] {
            for (uint32_t k = 0; k < kYuvImages; ++k) {
                const uint32_t candidate = (nextYuvIndex_ + k) % kYuvImages;
                if (!yuvFrames_[candidate].inUse) return candidate;
            }
            return kYuvImages;
        };
        uint32_t index = findFree();
        ++yuvAcquires_;
        const uint32_t waitMs = acquireWaitMs();
        if ((readyImages_.empty() || index == kYuvImages) && waitMs > 0) {
            yuvCv_.wait_for(lock, std::chrono::milliseconds(waitMs), [&] {
                index = findFree();
                return !readyImages_.empty() && index != kYuvImages;
            });
        }
        if (readyImages_.empty() || index == kYuvImages) {
            // A short wait is opt-in and limited to the validated dada device.
            // Classify the miss so a recording can distinguish feeder starvation
            // from all GPU output images still being in flight.
            yuvNoBuffer_.fetch_add(1, std::memory_order_relaxed);
            if (index == kYuvImages) {
                ++yuvNoImage_;
                YLOG("VIDEO_YUV_ACQUIRE_DROP reason=noImage frameOrdinal=%llu timestampNs=%llu waitMs=%u "
                     "ready=%zu pending=%zu outstanding=%u",
                     static_cast<unsigned long long>(frameOrdinal), static_cast<unsigned long long>(timestampNs),
                     waitMs, readyImages_.size(), pendingImages_.size(), outstandingImages_);
            } else {
                ++yuvNoReady_;
                YLOG("VIDEO_YUV_ACQUIRE_DROP reason=noReady frameOrdinal=%llu timestampNs=%llu waitMs=%u "
                     "ready=%zu pending=%zu outstanding=%u",
                     static_cast<unsigned long long>(frameOrdinal), static_cast<unsigned long long>(timestampNs),
                     waitMs, readyImages_.size(), pendingImages_.size(), outstandingImages_);
            }
            yuvCv_.notify_one();
            return false;
        }
        if (yuvAcquires_ % 120 == 0)
            YLOG("VIDEO_YUV_FLOW acquires=%llu noReady=%llu noImage=%llu ready=%zu pending=%zu outstanding=%u "
                 "dequeueMsAvg=%.2f dequeueMsMax=%.1f fenceMsAvg=%.2f fenceMsMax=%.1f queued=%llu",
                 static_cast<unsigned long long>(yuvAcquires_), static_cast<unsigned long long>(yuvNoReady_),
                 static_cast<unsigned long long>(yuvNoImage_), readyImages_.size(), pendingImages_.size(),
                 outstandingImages_, yuvDequeues_ ? yuvDequeueMs_ / double(yuvDequeues_) : 0.0, yuvDequeueMaxMs_,
                 yuvFenceWaits_ ? yuvFenceMs_ / double(yuvFenceWaits_) : 0.0, yuvFenceMaxMs_,
                 static_cast<unsigned long long>(yuvQueued_));
        const ReadyImage next = readyImages_.front();
        readyImages_.pop_front();
        yuvFrames_[index] = {next.image, next.buffer, 0, true};
        nextYuvIndex_ = (index + 1) % kYuvImages;
        VkDescriptorImageInfo imageInfo{VK_NULL_HANDLE, views_[index], VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorBufferInfo bufferInfo{next.buffer->buffer, 0, VK_WHOLE_SIZE};
        std::array<VkWriteDescriptorSet, 2> writes{};
        for (auto& w : writes) {
            w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w.dstSet = packSets_[index];
            w.descriptorCount = 1;
        }
        writes[0].dstBinding = 0;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[0].pImageInfo = &imageInfo;
        writes[1].dstBinding = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[1].pBufferInfo = &bufferInfo;
        vkUpdateDescriptorSets(context_.device(), 2, writes.data(), 0, nullptr);
        yuvCv_.notify_one();
        *imageIndex = index;
        return true;
    }
    const VkResult result = vkAcquireNextImageKHR(context_.device(), swapchain_, kEncoderAcquireWaitNs,
                                                  available_[frameSlot], VK_NULL_HANDLE, imageIndex);
    if (result == VK_NOT_READY || result == VK_TIMEOUT) return false;
    check(result, "video acquire");
    return true;
}
bool VideoOutput::stampsPresentTime() const noexcept { return yuv_ || context_.displayTimingEnabled(); }
VkResult VideoOutput::present(VkQueue queue, uint32_t frameSlot, uint32_t imageIndex, uint64_t presentTimeNs) {
    if (yuv_) {
        // The caller holds the queue lock. A fence after the frame's work tells
        // the feeder thread when the encoder buffer is complete.
        std::lock_guard<std::mutex> lock(yuvMutex_);
        if (imageIndex >= kYuvImages || !yuvFrames_[imageIndex].inUse) return VK_ERROR_UNKNOWN;
        VkResult r = vkResetFences(context_.device(), 1, &yuvFences_[imageIndex]);
        if (r != VK_SUCCESS) return r;
        const VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &rendered_[frameSlot];
        submit.pWaitDstStageMask = &stage;
        r = vkQueueSubmit(queue, 1, &submit, yuvFences_[imageIndex]);
        if (r != VK_SUCCESS) return r;
        yuvFrames_[imageIndex].timestampNs = presentTimeNs ? int64_t(presentTimeNs) : monotonicNs();
        pendingImages_.push_back(imageIndex);
        yuvCv_.notify_one();
        return VK_SUCCESS;
    }
    VkPresentTimeGOOGLE time{};
    time.presentID = 0;
    time.desiredPresentTime = presentTimeNs;
    VkPresentTimesInfoGOOGLE times{};
    times.sType = VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE;
    times.swapchainCount = 1;
    times.pTimes = &time;
    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    if (presentTimeNs != 0 && stampsPresentTime()) present.pNext = &times;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &rendered_[frameSlot];
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain_;
    present.pImageIndices = &imageIndex;
    return vkQueuePresentKHR(queue, &present);
}

// ---- YUV encoder input -------------------------------------------------------

bool VideoOutput::startYuv(JNIEnv* env, jobject surface, uint32_t width, uint32_t height, uint32_t bitDepth) {
    if (width % 4u != 0u || height % 2u != 0u) throw std::runtime_error("size not a multiple of 4x2");
    const VkFormat format = preferredFormat(bitDepth);
    if (format != VK_FORMAT_R8G8B8A8_UNORM && format != VK_FORMAT_A2B10G10R10_UNORM_PACK32)
        throw std::runtime_error("no 8/10-bit storage output format");
    if (env->GetJavaVM(&vm_) != JNI_OK) throw std::runtime_error("no JavaVM");
    const VkDevice device = context_.device();
    auto globalClass = [&](const char* name) {
        jclass local = env->FindClass(name);
        if (clearException(env) || !local) throw std::runtime_error(std::string("class ") + name);
        auto global = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return global;
    };
    writerClass_ = globalClass("android/media/ImageWriter");
    imageClass_ = globalClass("android/media/Image");
    hardwareBufferClass_ = globalClass("android/hardware/HardwareBuffer");
    const jmethodID newInstance =
        env->GetStaticMethodID(writerClass_, "newInstance", "(Landroid/view/Surface;II)Landroid/media/ImageWriter;");
    dequeue_ = env->GetMethodID(writerClass_, "dequeueInputImage", "()Landroid/media/Image;");
    queue_ = env->GetMethodID(writerClass_, "queueInputImage", "(Landroid/media/Image;)V");
    writerClose_ = env->GetMethodID(writerClass_, "close", "()V");
    getHardwareBuffer_ = env->GetMethodID(imageClass_, "getHardwareBuffer", "()Landroid/hardware/HardwareBuffer;");
    setTimestamp_ = env->GetMethodID(imageClass_, "setTimestamp", "(J)V");
    setDataSpace_ = env->GetMethodID(imageClass_, "setDataSpace", "(I)V");
    imageClose_ = env->GetMethodID(imageClass_, "close", "()V");
    hardwareBufferClose_ = env->GetMethodID(hardwareBufferClass_, "close", "()V");
    if (clearException(env) || !newInstance || !dequeue_ || !queue_ || !writerClose_ || !getHardwareBuffer_ ||
        !setTimestamp_ || !setDataSpace_ || !imageClose_ || !hardwareBufferClose_)
        throw std::runtime_error("ImageWriter methods");
    // ImageFormat.YUV_420_888 (NV12 layout on the encoder) / ImageFormat.YCBCR_P010.
    const jint writerFormat = bitDepth == 8 ? 0x23 : 0x36;
    jobject writer = env->CallStaticObjectMethod(writerClass_, newInstance, surface,
                                                 static_cast<jint>(kYuvWriterImages), writerFormat);
    if (clearException(env) || !writer) throw std::runtime_error("ImageWriter.newInstance");
    writer_ = env->NewGlobalRef(writer);
    env->DeleteLocalRef(writer);

    // Pack pipeline: storage image in, encoder buffer out.
    std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
    bindings[0] = {0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    bindings[1] = {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = bindings.size();
    setInfo.pBindings = bindings.data();
    check(vkCreateDescriptorSetLayout(device, &setInfo, nullptr, &packSetLayout_), "yuv set layout");
    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, 8 * sizeof(uint32_t)};
    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &packSetLayout_;
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &push;
    check(vkCreatePipelineLayout(device, &layoutInfo, nullptr, &packLayout_), "yuv pipeline layout");
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = bitDepth == 8 ? video_yuv_pack_nv12_spv_size : video_yuv_pack_p010_spv_size;
    shaderInfo.pCode = reinterpret_cast<const uint32_t*>(bitDepth == 8 ? video_yuv_pack_nv12_spv
                                                                       : video_yuv_pack_p010_spv);
    VkShaderModule module = VK_NULL_HANDLE;
    check(vkCreateShaderModule(device, &shaderInfo, nullptr, &module), "yuv shader");
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipelineInfo.stage.module = module;
    pipelineInfo.stage.pName = "main";
    pipelineInfo.layout = packLayout_;
    const VkResult pipelineResult =
        vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &packPipeline_);
    vkDestroyShaderModule(device, module, nullptr);
    check(pipelineResult, "yuv pipeline");
    std::array<VkDescriptorPoolSize, 2> sizes{{{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kYuvImages},
                                               {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kYuvImages}}};
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = kYuvImages;
    poolInfo.poolSizeCount = sizes.size();
    poolInfo.pPoolSizes = sizes.data();
    check(vkCreateDescriptorPool(device, &poolInfo, nullptr, &packPool_), "yuv pool");
    std::array<VkDescriptorSetLayout, kYuvImages> layouts{};
    layouts.fill(packSetLayout_);
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = packPool_;
    allocInfo.descriptorSetCount = kYuvImages;
    allocInfo.pSetLayouts = layouts.data();
    check(vkAllocateDescriptorSets(device, &allocInfo, packSets_.data()), "yuv sets");

    // Output images the tonemap writes (same formats as the RGBA swapchain).
    outputFormat_ = format;
    extent_ = {width, height};
    for (uint32_t i = 0; i < kYuvImages; ++i) {
        ownedImages_.push_back(rawrcam::vulkan::createOwnedImage(context_.physicalDevice(), device, width, height,
                                                                 format, VK_IMAGE_USAGE_STORAGE_BIT));
        images_.push_back(ownedImages_.back().image);
        views_.push_back(ownedImages_.back().view);
    }
    initialized_.assign(kYuvImages, false);
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    for (auto& fence : yuvFences_) check(vkCreateFence(device, &fenceInfo, nullptr, &fence), "yuv fence");
    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (uint32_t i = 0; i < available_.size(); ++i) {
        check(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &available_[i]), "yuv available semaphore");
        check(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &rendered_[i]), "yuv rendered semaphore");
    }
    offeredFormats_ = bitDepth == 8 ? "imagewriter:nv12" : "imagewriter:p010";
    yuvStopping_ = false;
    outstandingImages_ = 0;
    nextYuvIndex_ = 0;
    yuvNoBuffer_.store(0, std::memory_order_relaxed);
    // Have encoder buffers in hand (and their imports done) before the first frame.
    char productDevice[PROP_VALUE_MAX] = {}, readySetting[PROP_VALUE_MAX] = {};
    __system_property_get("ro.product.device", productDevice);
    __system_property_get("debug.rawr.video_yuv_ready", readySetting);
    yuvReadyTarget_ = videoReadyBufferCount(productDevice, readySetting);
    for (uint32_t i = 0; i < yuvReadyTarget_; ++i) {
        if (!dequeueEncoderImage(env)) throw std::runtime_error("first encoder buffer");
        ++outstandingImages_;
    }
    yuv_ = true;
    startedAtNs_ = monotonicNs();
    feeder_ = std::thread([this] { feederLoop(); });
    YLOG("VIDEO_YUV_OUTPUT format=%s %ux%u images=%u writerImages=%u", bitDepth == 8 ? "nv12" : "p010", width,
         height, kYuvImages, kYuvWriterImages);
    return true;
}

VideoOutput::EncoderBuffer* VideoOutput::importEncoderBuffer(AHardwareBuffer* ahb) {
    uint64_t id = 0;
    if (AHardwareBuffer_getId(ahb, &id) != 0) throw std::runtime_error("AHardwareBuffer_getId");
    if (auto found = encoderBuffers_.find(id); found != encoderBuffers_.end()) return found->second.get();
    const bool tenBit = outputFormat_ == VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    const uint32_t sampleBytes = tenBit ? 2u : 1u;
    AHardwareBuffer_Planes planes{};
    if (AHardwareBuffer_lockPlanes(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &planes) != 0)
        throw std::runtime_error("lockPlanes");
    const auto* base = static_cast<const uint8_t*>(planes.planes[0].data);
    const uint32_t count = planes.planeCount;
    const uint32_t yStride = planes.planes[0].rowStride;
    const size_t uOffset = count >= 3 ? static_cast<const uint8_t*>(planes.planes[1].data) - base : 0;
    const size_t vOffset = count >= 3 ? static_cast<const uint8_t*>(planes.planes[2].data) - base : 0;
    const uint32_t cStride = count >= 3 ? planes.planes[1].rowStride : 0;
    const uint32_t cPixel = count >= 3 ? planes.planes[1].pixelStride : 0;
    AHardwareBuffer_unlock(ahb, nullptr);
    const size_t cOffset = std::min(uOffset, vOffset);
    YLOG("VIDEO_YUV_BUFFER id=%llu planes=%u yStride=%u uOffset=%zu vOffset=%zu cStride=%u cPixelStride=%u",
         static_cast<unsigned long long>(id), count, yStride, uOffset, vOffset, cStride, cPixel);
    const size_t gap = uOffset > vOffset ? uOffset - vOffset : vOffset - uOffset;
    if (count < 3 || yStride % 4u || cStride % 4u || cOffset % 4u || cPixel != 2u * sampleBytes || gap != sampleBytes)
        throw std::runtime_error("unsupported encoder buffer layout");
    auto out = std::make_unique<EncoderBuffer>();
    out->yOffsetWords = 0;
    out->yStrideWords = yStride / 4u;
    out->cOffsetWords = static_cast<uint32_t>(cOffset / 4u);
    out->cStrideWords = cStride / 4u;
    out->swapUV = vOffset < uOffset ? 1u : 0u;
    const VkDevice device = context_.device();
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    check(context_.getAhbProperties()(device, ahb, &props), "encoder buffer properties");
    // Same recipe as the validated probe (VideoGpuProbe): a plain storage buffer.
    // Declaring the AHB handle type on the buffer crashes vkBindBufferMemory on Adreno 840.
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = props.allocationSize;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(device, &bufferInfo, nullptr, &out->buffer), "encoder buffer");
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.buffer = ahb;
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.pNext = &import;
    dedicated.buffer = out->buffer;
    VkMemoryAllocateInfo memoryInfo{};
    memoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    memoryInfo.pNext = &dedicated;
    memoryInfo.allocationSize = props.allocationSize;
    memoryInfo.memoryTypeIndex = rawrcam::vulkan::findImportMemoryType(context_.physicalDevice(), props.memoryTypeBits, 0);
    VkResult r = vkAllocateMemory(device, &memoryInfo, nullptr, &out->memory);
    if (r == VK_SUCCESS) r = vkBindBufferMemory(device, out->buffer, out->memory, 0);
    if (r != VK_SUCCESS) {
        if (out->memory) vkFreeMemory(device, out->memory, nullptr);
        vkDestroyBuffer(device, out->buffer, nullptr);
        check(r, "encoder buffer import");
    }
    auto* raw = out.get();
    encoderBuffers_.emplace(id, std::move(out));
    return raw;
}

bool VideoOutput::dequeueEncoderImage(JNIEnv* env) {
    jobject image = env->CallObjectMethod(writer_, dequeue_);
    if (clearException(env) || !image) return false;
    jobject hardwareBuffer = env->CallObjectMethod(image, getHardwareBuffer_);
    EncoderBuffer* buffer = nullptr;
    if (!clearException(env) && hardwareBuffer) {
        try {
            buffer = importEncoderBuffer(AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer));
        } catch (const std::exception& error) {
            YLOG("VIDEO_YUV_IMPORT_FAIL %s", error.what());
        }
        env->CallVoidMethod(hardwareBuffer, hardwareBufferClose_);
        clearException(env);
        env->DeleteLocalRef(hardwareBuffer);
    }
    if (!buffer) {
        env->CallVoidMethod(image, imageClose_);
        clearException(env);
        env->DeleteLocalRef(image);
        return false;
    }
    jobject global = env->NewGlobalRef(image);
    env->DeleteLocalRef(image);
    std::lock_guard<std::mutex> lock(yuvMutex_);
    readyImages_.push_back({global, buffer});
    yuvCv_.notify_all();
    return true;
}

void VideoOutput::queueEncoderImage(JNIEnv* env, uint32_t index) {
    jobject image = yuvFrames_[index].image;
    env->CallVoidMethod(image, setTimestamp_, static_cast<jlong>(yuvFrames_[index].timestampNs));
    clearException(env);
    // DataSpace.DATASPACE_BT709 (BT.709, SMPTE 170M transfer, limited range): what the encoder is configured for.
    env->CallVoidMethod(image, setDataSpace_, static_cast<jint>(281083904));
    clearException(env);
    env->CallVoidMethod(writer_, queue_, image);
    if (clearException(env)) YLOG("VIDEO_YUV_QUEUE_FAIL index=%u", index);
    env->DeleteGlobalRef(image);
}

void VideoOutput::feederLoop() {
    pthread_setname_np(pthread_self(), "RawrYuvFeed");
    JNIEnv* env = nullptr;
    if (!vm_ || vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    const VkDevice device = context_.device();
    std::unique_lock<std::mutex> lock(yuvMutex_);
    while (!yuvStopping_) {
        // Order: hand finished frames to the encoder, then keep encoder buffers
        // dequeued ahead, and only then block on a GPU fence. Blocking on fences
        // first (~15 ms per frame at 4K60) starved the ready list and dropped
        // frames (VIDEO_YUV_FLOW noReady). Finished frames still go first so an
        // encoder holding buffers for lookahead always gets its next input.
        const bool topUp = readyImages_.size() < yuvReadyTarget_ && outstandingImages_ < kYuvWriterImages;
        if (!pendingImages_.empty() &&
            (!topUp || vkGetFenceStatus(device, yuvFences_[pendingImages_.front()]) == VK_SUCCESS)) {
            const uint32_t index = pendingImages_.front();
            const VkFence fence = yuvFences_[index];
            lock.unlock();
            const auto w0 = std::chrono::steady_clock::now();
            const VkResult r = vkWaitForFences(device, 1, &fence, VK_TRUE, 2'000'000);
            const double waited = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - w0).count();
            if (r == VK_SUCCESS) queueEncoderImage(env, index);
            lock.lock();
            yuvFenceMs_ += waited;
            ++yuvFenceWaits_;
            if (waited > yuvFenceMaxMs_) yuvFenceMaxMs_ = waited;
            if (r == VK_SUCCESS) ++yuvQueued_;
            if (r != VK_TIMEOUT) {
                if (r != VK_SUCCESS) {  // lost frame: return the buffer unqueued
                    YLOG("VIDEO_YUV_FENCE_FAIL result=%d", r);
                    lock.unlock();
                    env->CallVoidMethod(yuvFrames_[index].image, imageClose_);
                    clearException(env);
                    env->DeleteGlobalRef(yuvFrames_[index].image);
                    lock.lock();
                }
                pendingImages_.pop_front();
                yuvFrames_[index] = {};
                --outstandingImages_;
                yuvCv_.notify_all();
            }
            continue;
        }
        if (topUp) {
            ++outstandingImages_;
            lock.unlock();
            // Blocks while the encoder holds every buffer.
            const auto d0 = std::chrono::steady_clock::now();
            const bool ok = dequeueEncoderImage(env);
            const double took = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - d0).count();
            lock.lock();
            yuvDequeueMs_ += took;
            if (took > yuvDequeueMaxMs_) yuvDequeueMaxMs_ = took;
            ++yuvDequeues_;
            if (!ok) {
                --outstandingImages_;
                lock.unlock();
                usleep(5000);
                lock.lock();
            }
            continue;
        }
        yuvCv_.wait_for(lock, std::chrono::milliseconds(20));
    }
    lock.unlock();
    vm_->DetachCurrentThread();
}

void VideoOutput::recordYuvPack(VkCommandBuffer command, uint32_t imageIndex) {
    if (!yuv_ || imageIndex >= kYuvImages || !yuvFrames_[imageIndex].buffer) return;
    const EncoderBuffer& buffer = *yuvFrames_[imageIndex].buffer;
    VkImageMemoryBarrier toneDone{};
    toneDone.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toneDone.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toneDone.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toneDone.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toneDone.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toneDone.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toneDone.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toneDone.image = images_[imageIndex];
    toneDone.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    // The encoder owned the buffer before: take it back (contents are rewritten).
    VkBufferMemoryBarrier acquireBuffer{};
    acquireBuffer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    acquireBuffer.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    acquireBuffer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    acquireBuffer.dstQueueFamilyIndex = context_.queueFamily();
    acquireBuffer.buffer = buffer.buffer;
    acquireBuffer.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquireBuffer, 1, &toneDone);
    struct {
        uint32_t width, height, yOffsetWords, yStrideWords, cOffsetWords, cStrideWords, swapUV, pad;
    } push{extent_.width, extent_.height, buffer.yOffsetWords, buffer.yStrideWords, buffer.cOffsetWords,
           buffer.cStrideWords, buffer.swapUV, 0};
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, packPipeline_);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, packLayout_, 0, 1, &packSets_[imageIndex], 0,
                            nullptr);
    vkCmdPushConstants(command, packLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    vkCmdDispatch(command, (extent_.width / 4u + 7u) / 8u, (extent_.height / 2u + 7u) / 8u, 1);
    // Hand the buffer to the encoder.
    VkBufferMemoryBarrier release{};
    release.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    release.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    release.srcQueueFamilyIndex = context_.queueFamily();
    release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    release.buffer = buffer.buffer;
    release.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                         nullptr, 1, &release, 0, nullptr);
}

void VideoOutput::stopYuv() noexcept {
    const bool active = yuv_ || writer_ || feeder_.joinable() || packPipeline_ || !ownedImages_.empty();
    if (!active) return;
    {
        std::lock_guard<std::mutex> lock(yuvMutex_);
        yuvStopping_ = true;
    }
    yuvCv_.notify_all();
    if (feeder_.joinable()) feeder_.join();
    const VkDevice device = context_.device();
    if (device) (void)context_.waitIdle();
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm_ && vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        attached = vm_->AttachCurrentThread(&env, nullptr) == JNI_OK;
        if (!attached) env = nullptr;
    }
    YLOG("VIDEO_YUV_STATS noBufferDrops=%llu noReady=%llu noImage=%llu buffers=%zu",
         static_cast<unsigned long long>(yuvNoBuffer_.load(std::memory_order_relaxed)),
         static_cast<unsigned long long>(yuvNoReady_), static_cast<unsigned long long>(yuvNoImage_),
         encoderBuffers_.size());
    yuvNoReady_ = yuvNoImage_ = yuvAcquires_ = yuvDequeues_ = yuvQueued_ = yuvFenceWaits_ = 0;
    yuvDequeueMs_ = yuvDequeueMaxMs_ = yuvFenceMs_ = yuvFenceMaxMs_ = 0;
    if (env) {
        // Closing the writer returns every dequeued image to the encoder Surface.
        if (writer_) {
            env->CallVoidMethod(writer_, writerClose_);
            clearException(env);
        }
        for (auto& held : readyImages_)
            if (held.image) env->DeleteGlobalRef(held.image);
        for (auto& frame : yuvFrames_)
            if (frame.image) env->DeleteGlobalRef(frame.image);
        if (writer_) env->DeleteGlobalRef(writer_);
        for (jclass c : {writerClass_, imageClass_, hardwareBufferClass_})
            if (c) env->DeleteGlobalRef(c);
    }
    if (attached) vm_->DetachCurrentThread();
    writer_ = nullptr;
    writerClass_ = imageClass_ = hardwareBufferClass_ = nullptr;
    readyImages_.clear();
    pendingImages_.clear();
    yuvFrames_.fill(YuvFrame{});
    outstandingImages_ = 0;
    if (device) {
        for (auto& [id, buffer] : encoderBuffers_) {
            if (buffer->buffer) vkDestroyBuffer(device, buffer->buffer, nullptr);
            if (buffer->memory) vkFreeMemory(device, buffer->memory, nullptr);
        }
        for (auto& fence : yuvFences_)
            if (fence) vkDestroyFence(device, fence, nullptr);
        if (packPipeline_) vkDestroyPipeline(device, packPipeline_, nullptr);
        if (packLayout_) vkDestroyPipelineLayout(device, packLayout_, nullptr);
        if (packPool_) vkDestroyDescriptorPool(device, packPool_, nullptr);
        if (packSetLayout_) vkDestroyDescriptorSetLayout(device, packSetLayout_, nullptr);
        for (auto& image : ownedImages_) rawrcam::vulkan::destroyOwnedImage(device, image);
    }
    encoderBuffers_.clear();
    yuvFences_.fill(VK_NULL_HANDLE);
    packPipeline_ = VK_NULL_HANDLE;
    packLayout_ = VK_NULL_HANDLE;
    packPool_ = VK_NULL_HANDLE;
    packSetLayout_ = VK_NULL_HANDLE;
    packSets_.fill(VK_NULL_HANDLE);
    if (!ownedImages_.empty()) {
        // The views belonged to the owned images: nothing left for stop() to destroy.
        views_.clear();
        images_.clear();
        initialized_.clear();
    }
    ownedImages_.clear();
    yuvStopping_ = false;
    yuv_ = false;
}
}  // namespace rawrcam::video
