package dev.elemore.iroh.shaderpack.runtime;

import java.util.Map;
import java.util.Set;

/**
 * What the game supplies to one pack frame (the executor's only window on the world). In game: Iroh's renderer
 * (camera, previous and shadow matrices, gbuffer passes, lightmap, atlas, UniformSupply + CustomEval values, time,
 * weather). Offline: a {@link FrameCapture} read from disk or a {@link SyntheticScene}.
 */
public interface PackFrameInputs {

    int width();

    int height();

    /** uniform values by name (standard, compat iroh_* and custom), each 1..16 doubles; missing names use the GLSL default */
    Map<String, double[]> uniforms();

    /**
     * Images the frame provides instead of running the passes that write them (a replay starts at a stage): names are
     * pack target names (colortex0, depthtex0, depthtex1, depthtex2, shadowtex0, shadowtex1, shadowcolor0 ...) or custom
     * texture sampler names. Values: rgba floats, 4 per texel (depth in .r).
     */
    Set<String> suppliedImages();

    float[] image(String name);

    /** extent of a supplied image: {w, h, d} */
    int[] extent(String name);

    /** the stage the executor starts at; passes before it are supplied ("deferred" for a post-chain replay, "begin" in game) */
    String startStage();

    double frameSeconds();

    /**
     * P3-5 geometry for FULL mode: one entry per draw batch. {@code geometryClass} names a PassGraph.GEOMETRY class
     * (terrain_solid, terrain_cutout, terrain_translucent, entities, ...); the shadow pass draws the matching casters.
     * {@code layout} "wide-terrain" = WideTerrainVertex quads; {@code origin} = section origin minus camera position
     * (blocks, camera-relative, as Iris' chunkOffset).
     */
    record Geometry(String geometryClass, String layout, java.nio.ByteBuffer vertices, int quads, double[] origin, Indirect indirect) {
        public Geometry(String geometryClass, String layout, java.nio.ByteBuffer vertices, int quads, double[] origin) {
            this(geometryClass, layout, vertices, quads, origin, null);
        }
    }

    /**
     * P3-6 in-game terrain: draw an Iroh chunk area's sections with the renderer's own buffers: its area vertex
     * buffer, the index buffer the raster path uses for that layer, and indexed-indirect commands Iroh wrote (same
     * visibility as raster; baseInstance carries each section's offset, decoded by the translated vertex shader).
     * geometryClass "shadow:<class>" = the shadow pass's own visibility list (not used by the main passes).
     */
    record Indirect(long vertexBuffer, long indexBuffer, int indexType, long indirectBuffer, long indirectOffset, int drawCount, int stride) {}

    default java.util.List<Geometry> geometry() {
        return java.util.List.of();
    }

    /**
     * Draw textures for geometry programs ("geo:tex" atlas, "geo:normals", "geo:specular"): pixels via {@link #image}
     * and {@link #extent} under those names; absent = Iris defaults (normals flat (0.5, 0.5, 1, 1), specular 0).
     */
    default java.util.Set<String> geometryTextures() {
        return java.util.Set.of();
    }

    /** P3-6: draw textures already on the backend (in game: the block atlas imported from Iroh), by "geo:*" name */
    default Map<String, Integer> geometryImages() {
        return Map.of();
    }

    /** P3-6: backend image the pack's main depth (depthtex0 at frame end) is copied into (full mode: the scene depth) */
    default int depthTo() {
        return -1;
    }

    /** targets / depth images filled by a copy from a backend image (post mode: the raster scene colour and depth) */
    default Map<String, Integer> copySources() {
        return Map.of();
    }

    /** targets / depth images cleared to these values every frame (post mode: flat material, lit shadow map) */
    default Map<String, float[]> clearValues() {
        return Map.of();
    }
}
