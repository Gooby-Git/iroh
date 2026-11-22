package dev.elemore.iroh.rt;

/**
 * P5-1: the Navidson-style look of the RT path as frame data (Minecraft-free; the game adapter fills {@link Inputs} from
 * the level's time, weather and camera, the offline scenes from fixed values). Provenance:
 * <ul>
 * <li>sky and sun colours: Iroh's OWN base colours and an own sun transmittance (air mass after Kasten and Young 1989,
 *   per-channel optical depth), multiplied by the OWNER's Navidson multipliers (NV_FANTASY_SKY 0.6, NV_FANTASY_SUNSET 0.7,
 *   NV_NIGHT_SKY 0.35 + NV_NIGHT_SKY_TINT 0.5, NV_NIGHT_LIGHT 0.25, gloom grey-out; knowledge/history/navidson-inventory
 *   section 7d; LGPL grant 2026-11-15). Complementary's base sky constants are NOT used.</li>
 * <li>elemental weights and tints: the owner's NvElementWeights / tint (section 7c), light tint 0.6, sky tint 0.5.</li>
 * <li>mountain fog parameters: the owner's defaults (height 24, patches 0.6, opacity cap 0.5, haze 0.15), with
 *   {@link Inputs#worldScale} shrinking distances for small test worlds.</li>
 * </ul>
 */
public final class RtLookModel {
    private RtLookModel() {}

    /** What the look needs per frame. */
    public static final class Inputs {
        public float sunElevationDeg = 45, sunAzimuthDeg = 215;
        /** Navidson-like slightly soft sun (provisional decision): 1.2 degrees; physical 0.27. */
        public float sunRadiusDeg = 1.2f;
        public float rain, thunder;
        public boolean underwater;
        public float timeSeconds;
        /** Owner's element weights at the camera (air, water, fire, earth), see {@link #elementWeights}. */
        public float[] elementWeights = new float[4];
        /** Mountain fog base altitude (world y) and the owner's height H = 24. */
        public float fogBase = 90, fogHeight = 24;
        /** 1 in game; < 1 shrinks fog distances for a small offline world. */
        public float worldScale = 1;
        /** Tempest cloud-shadow hook: cover 0..1 (stub until Tempest's map is bound). */
        public float cloudCover;
        /** Exposure the post stage applies (auto exposure is a later card). */
        public float exposure = 1;
    }

    // owner's elemental regions: Air (-Z), Water (+X), Fire (+Z), Earth (-X)
    static final float[][] RGB_E = {{1.08f, 0.98f, 1.10f}, {0.86f, 1.00f, 1.14f}, {1.18f, 0.96f, 0.80f}, {0.98f, 1.06f, 0.84f}};

