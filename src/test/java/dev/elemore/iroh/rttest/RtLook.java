package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.RtFrameData;
import dev.elemore.iroh.rt.RtLookModel;
import dev.elemore.iroh.upscale.fsr.NativeLoader;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import static dev.elemore.iroh.rt.RtFrameData.*;

/**
 * P5-1 step 2 (and the preset table of step 3): the RT look on the village world, ./gradlew rtLook. For each quality
 * preset, six scenes (day, sunset, night with torches, cave, underwater, rain) through the FULL chain (jittered G-buffer,
 * RT, own denoiser, compose + look, FSR 3.1 to 640x360, tone map + owner's grade), 24 static frames each so the
 * temporal stages converge as they would after a second of play. Contact sheet per preset, a side-by-side "flat raster
 * lighting vs RT" sheet for day and night, an elemental-grade strip; build/rt/sheets and ~/Pictures/iroh-rt-preview.
 * Measures per-pass rays per render pixel and lavapipe wall time per tile (RELATIVE). Never a GPU.
 */
public final class RtLook {
    static final int DW = 640, DH = 360, FRAMES = 24;
    static final Path SHEETS = Path.of(System.getProperty("rt.out", "build/rt"), "sheets");
    static final Path PICTURES = Path.of(System.getProperty("user.home"), "Pictures", "iroh-rt-preview");

    record Scene(String name, Vector3f pos, Vector3f target, RtLookModel.Inputs in) {}

    record Preset(String name, float scale, int candidates, boolean halfGi, boolean halfRefl, float roughMax, String note) {}

    static RtLookModel.Inputs inputs(float elev, float az, float rain, boolean under, float cloud) {
        RtLookModel.Inputs in = new RtLookModel.Inputs();
        in.sunElevationDeg = elev;
        in.sunAzimuthDeg = az;
        in.rain = rain;
        in.underwater = under;
        in.timeSeconds = 12.5f;
        in.fogBase = 6.5f;
        in.fogHeight = 5;
        in.worldScale = 0.6f;
        in.cloudCover = cloud;
        return in;
    }

    static List<Scene> scenes() {
        List<Scene> s = new ArrayList<>();
        s.add(new Scene("day", new Vector3f(1f, 15f, 42f), new Vector3f(22f, 8.5f, 16f), inputs(48, 215, 0, false, 0.35f)));
        s.add(new Scene("sunset", new Vector3f(42f, 15.5f, 1.5f), new Vector3f(15f, 9f, 26f), inputs(4, 262, 0, false, 0)));
        s.add(new Scene("night, torches", new Vector3f(12.5f, 12.8f, 27.5f), new Vector3f(10.6f, 9.6f, 12f), inputs(-35, 215, 0, false, 0)));
        s.add(new Scene("cave", new Vector3f(30.6f, 10.2f, 35.1f), new Vector3f(46f, 9.0f, 35.4f), inputs(40, 140, 0, false, 0)));
        s.add(new Scene("underwater", new Vector3f(13f, 5.4f, 34.6f), new Vector3f(13.3f, 6.2f, 26f), inputs(55, 200, 0, true, 0)));
        s.add(new Scene("rain", new Vector3f(1f, 15f, 42f), new Vector3f(22f, 8.5f, 16f), inputs(40, 215, 1, false, 0.8f)));
        return s;
    }

    static List<Preset> presets() {
        return List.of(
                new Preset("LOW", 2.0f, 4, true, true, 0.15f, "FSR Performance 2.0x, RIS M=4, GI + reflections checkerboard"),
                new Preset("MEDIUM", 1.7f, 8, true, true, 0.30f, "FSR Balanced 1.7x, RIS M=8, GI + reflections checkerboard"),
                new Preset("HIGH", 1.5f, 8, false, false, 0.40f, "FSR Quality 1.5x, RIS M=8, full-rate GI + reflections"),
                new Preset("ULTRA", 1.0f, 16, false, false, 0.50f, "FSR native AA 1.0x, RIS M=16, full rate"));
    }

    record Tile(BufferedImage img, double ms, double[] rays, int rw, int rh) {}

