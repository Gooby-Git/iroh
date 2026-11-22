package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public class CustomVertexFormat {

    public static final VertexFormatElement ELEMENT_POSITION = new VertexFormatElement(0, 0,VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.POSITION, 4);
    public static final VertexFormatElement ELEMENT_COLOR = new VertexFormatElement(1, 0, VertexFormatElement.Type.UINT, VertexFormatElement.Usage.COLOR, 1);
    public static final VertexFormatElement ELEMENT_UV0 = new VertexFormatElement(2, 0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.UV, 2);

    public static final VertexFormat COMPRESSED_TERRAIN =
            VertexFormat.builder()
                        .add("Position", ELEMENT_POSITION)
                        .add("UV0", ELEMENT_UV0)
                        .add("Color", ELEMENT_COLOR)
                        .build();

    /**
     * Iroh P3-6: the PACK FULL-mode section vertex (WideTerrainVertex, 36 bytes). Its first 16 bytes keep the
     * compressed layout (int16 position grid, light bytes at +6/+7, uv0 x32768 at +8, RGBA at +12), so the raster
     * terrain pipeline still draws these sections correctly with a 36-byte stride (the fallback when the pack path
     * switches itself off). Only chosen at startup with -Diroh.pack.enabled=true -Diroh.pack.mode=full.
     */
    public static final VertexFormat PACK_WIDE_TERRAIN =
            VertexFormat.builder()
                        .add("Position", ELEMENT_POSITION)
                        .add("UV0", ELEMENT_UV0)
                        .add("Color", ELEMENT_COLOR)
                        .padding(20)
                        .build();

    /** a 16-byte-compatible int16 terrain format (compressed or pack-wide) */
    public static boolean compressedLayout(VertexFormat f) {
        return f == COMPRESSED_TERRAIN || f == PACK_WIDE_TERRAIN;
    }

    public static final VertexFormat NONE = VertexFormat.builder().build();
}
