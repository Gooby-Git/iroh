package net.vulkanmod.render.chunk.buffer;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.util.Util;
import net.vulkanmod.vulkan.memory.*;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

public class AreaBuffer {
    private static final boolean DEBUG = false;
    private static final Logger LOGGER = Initializer.LOGGER;

    /**
     * P2-4 (perf/streaming): the device side of an area buffer, so the segment logic runs offline with a fake
     * (StreamingTest). {@link #GAME} is the game's: VMA buffers or the AreaArena pool, UploadManager copies, deferred
     * segment frees.
     */
    public interface Ops {
        /** iroh.area.arena, read per operation (a runtime switch: new buffers and grows follow it at once) */
        boolean arena();

        /** the pooled size for at least {@code minBytes} */
        long arenaSize(long minBytes);

        Buffer allocate(int usage, long bytes, boolean arena);

        /** {@code b} is no longer referenced by new work; frames in flight may still read it */
        void retire(Buffer b);

        void recordUpload(Buffer dst, long dstOffset, long size, ByteBuffer src);

        void copyAll(Buffer src, Buffer dst);

        /** one transfer with {@code n} regions, each at the same offset in src and dst */
        void copyRegions(Buffer src, Buffer dst, long[] offsets, long[] sizes, int n);

        /** free the segment at {@code elementOffset} once the frames in flight are done with it */
        void deferSegmentFree(AreaBuffer a, int elementOffset);
    }

    public static final Ops GAME = new GameOps();

    private final Ops ops;
    private final int usage;
    private final int elementSize;

    private final Int2ReferenceOpenHashMap<Segment> usedSegments = new Int2ReferenceOpenHashMap<>();

    Segment first, last;

    private Buffer buffer;

    int size, used = 0;
    int segments = 0;
    /** P2-4 stats: bytes the grows copied (tests, memStats) */
    long grows, grownCopyBytes;
    /**
     * P2-4: device buffer creations + grows of every area buffer (render thread; the intake budget does not learn from a
     * section during which one happened: a rare, much larger cost than the bytes predict).
     */
    public static long bufferEvents;

    public AreaBuffer(Usage usage, int elementCount, int elementSize) {
        this(usage, elementCount, elementSize, GAME);
    }

    AreaBuffer(Usage usage, int elementCount, int elementSize, Ops ops) {
        this.ops = ops;
        this.usage = usage.usage;
        this.elementSize = elementSize;

        this.size = elementCount * elementSize;
        this.buffer = this.allocateBuffer();

        Segment s = new Segment(0, this.size);

        segments++;
        last = first = s;
    }

    private Buffer allocateBuffer() {
        bufferEvents++;
        boolean arena = this.ops.arena();
        if (!arena)
            return this.ops.allocate(this.usage, this.size, false);
        // P2-4: a pooled class; the buffer may be larger than asked, the usable size is whole elements of it
        long bytes = this.ops.arenaSize(this.size);
        Buffer b = this.ops.allocate(this.usage, bytes, true);
        this.size = usable(bytes);
        return b;
    }

    private int usable(long bytes) {
        long whole = bytes / this.elementSize * this.elementSize;
        return (int) Math.min(whole, Integer.MAX_VALUE / this.elementSize * this.elementSize);
    }

    public Segment upload(ByteBuffer byteBuffer, int oldOffset, long paramsPtr) {
        // Free old segment
        // Need to delay segment freeing since it might be still used by prev frames in flight.
        // TERRAIN-HOLES: only a segment this slot owns (paramsPtr 0 = offline callers without slots). The draw parameters
        // can name a segment the slot does not own: sequential translucent indices keep firstIndex 0 with no index
        // segment, so the next sorted upload freed index offset 0 of another section (RQ-HOLES-a).
        if (oldOffset != -1 && (paramsPtr == 0L || ownerAt(oldOffset) == paramsPtr)) {
            this.ops.deferSegmentFree(this, oldOffset);
        }

        int size = byteBuffer.remaining();

        if (DEBUG && size % elementSize != 0)
            throw new RuntimeException("Unaligned buffer");

        Segment segment = findSegment(size);

        if (segment.size - size > 0) {
            Segment s1 = new Segment(segment.offset + size, segment.size - size);
            segments++;

            if (segment.next != null) {
                s1.bindNext(segment.next);
            } else
                this.last = s1;

            segment.bindNext(s1);

            segment.size = size;
        }

        segment.free = false;
        this.usedSegments.put(segment.offset, segment);

        segment.paramsPtr = paramsPtr;

        Buffer dst = this.buffer;
        this.ops.recordUpload(dst, segment.offset, size, byteBuffer);

        this.used += size;

        return segment;
    }

