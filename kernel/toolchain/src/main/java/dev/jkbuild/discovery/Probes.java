// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The default {@link LocalToolProbe} chain, in the order callers should
 * consult them. ServiceLoader-discovered probes are appended after the
 * built-ins so a plugin can extend (but not preempt) the default order.
 *
 * <p>Order rationale: explicit user intent first ({@code env}), then the
 * version managers in rough popularity order for JVM developers, then
 * OS-level system installs as a last resort.
 */
public final class Probes {

    private Probes() {}

    public static List<LocalToolProbe> defaultChain() {
        List<LocalToolProbe> chain = new ArrayList<>();
        chain.add(new EnvVarProbe());
        chain.add(new JkProbe());           // ~/.jk/jdks — jk's own installs
        chain.add(new IntellijProbe());     // ~/.jdks or ~/Library/Java/JavaVirtualMachines
        chain.add(new GradleProbe());       // ~/.gradle/jdks — Gradle toolchain auto-provisioned
        chain.add(new SdkmanProbe());
        chain.add(new JbangProbe());
        chain.add(new MiseProbe());
        chain.add(new AsdfProbe());
        chain.add(new JenvProbe());
        chain.add(new HomebrewProbe());
        chain.add(new SystemProbe());
        for (LocalToolProbe extension : ServiceLoader.load(LocalToolProbe.class)) {
            chain.add(extension);
        }
        return List.copyOf(chain);
    }
}
