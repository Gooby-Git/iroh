package dev.elemore.iroh.shaderpack.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * CPU encode/decode between RGBA float texels and the Vulkan formats the pack plan allocates. Used to upload captured
 * frames and to check read-back targets (NaN / inf / range). Unknown formats throw.
 */
public final class TexelCodec {

    private TexelCodec() {}

    public static int bytes(String vk) {
        return switch (vk) {
            case "R8_UNORM", "R8_SNORM", "R8_UINT" -> 1;
            case "R8G8_UNORM", "R8G8_SNORM", "R16_SFLOAT", "R16_UNORM", "R16_UINT", "R16_SNORM" -> 2;
            case "R8G8B8A8_UNORM", "R8G8B8A8_SNORM", "R8G8B8A8_UINT", "R16G16_SFLOAT", "R16G16_UNORM", "R32_SFLOAT", "R32_UINT",
                 "B10G11R11_UFLOAT_PACK32", "A2B10G10R10_UNORM_PACK32", "D32_SFLOAT" -> 4;
            case "R16G16B16A16_SFLOAT", "R16G16B16A16_UNORM", "R16G16B16A16_SNORM", "R32G32_SFLOAT" -> 8;
            case "R32G32B32A32_SFLOAT", "R32G32B32A32_UINT" -> 16;
            default -> throw new IllegalArgumentException("no codec for " + vk);
        };
    }

