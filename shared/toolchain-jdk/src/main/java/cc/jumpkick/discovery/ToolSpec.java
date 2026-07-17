// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import java.util.Objects;

/**
 * What we're looking for. {@code kind} matches SDKMAN candidate slugs ({@code java}, {@code
 * kotlin}, {@code maven}, {@code gradle}) so the probes can map directly to {@code
 * ~/.sdkman/candidates/<kind>/}.
 *
 * <p>{@code distribution} only applies to JDKs — the SDKMAN-style suffix ({@code tem}, {@code
 * graalce}, {@code zulu}, …). Nullable for build tools (Maven, Gradle, Kotlin) which have a single
 * distribution.
 */
public record ToolSpec(String kind, String version, String distribution) {

    public ToolSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(version, "version");
        if (kind.isBlank()) throw new IllegalArgumentException("kind must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("version must not be blank");
    }

    public static ToolSpec jdk(String version, String distribution) {
        return new ToolSpec("java", version, distribution);
    }

    public static ToolSpec maven(String version) {
        return new ToolSpec("maven", version, null);
    }

    public static ToolSpec gradle(String version) {
        return new ToolSpec("gradle", version, null);
    }

    public static ToolSpec kotlin(String version) {
        return new ToolSpec("kotlin", version, null);
    }
}
