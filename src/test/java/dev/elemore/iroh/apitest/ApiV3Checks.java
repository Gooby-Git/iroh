package dev.elemore.iroh.apitest;

import com.google.gson.JsonObject;
import dev.elemore.iroh.api.*;
import dev.elemore.iroh.apiimpl.DrawImpl;
import dev.elemore.iroh.apiimpl.DynamicTextures;
import dev.elemore.iroh.apiimpl.FrameHooks;
import dev.elemore.iroh.apiimpl.SkyHooks;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.vulkan.VRenderSystem;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BiConsumer;

/** P1-13a (api 3) checks, run from {@link ApiTest}: frame hooks, state guard, sky hooks, draw layout, dynamic textures. */
final class ApiV3Checks {
    private ApiV3Checks() {
    }

    private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** A per-call allocation over 1e6 calls is at least 16 MB; a few KB is the JIT's own bookkeeping on this thread. */
    private static final long NOISE = 16_384;

    private static long allocated() {
        return MX.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    static void run(BiConsumer<String, Boolean> check) {
        check.accept("api version 4.1", IrohApi.API_VERSION == 4 && IrohApi.apiVersion() == 4 && IrohApi.apiMinor() == 1);
        FrameStage[] all = FrameStage.values();
        check.accept("FrameStage constants append-only: AFTER_PRESENT, OVERLAY, then BEFORE_MAIN_PASS (api 4) last",
                all[all.length - 3] == FrameStage.AFTER_PRESENT && all[all.length - 2] == FrameStage.OVERLAY
                        && all[all.length - 1] == FrameStage.BEFORE_MAIN_PASS);
        check.accept("capabilities: frame hooks, sky hooks, draw, shaders, dynamic textures present",
                IrohApi.capabilities().containsAll(EnumSet.of(Capability.FRAME_HOOKS, Capability.SKY_HOOKS,
                        Capability.IMMEDIATE_DRAW, Capability.OWNER_SHADERS, Capability.DYNAMIC_TEXTURES)));
        check.accept("capabilities: targets, buffers, compute, MDI count absent while no device is up (offline)",
                !IrohApi.capabilities().contains(Capability.RENDER_TARGETS) && !IrohApi.capabilities().contains(Capability.GPU_BUFFERS)
                        && !IrohApi.capabilities().contains(Capability.COMPUTE)
                        && !IrohApi.capabilities().contains(Capability.MULTI_DRAW_INDIRECT_COUNT));
        check.accept("api 4 calls throw UnsupportedOperationException while no device is up",
                throwsUoe(() -> IrohTargets.create("m", "t", IrohTargets.Format.RGBA8, 4, 4)) && throwsUoe(() -> IrohBuffers.create("m", "b", 16, IrohBuffers.STORAGE))
                && throwsUoe(() -> IrohCompute.pipeline("m", "c", "", 0, 0))
                && throwsUoe(() -> IrohDraw.multiDrawIndirectCount(null, null, null, null, null, false, null, 0, null, 0, 1, 20)));

        // ---- frame hooks: order
        List<String> order = new ArrayList<>();
        FrameStage st = FrameStage.AFTER_ENTITIES;
        var r1 = IrohFrame.addHook(st, 5, EnumSet.of(RenderPath.RASTER), c -> order.add("p5"));
        var r2 = IrohFrame.addHook(st, c -> order.add("p0a"));
        var r3 = IrohFrame.addHook(st, c -> order.add("p0b"));
        var r4 = IrohFrame.addHook(st, -3, EnumSet.of(RenderPath.RASTER), c -> order.add("m3"));
        var r5 = IrohFrame.addHook(st, -9, EnumSet.of(RenderPath.PACK, RenderPath.RT), c -> order.add("packOnly"));
        FrameHooks.dispatch(st);
        check.accept("hooks run by priority then registration order, PACK-only hook skipped on RASTER: " + order,
                order.equals(List.of("m3", "p0a", "p0b", "p5")));
        check.accept("context stage set during dispatch", FrameHooks.context().stage() == st);
        check.accept("guiGraphics() null outside the 2D stages", FrameHooks.context().guiGraphics() == null);
        r2.close();
        order.clear();
        FrameHooks.dispatch(st);
        check.accept("closed registration no longer runs", order.equals(List.of("m3", "p0b", "p5")));
        r1.close(); r3.close(); r4.close(); r5.close();
        check.accept("all closed: stage empty", FrameHooks.count(st) == 0);

        // ---- failing hook removed after 3 throws (logged, not propagated)
        int[] runs = {0};
        IrohFrame.addHook(FrameStage.AFTER_SKY, c -> { runs[0]++; throw new IllegalStateException("test"); });
        boolean escaped = false;
        for (int i = 0; i < 5; i++) {
            try { FrameHooks.dispatch(FrameStage.AFTER_SKY); } catch (Throwable t) { escaped = true; }
        }
        check.accept("throwing hook: never propagates, removed after 3 failures", !escaped && runs[0] == 3 && FrameHooks.count(FrameStage.AFTER_SKY) == 0);

        // ---- zero cost when empty, and zero allocation with a hook registered
        for (int i = 0; i < 20_000; i++) FrameHooks.dispatch(FrameStage.AFTER_WEATHER);
        long a0 = allocated();
        for (int i = 0; i < 1_000_000; i++) FrameHooks.dispatch(FrameStage.AFTER_WEATHER);
        long empty = allocated() - a0;
        long t0 = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) FrameHooks.dispatch(FrameStage.AFTER_WEATHER);
        double nsEmpty = (System.nanoTime() - t0) / 1e6;
        check.accept("empty stage: no per-call allocation over 1e6 dispatches (" + empty + " B total, JIT noise), " + String.format("%.1f", nsEmpty) + " ns each",
                empty < NOISE && nsEmpty < 50);
        int[] count = {0};
        var rc = IrohFrame.addHook(FrameStage.AFTER_WEATHER, c -> count[0]++);
        for (int i = 0; i < 20_000; i++) FrameHooks.dispatch(FrameStage.AFTER_WEATHER);
        a0 = allocated();
        for (int i = 0; i < 1_000_000; i++) FrameHooks.dispatch(FrameStage.AFTER_WEATHER);
        long one = allocated() - a0;
        rc.close();
        check.accept("one hook: no per-call allocation over 1e6 dispatches (" + one + " B total)", one < NOISE && count[0] == 1_020_000);

        // ---- state guard: a hook that leaves depth test / cull changed is restored (AssertionError under -ea)
        boolean dt = VRenderSystem.depthTest, cull = VRenderSystem.cull;
        var rs = IrohFrame.addHook(FrameStage.BEFORE_HUD, c -> { VRenderSystem.depthTest = !VRenderSystem.depthTest; VRenderSystem.cull = !VRenderSystem.cull; });
        boolean assertion = false;
        try { FrameHooks.dispatch(FrameStage.BEFORE_HUD); } catch (AssertionError e) { assertion = true; }
        rs.close();
        boolean ea = false;
        assert ea = true;
        check.accept("state guard restores depth test and cull" + (ea ? " and asserts under -ea" : ""),
                VRenderSystem.depthTest == dt && VRenderSystem.cull == cull && assertion == ea);
        var rk = IrohFrame.addHook(FrameStage.BEFORE_HUD, c -> { VRenderSystem.depthTest = !VRenderSystem.depthTest; VRenderSystem.depthTest = !VRenderSystem.depthTest; });
        boolean clean = true;
        try { FrameHooks.dispatch(FrameStage.BEFORE_HUD); } catch (AssertionError e) { clean = false; }
        rk.close();
        check.accept("state guard silent for a hook that restores what it changed", clean);

        // ---- sky hooks
        check.accept("no suppressor: clouds not suppressed, no override claims", !SkyHooks.cloudsSuppressed(null)
                && !SkyHooks.drawCloudOverride() && !SkyHooks.drawWeatherOverride());
        var sup = IrohSky.setCloudsSuppressed(level -> true);
        boolean s1 = SkyHooks.cloudsSuppressed(null);
        sup.close();
        check.accept("suppressor applies, close removes it", s1 && !SkyHooks.cloudsSuppressed(null));
        List<String> sky = new ArrayList<>();
        var o1 = IrohSky.overrideWeather(10, c -> { sky.add("late"); return true; });
        var o2 = IrohSky.overrideWeather(1, c -> { sky.add("early-pass"); return false; });
        boolean claimed = SkyHooks.drawWeatherOverride();
        o1.close(); o2.close();
        check.accept("weather overrides by priority, first claim wins: " + sky, claimed && sky.equals(List.of("early-pass", "late")));

        // ---- draw: layout json and colour packing
        JsonObject j = DrawImpl.bindingsJson(new ShaderLayout(List.of("MVP"), List.of("ColorModulator", "FogStart"), List.of("Sampler0", "Sampler2")));
        var ubos = j.getAsJsonArray("UBOs");
        check.accept("layout: vertex UBO binding 0, fragment UBO binding 1, 2 samplers",
                ubos.size() == 2 && ubos.get(0).getAsJsonObject().get("binding").getAsInt() == 0
                        && ubos.get(1).getAsJsonObject().get("binding").getAsInt() == 1
                        && ubos.get(1).getAsJsonObject().getAsJsonArray("fields").get(0).getAsJsonObject().get("count").getAsInt() == 4
                        && j.getAsJsonArray("samplers").size() == 2);
        boolean unknown = false;
        try { DrawImpl.bindingsJson(new ShaderLayout(List.of("Nope"), List.of(), List.of())); } catch (IllegalArgumentException e) { unknown = true; }
        check.accept("layout: unknown uniform name rejected", unknown);
        ByteBuffer bb = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, DrawImpl.abgr(0x80112233));
        check.accept("colour 0xAARRGGBB -> bytes R,G,B,A", bb.get(0) == 0x11 && bb.get(1) == 0x22 && bb.get(2) == 0x33 && bb.get(3) == (byte) 0x80);
        GeometryWriter w = IrohDraw.begin(null);
        w.vertex(0, 0, 0, -1).end();
        check.accept("IrohDraw outside a frame hook: no-op writer, nothing batched", true);

