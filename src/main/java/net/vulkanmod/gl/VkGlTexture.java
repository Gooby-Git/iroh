package net.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import dev.elemore.iroh.glshim.PixelTransfer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class VkGlTexture {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlTexture> map = new Int2ReferenceOpenHashMap<>();
    private static int boundTextureId = 0;
    private static VkGlTexture boundTexture;
    private static int activeTexture = 0;
    /** Iroh (P1-2): GL binds a texture per unit; glActiveTexture selects which unit later calls act on. */
    private static final int[] unitBinding = new int[32];

    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;
    // Iroh (P1-2 slice 2): the rest of GL's pixel store state
    private static int unpackAlignment = 4;
    private static int packRowLength, packSkipRows, packSkipPixels;
    private static int packAlignment = 4;

    public static void bindIdToImage(int id, VulkanImage vulkanImage) {
        VkGlTexture texture = map.get(id);
        texture.vulkanImage = vulkanImage;
    }

    public static int genTextureId() {
        int id = ID_COUNTER;
        map.put(id, new VkGlTexture(id));
        ID_COUNTER++;
        return id;
    }

    public static void bindTexture(int id) {
        boundTextureId = id;
        boundTexture = map.get(id);
        if (activeTexture >= 0 && activeTexture < unitBinding.length)
            unitBinding[activeTexture] = id;

        if (id <= 0)
            return;

        if (boundTexture == null)
            throw new NullPointerException("bound texture is null");

        VulkanImage vulkanImage = boundTexture.vulkanImage;
        if (vulkanImage != null)
            VTextureSelector.bindTexture(activeTexture, vulkanImage);
    }

    public static void glDeleteTextures(IntBuffer intBuffer) {
        for (int i = intBuffer.position(); i < intBuffer.limit(); i++) {
            glDeleteTextures(intBuffer.get(i));
        }
    }

    public static void glDeleteTextures(int i) {
        // GL: deleting a bound texture unbinds it from every unit
        for (int u = 0; u < unitBinding.length; u++)
            if (unitBinding[u] == i) unitBinding[u] = 0;
        if (boundTextureId == i) {
            boundTextureId = 0;
            boundTexture = null;
        }
        VkGlTexture glTexture = map.remove(i);
        VulkanImage image = glTexture != null ? glTexture.vulkanImage : null;
        if (image != null)
            MemoryManager.getInstance().addToFreeable(image);
    }

    public static VkGlTexture getTexture(int id) {
        if (id == 0)
            return null;

        return map.get(id);
    }

    public static void activeTexture(int i) {
        activeTexture = i - GL30.GL_TEXTURE0;
        VTextureSelector.setActiveTexture(activeTexture);
        // Iroh (P1-2): later glBindTexture/glTexParameter/glTexImage act on THIS unit's texture (GL semantics);
        // the base kept the previous unit's texture as the target.
        if (activeTexture >= 0 && activeTexture < unitBinding.length) {
            boundTextureId = unitBinding[activeTexture];
            boundTexture = map.get(boundTextureId);
        }
    }

    /** Active unit index (0 = GL_TEXTURE0). */
    public static int getActiveUnit() {
        return activeTexture;
    }

    /** Texture id bound to a unit (GL_TEXTURE_BINDING_2D with that unit active). */
    public static int getUnitBinding(int unit) {
        return unit >= 0 && unit < unitBinding.length ? unitBinding[unit] : 0;
    }

    public static int getBoundTextureId() {
        return boundTextureId;
    }

    public static boolean exists(int id) {
        return map.containsKey(id);
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixels) {
        if (checkParams(level, width, height) || boundTexture == null)
            return;

        boundTexture.updateParams(level, width, height, internalFormat, type);
        boundTexture.allocateIfNeeded();

        VTextureSelector.bindTexture(activeTexture, boundTexture.vulkanImage);

        texSubImage2D(target, level, 0, 0, width, height, format, type, pixels);
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        if (checkParams(level, width, height) || boundTexture == null)
            return;

        boundTexture.updateParams(level, width, height, internalFormat, type);
        boundTexture.allocateIfNeeded();

        VTextureSelector.bindTexture(activeTexture, boundTexture.vulkanImage);

        texSubImage2D(target, level, 0, 0, width, height, format, type, pixels);
    }

    private static boolean checkParams(int level, int width, int height) {
        if (width == 0 || height == 0)
            return true;

        return false;
    }

    /**
     * Iroh (P1-2 slice 2, td-834bea): GL unpack semantics. {@code pixels} is a client pointer, or, with a
     * PIXEL_UNPACK buffer bound, a byte offset into it (the base ignored skip/alignment there and passed row length 0
     * through, which uploaded staging garbage: Xaero's striped map tiles). Row length 0 = width, rows padded to the
     * unpack alignment, skip rows / pixels applied, any supported (format, type) converted to the image's layout
     * (Xaero: BGRA + UNSIGNED_INT_8_8_8_8, i.e. bytes A,R,G,B on little-endian, into an RGBA8 image).
     */
    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        if (width <= 0 || height <= 0 || boundTexture == null || boundTexture.vulkanImage == null)
            return;

        long base;
        VkGlBuffer glBuffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (glBuffer != null) {
            if (glBuffer.data == null)
                return;
            base = MemoryUtil.memAddress0(glBuffer.data) + pixels;
        } else {
            if (pixels == 0L)
                return;
            base = pixels;
        }

        boundTexture.upload(level, xOffset, yOffset, width, height, format, type, base, glBuffer != null ? glBuffer.data.capacity() - pixels : Long.MAX_VALUE);
    }

    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width , int height, int format, int type, @Nullable ByteBuffer pixels) {
        if (width <= 0 || height <= 0 || boundTexture == null || boundTexture.vulkanImage == null)
            return;

        if (pixels != null) {
            boundTexture.upload(level, xOffset, yOffset, width, height, format, type, MemoryUtil.memAddress(pixels), pixels.remaining());
            return;
        }

        // null client pointer: with an unpack buffer bound GL reads from its start, otherwise nothing is uploaded
        VkGlBuffer glBuffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (glBuffer != null && glBuffer.data != null)
            boundTexture.upload(level, xOffset, yOffset, width, height, format, type, MemoryUtil.memAddress0(glBuffer.data), glBuffer.data.capacity());
    }

    /** Iroh: one upload with the current unpack state; {@code avail} = bytes readable from {@code base} (PBO bound). */
    void upload(int level, int xOffset, int yOffset, int width, int height, int format, int type, long base, long avail) {
        VulkanImage image = this.vulkanImage;
        PixelTransfer.Dst dst = dstLayout(image.format);
        if (dst == null || dst == PixelTransfer.Dst.D32F) {
            dev.elemore.iroh.glshim.GlShim.unhandled("glTexSubImage2D(into vk format " + image.format + ")");
            return;
        }
        if (level >= image.mipLevels)
            return;
        long stride = PixelTransfer.rowStride(width, unpackRowLength, unpackAlignment, format, type);
        int bpp = PixelTransfer.bytesPerPixel(format, type);
        if (stride <= 0 || bpp <= 0) {
            dev.elemore.iroh.glshim.GlShim.unhandled("glTexSubImage2D(format 0x" + Integer.toHexString(format) + ", type 0x" + Integer.toHexString(type) + ")");
            return;
        }
        long skip = PixelTransfer.skipOffset(width, unpackRowLength, unpackAlignment, unpackSkipRows, unpackSkipPixels, format, type);
        long need = skip + stride * (height - 1) + (long) width * bpp;
        if (need > avail) {
            dev.elemore.iroh.glshim.GlShim.unhandled("glTexSubImage2D(reads past the unpack buffer)");
            return;
        }
        long src = base + skip;

        if (PixelTransfer.identical(format, type, dst) && stride % dst.bytes == 0) {
            // same bytes: let the copy command walk the client rows (bufferRowLength in texels)
            image.uploadSubTextureAsync(level, width, height, xOffset, yOffset, 0, 0, (int) (stride / dst.bytes), src);
            return;
        }
        ByteBuffer tmp = MemoryUtil.memAlloc(width * height * dst.bytes);
        try {
            if (!PixelTransfer.unpack(src, stride, width, height, format, type, dst, MemoryUtil.memAddress(tmp))) {
                dev.elemore.iroh.glshim.GlShim.unhandled("glTexSubImage2D(format 0x" + Integer.toHexString(format) + ", type 0x" + Integer.toHexString(type) + ")");
                return;
            }
            image.uploadSubTextureAsync(level, width, height, xOffset, yOffset, 0, 0, width, MemoryUtil.memAddress(tmp));
        } finally {
            MemoryUtil.memFree(tmp);
        }
    }

    /** Byte layout Iroh uses for a Vulkan format, or null when GL pixel transfers into it are not supported. */
    public static PixelTransfer.Dst dstLayout(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UINT -> PixelTransfer.Dst.RGBA8;
            case VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB -> PixelTransfer.Dst.BGRA8;
            case VK_FORMAT_R8_UNORM -> PixelTransfer.Dst.R8;
            case VK_FORMAT_R8G8_UNORM -> PixelTransfer.Dst.RG8;
            case VK_FORMAT_D32_SFLOAT -> PixelTransfer.Dst.D32F;
            default -> null;
        };
    }

    /** Iroh: Vulkan format for a GL internal format (GL: the internal format alone decides storage). */
    public static int vkFormatFor(int internalFormat) {
        return switch (internalFormat) {
            case 0x1908 /* RGBA */, 0x8058 /* RGBA8 */, 0x1907 /* RGB */, 0x8051 /* RGB8 */, 4, 3,
                 0x8056 /* RGBA4 */, 0x8057 /* RGB5_A1 */, 0x8D62 /* RGB565 */ -> VK_FORMAT_R8G8B8A8_UNORM;
            case 0x8C43 /* SRGB8_ALPHA8 */, 0x8C41 /* SRGB8 */, 0x8C40 /* SRGB */, 0x8C42 /* SRGB_ALPHA */ -> VK_FORMAT_R8G8B8A8_SRGB;
            case 0x80E1 /* BGRA */ -> VK_FORMAT_B8G8R8A8_UNORM;
            case 0x1903 /* RED */, 0x8229 /* R8 */, 0x1906 /* ALPHA */, 0x1909 /* LUMINANCE */, 1 -> VK_FORMAT_R8_UNORM;
            case 0x8227 /* RG */, 0x822B /* RG8 */, 0x190A /* LUMINANCE_ALPHA */, 2 -> VK_FORMAT_R8G8_UNORM;
            case 0x8D9C /* RGBA8UI */ -> VK_FORMAT_R8G8B8A8_UINT;
            case 0x8367 /* UNSIGNED_INT_8_8_8_8_REV, the base's odd "internal format" */ -> VK_FORMAT_R8G8B8A8_UINT;
            case 0x1902 /* DEPTH_COMPONENT */, 0x81A5 /* 16 */, 0x81A6 /* 24 */, 0x81A7 /* 32 */, 0x8CAC /* 32F */,
                 0x84F9 /* DEPTH_STENCIL */, 0x88F0 /* DEPTH24_STENCIL8 */, 0x8CAD /* DEPTH32F_STENCIL8 */ -> Vulkan.getDefaultDepthFormat();
            default -> {
                dev.elemore.iroh.glshim.GlShim.unhandled("glTexImage2D(internal format 0x" + Integer.toHexString(internalFormat) + ", stored as RGBA8)");
                yield VK_FORMAT_R8G8B8A8_UNORM;
            }
        };
    }

    /** GL_TEXTURE_INTERNAL_FORMAT answer: the sized format GL implementations report for Iroh's storage. */
    public static int glInternalFormatOf(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM -> 0x8058;   // GL_RGBA8
            case VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB -> 0x8C43;    // GL_SRGB8_ALPHA8
            case VK_FORMAT_R8_UNORM -> 0x8229;                                   // GL_R8
            case VK_FORMAT_R8G8_UNORM -> 0x822B;                                 // GL_RG8
            case VK_FORMAT_R8G8B8A8_UINT -> 0x8D7C;                              // GL_RGBA8UI
            case VK_FORMAT_D32_SFLOAT -> 0x8CAC;                                 // GL_DEPTH_COMPONENT32F
            case VK_FORMAT_D24_UNORM_S8_UINT -> 0x88F0;                          // GL_DEPTH24_STENCIL8
            case VK_FORMAT_D32_SFLOAT_S8_UINT -> 0x8CAD;                         // GL_DEPTH32F_STENCIL8
            case VK_FORMAT_X8_D24_UNORM_PACK32 -> 0x81A6;                        // GL_DEPTH_COMPONENT24
            default -> 0x8058;
        };
    }

    public static void texParameteri(int target, int pName, int param) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return;

        switch (pName) {
            case GL30.GL_TEXTURE_MAX_LEVEL -> boundTexture.setMaxLevel(param);
            case GL30.GL_TEXTURE_MAX_LOD -> boundTexture.setMaxLod(param);
            case GL30.GL_TEXTURE_MIN_LOD -> {}
            case GL30.GL_TEXTURE_LOD_BIAS -> {}

            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.setMagFilter(param);
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.setMinFilter(param);

            case GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T -> boundTexture.setClamp(param);

            default -> {}
        }

        //TODO
    }

    public static int getTexParameteri(int target, int pName) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return -1;

        return switch (pName) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> glInternalFormatOf(boundTexture.vulkanImage.format);
            case GL11.GL_TEXTURE_WIDTH -> boundTexture.vulkanImage.width;
            case GL11.GL_TEXTURE_HEIGHT -> boundTexture.vulkanImage.height;

            case GL30.GL_TEXTURE_MAX_LEVEL -> boundTexture.maxLevel;
            case GL30.GL_TEXTURE_MAX_LOD -> boundTexture.maxLod;

            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.magFilter;
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.minFilter;

            default -> -1;
        };
    }

    public static int getTexLevelParameter(int target, int level, int pName) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null || boundTexture.vulkanImage == null)
            return 0;

        // Iroh: per level, GL values (Xaero stores GL_TEXTURE_INTERNAL_FORMAT in its cache and re-uploads with it)
        VulkanImage img = boundTexture.vulkanImage;
        boolean exists = level >= 0 && level < img.mipLevels;
        return switch (pName) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> exists ? glInternalFormatOf(img.format) : 1;
            case GL11.GL_TEXTURE_WIDTH -> exists ? Math.max(1, img.width >> level) : 0;
            case GL11.GL_TEXTURE_HEIGHT -> exists ? Math.max(1, img.height >> level) : 0;
            case 0x86A1 /* GL_TEXTURE_COMPRESSED */ -> 0;
            case 0x805C, 0x805D, 0x805E, 0x805F /* RED/GREEN/BLUE/ALPHA_SIZE */ -> exists && dstLayout(img.format) != PixelTransfer.Dst.D32F ? 8 : 0;
            case 0x884A /* GL_TEXTURE_DEPTH_SIZE */ -> exists && VulkanImage.isDepthFormat(img.format) ? (img.format == VK_FORMAT_D32_SFLOAT ? 32 : 24) : 0;
            default -> 0;
        };
    }

    public static void pixelStoreI(int pName, int value) {
        switch (pName) {
            case GL11.GL_UNPACK_ROW_LENGTH -> unpackRowLength = value;
            case GL11.GL_UNPACK_SKIP_ROWS -> unpackSkipRows = value;
            case GL11.GL_UNPACK_SKIP_PIXELS -> unpackSkipPixels = value;
            case GL11.GL_UNPACK_ALIGNMENT -> unpackAlignment = value;
            case GL11.GL_PACK_ROW_LENGTH -> packRowLength = value;
            case GL11.GL_PACK_SKIP_ROWS -> packSkipRows = value;
            case GL11.GL_PACK_SKIP_PIXELS -> packSkipPixels = value;
            case GL11.GL_PACK_ALIGNMENT -> packAlignment = value;
        }
    }

    /** Iroh: GL pack state for readbacks (glGetTexImage / glReadPixels): stride and first-pixel offset. */
    public static long packStride(int width, int format, int type) {
        return PixelTransfer.rowStride(width, packRowLength, packAlignment, format, type);
    }

    public static long packSkip(int width, int format, int type) {
        return PixelTransfer.skipOffset(width, packRowLength, packAlignment, packSkipRows, packSkipPixels, format, type);
    }

    /**
     * Iroh: client pointer for a readback, or with a PIXEL_PACK buffer bound, {@code pixels} as an offset into it.
     * 0 if there is nowhere to write. {@code avail} receives the writable byte count.
     */
    public static long packDestination(long pixels, long[] avail) {
        VkGlBuffer pbo = VkGlBuffer.getPixelPackBufferBound();
        if (pbo != null) {
            if (pbo.data == null) return 0L;
            avail[0] = pbo.data.capacity() - pixels;
            return MemoryUtil.memAddress0(pbo.data) + pixels;
        }
        avail[0] = Long.MAX_VALUE;
        return pixels;
    }

    public static void generateMipmap(int target) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        // TODO: crashing
