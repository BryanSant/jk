// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

/**
 * Dependency scope per PRD §7.2. Maven scope names retained. {@code system} is intentionally absent
 * — rejected on import.
 */
public enum Scope {
    EXPORT("export", "export-dependencies"),
    MAIN("main", "dependencies"),
    PROVIDED("provided", "provided-dependencies"),
    RUNTIME("runtime", "runtime-dependencies"),
    TEST("test", "test-dependencies"),
    PROCESSOR("processor", "processor-dependencies"),
    PLATFORM("platform", "platform-dependencies"),
    /**
     * Development-time only (Gradle's {@code developmentOnly}, tool-targets/spring-boot plan):
     * on the {@code jk run}/{@code jk dev} runtime classpath, never in any artifact or POM.
     */
    DEV("dev", "dev-dependencies"),
    /**
     * Development + test only (Gradle's {@code testAndDevelopmentOnly}): {@link #DEV} plus the
     * test source set's compile/runtime classpaths. Never in artifacts or POMs.
     */
    TEST_DEV("test-dev", "test-dev-dependencies");

    // Precomputed once at class-load rather than reallocated on every call: canonical() and
    // tomlSection() are hot (dependency loops, ~21 + ~call sites) and effectively constant.
    private final String canonical;
    private final String tomlSection;

    Scope(String canonical, String tomlSection) {
        this.canonical = canonical;
        this.tomlSection = tomlSection;
    }

    /** Lowercase canonical name of this scope (e.g. {@code "main"}, {@code "test"}). */
    public String canonical() {
        return canonical;
    }

    /**
     * The {@code jk.toml} section header for this scope's dependency table.
     * MAIN maps to {@code "dependencies"}; every other scope maps to
     * {@code "<canonical>-dependencies"} (e.g. {@code "test-dependencies"}).
     */
    public String tomlSection() {
        return tomlSection;
    }
}
