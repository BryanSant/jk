// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Derives the {@link Phase}s an extension participates in from the capability interfaces it
 * implements — the bridge between "which capability does this plugin implement" and the {@link
 * build.jumpkick.plugin.Extension#phases()} metadata. Domain extensions that run in-engine
 * (GitBackend, JavaCompileStrategy, LocalToolProbe) tag their phase directly; capability plugins
 * get theirs from here.
 */
public final class Capabilities {

    private Capabilities() {}

    /** The phases {@code extension} participates in by virtue of the capability interfaces it implements. */
    public static Set<Phase> phasesOf(Object extension) {
        EnumSet<Phase> phases = EnumSet.noneOf(Phase.class);
        if (extension instanceof ResolveExtension) phases.add(Phase.RESOLVE);
        if (extension instanceof BuildExtension) phases.add(Phase.COMPILE);
        if (extension instanceof TestExtension) phases.add(Phase.TEST);
        if (extension instanceof PackageExtension) phases.add(Phase.PACKAGE);
        if (extension instanceof RunExtension) phases.add(Phase.RUN);
        if (extension instanceof ImageExtension) phases.add(Phase.IMAGE);
        if (extension instanceof PublishExtension) phases.add(Phase.PUBLISH);
        return Collections.unmodifiableSet(phases);
    }
}