//        boundTexture.generateMipmaps();
    }

    /**
     * Iroh (P1-2 slice 2): glGetTexImage with GL pack semantics: the given level, conversion to (format, type), pack
     * row length / alignment / skips, PIXEL_PACK buffer offsets, and the image as it is after every earlier GL call
     * (FrameSync). Xaero downloads its branch tiles this way (BGRA + UNSIGNED_INT_8_8_8_8 into a pack buffer).
     */
    public static void getTexImage(int tex, int level, int format, int type, long pixels) {
        if (boundTexture == null || boundTexture.vulkanImage == null)
            return;
        VulkanImage image = boundTexture.vulkanImage;
        PixelTransfer.Dst src = dstLayout(image.format);
        if (src == null || level < 0 || level >= image.mipLevels) {
            dev.elemore.iroh.glshim.GlShim.unhandled("glGetTexImage(vk format " + image.format + ", level " + level + ")");
            return;
        }
        int w = Math.max(1, image.width >> level), h = Math.max(1, image.height >> level);
        readInto(image, level, 0, 0, w, h, format, type, pixels, src, "glGetTexImage");
    }

    /** Shared by glGetTexImage and glReadPixels: rect of an image -> client memory / pack buffer in (format, type). */
    public static void readInto(VulkanImage image, int level, int x, int y, int w, int h, int format, int type, long pixels,
                                PixelTransfer.Dst src, String fn) {
        int bpp = PixelTransfer.bytesPerPixel(format, type);
        if (bpp <= 0) {
            dev.elemore.iroh.glshim.GlShim.unhandled(fn + "(format 0x" + Integer.toHexString(format) + ", type 0x" + Integer.toHexString(type) + ")");
            return;
        }
        long[] avail = new long[1];
        long dst = packDestination(pixels, avail);
        if (dst == 0L)
            return;
        long stride = packStride(w, format, type);
        long skip = packSkip(w, format, type);
        if (skip + stride * (h - 1) + (long) w * bpp > avail[0]) {
            dev.elemore.iroh.glshim.GlShim.unhandled(fn + "(writes past the pack buffer)");
            return;
        }
        ByteBuffer tmp = MemoryUtil.memAlloc(w * h * image.formatSize);
        try {
            if (!dev.elemore.iroh.glshim.Readback.read(image, level, x, y, w, h, MemoryUtil.memAddress(tmp)))
                return;
            if (!PixelTransfer.pack(MemoryUtil.memAddress(tmp), src, w, h, format, type, dst + skip, stride))
                dev.elemore.iroh.glshim.GlShim.unhandled(fn + "(format 0x" + Integer.toHexString(format) + ", type 0x" + Integer.toHexString(type) + ")");
        } finally {
            MemoryUtil.memFree(tmp);
        }
    }

    public static void setVulkanImage(int id, VulkanImage vulkanImage) {
        VkGlTexture texture = map.get(id);

        texture.vulkanImage = vulkanImage;
    }

    public static VkGlTexture getBoundTexture() {
        return boundTexture;
    }

    public final int id;
    VulkanImage vulkanImage;

    int width, height;
    int vkFormat;

    boolean needsUpdate = false;
    int maxLevel = 0;
    int maxLod = 0;
    int minFilter, magFilter = GL11.GL_LINEAR;

    boolean clamp = true;

    public VkGlTexture(int id) {
        this.id = id;
    }

    void updateParams(int level, int width, int height, int internalFormat, int type) {
        // Iroh: a level that already exists in the image is just uploaded (the base reallocated, losing level 0)
        if (level > this.maxLevel && (this.vulkanImage == null || level >= this.vulkanImage.mipLevels)) {
            this.maxLevel = level;

            this.needsUpdate = true;
        }

        if (level == 0) {
            int vkFormat = vkFormatFor(internalFormat);

            if (this.vulkanImage == null || this.width != width || this.height != height || vkFormat != vulkanImage.format) {
                this.width = width;
                this.height = height;
                this.vkFormat = vkFormat;

                this.needsUpdate = true;
            }
        }
    }

    void allocateIfNeeded() {
        if (needsUpdate) {
            allocateImage(width, height, vkFormat);
            updateSampler();

            needsUpdate = false;
        }
    }

    void allocateImage(int width, int height, int vkFormat) {
        if (this.vulkanImage != null)
            this.vulkanImage.free();

        if (VulkanImage.isDepthFormat(vkFormat)) {
            this.vulkanImage = VulkanImage.createDepthImage(
                    vkFormat, width, height,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    false, true);
        }
        else {
            this.vulkanImage = new VulkanImage.Builder(width, height)
                    .setName(String.format("GlTexture %d", this.id))
                    .setMipLevels(maxLevel + 1)
                    .setFormat(vkFormat)
                    .addUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .createVulkanImage();
        }
    }

    void updateSampler() {
        if (vulkanImage == null)
            return;

        byte samplerFlags;
        samplerFlags = clamp ? SamplerManager.CLAMP_BIT : 0;
        samplerFlags |= magFilter == GL11.GL_LINEAR ? SamplerManager.LINEAR_FILTERING_BIT : 0;

        samplerFlags |= switch (minFilter) {
            // Iroh (td-48591b): all four GL mipmap min filters; the base dropped mipmaps for NEAREST_MIPMAP_LINEAR (what
            // vanilla's atlases use when set through glTexParameter) and LINEAR_MIPMAP_NEAREST.
            case GL11.GL_LINEAR_MIPMAP_LINEAR, GL11.GL_NEAREST_MIPMAP_LINEAR -> SamplerManager.USE_MIPMAPS_BIT | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT;
            case GL11.GL_NEAREST_MIPMAP_NEAREST, GL11.GL_LINEAR_MIPMAP_NEAREST -> SamplerManager.USE_MIPMAPS_BIT;
            default -> 0;
        };

        vulkanImage.updateTextureSampler(maxLod, samplerFlags);
    }

    void generateMipmaps() {
        //TODO test
        ImageUtil.generateMipmaps(vulkanImage);
    }

    void setMaxLevel(int l) {
        if (l < 0)
            throw new IllegalStateException("max level cannot be < 0.");

        if (maxLevel != l) {
            maxLevel = l;
            needsUpdate = true;
        }
    }

    void setMaxLod(int l) {
        if (l < 0)
            throw new IllegalStateException("max level cannot be < 0.");

        if (maxLod != l) {
            maxLod = l;
            updateSampler();
        }
    }

    void setMagFilter(int v) {
        switch (v) {
            case GL11.GL_LINEAR, GL11.GL_NEAREST -> {
            }

            default -> throw new IllegalArgumentException("illegal mag filter value: " + v);
        }

        this.magFilter = v;
        updateSampler();
    }

    void setMinFilter(int v) {
        switch (v) {
            case GL11.GL_LINEAR, GL11.GL_NEAREST,
                 GL11.GL_LINEAR_MIPMAP_LINEAR, GL11.GL_NEAREST_MIPMAP_LINEAR,
                 GL11.GL_LINEAR_MIPMAP_NEAREST, GL11.GL_NEAREST_MIPMAP_NEAREST -> {
            }

            default -> throw new IllegalArgumentException("illegal min filter value: " + v);
        }

        this.minFilter = v;
        updateSampler();
    }

    void setClamp(int v) {
        if (v == GL30.GL_CLAMP_TO_EDGE) {
            this.clamp = true;
        } else {
            this.clamp = false;
        }

        updateSampler();
    }

    public VulkanImage getVulkanImage() {
        return vulkanImage;
    }

    public void setVulkanImage(VulkanImage vulkanImage) {
        this.vulkanImage = vulkanImage;
    }

}
