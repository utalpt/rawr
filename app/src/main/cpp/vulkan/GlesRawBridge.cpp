#include "vulkan/GlesRawBridge.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>

#include <GLES2/gl2ext.h>
#include <poll.h>

#include <array>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "imaging/FrameLimits.h"
#include "imaging/RawPixelSource.h"

namespace rawrcam::vulkan {
namespace {
void require(bool ok, const char* step) {
    if (!ok) throw std::runtime_error(step);
}
void vkOk(VkResult r, const char* step) {
    if (r != VK_SUCCESS) throw std::runtime_error(std::string(step) + " VkResult=" + std::to_string(r));
}
struct Current {
    EGLDisplay display, previous = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    EGLSurface draw = eglGetCurrentSurface(EGL_DRAW), read = eglGetCurrentSurface(EGL_READ);
    explicit Current(EGLDisplay d, EGLSurface s, EGLContext c) : display(d) {
        require(eglMakeCurrent(d, s, s, c), "bridge eglMakeCurrent");
    }
    ~Current() { eglMakeCurrent(context == EGL_NO_CONTEXT ? display : previous, draw, read, context); }
};
struct Source {
    EGLDisplay display;
    PFNEGLDESTROYIMAGEKHRPROC destroy;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0, framebuffer = 0;
    ~Source() {
        glDeleteFramebuffers(1, &framebuffer);
        glDeleteTextures(1, &texture);
        if (image) destroy(display, image);
    }
};
struct Twin {
    VkDevice device{};
    VkBuffer buffer{};
    VkDeviceMemory memory{};
    AHardwareBuffer* blob{};
    GLuint pack{};
    uint32_t bytes{};
    ~Twin() {
        if (buffer) vkDestroyBuffer(device, buffer, nullptr);
        if (memory) vkFreeMemory(device, memory, nullptr);
        if (blob) AHardwareBuffer_release(blob);
    }
};
// RAW10 decode, ES 3.1. One invocation per MIPI group of four pixels: read the five packed bytes and
// write the two uint words holding four uint16 values. A loop over the four texels inside one invocation
// miscompiled on the Adreno V2562 driver (raw10_unpack.comp carries the same warning), so the group is
// the unit of work here too. Addressing is by byte, so any plane row stride works.
constexpr char kRaw10Decode[] = R"glsl(#version 310 es
layout(local_size_x = 8, local_size_y = 8) in;
layout(std430, binding = 0) readonly buffer PackedBytes { uint packedBytes[]; };
layout(std430, binding = 1) writeonly buffer DecodedWords { uint decodedWords[]; };
uniform uint uWidth;
uniform uint uHeight;
uniform uint uRowStrideBytes;
uniform uint uGroupsPerRow;
uint byteAt(uint offset) { return (packedBytes[offset >> 2u] >> ((offset & 3u) * 8u)) & 0xffu; }
void main() {
    uint groupX = gl_GlobalInvocationID.x;
    uint y = gl_GlobalInvocationID.y;
    if (groupX >= uGroupsPerRow || y >= uHeight) return;
    uint base = y * uRowStrideBytes + groupX * 5u;
    uint lsb = byteAt(base + 4u);
    uint p0 = (byteAt(base) << 2u) | (lsb & 0x3u);
    uint p1 = (byteAt(base + 1u) << 2u) | ((lsb >> 2u) & 0x3u);
    uint p2 = (byteAt(base + 2u) << 2u) | ((lsb >> 4u) & 0x3u);
    uint p3 = (byteAt(base + 3u) << 2u) | ((lsb >> 6u) & 0x3u);
    uint word = (y * uWidth + groupX * 4u) >> 1u;
    decodedWords[word] = p0 | (p1 << 16u);
    decodedWords[word + 1u] = p2 | (p3 << 16u);
}
)glsl";
GLuint compileShader(GLenum stage, const char* source, const char* step) {
    GLuint shader = glCreateShader(stage);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok) return shader;
    GLint length = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
    std::string log(static_cast<size_t>(length > 0 ? length : 1), '\0');
    glGetShaderInfoLog(shader, length, nullptr, log.data());
    glDeleteShader(shader);
    throw std::runtime_error(std::string(step) + " " + log);
}
GLuint linkProgram(const char* source) {
    GLuint shader = compileShader(GL_COMPUTE_SHADER, source, "bridge RAW10 shader");
    GLuint program = glCreateProgram();
    glAttachShader(program, shader);
    glLinkProgram(program);
    glDeleteShader(shader);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok) return program;
    GLint length = 0;
    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
    std::string log(static_cast<size_t>(length > 0 ? length : 1), '\0');
    glGetProgramInfoLog(program, length, nullptr, log.data());
    glDeleteProgram(program);
    throw std::runtime_error("bridge RAW10 link " + log);
}
// Copies the decoded BLOB back and compares sampled rows with the CPU decode the DNG path uses, so a
// driver that decodes differently shows up in the log instead of in the frames.
std::string compareRows(AHardwareBuffer* camera, uint32_t w, uint32_t h, uint32_t rowStrideBytes, GLuint decoded) {
    void* base = nullptr;
    if (AHardwareBuffer_lock(camera, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &base) != 0 || !base)
        return "RAW_INGRESS_GLES_BRIDGE_PARITY format=RAW10 width=" + std::to_string(w) +
               " height=" + std::to_string(h) + " skipped=camera_cpu_lock";
    const uint64_t bytes = uint64_t(w) * h * 2;
    GLuint staging = 0;
    glGenBuffers(1, &staging);
    glBindBuffer(GL_COPY_READ_BUFFER, decoded);
    glBindBuffer(GL_COPY_WRITE_BUFFER, staging);
    glBufferData(GL_COPY_WRITE_BUFFER, static_cast<GLsizeiptr>(bytes), nullptr, GL_STREAM_READ);
    glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, static_cast<GLsizeiptr>(bytes));
    glFinish();
    std::string line = "RAW_INGRESS_GLES_BRIDGE_PARITY format=RAW10 width=" + std::to_string(w) +
                       " height=" + std::to_string(h) + " rowStrideBytes=" + std::to_string(rowStrideBytes);
    const void* mapped = glMapBufferRange(GL_COPY_WRITE_BUFFER, 0, static_cast<GLsizeiptr>(bytes), GL_MAP_READ_BIT);
    if (!mapped) {
        line += " skipped=readback";
    } else {
        std::vector<uint16_t> reference(w);
        const uint32_t rows[]{0u, h / 2u, h - 1u};
        uint32_t checked = 0, mismatched = 0;
        for (uint32_t y : rows) {
            rawrcam::imaging::unpackRaw10Row(static_cast<const uint8_t*>(base) + static_cast<size_t>(rowStrideBytes) * y,
                                            w, reference.data());
            const auto* got = static_cast<const uint16_t*>(mapped) + static_cast<size_t>(w) * y;
            ++checked;
            if (std::memcmp(got, reference.data(), static_cast<size_t>(w) * 2) != 0) ++mismatched;
        }
        line += " rows=" + std::to_string(checked) + " mismatched=" + std::to_string(mismatched);
        glUnmapBuffer(GL_COPY_WRITE_BUFFER);
    }
    glBindBuffer(GL_COPY_READ_BUFFER, 0);
    glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
    glDeleteBuffers(1, &staging);
    AHardwareBuffer_unlock(camera, nullptr);
    return line;
}
}  // namespace
struct GlesRawBridge::Impl {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC client{};
    PFNEGLCREATEIMAGEKHRPROC create{};
    PFNEGLDESTROYIMAGEKHRPROC destroy{};
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC target{};
    PFNGLBUFFERSTORAGEEXTERNALEXTPROC storage{};
    // ES 3.1 compute, the only way to turn packed MIPI RAW10 bytes into R16 values on the GPU.
    bool compute = false;
    GLuint raw10Program = 0;
    GLint raw10Width = -1, raw10Height = -1, raw10Stride = -1, raw10Groups = -1;
    std::array<std::unique_ptr<Twin>, imaging::kRealtimeFramesInFlight> slots{};
    ~Impl() {
        // Destroying the private context releases all GL pack-buffer references.
        if (context) eglDestroyContext(display, context);
        if (surface) eglDestroySurface(display, surface);
    }
    void init() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        require(display && eglInitialize(display, nullptr, nullptr), "bridge EGL display");
        const EGLint attrs[]{EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_NONE};
        EGLConfig cfg{};
        EGLint n{};
        require(eglChooseConfig(display, attrs, &cfg, 1, &n) && n, "bridge EGL config");
        const EGLint sa[]{EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        // ES 3.1 carries the RAW10 decode; an ES 3.0 context still serves the RAW16 pack.
        const EGLint es31[]{EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 1, EGL_NONE};
        const EGLint es3[]{EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        surface = eglCreatePbufferSurface(display, cfg, sa);
        context = eglCreateContext(display, cfg, EGL_NO_CONTEXT, es31);
        if (!context) context = eglCreateContext(display, cfg, EGL_NO_CONTEXT, es3);
        require(surface && context, "bridge ES3 context");
        Current current(display, surface, context);
        int major = 0, minor = 0;
        const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
        compute = version && std::sscanf(version, "OpenGL ES %d.%d", &major, &minor) == 2 &&
                  (major > 3 || (major == 3 && minor >= 1));
        client = reinterpret_cast<decltype(client)>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        create = reinterpret_cast<decltype(create)>(eglGetProcAddress("eglCreateImageKHR"));
        destroy = reinterpret_cast<decltype(destroy)>(eglGetProcAddress("eglDestroyImageKHR"));
        target = reinterpret_cast<decltype(target)>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        storage = reinterpret_cast<decltype(storage)>(eglGetProcAddress("glBufferStorageExternalEXT"));
        GLint count{};
        glGetIntegerv(GL_NUM_EXTENSIONS, &count);
        bool external = false;
        for (GLint i = 0; i < count; ++i)
            external |= std::strcmp(reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i)),
                                    "GL_EXT_external_buffer") == 0;
        require(external && client && create && destroy && target && storage, "bridge external buffer extensions");
    }
    std::unique_ptr<Twin> allocate(VkDevice device, PFN_vkGetAndroidHardwareBufferPropertiesANDROID props,
                                   uint32_t bytes, GLenum target) {
        auto t = std::make_unique<Twin>();
        t->device = device;
        t->bytes = bytes;
        AHardwareBuffer_Desc bd{};
        bd.width = bytes;
        bd.height = bd.layers = 1;
        bd.format = AHARDWAREBUFFER_FORMAT_BLOB;
        bd.usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
        require(AHardwareBuffer_allocate(&bd, &t->blob) == 0, "bridge BLOB allocation");
        VkAndroidHardwareBufferPropertiesANDROID hp{};
        hp.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        vkOk(props(device, t->blob, &hp), "bridge BLOB properties");
        VkExternalMemoryBufferCreateInfo ext{};
        ext.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
        ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkBufferCreateInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.pNext = &ext;
        bi.size = bytes;
        bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        vkOk(vkCreateBuffer(device, &bi, nullptr, &t->buffer), "bridge vkCreateBuffer");
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, t->buffer, &req);
        uint32_t bits = req.memoryTypeBits & hp.memoryTypeBits;
        require(bits && req.size <= hp.allocationSize, "bridge incompatible BLOB memory requirements");
        VkImportAndroidHardwareBufferInfoANDROID imp{};
        imp.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        imp.buffer = t->blob;
        VkMemoryDedicatedAllocateInfo ded{};
        ded.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
        ded.pNext = &imp;
        ded.buffer = t->buffer;
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.pNext = &ded;
        ai.allocationSize = hp.allocationSize;
        ai.memoryTypeIndex = __builtin_ctz(bits);
        vkOk(vkAllocateMemory(device, &ai, nullptr, &t->memory), "bridge vkAllocateMemory");
        vkOk(vkBindBufferMemory(device, t->buffer, t->memory, 0), "bridge vkBindBufferMemory");
        auto cb = client(t->blob);
        require(cb, "bridge BLOB client buffer");
        glGenBuffers(1, &t->pack);
        glBindBuffer(target, t->pack);
        storage(target, 0, bytes, cb, 0);
        if (glGetError() != GL_NO_ERROR) {
            glDeleteBuffers(1, &t->pack);
            throw std::runtime_error("bridge pack storage");
        }
        return t;
    }
};
GlesRawBridge::GlesRawBridge() = default;
GlesRawBridge::~GlesRawBridge() = default;
void GlesRawBridge::reset() noexcept { impl_.reset(); }
ImportedRaw GlesRawBridge::pack(VkDevice device, PFN_vkGetAndroidHardwareBufferPropertiesANDROID props, uint32_t slot,
                                AHardwareBuffer* camera, int fd, uint32_t w, uint32_t h) {
    ImportedRaw raw{};
    AHardwareBuffer_describe(camera, &raw.desc);
    const uint64_t bytes = uint64_t(w) * h * 2;
    require(w && h && raw.desc.width == w && raw.desc.height == h && raw.desc.layers == 1 && raw.desc.format == 0x20 &&
                bytes <= UINT32_MAX && props,
            "bridge RAW16 geometry");
    if (fd >= 0) {
        pollfd p{fd, POLLIN, 0};
        int ready;
        do {
            ready = poll(&p, 1, 1000);
        } while (ready < 0 && errno == EINTR);
        require(ready == 1 && p.revents == POLLIN, "bridge acquire fence");
    }
    if (!impl_) {
        auto next = std::make_unique<Impl>();
        next->init();
        impl_ = std::move(next);
    }
    auto& e = *impl_;
    Current current(e.display, e.surface, e.context);
    auto& twin = e.slots.at(slot);
    if (!twin || twin->bytes != bytes) {
        auto next = e.allocate(device, props, bytes, GL_PIXEL_PACK_BUFFER);
        if (twin) glDeleteBuffers(1, &twin->pack);
        twin = std::move(next);
    }
    // Keep the source import frame-scoped so reader pool churn cannot pin stale camera allocations.
    Source src{e.display, e.destroy};
    const EGLint attrs[]{EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    auto cb = e.client(camera);
    require(cb, "bridge RAW client buffer");
    src.image = e.create(e.display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
    require(src.image, "bridge RAW EGLImage");
    glGenTextures(1, &src.texture);
    glBindTexture(GL_TEXTURE_2D, src.texture);
    e.target(GL_TEXTURE_2D, src.image);
    glGenFramebuffers(1, &src.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, src.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, src.texture, 0);
    require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "bridge RAW framebuffer");
    GLint fmt{}, type{};
    glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &fmt);
    glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &type);
    require(fmt == GL_RED_INTEGER && type == GL_UNSIGNED_SHORT && glGetError() == GL_NO_ERROR,
            "bridge RAW read format");
    glBindBuffer(GL_PIXEL_PACK_BUFFER, twin->pack);
    glPixelStorei(GL_PACK_ALIGNMENT, 2);
    glReadPixels(0, 0, w, h, GL_RED_INTEGER, GL_UNSIGNED_SHORT, nullptr);
    glFinish();
    require(glGetError() == GL_NO_ERROR, "bridge RAW pack");
    raw.ahb = camera;
    raw.importBuffer = twin->buffer;
    raw.importBufferAvailable = true;
    raw.gpuUnpack = true;
    raw.rowStrideBytes = w * 2;
    return raw;
}
ImportedRaw GlesRawBridge::packRaw10(VkDevice device, PFN_vkGetAndroidHardwareBufferPropertiesANDROID props,
                                    uint32_t slot, AHardwareBuffer* camera, int fd, uint32_t w, uint32_t h,
                                    uint32_t rowStrideBytes, std::string* parity) {
    ImportedRaw raw{};
    AHardwareBuffer_describe(camera, &raw.desc);
    const uint64_t bytes = uint64_t(w) * h * 2;
    constexpr uint32_t kHalPixelFormatRaw10 = 0x25;
    require(w && h && w % 4u == 0 && bytes <= UINT32_MAX && props && raw.desc.width == w &&
                raw.desc.height == h && raw.desc.layers == 1 && raw.desc.format == kHalPixelFormatRaw10,
            "bridge RAW10 geometry");
    // The shader reads the four MSB bytes and the shared LSB byte of every group, so the row must hold
    // at least width/4 groups; a larger stride (HAL alignment) is fine because it is used verbatim.
    require(uint64_t(rowStrideBytes) >= uint64_t(w / 4u) * 5u, "bridge RAW10 row stride");
    if (fd >= 0) {
        pollfd p{fd, POLLIN, 0};
        int ready;
        do {
            ready = poll(&p, 1, 1000);
        } while (ready < 0 && errno == EINTR);
        require(ready == 1 && p.revents == POLLIN, "bridge acquire fence");
    }
    if (!impl_) {
        auto next = std::make_unique<Impl>();
        next->init();
        impl_ = std::move(next);
    }
    auto& e = *impl_;
    Current current(e.display, e.surface, e.context);
    require(e.compute, "bridge RAW10 needs an OpenGL ES 3.1 context");
    while (glGetError() != GL_NO_ERROR) {
    }
    auto& twin = e.slots.at(slot);
    if (!twin || twin->bytes != bytes) {
        auto next = e.allocate(device, props, bytes, GL_SHADER_STORAGE_BUFFER);
        if (twin) glDeleteBuffers(1, &twin->pack);
        twin = std::move(next);
    }
    if (!e.raw10Program) {
        e.raw10Program = linkProgram(kRaw10Decode);
        e.raw10Width = glGetUniformLocation(e.raw10Program, "uWidth");
        e.raw10Height = glGetUniformLocation(e.raw10Program, "uHeight");
        e.raw10Stride = glGetUniformLocation(e.raw10Program, "uRowStrideBytes");
        e.raw10Groups = glGetUniformLocation(e.raw10Program, "uGroupsPerRow");
        require(e.raw10Width >= 0 && e.raw10Height >= 0 && e.raw10Stride >= 0 && e.raw10Groups >= 0,
                "bridge RAW10 uniforms");
    }
    // The camera plane is imported as a storage buffer for one frame: the reader pool recycles
    // allocations, so nothing here may outlive the frame. External storage is immutable, so the GL
    // buffer object itself cannot be reused either: it is created and destroyed around the dispatch.
    auto cb = e.client(camera);
    require(cb, "bridge RAW10 client buffer");
    GLuint source = 0;
    glGenBuffers(1, &source);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, source);
    e.storage(GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(uint64_t(rowStrideBytes) * h), cb, 0);
    const GLenum importError = glGetError();
    if (importError != GL_NO_ERROR) {
        glDeleteBuffers(1, &source);
        throw std::runtime_error("bridge RAW10 external buffer");
    }
    glUseProgram(e.raw10Program);
    glUniform1ui(e.raw10Width, w);
    glUniform1ui(e.raw10Height, h);
    glUniform1ui(e.raw10Stride, rowStrideBytes);
    glUniform1ui(e.raw10Groups, w / 4u);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, source);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, twin->pack);
    glDispatchCompute((w / 4u + 7u) / 8u, (h + 7u) / 8u, 1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    glFinish();
    const GLenum dispatchError = glGetError();
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, 0);
    glDeleteBuffers(1, &source);
    require(dispatchError == GL_NO_ERROR, "bridge RAW10 dispatch");
    if (parity) *parity = compareRows(camera, w, h, rowStrideBytes, twin->pack);
    raw.ahb = camera;
    raw.importBuffer = twin->buffer;
    raw.importBufferAvailable = true;
    raw.gpuUnpack = true;
    // The decoded BLOB holds tightly packed uint16 rows, like the unpacked camera image.
    raw.rowStrideBytes = w * 2;
    return raw;
}
}  // namespace rawrcam::vulkan
