package dev.elemore.iroh.rt;

/**
 * Planning model for the RT core's device memory (docs/P5-PLAN.md section 4). All per-triangle numbers are PARAMETERS:
 * the only measured value is lavapipe's (208 B per quad = 104 B/triangle, compaction saved 0% there), which says
 * nothing about RADV's BVH format. RADV on RDNA4 is assumed at 64 B per triangle after compaction (UNVERIFIED: measure
 * in card P5-1 with VkAccelerationStructureBuildSizesInfoKHR + the compacted-size query on the real device).
 * BLAS input costs nothing extra: the build reads the raster vertex arena in place.
 */
public final class RtMemoryModel {
    public static final double LAVAPIPE_BYTES_PER_TRIANGLE = 104;
    public static final double RADV_BYTES_PER_TRIANGLE_ASSUMED = 64;

    public final double bytesPerTriangle;
    public final double scratchFactor;      // build scratch / AS size (lavapipe measured ~0.37 for a 1300-quad BLAS)
    public final int buildBatch;            // BLAS builds in flight (scratch is reused per batch)

    public RtMemoryModel(double bytesPerTriangle, double scratchFactor, int buildBatch) {
        this.bytesPerTriangle = bytesPerTriangle;
        this.scratchFactor = scratchFactor;
        this.buildBatch = buildBatch;
    }

    public static RtMemoryModel radvAssumed() {
        return new RtMemoryModel(RADV_BYTES_PER_TRIANGLE_ASSUMED, 0.5, 8);
    }

    public long blasBytes(int quads) {
        return (long) Math.ceil(quads * 2.0 * bytesPerTriangle) + 256; // + header
    }

    /** TLAS: 64-byte instance records + the structure (~128 B per instance, assumed). */
    public static long tlasBytes(int instances) {
        return instances * (64L + 128L) + 4096;
    }

    /** Columns within a render distance (chunks): (2R+1)^2. */
    public static long columns(int renderDistance) {
        long d = 2L * renderDistance + 1;
        return d * d;
    }

    /**
     * Total for {@code sections} resident sections averaging {@code quadsPerSection}, plus the radiance cache
     * ({@code cacheEntries} x 48 B) and the per-pixel RT buffers at a render size ({@code bytesPerPixel} each).
     */
    public long total(long sections, int quadsPerSection, long cacheEntries, long renderPixels, int bytesPerPixel) {
        long blas = sections * blasBytes(quadsPerSection);
        long scratch = (long) (buildBatch * blasBytes(quadsPerSection) * scratchFactor);
        return blas + scratch + tlasBytes((int) Math.min(Integer.MAX_VALUE, sections * 2)) + cacheEntries * 48 + renderPixels * bytesPerPixel;
    }
}