    /** Owner's NvElementWeights (rotation 0): neutral within 1200 blocks, fading in over 1500, region blend 600. */
    public static float[] elementWeights(double x, double z) {
        double ax = Math.abs(x), az = Math.abs(z);
        double[] d = {-z - ax, x - az, z - ax, -x - az};
        double[] w = new double[4];
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            double v = Math.max(0, Math.min(1, d[i] * (0.7071 / 600.0) + 0.5));
            w[i] = v * v * (3 - 2 * v);
            sum += w[i];
        }
        double r = Math.sqrt(x * x + z * z);
        double c = Math.max(0, Math.min(1, (r - 1200) / 1500));
        c = c * c * (3 - 2 * c);
        float[] o = new float[4];
        for (int i = 0; i < 4; i++) o[i] = (float) (w[i] / Math.max(sum, 1) * c);
        return o;
    }

    /** Owner's luminance-preserving element tint at {@code amount}. */
    public static float[] tint(float[] w, float amount) {
        float[] t = {1, 1, 1};
        for (int e = 0; e < 4; e++) for (int k = 0; k < 3; k++) t[k] += w[e] * (RGB_E[e][k] - 1);
        for (int k = 0; k < 3; k++) t[k] = Math.max(t[k], 0.05f);
        float l = luma(t);
        for (int k = 0; k < 3; k++) t[k] = Math.max(1 + (t[k] / l - 1) * amount, 0);
        return t;
    }

    static float luma(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    static float[] mul(float[] a, float[] b) {
        return new float[]{a[0] * b[0], a[1] * b[1], a[2] * b[2]};
    }

    static float[] mulMix(float[] c, float[] m, float amount) {
        return new float[]{c[0] * (1 + (m[0] - 1) * amount), c[1] * (1 + (m[1] - 1) * amount), c[2] * (1 + (m[2] - 1) * amount)};
    }

    static float[] mix(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static float[] scale(float[] a, float s) {
        return new float[]{a[0] * s, a[1] * s, a[2] * s};
    }

    static float smooth(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** Owner's gloom / rain grey-out of a sky colour: mix towards luma*(0.86, 0.91, 1.0) by 0.7 g, darken by 0.5 g. */
    static float[] gloom(float[] c, float g) {
        float l = luma(c);
        return scale(mix(c, new float[]{l * 0.86f, l * 0.91f, l}, 0.7f * g), 1 - 0.5f * g);
    }

    /** Fills the look block (and the P5-0 sun/sky fields) of {@code fd}; returns the post-stage parameters (12 floats). */
    public static float[] apply(RtFrameData fd, Inputs in) {
        double el = Math.toRadians(in.sunElevationDeg), az = Math.toRadians(in.sunAzimuthDeg);
        float[] sun = {(float) (Math.cos(el) * Math.sin(az)), (float) Math.sin(el), (float) (Math.cos(el) * Math.cos(az))};
        float sinE = sun[1];
        float day = smooth(-0.10f, 0.12f, sinE);
        float sunset = smooth(0.40f, 0.02f, Math.abs(sinE)) * smooth(-0.14f, 0.0f, sinE);
        float night = 1 - smooth(-0.18f, -0.02f, sinE);
        float rain = Math.max(in.rain, 0.9f * in.thunder);
        float[] lightTint = tint(in.elementWeights, 0.6f), skyTint = tint(in.elementWeights, 0.5f);

        // ---- sun (own transmittance) or moon as the NEE light
        float hDeg = (float) Math.max(-1, in.sunElevationDeg);
        double am = 1.0 / (Math.max(sinE, 0) + 0.50572 * Math.pow(hDeg + 6.07995, -1.6364));
        float[] tau = {0.09f, 0.17f, 0.36f};
        float[] e0 = {3.5f, 3.3f, 3.1f};
        float[] sunE = new float[3];
        for (int k = 0; k < 3; k++) sunE[k] = (float) (e0[k] * Math.exp(-tau[k] * am)) * smooth(-0.03f, 0.05f, sinE);
        float[] moonE = scale(new float[]{0.20f, 0.23f, 0.33f}, 0.25f);   // owner: night light x NV_NIGHT_LIGHT 0.25
        boolean moon = sinE < 0.02f;
        float[] lightDir = moon ? new float[]{-sun[0], -sun[1], -sun[2]} : sun;
        float[] lightE = moon ? scale(moonE, smooth(0.0f, 0.15f, -sinE)) : sunE;
        lightE = mul(scale(lightE, (1 - 0.85f * rain) * (1 - 0.6f * in.thunder * night)), lightTint);   // overcast; owner storm-night darkness 0.6

        // ---- sky colours: own bases x owner multipliers
        float[] dayUp = mulMix(new float[]{0.20f, 0.42f, 0.95f}, new float[]{0.78f, 0.86f, 1.22f}, 0.6f);
        float[] dayMid = mulMix(new float[]{0.80f, 0.95f, 1.15f}, new float[]{0.90f, 1.07f, 1.10f}, 0.6f);
        float[] dayDown = mulMix(new float[]{0.30f, 0.29f, 0.27f}, new float[]{0.95f, 1.05f, 1.06f}, 0.6f);
        float[] setUp = mulMix(new float[]{0.22f, 0.24f, 0.45f}, new float[]{1.20f, 0.82f, 1.45f}, 0.7f);
        float[] setMid = mulMix(new float[]{0.95f, 0.58f, 0.40f}, new float[]{1.12f, 0.90f, 1.08f}, 0.7f);
        float[] setBand = mulMix(new float[]{1.40f, 0.55f, 0.22f}, new float[]{1.08f, 0.86f, 1.25f}, 0.7f * (1 - rain));
        float[] nBase = {0.012f, 0.018f, 0.040f};
        float nl = luma(nBase);
        float[] nightUp = mix(scale(nBase, 0.35f * 2.2f), new float[]{nl * 0.62f, nl * 0.70f, nl * 1.65f}, 0.5f * (1 - rain));
        float[] nightMid = scale(new float[]{(float) Math.sqrt(nightUp[0]), (float) Math.sqrt(nightUp[1]), (float) Math.sqrt(nightUp[2])}, 0.65f * (1 + (0.35f - 1) * 0.5f) * 0.25f);
        float[] up = mix(nightUp, mix(dayUp, setUp, sunset), day);
        float[] mid = mix(nightMid, mix(dayMid, setMid, sunset), day);
        float[] down = mix(scale(nightMid, 0.4f), dayDown, day);
        up = mul(gloom(up, rain), skyTint);
        mid = mul(gloom(mid, rain), skyTint);
        down = gloom(down, rain);
        setBand = mul(setBand, skyTint);

        // ---- write
        System.arraycopy(lightDir, 0, fd.sunDir, 0, 3);
        System.arraycopy(lightE, 0, fd.sunE, 0, 3);
        fd.sunAngularRadius = (float) Math.toRadians(moon ? 0.9f : in.sunRadiusDeg);
        System.arraycopy(up, 0, fd.skyZenith, 0, 3);
        System.arraycopy(mid, 0, fd.skyHorizon, 0, 3);
        set(fd.skyUp, up, sunset * day);
        set(fd.skyMid, mid, night);
        set(fd.skySunset, setBand, rain);
        set(fd.skyDown, down, in.timeSeconds);
        float s = in.worldScale;
        float[] fogCol = mix(mid, mix(mid, setBand, 0.35f), sunset);
        fogCol = scale(fogCol, 0.85f);
        set(fd.fogColor, fogCol, 0.5f * day * (1 - rain));
        // owner's NvMountainFog defaults: H 24, patches 0.6, opacity 0.5, density 0.025 * (1 + rain) per (ATM_FOG_DISTANCE 100 / 100)
        fd.mfog0[0] = in.fogBase;
        fd.mfog0[1] = in.fogHeight;
        fd.mfog0[2] = 0.025f * (1 + rain) / s;
        fd.mfog0[3] = 0.5f;
        fd.mfog1[0] = 0.6f;
        fd.mfog1[1] = 0.15f + 0.25f * rain;
        fd.mfog1[2] = 100f * s;
        fd.mfog1[3] = 48f * s;
        fd.water2[1] = in.timeSeconds;
        float skyLum = luma(up) * 0.6f + luma(mid) * 0.4f;
        set(fd.waterColor, scale(new float[]{0.10f, 0.42f, 0.55f}, skyLum * 0.12f + luma(lightE) * 0.02f), 0);
        fd.wet[0] = rain;
        fd.cloud[0] = in.cloudCover;
        fd.cloud[1] = 24f;
        fd.cloud[2] = in.timeSeconds * 2f;
        fd.cloud[3] = in.timeSeconds * 0.7f;
        fd.flags |= RtFrameData.F_LOOK | RtFrameData.F_WAVES | (in.cloudCover > 0 ? RtFrameData.F_CLOUD_SHADOW : 0) | (in.underwater ? RtFrameData.F_UNDERWATER : 0);
        // post: weights, {exposure, saturation, grade amount, strength}, {contrast, shoulder, hdrMax, midOut}
        float[] w = in.elementWeights;
        return new float[]{w[0], w[1], w[2], w[3], in.exposure, 1.08f, 1f, 1f, 1.15f, 0.97f, 16f, 0.20f};
    }

    static void set(float[] dst, float[] rgb, float w) {
        dst[0] = rgb[0];
        dst[1] = rgb[1];
        dst[2] = rgb[2];
        dst[3] = w;
    }
}