    public Segment findSegment(int size) {
        Segment segment = null;

        Segment segment1 = this.first;
        while (segment1 != null) {
            if (segment1.isFree() && segment1.size >= size) {
                if (segment == null || segment1.size < segment.size)
                    segment = segment1;
            }

            segment1 = segment1.next;
        }

        if (segment == null || segment.size < size) {
            return this.reallocate(size);
        }

        return segment;
    }

    public Segment reallocate(int uploadSize) {
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.AREA_REALLOC); // perf/frame-attrib
        try {
            return reallocate0(uploadSize);
        } finally {
            if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.AREA_REALLOC);
        }
    }

    private Segment reallocate0(int uploadSize) {
        int oldSize = this.size;
        boolean arena = this.ops.arena();

        int increment;
        long allocBytes;
        int newSize;
        if (arena) {
            // P2-4: grow by half (fewer grows than the 1/8 rule) into a pooled class; the usable size is whole elements
            long want = (long) oldSize + Math.max(oldSize >> 1, (long) uploadSize << 1);
            allocBytes = this.ops.arenaSize(want);
            newSize = usable(allocBytes);
            increment = newSize - oldSize;
        } else {
            int minIncrement = this.size >> 3;
            minIncrement = (int) Util.align(minIncrement, this.elementSize);

            increment = Math.max(minIncrement, uploadSize << 1);
            newSize = oldSize + increment;
            allocBytes = newSize;
        }

        if (increment < uploadSize)
            throw new RuntimeException(String.format("Size increment %d < %d (Upload size)", increment, uploadSize));

        if (dev.elemore.iroh.diag.SlowFrames.ON) {
            dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.AREA_REALLOCS, 1);
            dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.AREA_GROW_KB, newSize >> 10);
        }

        this.size = newSize;
        Buffer dst = this.ops.allocate(this.usage, allocBytes, arena);

        if (arena)
            copyUsedRanges(this.buffer, dst); // P2-4: same offsets, free gaps skipped (no compaction: see moveUsedSegments)
        else {
            this.ops.copyAll(this.buffer, dst);
            this.grownCopyBytes += Math.min(this.buffer.getBufferSize(), dst.getBufferSize());
        }
        this.grows++;
        bufferEvents++;

        // TODO: moving only used segments causes corruption
