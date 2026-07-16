// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.util.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The slice of Gradle Module Metadata ({@code .module} sidecar JSON) jk needs for Kotlin
 * Multiplatform "root" modules: which platform artifact a variant redirects to.
 *
 * <p>KMP libraries (androidx compose runtime, kotlinx-*) publish a root module whose POM carries
 * a JVM fallback dependency ({@code <artifact>-jvm}) for plain-Maven consumers, plus a {@code
 * .module} file whose variants each point {@code available-at} another module ({@code -android},
 * {@code -jvm}, {@code -iosarm64}, …). Gradle selects one variant by attributes; a Maven-POM view
 * resolves the root artifact AND the -jvm fallback, double-defining every class at dex time — the
 * exact failure Now-in-Android hit. jk mirrors Gradle's selection for the one axis that matters
 * here: {@code org.gradle.usage == java-runtime} filtered by {@code org.gradle.jvm.environment}
 * ({@code android} for Android projects, else {@code standard-jvm}), falling back to the other
 * environment when the preferred one isn't published.
 *
 * <p>Not a general GMM engine — capabilities, dependency constraints, and file-level metadata are
 * deliberately out of scope; a module whose metadata doesn't parse simply reads as "no redirect",
 * which is the plain-Maven behavior jk had before.
 */
public final class GradleModuleMetadata {

    /** The POM comment marker Gradle writes on every module published with a {@code .module} file. */
    public static final String POM_MARKER = "do_not_remove: published-with-gradle-metadata";

    /** A variant redirect: this module's classes actually live at {@code module}:{@code version}. */
    public record Redirect(String group, String module, String version) {}

    private final List<Map<String, Object>> variants;

    private GradleModuleMetadata(List<Map<String, Object>> variants) {
        this.variants = variants;
    }

    @SuppressWarnings("unchecked")
    public static GradleModuleMetadata parse(Path moduleFile) throws IOException {
        Object root = MiniJson.parse(Files.readString(moduleFile, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> map)) throw new IOException("not a GMM document: " + moduleFile);
        Object variants = map.get("variants");
        if (!(variants instanceof List<?> list)) return new GradleModuleMetadata(List.of());
        return new GradleModuleMetadata((List<Map<String, Object>>) (List<?>) list);
    }

    /**
     * The runtime redirect for {@code jvmEnvironment} ({@code "android"} / {@code "standard-jvm"}),
     * falling back to the other environment, or empty when this module publishes its runtime
     * in-place (no {@code available-at} on the matching variant).
     */
    public Optional<Redirect> runtimeRedirect(String jvmEnvironment) {
        String fallback = "android".equals(jvmEnvironment) ? "standard-jvm" : "android";
        return runtimeRedirectFor(jvmEnvironment).or(() -> runtimeRedirectFor(fallback));
    }

    private Optional<Redirect> runtimeRedirectFor(String jvmEnvironment) {
        for (Map<String, Object> variant : variants) {
            if (!(variant.get("attributes") instanceof Map<?, ?> attrs)) continue;
            if (!"java-runtime".equals(attrs.get("org.gradle.usage"))) continue;
            // Sources/javadoc variants also declare java-runtime usage — category tells them apart.
            Object category = attrs.get("org.gradle.category");
            if (category != null && !"library".equals(category)) continue;
            // Gradle's compatibility rule: an ABSENT org.gradle.jvm.environment is
            // standard-jvm-compatible. androidx declares the attribute explicitly; kotlinx
            // (datetime, coroutines, serialization) omits it on the jvm variants.
            Object env = attrs.get("org.gradle.jvm.environment");
            boolean matches = env == null ? "standard-jvm".equals(jvmEnvironment) : env.equals(jvmEnvironment);
            if (!matches) continue;
            Redirect r = availableAt(variant);
            if (r != null) return Optional.of(r);
        }
        return Optional.empty();
    }

    /**
     * Every module this root's variants redirect to — the set of platform-artifact siblings whose
     * POM-fallback dependency edges must be dropped when a redirect is taken (keeping the -jvm
     * fallback alongside the -android redirect would recreate the double-define).
     */
    public Set<String> redirectTargetModules() {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> variant : variants) {
            Redirect r = availableAt(variant);
            if (r != null) out.add(r.group() + ":" + r.module());
        }
        return out;
    }

    private static Redirect availableAt(Map<String, Object> variant) {
        if (!(variant.get("available-at") instanceof Map<?, ?> at)) return null;
        Object group = at.get("group");
        Object module = at.get("module");
        Object version = at.get("version");
        if (group instanceof String g && module instanceof String m && version instanceof String v) {
            return new Redirect(g, m, v);
        }
        return null;
    }
}