    public static boolean supported(String vk) {
        try {
            bytes(vk);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** rgba: 4 floats per texel; returns the packed little-endian bytes */
    public static ByteBuffer encode(String vk, float[] rgba) {
        int n = rgba.length / 4;
        int b = bytes(vk);
        ByteBuffer out = ByteBuffer.allocate(n * b).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            float r = rgba[4 * i], g = rgba[4 * i + 1], bl = rgba[4 * i + 2], a = rgba[4 * i + 3];
            switch (vk) {
                case "R8_UNORM" -> out.put(unorm8(r));
                case "R8_SNORM" -> out.put(snorm8(r));
                case "R8_UINT" -> out.put((byte) Math.round(r));
                case "R8G8_UNORM" -> out.put(unorm8(r)).put(unorm8(g));
                case "R8G8_SNORM" -> out.put(snorm8(r)).put(snorm8(g));
                case "R8G8B8A8_UNORM" -> out.put(unorm8(r)).put(unorm8(g)).put(unorm8(bl)).put(unorm8(a));
                case "R8G8B8A8_SNORM" -> out.put(snorm8(r)).put(snorm8(g)).put(snorm8(bl)).put(snorm8(a));
                case "R8G8B8A8_UINT" -> out.put((byte) r).put((byte) g).put((byte) bl).put((byte) a);
                case "R16_SFLOAT" -> out.putShort(half(r));
                case "R16_UNORM" -> out.putShort(unorm16(r));
                case "R16_SNORM" -> out.putShort((short) Math.round(clamp(r, -1, 1) * 32767));
                case "R16_UINT" -> out.putShort((short) Math.round(r));
                case "R16G16_SFLOAT" -> out.putShort(half(r)).putShort(half(g));
                case "R16G16_UNORM" -> out.putShort(unorm16(r)).putShort(unorm16(g));
                case "R32_SFLOAT", "D32_SFLOAT" -> out.putFloat(r);
                case "R32_UINT" -> out.putInt(Math.round(r));
                case "B10G11R11_UFLOAT_PACK32" -> out.putInt(packB10G11R11(r, g, bl));
                case "A2B10G10R10_UNORM_PACK32" -> out.putInt((Math.round(clamp(r, 0, 1) * 1023)) | (Math.round(clamp(g, 0, 1) * 1023) << 10)
                        | (Math.round(clamp(bl, 0, 1) * 1023) << 20) | (Math.round(clamp(a, 0, 1) * 3) << 30));
                case "R16G16B16A16_SFLOAT" -> out.putShort(half(r)).putShort(half(g)).putShort(half(bl)).putShort(half(a));
                case "R16G16B16A16_UNORM" -> out.putShort(unorm16(r)).putShort(unorm16(g)).putShort(unorm16(bl)).putShort(unorm16(a));
                case "R16G16B16A16_SNORM" -> out.putShort((short) Math.round(clamp(r, -1, 1) * 32767)).putShort((short) Math.round(clamp(g, -1, 1) * 32767))
                        .putShort((short) Math.round(clamp(bl, -1, 1) * 32767)).putShort((short) Math.round(clamp(a, -1, 1) * 32767));
                case "R32G32_SFLOAT" -> out.putFloat(r).putFloat(g);
                case "R32G32B32A32_SFLOAT" -> out.putFloat(r).putFloat(g).putFloat(bl).putFloat(a);
                case "R32G32B32A32_UINT" -> out.putInt(Math.round(r)).putInt(Math.round(g)).putInt(Math.round(bl)).putInt(Math.round(a));
                default -> throw new IllegalArgumentException(vk);
            }
        }
        out.flip();
        return out;
    }

    public static float[] decode(String vk, ByteBuffer in, int texels) {
        ByteBuffer b = in.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        float[] o = new float[texels * 4];
        for (int i = 0; i < texels; i++) {
            float r = 0, g = 0, bl = 0, a = 1;
            switch (vk) {
                case "R8_UNORM" -> r = (b.get() & 0xff) / 255f;
                case "R8_SNORM" -> r = Math.max(-1, b.get() / 127f);
                case "R8_UINT" -> r = b.get() & 0xff;
                case "R8G8_UNORM" -> { r = (b.get() & 0xff) / 255f; g = (b.get() & 0xff) / 255f; }
                case "R8G8_SNORM" -> { r = Math.max(-1, b.get() / 127f); g = Math.max(-1, b.get() / 127f); }
                case "R8G8B8A8_UNORM" -> { r = (b.get() & 0xff) / 255f; g = (b.get() & 0xff) / 255f; bl = (b.get() & 0xff) / 255f; a = (b.get() & 0xff) / 255f; }
                case "R8G8B8A8_SNORM" -> { r = Math.max(-1, b.get() / 127f); g = Math.max(-1, b.get() / 127f); bl = Math.max(-1, b.get() / 127f); a = Math.max(-1, b.get() / 127f); }
                case "R8G8B8A8_UINT" -> { r = b.get() & 0xff; g = b.get() & 0xff; bl = b.get() & 0xff; a = b.get() & 0xff; }
                case "R16_SFLOAT" -> r = unhalf(b.getShort());
                case "R16_UNORM" -> r = (b.getShort() & 0xffff) / 65535f;
                case "R16_SNORM" -> r = Math.max(-1, b.getShort() / 32767f);
                case "R16_UINT" -> r = b.getShort() & 0xffff;
                case "R16G16_SFLOAT" -> { r = unhalf(b.getShort()); g = unhalf(b.getShort()); }
                case "R16G16_UNORM" -> { r = (b.getShort() & 0xffff) / 65535f; g = (b.getShort() & 0xffff) / 65535f; }
                case "R32_SFLOAT", "D32_SFLOAT" -> r = b.getFloat();
                case "R32_UINT" -> r = b.getInt() & 0xffffffffL;
                case "B10G11R11_UFLOAT_PACK32" -> {
                    int v = b.getInt();
                    r = uf11(v & 0x7ff);
                    g = uf11((v >>> 11) & 0x7ff);
                    bl = uf10((v >>> 22) & 0x3ff);
                }
                case "A2B10G10R10_UNORM_PACK32" -> {
                    int v = b.getInt();
                    r = (v & 1023) / 1023f;
                    g = ((v >>> 10) & 1023) / 1023f;
                    bl = ((v >>> 20) & 1023) / 1023f;
                    a = ((v >>> 30) & 3) / 3f;
                }
                case "R16G16B16A16_SFLOAT" -> { r = unhalf(b.getShort()); g = unhalf(b.getShort()); bl = unhalf(b.getShort()); a = unhalf(b.getShort()); }
                case "R16G16B16A16_UNORM" -> { r = (b.getShort() & 0xffff) / 65535f; g = (b.getShort() & 0xffff) / 65535f; bl = (b.getShort() & 0xffff) / 65535f; a = (b.getShort() & 0xffff) / 65535f; }
                case "R16G16B16A16_SNORM" -> { r = b.getShort() / 32767f; g = b.getShort() / 32767f; bl = b.getShort() / 32767f; a = b.getShort() / 32767f; }
                case "R32G32_SFLOAT" -> { r = b.getFloat(); g = b.getFloat(); }
                case "R32G32B32A32_SFLOAT" -> { r = b.getFloat(); g = b.getFloat(); bl = b.getFloat(); a = b.getFloat(); }
                case "R32G32B32A32_UINT" -> { r = b.getInt(); g = b.getInt(); bl = b.getInt(); a = b.getInt(); }
                default -> throw new IllegalArgumentException(vk);
            }
            o[4 * i] = r;
            o[4 * i + 1] = g;
            o[4 * i + 2] = bl;
            o[4 * i + 3] = a;
        }
        return o;
    }

    static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    static byte unorm8(float v) {
        return (byte) Math.round(clamp(v, 0, 1) * 255);
    }

    static byte snorm8(float v) {
        return (byte) Math.round(clamp(v, -1, 1) * 127);
    }

    static short unorm16(float v) {
        return (short) Math.round(clamp(v, 0, 1) * 65535);
    }

    public static short half(float f) {
        return Float.floatToFloat16(f);
    }

    public static float unhalf(short h) {
        return Float.float16ToFloat(h);
    }

    /** unsigned 11-bit float: 5-bit exponent, 6-bit mantissa (10-bit: 5 mantissa) */
    static int ufloat(float v, int mantBits) {
        if (Float.isNaN(v))
            return (0x1f << mantBits) | 1;
        if (v <= 0)
            return 0;
        int bits = Float.floatToIntBits(v);
        int exp = ((bits >>> 23) & 0xff) - 127 + 15;
        int mant = (bits & 0x7fffff) >>> (23 - mantBits);
        if (exp >= 31)
            return (0x1e << mantBits) | ((1 << mantBits) - 1); // clamp to max finite
        if (exp <= 0) {
            // denormal
            int m = ((bits & 0x7fffff) | 0x800000) >>> (24 - mantBits - exp);
            return Math.min(m, (1 << mantBits) - 1);
        }
        return (exp << mantBits) | mant;
    }

    static float unufloat(int v, int mantBits) {
        int exp = v >>> mantBits;
        int mant = v & ((1 << mantBits) - 1);
        if (exp == 0)
            return (float) (mant / (double) (1 << mantBits) * Math.pow(2, -14));
        if (exp == 31)
            return mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        return (float) ((1 + mant / (double) (1 << mantBits)) * Math.pow(2, exp - 15));
    }

    static int packB10G11R11(float r, float g, float b) {
        return ufloat(r, 6) | (ufloat(g, 6) << 11) | (ufloat(b, 5) << 22);
    }

    static float uf11(int v) {
        return unufloat(v, 6);
    }

    static float uf10(int v) {
        return unufloat(v, 5);
    }
}