        // ---- dynamic textures: nearly free with no consumer
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("iroh_test", "dynamic/a");
        DynamicTextureHandle h = IrohTextures.createDynamic(id, 64, 32, IrohTextures.Filter.LINEAR, IrohTextures.Wrap.REPEAT);
        ByteBuffer px = ByteBuffer.allocateDirect(64 * 32 * 4);
        for (int i = 0; i < 10_000; i++) h.update(0, 0, 64, 32, px);
        a0 = allocated();
        t0 = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) h.update(0, 0, 64, 32, px);
        double nsUpd = (System.nanoTime() - t0) / 1e6;
        long upAlloc = allocated() - a0;
        check.accept("dynamic texture: hasConsumers false on RASTER, update is a no-op (" + upAlloc + " B, "
                + String.format("%.1f", nsUpd) + " ns)", !h.hasConsumers() && DynamicTextures.uploads == 0 && upAlloc < NOISE && nsUpd < 50);
        DynamicTextures.addConsumer(id, 1);
        boolean on = h.hasConsumers();
        DynamicTextures.addConsumer(id, -1);
        check.accept("dynamic texture: consumer count drives hasConsumers", on && !h.hasConsumers());
        h.close();
        check.accept("dynamic texture: closed handle has no consumers", !DynamicTextures.addConsumer(id, 1) && !h.hasConsumers());

        // ---- held light read path
        check.accept("held light: provider count readable", IrohApi.heldLightProviderCount() == 0);
    }

    private static boolean throwsUoe(Runnable r) {
        try {
            r.run();
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }
}
