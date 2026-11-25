package net.vulkanmod.render.chunk.build.task;

import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.interfaces.VisibilitySetExtended;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.vertex.QuadSorter;
import net.vulkanmod.render.vertex.TerrainRenderType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public class CompileResult {
    public final RenderSection renderSection;
    public final boolean fullUpdate;

    final List<BlockEntity> globalBlockEntities = new ArrayList<>();
    final List<BlockEntity> blockEntities = new ArrayList<>();
    public final EnumMap<TerrainRenderType, UploadBuffer> renderedLayers = new EnumMap<>(TerrainRenderType.class);

    VisibilitySet visibilitySet;
    QuadSorter.SortState transparencyState;
    CompiledSection compiledSection;

    CompileResult(RenderSection renderSection, boolean fullUpdate) {
        this.renderSection = renderSection;
        this.fullUpdate = fullUpdate;
    }

    /** P2-4: bytes this result will upload (intake budget prediction). */
    public long byteSize() {
        long b = 0;
        for (var u : this.renderedLayers.values())
            if (u != null) b += u.byteSize();
        return b;
    }

    public void updateSection() {
        this.renderSection.updateGlobalBlockEntities(globalBlockEntities);
        this.renderSection.setCompiledSection(compiledSection);
        this.renderSection.setVisibility(((VisibilitySetExtended)visibilitySet).getVisibility());
        this.renderSection.setCompletelyEmpty(compiledSection.isCompletelyEmpty);
        this.renderSection.setContainsBlockEntities(!blockEntities.isEmpty());
        var cull = net.vulkanmod.render.chunk.cull.SectionCullData.current(); // P2-7b: compiled + opaque flags for the GPU cull
        if (cull != null)
            cull.sectionCompiled(this.renderSection.gridIndex, this.renderSection.xOffset() >> 4, this.renderSection.zOffset() >> 4,
                    this.renderSection.getVisibility(), compiledSection.isCompletelyEmpty);
    }
}
