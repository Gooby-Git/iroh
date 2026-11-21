package dev.elemore.iroh.api;

/**
 * Declared fixed-function state of an {@link IrohDraw} pipeline (api 3). Everything a draw needs is declared up front, so
 * nothing about the render state leaks between mods: the draw applies it and restores the previous state.
 *
 * @param layout     vertex layout written by {@link GeometryWriter} (NONE for vertex-pulling draws)
 * @param topology   primitive type
 * @param blend      blending
 * @param depthTest  depth test (LEQUAL) against the bound target's depth
 * @param depthWrite write depth
 * @param cull       back-face culling (GL counter-clockwise front faces, see glshim.Orientation)
 * @param shader     program
 */
public record PipelineDecl(VertexLayout layout, Topology topology, Blend blend, boolean depthTest, boolean depthWrite,
                           boolean cull, ShaderHandle shader) {

    public enum VertexLayout {
        /** No vertex buffer (gl_VertexIndex / gl_InstanceIndex pulling). */
        NONE,
        /** vec3 Position, vec4 Color (unorm8x4). 16 bytes. */
        POSITION_COLOR,
        /** vec3 Position, vec2 UV0, vec4 Color. 24 bytes. */
        POSITION_TEX_COLOR
    }

    public enum Topology { TRIANGLES, QUADS, LINES, LINE_STRIP }

    public enum Blend {
        OPAQUE,
        /** src alpha, one minus src alpha (rgb); one, one minus src alpha (alpha). */
        TRANSLUCENT,
        /** src alpha, one. */
        ADDITIVE
    }
}
