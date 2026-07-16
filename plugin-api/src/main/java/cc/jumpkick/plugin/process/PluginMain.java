// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.process;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The one entry point every out-of-process plugin jar declares as its {@code Main-Class}. Replaces
 * the bespoke {@code main()} each runner used to hand-roll (arg checks, spec read, NDJSON escaping,
 * exit codes): this loads the jar's {@link Plugin} via {@link ServiceLoader}, builds the {@link
 * ProtocolWriter} from its manifest, and bridges stdio to {@link Plugin#run}.
 *
 * <p>Exit codes: the plugin's own return value on success; {@code 70} when no plugin can be
 * selected; {@code 1} when {@code run} throws.
 */
public final class PluginMain {

    private PluginMain() {}

    public static void main(String[] args) {
        List<Plugin> plugins = new ArrayList<>();
        for (Plugin p : ServiceLoader.load(Plugin.class)) {
            plugins.add(p);
        }
        if (plugins.isEmpty()) {
            System.err.println("jk-plugin-host: no cc.jumpkick.plugin.Plugin found on the classpath");
            System.exit(70);
            return;
        }
        Plugin plugin = select(plugins);
        if (plugin == null) {
            System.exit(70);
            return;
        }

        // Dedicated UTF-8 stdout for the protocol stream (the tool's own stdout
        // chatter still flows through and is treated as passthrough by the host).
        PrintStream out = new PrintStream(
                new FileOutputStream(FileDescriptor.out), /* autoFlush */ false, StandardCharsets.UTF_8);
        ProtocolWriter writer = new ProtocolWriter(out, plugin.manifest().protocolPrefix());

        try {
            System.exit(plugin.run(List.of(args), writer));
        } catch (Exception e) {
            System.err.println(plugin.manifest().id() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Pick the plugin to run. A worker jar bundles exactly one {@link Plugin}, so the common case is
     * unambiguous. But the test runner is launched with the module-under-test on its classpath so it
     * can discover that module's tests — and when that module is itself a plugin (e.g. {@code jk
     * test} on jk's own {@code kotlin-compiler}), {@link ServiceLoader} finds two: the runner and the
     * module's own plugin. The launcher resolves this explicitly by naming the intended plugin in the
     * {@code jk.plugin.class} system property; we honor it when set. Absent the property, exactly one
     * plugin is required. Returns {@code null} (after a diagnostic) when no selection can be made —
     * code-source matching is deliberately avoided: when the module-under-test depends on {@code
     * plugin-api}, this class loads from the plain plugin-api jar, whose code source bundles no
     * plugin at all.
     */
    private static Plugin select(List<Plugin> plugins) {
        String wanted = System.getProperty("jk.plugin.class");
        if (wanted != null && !wanted.isBlank()) {
            for (Plugin p : plugins) {
                if (wanted.equals(p.getClass().getName())) return p;
            }
            System.err.println(
                    "jk-plugin-host: requested plugin " + wanted + " not found among " + classNames(plugins));
            return null;
        }
        if (plugins.size() == 1) return plugins.get(0);
        System.err.println("jk-plugin-host: expected exactly one Plugin, found "
                + plugins.size()
                + " "
                + classNames(plugins)
                + " — set -Djk.plugin.class to choose");
        return null;
    }

    private static String classNames(List<Plugin> plugins) {
        StringBuilder sb = new StringBuilder("[");
        for (Plugin p : plugins) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(p.getClass().getName());
        }
        return sb.append(']').toString();
    }
}
