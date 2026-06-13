// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.worker;

import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.protocol.ProtocolWriter;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The one entry point every out-of-process plugin jar declares as its
 * {@code Main-Class}. Replaces the bespoke {@code main()} each runner used to
 * hand-roll (arg checks, spec read, NDJSON escaping, exit codes): this loads the
 * jar's single {@link Plugin} via {@link ServiceLoader}, builds the
 * {@link ProtocolWriter} from its manifest, and bridges stdio to {@link Plugin#run}.
 *
 * <p>Exit codes: the plugin's own return value on success; {@code 70} when no
 * plugin is on the classpath; {@code 1} when {@code run} throws.
 */
public final class PluginWorkerMain {

    private PluginWorkerMain() {}

    public static void main(String[] args) {
        List<Plugin> plugins = new ArrayList<>();
        for (Plugin p : ServiceLoader.load(Plugin.class)) {
            plugins.add(p);
        }
        if (plugins.isEmpty()) {
            System.err.println("jk-plugin-host: no dev.jkbuild.plugin.Plugin found on the classpath");
            System.exit(70);
            return;
        }
        if (plugins.size() > 1) {
            System.err.println("jk-plugin-host: expected exactly one Plugin, found " + plugins.size());
            System.exit(70);
            return;
        }
        Plugin plugin = plugins.get(0);

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
}
