package net.vulkanmod.render.chunk.build;

import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.vulkanmod.interfaces.biome.BiomeManagerExtended;
import net.vulkanmod.render.chunk.build.biome.BiomeData;

public class RenderRegionBuilder {
    private static final DataLayer DEFAULT_SKY_LIGHT_DATA_LAYER = new DataLayer(15);
    private static final DataLayer DEFAULT_BLOCK_LIGHT_DATA_LAYER = new DataLayer(0);

    private static final int MAX_CACHE_ENTRIES = 256;
    private final Long2ReferenceLinkedOpenHashMap<LevelChunk> levelChunkCache = new Long2ReferenceLinkedOpenHashMap<>(MAX_CACHE_ENTRIES);

    public RenderRegion createRegion(Level level, int secX, int secY, int secZ) {
        LevelChunk levelChunk = getLevelChunk(level, secX, secZ);
        var sections = levelChunk.getSections();
        LevelChunkSection section = sections[level.getSectionIndexFromSectionY(secY)];

        if (section == null || section.hasOnlyAir())
            return null;

        var blockEntityMap = levelChunk.getBlockEntities();

        int minSecX = secX - 1;
        int minSecZ = secZ - 1;
        int minSecY = secY - 1;
        int maxSecX = secX + 1;
        int maxSecZ = secZ + 1;
        int maxSecY = secY + 1;

        PalettedContainer<BlockState>[] blockData = new PalettedContainer[RenderRegion.SIZE];

        DataLayer[][] lightData = new DataLayer[RenderRegion.SIZE][2 /* Light types */];

        long biomeZoomSeed = BiomeManagerExtended.of(level.getBiomeManager()).getBiomeZoomSeed();
        BiomeData biomeData = new BiomeData(biomeZoomSeed, minSecX, minSecY, minSecZ);

        final int minHeightSec = level.getMinBuildHeight() >> 4;
        for (int x = minSecX; x <= maxSecX; ++x) {
            for (int z = minSecZ; z <= maxSecZ; ++z) {
                LevelChunk levelChunk1 = getLevelChunk(level, x, z);
                sections = levelChunk1.getSections();

                for (int y = minSecY; y <= maxSecY; ++y) {
                    int sectionIdx = y - minHeightSec;
                    section = sectionIdx >= 0 && sectionIdx < sections.length ? sections[sectionIdx] : null;

                    final int relX = (x - minSecX), relY = (y - minSecY), relZ = (z - minSecZ);
                    final int idx = (relY * RenderRegion.WIDTH + relZ) * RenderRegion.WIDTH + relX;

                    PalettedContainer<BlockState> values = section == null || section.hasOnlyAir() ? null : this.cloneOf(section, x, y, z);

                    blockData[idx] = values;

                    SectionPos pos = SectionPos.of(x, y, z);
                    DataLayer[] dataLayers = getSectionDataLayers(level, pos);

                    lightData[idx] = dataLayers;

                    biomeData.getBiomeData(level, section, relX, relY, relZ);
                }
            }
        }

        return new RenderRegion(level, secX, secY, secZ, blockData, lightData, biomeData, blockEntityMap);
    }

    private DataLayer[] getSectionDataLayers(Level level, SectionPos pos) {
        DataLayer[] dataLayers = new DataLayer[2];

        DataLayer blockDataLayer;
        blockDataLayer = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);

        if (blockDataLayer == null)
            blockDataLayer = DEFAULT_BLOCK_LIGHT_DATA_LAYER;

        dataLayers[LightLayer.BLOCK.ordinal()] = blockDataLayer;

        DataLayer skyDataLayer;
        if (level.dimensionType().hasSkyLight()) {
            skyDataLayer = level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(pos);

            if (skyDataLayer == null)
                skyDataLayer = DEFAULT_SKY_LIGHT_DATA_LAYER;
        } else
            skyDataLayer = null;

        dataLayers[LightLayer.SKY.ordinal()] = skyDataLayer;

        return dataLayers;
    }

    private LevelChunk getLevelChunk(Level level, int x, int z) {
        long l = ChunkPos.asLong(x, z);
        LevelChunk chunk = this.levelChunkCache.getAndMoveToFirst(l);

        if (chunk == null) {
            chunk = level.getChunk(x, z);

            while (levelChunkCache.size() >= MAX_CACHE_ENTRIES) {
                levelChunkCache.removeLast();
            }

            this.levelChunkCache.putAndMoveToFirst(l, chunk);
        }

        return chunk;
    }

    public void remove(int x, int z) {
        levelChunkCache.remove(ChunkPos.asLong(x, z));
        if (!this.clones.isEmpty()) this.clones.clear(); // a block changed: no copy taken before it may be reused
    }

    public void clear() {
        this.levelChunkCache.clear();
        this.clones.clear();
    }

    /**
     * perf/hitches: block-state copies shared between the rebuilds scheduled in one batch. Each region copies the 3x3x3
     * sections around its section (27 PalettedContainer copies); neighbouring rebuilds copied the same sections again, up
     * to 27 times (block 6: 300-500 rebuilds pending, 17-36 MB allocated in a slow frame). A copy is read-only for the
     * builders, so one copy per section per batch is shared. The cache lives for one scheduling batch
     * ({@link #beginBatch}) and is dropped whenever a block changes ({@link #remove}). Off: -Diroh.graph.cloneCache=false.
     */
    public static final boolean CLONE_CACHE = !"false".equals(System.getProperty("iroh.graph.cloneCache"));
    private final it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap<PalettedContainer<BlockState>> clones =
            new it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap<>();
    public int clonesMade, clonesShared;

    /** Render thread, before a batch of rebuilds is scheduled. */
    public void beginBatch() {
        if (!this.clones.isEmpty()) this.clones.clear();
    }

    private PalettedContainer<BlockState> cloneOf(LevelChunkSection section, int x, int y, int z) {
        if (!CLONE_CACHE) return section.getStates().copy();
        long key = SectionPos.asLong(x, y, z);
        PalettedContainer<BlockState> c = this.clones.get(key);
        if (c != null) {
            this.clonesShared++;
            return c;
        }
        c = section.getStates().copy();
        this.clones.put(key, c);
        this.clonesMade++;
        return c;
    }
}