    /** One scene through the chain: static camera, FRAMES jittered frames; returns the display tile. */
    static Tile render(RtChain ch, RtReplay.Light light, Scene sc, Preset p, float[] postOut) {
        RtCamera cam = new RtCamera(sc.pos(), sc.target(), 70, ch.RW, ch.RH);
        light.look = fd -> {
            float[] post = RtLookModel.apply(fd, sc.in());
            System.arraycopy(post, 0, postOut, 0, 12);
            fd.lightCandidates = p.candidates();
            fd.fog = false;
        };
        int flags = F_ALPHA_TEST | F_SOFT_SUN | F_CACHE | F_REJECT | (p.halfGi() ? F_HALF_GI : 0) | (p.halfRefl() ? F_HALF_REFL : 0);
        ch.gpu.clearCache();
        ch.gpu.resetCounters();
        long t0 = System.nanoTime();
        float[] out = null;
        try (RtChain.Den den = ch.new Den(); RtChain.Fsr fd = ch.new Fsr()) {
            for (int k = 0; k < FRAMES; k++) {
                RtChain.Rt r = ch.trace(cam, cam, 90_000 + k, k, flags);
                RtChain.In in = ch.pack(r);
                float[][] o = den.run(in, k, k == 0, cam, cam, r.jitter, ch.js.offset(Math.max(0, k - 1)));
                float[] hdr = ch.compose(o[0], o[2], 4, o[1]);
                out = fd.run(hdr, in, r.jitter, k == 0, o[0]);
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6 / FRAMES;
        int[] c = ch.gpu.readCounters();
        double n = (double) FRAMES * ch.N;
        double[] rays = {c[1] / n, c[2] / n, c[3] / n, c[4] / n};
        // auto exposure (log-average luminance, key after Krawczyk et al. 2005), clamped so nights stay dark (owner rule)
        double sl = 0;
        for (int i = 0; i < DW * DH; i++) sl += Math.log(1e-3 + Math.max(0, RtImg.lum(out, i)));
        double avg = Math.exp(sl / (DW * DH)), key = 1.03 - 2 / (2 + Math.log10(avg + 1));
        postOut[4] = (float) Math.max(0.35, Math.min(1.6, key / avg)) * sc.in().exposure;
        float[] ldr = ch.gpu.post(out, DW * DH, postOut);
        return new Tile(RtImg.ldr(ldr, DW, DH), ms, rays, ch.RW, ch.RH);
    }

    // ------------------------------------------------------------------------------------------------ flat raster stand-in

    /** Vanilla-style lighting of the same G-buffer: albedo x face shade x lightmap(sky, block); no RT, LDR, no tone map. */
    static BufferedImage flat(RtScene scene, Scene sc, boolean night) {
        try (RtGpu g = new RtGpu(scene, DW, DH)) {
            RtCamera cam = new RtCamera(sc.pos(), sc.target(), 70, DW, DH);
            RtReplay.Light l = new RtReplay.Light(new RtFrameData().waterExtinction);
            g.setFrame(cam, cam, 0, F_ALPHA_TEST, l);
            g.gbuffer();
            int[] gb = g.readUvec4(g.gbufCur);
            BufferedImage img = new BufferedImage(DW, DH, BufferedImage.TYPE_INT_RGB);
            float dayF = night ? 0.18f : 1f;
            for (int i = 0; i < DW * DH; i++) {
                float vz = Float.intBitsToFloat(gb[i * 4 + 2]);
                double[] dir = cam.rayDir(i % DW, i / DW);
                double r, gg, b;
                if (vz <= 0) {
                    double t = Math.max(0, dir[1]);
                    r = night ? 0.02 : 0.62 - 0.25 * t; gg = night ? 0.03 : 0.75 - 0.2 * t; b = night ? 0.07 : 1.0;
                } else {
                    int nn = gb[i * 4];
                    double ex = (nn & 0xFFFF) / 65535.0 * 2 - 1, ey = (nn >>> 16) / 65535.0 * 2 - 1, ez = 1 - Math.abs(ex) - Math.abs(ey);
                    if (ez < 0) { double ox = ex; ex = (1 - Math.abs(ey)) * (ox >= 0 ? 1 : -1); ey = (1 - Math.abs(ox)) * (ey >= 0 ? 1 : -1); }
                    double len = Math.sqrt(ex * ex + ey * ey + ez * ez);
                    double nx = ex / len, ny = ey / len, nz = ez / len;
                    org.joml.Vector3f fw = cam.forward();
                    double t = vz / (dir[0] * fw.x + dir[1] * fw.y + dir[2] * fw.z);
                    double px = cam.pos.x + dir[0] * t + nx * 0.5, py = cam.pos.y + dir[1] * t + ny * 0.5, pz = cam.pos.z + dir[2] * t + nz * 0.5;
                    int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
                    int sky = 15, blk = 0;
                    if (cx >= 0 && cy >= 0 && cz >= 0 && cx < scene.W && cy < scene.H && cz < scene.D) {
                        int ci = (cy * scene.D + cz) * scene.W + cx;
                        sky = scene.skyLight[ci];
                        blk = scene.blockLight[ci];
                    }
                    double shade = ny > 0.5 ? 1.0 : ny < -0.5 ? 0.5 : Math.abs(nz) > 0.5 ? 0.8 : 0.6;
                    double sb = curve(sky) * dayF, bb = curve(blk);
                    int al = gb[i * 4 + 1];
                    double ar = (al & 0xFF) / 255.0, ag = ((al >>> 8) & 0xFF) / 255.0, ab = ((al >>> 16) & 0xFF) / 255.0;
                    double lr = Math.min(1, sb * 0.95 + bb * 1.0 + 0.04), lg = Math.min(1, sb * 0.95 + bb * 0.86 + 0.04), lb = Math.min(1, sb + bb * 0.66 + 0.05);
                    r = ar * shade * lr; gg = ag * shade * lg; b = ab * shade * lb;
                }
                img.setRGB(i % DW, i / DW, (c8(r) << 16) | (c8(gg) << 8) | c8(b));
            }
            return img;
        }
    }

    /** Vanilla's light-level brightness curve (no gamma boost): f / (4 - 3f), f = level / 15. */
    static double curve(int level) {
        double f = level / 15.0;
        return f / (4 - 3 * f);
    }

    static int c8(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    // ------------------------------------------------------------------------------------------------ sheets

    static BufferedImage sheet(String title, List<String> labels, List<BufferedImage> tiles, int cols) {
        int rows = (tiles.size() + cols - 1) / cols, head = 34, lab = 26;
        BufferedImage s = new BufferedImage(cols * DW, head + rows * (DH + lab), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = s.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(18, 20, 24));
        g.fillRect(0, 0, s.getWidth(), s.getHeight());
        g.setColor(new Color(235, 235, 235));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString(title, 10, 23);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        for (int t = 0; t < tiles.size(); t++) {
            int x = (t % cols) * DW, y = head + (t / cols) * (DH + lab);
            g.drawImage(tiles.get(t), x, y + lab, null);
            g.setColor(new Color(220, 220, 220));
            g.drawString(labels.get(t), x + 8, y + 18);
        }
        g.dispose();
        return s;
    }

    static void write(BufferedImage img, String name) throws Exception {
        Files.createDirectories(SHEETS);
        File f = SHEETS.resolve(name).toFile();
        ImageIO.write(img, "png", f);
        Files.createDirectories(PICTURES);
        Files.copy(f.toPath(), PICTURES.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("  sheet " + f.getAbsolutePath() + "  (copy: " + PICTURES.resolve(name) + ")");
    }

    public static void main(String[] args) throws Exception {
        if (!NativeLoader.load()) { System.out.println("# rtlook FAIL: FSR library unavailable: " + NativeLoader.unavailableReason()); System.exit(1); }
        String only = System.getProperty("rt.preset", "all");
        Files.createDirectories(RtReplay.OUT);
        RtReplay.metrics = new java.io.PrintStream(Files.newOutputStream(RtReplay.OUT.resolve("look_metrics.txt")), true);
        RtScene scene = new RtScene(true);
        int quads = 0;
        for (RtScene.SectionMesh m : scene.sections) quads += m.totalQuads();
        System.out.println("village: " + scene.sections.size() + " sections, " + quads + " quads, " + scene.lights.size() + " emissive quads; display " + DW + "x" + DH + ", " + FRAMES + " frames per tile");
        List<Scene> scenes = scenes();
        RtReplay.Light light = new RtReplay.Light(new RtFrameData().waterExtinction);
        Map<String, BufferedImage> highTiles = new HashMap<>();
        StringBuilder table = new StringBuilder("preset  render     scene              rays/px: sun   light  GI     refl   total  | ms/frame (lavapipe, relative)\n");
        long t0 = System.nanoTime();
        for (Preset p : presets()) {
            if (!only.equals("all") && !only.equalsIgnoreCase(p.name())) continue;
            int rw = Math.round(DW / p.scale()), rh = Math.round(DH / p.scale());
            RtScene.Material[] base = scene.materials.clone();
            List<BufferedImage> tiles = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            double sumMs = 0;
            try (RtChain ch = new RtChain(scene, light, rw, rh, DW, DH, 70)) {
                ch.srgbAlbedo = true;
                RtScene.Material[] gated = base.clone();
                for (int t = 0; t < gated.length; t++) {
                    RtScene.Material m = gated[t];
                    if (m != null && !m.water()) gated[t] = new RtScene.Material(m.roughness(), m.alphaMode(), m.water(), m.roughness() <= p.roughMax(), m.er(), m.eg(), m.eb(), m.f0());
                }
                ch.gpu.writeMaterials(gated);
                for (Scene sc : scenes) {
                    float[] post = new float[12];
                    Tile t = render(ch, light, sc, p, post);
                    tiles.add(t.img());
                    sumMs += t.ms();
                    double tot = t.rays()[0] + t.rays()[1] + t.rays()[2] + t.rays()[3];
                    labels.add(String.format(Locale.ROOT, "%s | %s %dx%d -> %dx%d | %.2f rays/px | %.0f ms/frame lavapipe", sc.name(), p.name(), rw, rh, DW, DH, tot, t.ms()));
                    table.append(String.format(Locale.ROOT, "%-7s %4dx%-4d  %-18s %.3f  %.3f  %.3f  %.3f  %.3f  | %.0f%n", p.name(), rw, rh, sc.name(), t.rays()[0], t.rays()[1], t.rays()[2], t.rays()[3], tot, t.ms()));
                    if (p.name().equals("HIGH")) highTiles.put(sc.name(), t.img());
                    if (p.name().equals("MEDIUM") && (sc.name().equals("day") || sc.name().startsWith("night")))
                        RtImg.save("look_medium_" + sc.name().replaceAll("[^a-z]+", "_"), t.img());
                }
            }
            write(sheet(String.format(Locale.ROOT, "Iroh RT look, preset %s: %s (render %dx%d -> 640x360). Offline on lavapipe (CPU Vulkan); mean %.0f ms/frame, RELATIVE only",
                    p.name(), p.note(), rw, rh, sumMs / scenes.size()), labels, tiles, 3), "contact_" + p.name().toLowerCase(Locale.ROOT) + ".png");
        }
        if (highTiles.containsKey("day")) {
            List<BufferedImage> sb = new ArrayList<>();
            List<String> lb = new ArrayList<>();
            for (Scene sc : scenes)
                if (sc.name().equals("day") || sc.name().startsWith("night")) {
                    sb.add(flat(scene, sc, sc.name().startsWith("night")));
                    lb.add(sc.name() + " | flat raster lighting (vanilla-style: face shade x light levels)");
                    sb.add(highTiles.get(sc.name()));
                    lb.add(sc.name() + " | Iroh RT, HIGH preset (FSR Quality)");
                }
            write(sheet("Raster-style flat lighting (left) vs Iroh RT (right), same world and camera", lb, sb, 2), "side_by_side_flat_vs_rt.png");
            // elemental grade strip: the day scene in the four owner regions (camera weights 1 for one region)
            if (only.equals("all") || only.equalsIgnoreCase("HIGH")) {
                Preset p = presets().get(2);
                int rw = Math.round(DW / p.scale()), rh = Math.round(DH / p.scale());
                List<BufferedImage> gt = new ArrayList<>();
                List<String> gl = new ArrayList<>();
                String[] reg = {"Air (north)", "Water (east)", "Fire (south)", "Earth (west)"};
                try (RtChain ch = new RtChain(scene, light, rw, rh, DW, DH, 70)) {
                    ch.srgbAlbedo = true;
                    for (int e = 0; e < 4; e++) {
                        Scene d = scenes.get(0);
                        RtLookModel.Inputs in = inputs(48, 215, 0, false, 0.35f);
                        in.elementWeights[e] = 1f;
                        Tile t = render(ch, light, new Scene("day " + reg[e], d.pos(), d.target(), in), p, new float[12]);
                        gt.add(t.img());
                        gl.add("day | owner's elemental grade: " + reg[e] + " (camera weight 1)");
                    }
                }
                write(sheet("Navidson elemental grade on the RT image (owner's region tints, light tint 0.6, sky tint 0.5), HIGH preset", gl, gt, 2), "grade_regions.png");
            }
        }
        System.out.println(table);
        Files.writeString(SHEETS.resolve("look_rays.txt"), table.toString());
        System.out.printf(Locale.ROOT, "wall time %.1f s%n", (System.nanoTime() - t0) / 1e9);
        System.out.println("# rtlook " + (RtReplay.fail == 0 ? "PASS" : "FAIL") + " (goldens " + RtReplay.pass + "/" + (RtReplay.pass + RtReplay.fail) + "; the look itself is the owner's call)");
        System.exit(RtReplay.fail == 0 ? 0 : 1);
    }
}
