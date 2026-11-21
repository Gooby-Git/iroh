package net.vulkanmod.mixin.render.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Queue;

/**
 * Iroh (rc3r2): {@code -Diroh.particleStats=true} logs once a second
 * {@code [iroh-particles] particles=N batches=B render avg=X ms max=Y ms over F frames}, measured around vanilla's
 * ParticleEngine.render (one buffer, one upload and one draw per non-empty ParticleRenderType; Iroh's
 * SingleQuadParticleM writes the 4 vertices directly and culls by section visibility). For the Phase 2 storm /
 * explosion scenes. HEAD / RETURN injects only, so other mods' injectors into render() are untouched; off = one
 * static boolean test per frame.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineStatsM {
    @Unique private static final boolean IROH_STATS = Boolean.getBoolean("iroh.particleStats");
    @Unique private static long iroh$t0, iroh$windowStart, iroh$sumNs, iroh$maxNs, iroh$particles, iroh$batches;
    @Unique private static int iroh$frames;

    @Shadow @Final private Map<ParticleRenderType, Queue<Particle>> particles;

    @Inject(method = "render", at = @At("HEAD"))
    private void iroh$statsBegin(LightTexture lightTexture, Camera camera, float f, CallbackInfo ci) {
        if (!IROH_STATS) return;
        long n = 0, b = 0;
        for (Queue<Particle> q : this.particles.values()) {
            if (q != null && !q.isEmpty()) {
                n += q.size();
                b++;
            }
        }
        iroh$particles += n;
        iroh$batches += b;
        iroh$t0 = System.nanoTime();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void iroh$statsEnd(LightTexture lightTexture, Camera camera, float f, CallbackInfo ci) {
        if (!IROH_STATS) return;
        long now = System.nanoTime(), dt = now - iroh$t0;
        iroh$sumNs += dt;
        iroh$maxNs = Math.max(iroh$maxNs, dt);
        iroh$frames++;
        if (iroh$windowStart == 0) iroh$windowStart = now;
        if (now - iroh$windowStart >= 1_000_000_000L) {
            Initializer.LOGGER.info("[iroh-particles] particles={} batches={} render avg={} ms max={} ms over {} frames",
                    iroh$particles / iroh$frames, iroh$batches / iroh$frames,
                    String.format("%.3f", iroh$sumNs / 1e6 / iroh$frames), String.format("%.3f", iroh$maxNs / 1e6), iroh$frames);
            iroh$windowStart = now;
            iroh$sumNs = iroh$maxNs = iroh$particles = iroh$batches = 0;
            iroh$frames = 0;
        }
    }
}