//        moveUsedSegments(dst);

        this.ops.retire(this.buffer);
        this.buffer = dst;

        if (last.isFree()) {
            last.size += increment;
        }
        else {
            int offset = last.offset + last.size;
            Segment segment = new Segment(offset, newSize - offset);
            segments++;

            last.bindNext(segment);

            last = segment;
        }

        if (DEBUG)
            checkSegments();

        return last;
    }

    /** Gaps shorter than this are copied along (one region instead of two). */
    static final int MERGE_GAP = 64 << 10;
    private long[] regionOffsets = new long[16], regionSizes = new long[16];

    /**
     * P2-4: copy every byte a segment in use may hold, at the SAME offset (draw parameters stay valid), skipping free
     * gaps of at least {@link #MERGE_GAP}. Segments waiting for a deferred free are still marked used, so they are
     * copied too. One transfer with all regions.
     */
    void copyUsedRanges(Buffer src, Buffer dst) {
        int n = 0;
        long start = -1, end = -1;
        for (Segment s = this.first; s != null; s = s.next) {
            if (s.isFree() || s.size == 0) continue;
            if (start >= 0 && s.offset - end < MERGE_GAP) {
                end = s.offset + s.size;
                continue;
            }
            if (start >= 0) n = addRegion(n, start, end);
            start = s.offset;
            end = s.offset + s.size;
        }
        if (start >= 0) n = addRegion(n, start, end);
        long bytes = 0;
        for (int i = 0; i < n; i++) bytes += this.regionSizes[i];
        this.grownCopyBytes += bytes;
        if (n > 0) this.ops.copyRegions(src, dst, this.regionOffsets, this.regionSizes, n);
    }

    private int addRegion(int n, long start, long end) {
        if (n == this.regionOffsets.length) {
            this.regionOffsets = java.util.Arrays.copyOf(this.regionOffsets, n * 2);
            this.regionSizes = java.util.Arrays.copyOf(this.regionSizes, n * 2);
        }
        this.regionOffsets[n] = start;
        this.regionSizes[n] = end - start;
        return n + 1;
    }

    void moveUsedSegments(Buffer dst) {
        int srcOffset, dstOffset, uploadSize;
        int usedCount = 0;

        dstOffset = 0;
        int currOffset = dstOffset;

        Segment segment = this.first;
        Segment prevUsed = null;

        srcOffset = -1;
        uploadSize = 0;

        while (segment != null) {
            if (!segment.isFree()) {
                usedCount++;

                if (segment.offset != srcOffset + uploadSize) {

                    if (srcOffset == -1) {
                        dstOffset = 0;
                        this.first = segment;
                        segment.prev = null;
                    } else {
                        UploadManager.INSTANCE.copyBuffer(this.buffer, srcOffset, dst, dstOffset, uploadSize);

                        dstOffset += uploadSize;
                    }

                    srcOffset = segment.offset;
                    uploadSize = segment.size;

                } else {
                    uploadSize += segment.size;
                }

                this.usedSegments.remove(segment.offset);
                segment.offset = currOffset;
                currOffset += segment.size;
                updateDrawParams(segment);
                this.usedSegments.put(segment.offset, segment);

                if (prevUsed != null) {
                    prevUsed.bindNext(segment);
                }

                prevUsed = segment;
            }

            segment = segment.next;
        }

        if (uploadSize > 0) {
            UploadManager.INSTANCE.copyBuffer(this.buffer, srcOffset, dst, dstOffset, uploadSize);
        }

        if (prevUsed != null) {
            prevUsed.next = null;
            this.last = prevUsed;

            this.segments = usedCount;
        }
    }

    public void setSegmentFree(int offset) {
        Segment segment = usedSegments.remove(offset * elementSize);

        if (segment == null)
            return;

        this.used -= segment.size;

        segment.free = true;
        segment.paramsPtr = -1;

        Segment next = segment.next;
        if (next != null && next.isFree()) {
            mergeSegments(segment, next);
        }

        Segment prev = segment.prev;
        if (prev != null && prev.isFree()) {
            mergeSegments(prev, segment);
        }
    }

    private void mergeSegments(Segment segment, Segment next) {
        segment.size += next.size;

        if (next.next != null) {
            next.next.prev = segment;
        } else {
            this.last = segment;
        }

        segment.next = next.next;
        this.segments--;
    }

    private void updateDrawParams(Segment segment) {
        int elementOffset = segment.offset / elementSize;
        if (this.usage == Usage.VERTEX.usage) {
            DrawParametersBuffer.setVertexOffset(segment.paramsPtr, elementOffset);
        } else {
            DrawParametersBuffer.setFirstIndex(segment.paramsPtr, elementOffset);
        }
    }

    public long getId() {
        return this.buffer.getId();
    }

    public void freeBuffer() {
        this.ops.retire(this.buffer);
    }

    /** P2-4: free the segment at {@code elementOffset} (element units, as the draw parameters hold) after the frames in flight. */
    public void freeSegmentDeferred(int elementOffset) {
        this.ops.deferSegmentFree(this, elementOffset);
    }

    /**
     * TERRAIN-HOLES: the draw-parameter slot ({@code paramsPtr} given to {@link #upload}) that owns the used segment
     * starting at {@code elementOffset}, or 0 when no used segment starts there.
     */
    public long ownerAt(int elementOffset) {
        Segment s = this.usedSegments.get(elementOffset * this.elementSize);
        return s == null ? 0L : s.paramsPtr;
    }

    int elementSize() {
        return this.elementSize;
    }

    /** TERRAIN-HOLES: bytes of the used segment starting at {@code elementOffset}, or -1. */
    public int usedSizeAt(int elementOffset) {
        Segment s = this.usedSegments.get(elementOffset * this.elementSize);
        return s == null ? -1 : s.size;
    }

    /**
     * TERRAIN-HOLES (RQ-P24-a): free, after the frames in flight, the segment at {@code elementOffset} only if the slot
     * {@code paramsPtr} owns it. Returns whether a free was queued. Any other answer means the slot's parameters do not
     * describe a segment of this buffer (already freed, never uploaded here, or sequential translucent indices), and
     * freeing would hit another section's live mesh.
     */
    public boolean freeOwnedDeferred(int elementOffset, long paramsPtr) {
        if (elementOffset < 0 || paramsPtr == 0L || ownerAt(elementOffset) != paramsPtr)
            return false;
        this.ops.deferSegmentFree(this, elementOffset);
        return true;
    }

    public Buffer getBuffer() {
        return this.buffer;
    }

    public int fragmentation() {
        return (size - used) - (last.isFree() ? last.size : 0);
    }

    public void checkSegments() {
        for (String e : segmentErrors()) LOGGER.error(e);
    }

    /** P2-4: the segment-list invariants, as messages (empty = consistent). */
    public java.util.List<String> segmentErrors() {
        java.util.List<String> errors = new java.util.ArrayList<>();
        Segment segment = first;
        Segment prev = null;
        int i = 0;
        int usedSegments = 0;
        long usedBytes = 0;

        if (segment.offset != 0)
            errors.add(String.format("expected first offset 0 but got %d", segment.offset));

        while (segment != null) {
            if (i >= this.segments) {
                errors.add("Count is greater than segments");
                break;
            }

            if (segment.prev != prev) {
                errors.add(String.format("expected previous segment not matching (segment %d)", i));
            }

            if (!segment.isFree()) {
                usedSegments++;
                usedBytes += segment.size;
            }
            else if (next(segment) != null && next(segment).isFree())
                errors.add(String.format("two free segments in a row (segment %d)", i));

            if (segment.offset % elementSize != 0) {
                errors.add(String.format("offset %d misaligned (segment %d)", segment.offset, i));
            }

            Segment next = segment.next;

            if (next != null) {
                int offset = segment.offset + segment.size;
                if (offset != next.offset)
                    errors.add(String.format("expected offset %d but got %d (segment %d)", offset, next.offset, i));

                if (next.prev != segment)
                    errors.add(String.format("segment pointer not correct (segment %d)", i));

            } else {
                if (segment != this.last)
                    errors.add(String.format("segment has no next pointer and it's not last (segment %d)", i));
                else {
                    int segmentEnd = segment.offset + segment.size;
                    if (segment.offset + segment.size != this.size)
                        errors.add(String.format("last segment end (%d) does not match buffer size (%d)", segmentEnd, this.size));
                }

            }

            prev = segment;
            segment = next;
            i++;
        }

        if (i != this.segments)
            errors.add("Count do not match segments");

        if (usedSegments != this.usedSegments.size())
            errors.add("Counted used segment do not match used segments map size");
        if (usedBytes != this.used)
            errors.add(String.format("used bytes %d but the used segments hold %d", this.used, usedBytes));
        return errors;
    }

    private static Segment next(Segment s) {
        return s.next;
    }

    public int getSize() {
        return size;
    }

    public int getUsed() {
        return used;
    }

    public static class Segment {
        int offset, size;
        boolean free = true;
        long paramsPtr;

        Segment next, prev;

        private Segment(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }

        public int getOffset() {
            return offset;
        }

        public int getSize() {
            return size;
        }

        public boolean isFree() {
            return free;
        }

        public void setFree(boolean free) {
            this.free = free;
        }

        public void bindNext(Segment s) {
            this.next = s;
            s.prev = this;
        }

    }

    public enum Usage {
        VERTEX(0),
        INDEX(1);

        final int usage;

        Usage(int i) {
            usage = i;
        }
    }

}
