// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The read-only project view a plugin sees (build-plugins plan §3.1 {@code ProjectView}): display
 * coordinates, the resolved entry point, and capability facts — never CAS paths, never jk
 * directory-layout knowledge.
 *
 * @param mainClass the resolved application entry point (CLI override &gt; declared &gt; unique
 *     main scan), or null when the project declares none and none is discoverable
 * @param nativeDeclared the project declares a {@code [native]} table
 * @param manifest extra {@code [manifest]} attributes destined for the packaged artifact
 */
public record ProjectFacts(
        String group,
        String name,
        String version,
        int javaRelease,
        String mainClass,
        boolean nativeDeclared,
        boolean kotlin,
        Map<String, String> manifest) {

    public ProjectFacts {
        manifest = manifest == null || manifest.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
    }
}
