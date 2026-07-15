// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import build.jumpkick.plugin.PluginConfig;

/**
 * The {@link PackageExtension} contribution surface: declares the one packager that replaces the
 * module's main artifact (Spring's boot-jar, the shrink plugin's slim jar, Android's apk/aar/aab).
 * Declare its {@link #inputs} then hand it a body with {@link #produce}; the body runs later against
 * a {@link PackageIo}. At most one packager per project.
 */
public interface PackageContext {

    /** The plugin's validated config table. */
    PluginConfig config();

    /** Read-only project facts (coords, resolved main, capability flags). */
    ProjectFacts project();

    /** Declare the packager's inputs (fingerprinted into the artifact's action key). */
    PackageContext inputs(In... ins);

    /** Register the packager body. {@code name} labels it in diagnostics; calling twice is an error. */
    void produce(String name, PackagerSpec.Body body);
}
