package dev.elemore.iroh.rttest;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Pinhole camera with Vulkan conventions (y down in NDC, depth 0..1), unjittered. */
public final class RtCamera {
    public final Vector3f pos, target;
    public final float fovYDeg, near = 0.05f, far = 512f;
    public final int w, h;

    public RtCamera(Vector3f pos, Vector3f target, float fovYDeg, int w, int h) {
        this.pos = new Vector3f(pos);
        this.target = new Vector3f(target);
        this.fovYDeg = fovYDeg;
        this.w = w;
        this.h = h;
    }

    public Matrix4f viewProj() {
        Matrix4f p = new Matrix4f().scale(1, -1, 1).perspective((float) Math.toRadians(fovYDeg), w / (float) h, near, far, true);
        return p.mul(new Matrix4f().lookAt(pos, target, new Vector3f(0, 1, 0)));
    }

    public Vector3f forward() {
        return new Vector3f(target).sub(pos).normalize();
    }

    /** Unit direction of the ray through the centre of pixel (x, y), as the shaders compute it. */
    public double[] rayDir(int x, int y) {
        Matrix4f inv = viewProj().invert();
        float nx = (x + 0.5f) / w * 2 - 1, ny = (y + 0.5f) / h * 2 - 1;
        org.joml.Vector4f f = inv.transform(new org.joml.Vector4f(nx, ny, 1, 1));
        double dx = f.x / f.w - pos.x, dy = f.y / f.w - pos.y, dz = f.z / f.w - pos.z;
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{dx / l, dy / l, dz / l};
    }
}
