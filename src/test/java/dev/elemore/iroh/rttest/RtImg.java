package dev.elemore.iroh.rttest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** PNG output, tone mapping, golden compare and error metrics for rtReplay. */
final class RtImg {
    private RtImg() {}

    static double lum(float[] v, int i) {
        return 0.2126 * v[i * 4] + 0.7152 * v[i * 4 + 1] + 0.0722 * v[i * 4 + 2];
    }

    /** Filmic curve (Narkowicz' public ACES fit) then sRGB. */
    static int tm(double x) {
        x = Math.max(0, x);
        double y = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
        y = Math.max(0, Math.min(1, y));
        double s = y <= 0.0031308 ? 12.92 * y : 1.055 * Math.pow(y, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    static BufferedImage hdr(float[] v, int w, int h, double exposure) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++)
            img.setRGB(i % w, i / w, (tm(v[i * 4] * exposure) << 16) | (tm(v[i * 4 + 1] * exposure) << 8) | tm(v[i * 4 + 2] * exposure));
        return img;
    }

    /** Display-referred values in [0,1] (already tone mapped and sRGB-encoded by post.comp), RGBA. */
    static BufferedImage ldr(float[] v, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++) {
            int r = (int) Math.round(Math.max(0, Math.min(1, v[i * 4])) * 255), g = (int) Math.round(Math.max(0, Math.min(1, v[i * 4 + 1])) * 255),
                    b = (int) Math.round(Math.max(0, Math.min(1, v[i * 4 + 2])) * 255);
            img.setRGB(i % w, i / w, (r << 16) | (g << 8) | b);
        }
        return img;
    }

    /** Linear display of a signal: value * scale, clamped, sRGB-encoded. */
    static BufferedImage linear(float[] v, int w, int h, double scale) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++) {
            int[] c = new int[3];
            for (int a = 0; a < 3; a++) {
                double y = Math.max(0, Math.min(1, v[i * 4 + a] * scale));
                c[a] = (int) Math.round((y <= 0.0031308 ? 12.92 * y : 1.055 * Math.pow(y, 1 / 2.4) - 0.055) * 255);
            }
            img.setRGB(i % w, i / w, (c[0] << 16) | (c[1] << 8) | c[2]);
        }
        return img;
    }

    static BufferedImage ids(int[] id, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++) {
            int x = id[i];
            int c = x < 0 ? 0 : (x * 0x9E3779B1) >>> 8 | 0x404040;
            img.setRGB(i % w, i / w, c & 0xFFFFFF);
        }
        return img;
    }

    /** |a-b| heat map (luminance), scaled so {@code full} maps to white. */
    static BufferedImage diff(float[] a, float[] b, int w, int h, double full) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++) {
            double d = Math.min(1, Math.abs(lum(a, i) - lum(b, i)) / full);
            int r = (int) (255 * Math.min(1, d * 2)), g = (int) (255 * Math.max(0, d * 2 - 1));
            img.setRGB(i % w, i / w, (r << 16) | (g << 8) | g);
        }
        return img;
    }

    /** Writes build/rt/name.png and compares with the golden (mean |diff| over 8-bit RGB). Missing golden: created. */
    static void save(String name, BufferedImage img) throws IOException {
        Path out = RtReplay.OUT.resolve(name + ".png");
        ImageIO.write(img, "png", out.toFile());
        if (System.getProperty("rt.quick") != null || System.getProperty("rt.frames") != null || System.getProperty("rt.res") != null)
            return; // goldens are for the default run (160x90, 256 frames, full CPU sample counts)
        Path g = RtReplay.GOLD.resolve(name + ".png");
        boolean update = "update".equals(System.getProperty("rt.golden"));
        if (!Files.exists(g) || update) {
            Files.createDirectories(RtReplay.GOLD);
            Files.copy(out, g, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            RtReplay.metric("golden " + (update ? "updated" : "created") + ": " + g);
            return;
        }
        BufferedImage gi = ImageIO.read(g.toFile());
        if (gi.getWidth() != img.getWidth() || gi.getHeight() != img.getHeight()) {
            RtReplay.check("golden " + name + " size " + gi.getWidth() + "x" + gi.getHeight() + " vs " + img.getWidth() + "x" + img.getHeight(), false);
            return;
        }
        double sum = 0;
        int max = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y), q = gi.getRGB(x, y);
                for (int s = 0; s < 24; s += 8) {
                    int d = Math.abs(((p >> s) & 0xFF) - ((q >> s) & 0xFF));
                    sum += d;
                    max = Math.max(max, d);
                }
            }
        double mean = sum / (img.getWidth() * img.getHeight() * 3.0);
        RtReplay.check(String.format(Locale.ROOT, "golden %s: mean |diff| %.3f (max %d) <= 1.0", name, mean, max), mean <= 1.0);
    }

    /** {relative MAE, relative bias, RMSE} of luminance over the mask, relative to the reference's mean luminance. */
    static double[] err(float[] g, float[] r, boolean[] mask) {
        double sa = 0, sb = 0, sr = 0, sq = 0;
        int n = 0;
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i]) continue;
            double a = lum(g, i), b = lum(r, i);
            sa += Math.abs(a - b);
            sb += a - b;
            sr += b;
            sq += (a - b) * (a - b);
            n++;
        }
        if (n == 0 || sr <= 0) return new double[]{0, 0, 0, n};
        return new double[]{sa / sr, sb / sr, Math.sqrt(sq / n), n};
    }
}
