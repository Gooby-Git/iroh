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
public final class RtLookSheets {
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
        s.add(new Scene("day", RtScene.v(1f, 15f, 42f), RtScene.v(22f, 8.5f, 16f), inputs(48, 215, 0, false, 0.35f)));
        s.add(new Scene("sunset", RtScene.v(42f, 15.5f, 1.5f), RtScene.v(15f, 9f, 26f), inputs(4, 262, 0, false, 0)));
        s.add(new Scene("night, torches", RtScene.v(12.5f, 12.8f, 27.5f), RtScene.v(10.6f, 9.6f, 12f), inputs(-35, 215, 0, false, 0)));
        s.add(new Scene("cave", RtScene.v(30.6f, 10.2f, 35.1f), RtScene.v(46f, 9.0f, 35.4f), inputs(40, 140, 0, false, 0)));
        s.add(new Scene("underwater", RtScene.v(13f, 5.4f, 34.6f), RtScene.v(13.3f, 6.2f, 26f), inputs(55, 200, 0, true, 0)));
        s.add(new Scene("rain", RtScene.v(1f, 15f, 42f), RtScene.v(22f, 8.5f, 16f), inputs(40, 215, 1, false, 0.8f)));
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

    static double lastLogAvg;

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
        // P5-2: exposure through RtLookModel (calibrated against flat vanilla-style lighting: see calibrate()); P5-1 used a
        // Krawczyk key meant for absolute cd/m^2 units, which put the day scene at ~0.5x and made it dark and grey
        double sl = 0;
        for (int i = 0; i < DW * DH; i++) sl += Math.log(1e-4 + Math.max(0, RtImg.lum(out, i)));
        double avg = Math.exp(sl / (DW * DH));
        lastLogAvg = avg;
        String fx = System.getProperty("rt.exposure");
        postOut[4] = fx != null ? Float.parseFloat(fx) : RtLookModel.autoExposure(avg, sc.in());
        float[] ldr = ch.gpu.post(out, DW * DH, postOut);
        return new Tile(RtImg.ldr(ldr, DW, DH), ms, rays, ch.RW, ch.RH);
    }

    // ------------------------------------------------------------------------------------------------ flat raster stand-in

    /** Vanilla-style lighting of the same G-buffer: albedo x face shade x lightmap(sky, block); no RT, LDR, no tone map. */
    /** The display-resolution G-buffer of a scene camera (unjittered). */
    static int[] gbuffer(RtScene scene, Scene sc) {
        try (RtGpu g = new RtGpu(scene, DW, DH)) {
            RtCamera cam = new RtCamera(sc.pos(), sc.target(), 70, DW, DH);
            RtReplay.Light l = new RtReplay.Light(new RtFrameData().waterExtinction);
            g.setFrame(cam, cam, 0, F_ALPHA_TEST, l);
            g.gbuffer();
            return g.readUvec4(g.gbufCur);
        }
    }

    static double[] octDecode(int nn) {
        double ex = (nn & 0xFFFF) / 65535.0 * 2 - 1, ey = (nn >>> 16) / 65535.0 * 2 - 1, ez = 1 - Math.abs(ex) - Math.abs(ey);
        if (ez < 0) { double ox = ex; ex = (1 - Math.abs(ey)) * (ox >= 0 ? 1 : -1); ey = (1 - Math.abs(ox)) * (ey >= 0 ? 1 : -1); }
        double len = Math.sqrt(ex * ex + ey * ey + ez * ez);
        return new double[]{ex / len, ey / len, ez / len};
    }

    static BufferedImage flat(RtScene scene, Scene sc, boolean night) {
        {
            int[] gb = gbuffer(scene, sc);
            RtCamera cam = new RtCamera(sc.pos(), sc.target(), 70, DW, DH);
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
                    double[] nv = octDecode(gb[i * 4]);
                    double nx = nv[0], ny = nv[1], nz = nv[2];
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

    // ------------------------------------------------------------------------------------------------ P5-2 calibration

    /** Calibration numbers of one day image against the flat (vanilla-style) image of the same camera. */
    record Calib(double rtSunLum, double flatSunLum, double rtSat, double flatSat, double rtShadowLum, int nSun, int nShadow,
                 double rtSkySat, double flatSkySat, double rtSkyLum, double flatSkyLum) {
        double lumRatio() { return rtSunLum / flatSunLum; }
        double satRatio() { return rtSat / flatSat; }
        double shadowDepth() { return rtShadowLum / rtSunLum; }
    }

    static double lin(int c8) {
        double c = c8 / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static boolean sunBlocked(RtScene scene, double px, double py, double pz, double[] sd) {
        for (double t = 0.02; t < 70; t += 0.05) {
            int x = (int) Math.floor(px + sd[0] * t), y = (int) Math.floor(py + sd[1] * t), z = (int) Math.floor(pz + sd[2] * t);
            if (y >= scene.H) return false;
            int b = scene.get(x, y, z);
            if (b != RtScene.AIR && b != RtScene.WATER) return true;
        }
        return false;
    }

    /**
     * Sunlit grass-top patch (top faces of grass with sky light 15 and an unblocked voxel ray to the sun, eroded by 2 px so
     * FSR/jitter edges and shadow borders never count) and shadowed grass-top patch (same, sun blocked), on the display
     * G-buffer of the scene camera; sky = sky pixels in the upper third. Means are taken in linear light of the DISPLAYED
     * sRGB images; saturation = (max - min) / max of the mean displayed colour.
     */
    static Calib calibrate(RtScene scene, Scene sc, BufferedImage rt, BufferedImage flat) {
        int[] gb = gbuffer(scene, sc);
        RtCamera cam = new RtCamera(sc.pos(), sc.target(), 70, DW, DH);
        org.joml.Vector3f fw = cam.forward();
        double el = Math.toRadians(sc.in().sunElevationDeg), az = Math.toRadians(sc.in().sunAzimuthDeg);
        double[] sd = {Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)};
        int[] cls = new int[DW * DH];   // 0 other, 1 sunlit grass, 2 shadowed grass, 3 sky (upper third)
        for (int i = 0; i < DW * DH; i++) {
            float vz = Float.intBitsToFloat(gb[i * 4 + 2]);
            if (vz <= 0) { cls[i] = i / DW < DH / 3 ? 3 : 0; continue; }
            int tile = (gb[i * 4 + 1] >>> 24) & 0x3F;
            double[] n = octDecode(gb[i * 4]);
            if (tile != RtScene.T_GRASS_TOP || n[1] < 0.9) continue;
            double[] dir = cam.rayDir(i % DW, i / DW);
            double t = vz / (dir[0] * fw.x + dir[1] * fw.y + dir[2] * fw.z);
            double px = cam.pos.x + dir[0] * t, py = cam.pos.y + dir[1] * t + 1e-3, pz = cam.pos.z + dir[2] * t;
            int cx = (int) Math.floor(px), cy = (int) Math.floor(py + 0.5), cz = (int) Math.floor(pz);
            if (cx < 0 || cz < 0 || cx >= scene.W || cz >= scene.D || cy >= scene.H) continue;
            if (scene.skyLight[(cy * scene.D + cz) * scene.W + cx] < 15) continue;
            cls[i] = sunBlocked(scene, px, py, pz, sd) ? 2 : 1;
        }
        double[][] acc = new double[8][3];   // [class*2 + (0 rt, 1 flat)]
        int[] cnt = new int[4];
        for (int y = 2; y < DH - 2; y++)
            for (int x = 2; x < DW - 2; x++) {
                int c = cls[y * DW + x];
                if (c == 0) continue;
                boolean core = true;
                for (int dy = -2; dy <= 2 && core; dy++) for (int dx = -2; dx <= 2; dx++) if (cls[(y + dy) * DW + x + dx] != c) { core = false; break; }
                if (!core) continue;
                cnt[c]++;
                int a = rt.getRGB(x, y), b = flat.getRGB(x, y);
                for (int k = 0; k < 3; k++) {
                    acc[c * 2][k] += lin((a >> (16 - 8 * k)) & 0xFF);
                    acc[c * 2 + 1][k] += lin((b >> (16 - 8 * k)) & 0xFF);
                }
            }
        for (int c = 1; c < 4; c++) for (int j = 0; j < 2; j++) for (int k = 0; k < 3; k++) acc[c * 2 + j][k] /= Math.max(1, cnt[c]);
        return new Calib(lumOf(acc[2]), lumOf(acc[3]), satOf(acc[2]), satOf(acc[3]), lumOf(acc[4]), cnt[1], cnt[2],
                satOf(acc[6]), satOf(acc[7]), lumOf(acc[6]), lumOf(acc[7]));
    }

    static double lumOf(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** Saturation of a linear mean colour as DISPLAYED: (max - min) / max of its sRGB encoding. */
    static double satOf(double[] c) {
        double[] e = new double[3];
        for (int k = 0; k < 3; k++) e[k] = c[k] <= 0.0031308 ? c[k] * 12.92 : 1.055 * Math.pow(c[k], 1 / 2.4) - 0.055;
        double mx = Math.max(e[0], Math.max(e[1], e[2])), mn = Math.min(e[0], Math.min(e[1], e[2]));
        return mx <= 0 ? 0 : (mx - mn) / mx;
    }

    /** The calibration bands (P5-2): see knowledge/rt-iroh.md "P5-2 calibration". */
    static final double LUM_LO = 0.90, LUM_HI = 1.10, SAT_LO = 0.90, SAT_HI = 1.25, SHADOW_LO = 0.20, SHADOW_HI = 0.60, SKY_SAT_MIN = 0.35;

    static void report(Calib c) {
        RtReplay.metric(String.format(Locale.ROOT, "calibration day: sunlit grass n=%d RT lum %.4f flat %.4f ratio %.3f | saturation RT %.3f flat %.3f ratio %.3f | shadowed grass n=%d lum %.4f depth %.3f | sky lum RT %.3f flat %.3f sat RT %.3f flat %.3f",
                c.nSun(), c.rtSunLum(), c.flatSunLum(), c.lumRatio(), c.rtSat(), c.flatSat(), c.satRatio(), c.nShadow(), c.rtShadowLum(), c.shadowDepth(), c.rtSkyLum(), c.flatSkyLum(), c.rtSkySat(), c.flatSkySat()));
        RtReplay.check(String.format(Locale.ROOT, "calibration: sunlit grass luminance %.3f of flat in [%.2f, %.2f] (n=%d)", c.lumRatio(), LUM_LO, LUM_HI, c.nSun()),
                c.nSun() > 500 && c.lumRatio() >= LUM_LO && c.lumRatio() <= LUM_HI);
        RtReplay.check(String.format(Locale.ROOT, "calibration: sunlit grass saturation %.3f of flat in [%.2f, %.2f]", c.satRatio(), SAT_LO, SAT_HI),
                c.satRatio() >= SAT_LO && c.satRatio() <= SAT_HI);
        RtReplay.check(String.format(Locale.ROOT, "calibration: shadowed/sunlit grass %.3f in [%.2f, %.2f] (n=%d; shadows keep depth)", c.shadowDepth(), SHADOW_LO, SHADOW_HI, c.nShadow()),
                c.nShadow() > 200 && c.shadowDepth() >= SHADOW_LO && c.shadowDepth() <= SHADOW_HI);
        RtReplay.check(String.format(Locale.ROOT, "calibration: sky saturation %.3f >= %.2f (not hazy grey)", c.rtSkySat(), SKY_SAT_MIN), c.rtSkySat() >= SKY_SAT_MIN);
    }

    /** -Prt.calib: the day scene only (HIGH), calibration numbers, ~15 s. */
    static void calibOnly(RtScene scene, RtReplay.Light light, Scene day) throws Exception {
        Preset p = presets().get(2);
        int rw = Math.round(DW / p.scale()), rh = Math.round(DH / p.scale());
        try (RtChain ch = new RtChain(scene, light, rw, rh, DW, DH, 70)) {
            ch.srgbAlbedo = true;
            Tile t = render(ch, light, day, p, new float[12]);
            BufferedImage fl = flat(scene, day, false);
            ImageIO.write(t.img(), "png", RtReplay.OUT.resolve("calib_day_rt.png").toFile());
            ImageIO.write(fl, "png", RtReplay.OUT.resolve("calib_day_flat.png").toFile());
            System.out.printf(Locale.ROOT, "log-average scene luminance (pre-exposure) %.5f%n", lastLogAvg);
            report(calibrate(scene, day, t.img(), fl));
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
        if (Boolean.getBoolean("rt.calib")) { calibOnly(scene, light, scenes.get(0)); System.exit(0); }
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
            // P5-2 calibration (step 1a): sunlit grass at noon-ish lands near the flat image's brightness and saturation,
            // shadows keep their depth, the sky stays blue
            Calib c = calibrate(scene, scenes.get(0), highTiles.get("day"), sb.get(0));
            report(c);
            BufferedImage before = ImageIO.read(RtLookSheets.class.getResourceAsStream("/rtlook/day_before_p52.png"));
            write(sheet("Day scene, HIGH preset: before P5-2 (left: P5-1 exposure, world edge at the left) vs after (right: calibrated exposure, padded world)",
                    List.of("before (P5-1)", String.format(Locale.ROOT, "after (P5-2): sunlit grass %.0f%% of flat brightness, saturation %.0f%%, shadow depth %.2f",
                            100 * c.lumRatio(), 100 * c.satRatio(), c.shadowDepth())), List.of(before, highTiles.get("day")), 2), "before_after_day.png");
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
