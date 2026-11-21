/**
 * Iroh's public API: the only Iroh classes another mod may link against. Everything outside this package is internal and
 * may change in any build.
 * <p>
 * Binary stability: classes and methods here are only ever added, never renamed, removed or given a new signature.
 * {@link dev.elemore.iroh.api.IrohApi#API_VERSION} goes up by one with each addition, so a caller can test for a method
 * before using it. Enum constants are only appended.
 * <p>
 * Linking: compile against the Iroh jar (compile-only, never bundled) and touch these classes only from code that runs
 * after checking {@code FabricLoader.getInstance().isModLoaded("iroh")}, keeping them in a nested holder class, so a
 * mod stays loadable without Iroh. Reference: {@code knowledge/api.md} in the Iroh skill.
 */
package dev.elemore.iroh.api;
