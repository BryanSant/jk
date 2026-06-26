// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import java.io.IOException;
import java.util.List;

/**
 * What the solver sees of the dependency universe: a list of available versions per package and the
 * dependency edges of each (package, version) pair. Decoupled from {@code MavenRepo} / {@code
 * EffectivePomBuilder} so the solver can be tested entirely in-process with no I/O.
 *
 * <p>Maven integration will provide a {@code MavenPackageSource} adapter in a follow-up session.
 */
public interface PackageSource {

    /**
     * @return all known versions of {@code pkg}, ordered from <b>highest</b> (preferred) to lowest.
     *     PubGrub picks the first version that satisfies the active constraints.
     */
    List<String> versions(String pkg) throws IOException, InterruptedException;

    /**
     * @return dependency edges of {@code (pkg, version)} as {@link Term}s. Each Term gives a
     *     downstream package and the version range the parent requires of it.
     */
    List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException;
}
