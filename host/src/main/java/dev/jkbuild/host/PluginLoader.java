// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.plugin.protocol.ProtocolWriter;
import dev.jkbuild.worker.WorkerProcess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Loads and runs a {@link Plugin} either in-process (via an isolated
 * {@link URLClassLoader}) or out-of-process (by forking a JVM via the
 * existing {@code WorkerProcess} / {@code PluginHostMain} path).
 *
 * <p>The decision is made per {@link PluginManifest#inProcess()}:
 * <ul>
 *   <li><b>in-process (default)</b>: the plugin jar and its deps are loaded
 *       into a child {@link URLClassLoader} parented to a minimal API
 *       classloader that exports only {@code plugin-api} classes. Plugin
 *       threads run on the Host's own thread pool; no JVM fork overhead.
 *       Dependency leakage between plugins is prevented by the classloader
 *       boundary.</li>
 *   <li><b>process (opt-in)</b>: delegates to {@link WorkerProcess} exactly
 *       as before Phase 6 — fork {@code java -jar <plugin>.jar}, stream
 *       events back. Used for plugins with hostile classpaths (JGit, Jib,
 *       BouncyCastle, etc.).</li>
 * </ul>
 *
 * <p>The distinction is transparent to callers: both paths produce the same
 * event stream — the in-process path captures the plugin's {@link ProtocolWriter}
 * output into the same {@link java.util.function.Consumer} used by
 * {@code WorkerProcess.run}.
 */
public final class PluginLoader {

    private PluginLoader() {}

    /**
     * Run the plugin at {@code jarPath} against {@code args}, routing
     * structured events to {@code onProtocol} and passthrough lines to
     * {@code onPassthrough}. Returns the plugin's exit code.
     *
     * <p>Selects in-process or out-of-process based on the manifest bundled
     * in the jar.
     */
    public static int run(Path jarPath, List<String> args,
                          java.util.function.Consumer<String> onProtocol,
                          java.util.function.Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(jarPath, List.of(), args, onProtocol, onPassthrough);
    }

    /**
     * Variant that allows supplying additional classpath entries for the
     * in-process loader — needed for the test-runner which must have the
     * user's test classes and runtime dependencies on the classloader path.
     *
     * @param extraClasspath additional jars/dirs placed on the isolated
     *                       {@link URLClassLoader} (in-process path only;
     *                       ignored for out-of-process forks where the caller
     *                       already embeds them in the {@code -cp} argument).
     */
    public static int run(Path jarPath, List<Path> extraClasspath, List<String> args,
                          java.util.function.Consumer<String> onProtocol,
                          java.util.function.Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        PluginManifest manifest = readManifest(jarPath);
        if (manifest != null && manifest.inProcess()) {
            return runInProcess(jarPath, extraClasspath, args, manifest, onProtocol, onPassthrough);
        }
        // Out-of-process: fork via PluginHostMain (existing path).
        String prefix = manifest != null ? manifest.protocolPrefix() : "##JK:";
        List<String> cmd = List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", jarPath.toAbsolutePath().toString());
        var fullArgs = new java.util.ArrayList<>(cmd);
        fullArgs.addAll(args);
        return WorkerProcess.run(fullArgs, prefix, onProtocol, onPassthrough);
    }

    // --- in-process path ---------------------------------------------------

    private static int runInProcess(Path jarPath, List<Path> extraClasspath,
                                     List<String> args,
                                     PluginManifest manifest,
                                     java.util.function.Consumer<String> onProtocol,
                                     java.util.function.Consumer<String> onPassthrough)
            throws IOException {
        // Isolated classloader: only plugin-api is shared with the Host.
        // Extra entries (test classes, runtime deps) go on the same loader.
        var urls = new java.util.ArrayList<URL>();
        urls.add(jarPath.toUri().toURL());
        for (Path extra : extraClasspath) urls.add(extra.toUri().toURL());
        ClassLoader apiLoader = Plugin.class.getClassLoader();
        try (URLClassLoader pluginLoader = new URLClassLoader(
                urls.toArray(new URL[0]), apiLoader)) {

            // Discover the Plugin implementation via ServiceLoader.
            Plugin plugin = loadPlugin(pluginLoader, jarPath);
            if (plugin == null) {
                if (onPassthrough != null) {
                    onPassthrough.accept("jk-plugin-loader: no Plugin found in " + jarPath.getFileName());
                }
                return 70;
            }

            // Capture the plugin's ProtocolWriter output into a line-oriented
            // buffer, splitting on the manifest prefix exactly as WorkerProcess does.
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(buf, /* autoFlush */ false, StandardCharsets.UTF_8);
            ProtocolWriter writer = new ProtocolWriter(out, manifest.protocolPrefix());

            try {
                return plugin.run(args, writer);
            } catch (Exception e) {
                if (onPassthrough != null) {
                    onPassthrough.accept("jk-plugin [" + manifest.id() + "]: " + e.getMessage());
                }
                return 1;
            } finally {
                out.flush();
                // Replay buffered output through the correct consumer splits.
                String output = buf.toString(StandardCharsets.UTF_8);
                for (String line : output.split("\n", -1)) {
                    if (line.isEmpty()) continue;
                    if (line.startsWith(manifest.protocolPrefix())) {
                        onProtocol.accept(line.substring(manifest.protocolPrefix().length()));
                    } else if (onPassthrough != null) {
                        onPassthrough.accept(line);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Plugin loadPlugin(URLClassLoader loader, Path jarPath) {
        try {
            ServiceLoader<Plugin> sl = ServiceLoader.load(Plugin.class, loader);
            for (Plugin p : sl) return p;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // --- manifest reading --------------------------------------------------

    /**
     * Read the {@link PluginManifest} from the jar without fully loading it:
     * opens the ServiceLoader registration file and loads only the manifest class.
     * Returns {@code null} if the jar doesn't carry a valid manifest.
     */
    static PluginManifest readManifest(Path jarPath) {
        try (var zf = new java.util.zip.ZipFile(jarPath.toFile())) {
            var svcEntry = zf.getEntry("META-INF/services/dev.jkbuild.plugin.Plugin");
            if (svcEntry == null) return null;
            String className = new String(zf.getInputStream(svcEntry).readAllBytes(),
                    StandardCharsets.UTF_8).strip().split("\n")[0].trim();
            if (className.isEmpty()) return null;

            // Load only the manifest-bearing class in a throwaway loader.
            URL jarUrl = jarPath.toUri().toURL();
            try (URLClassLoader probe = new URLClassLoader(
                    new URL[]{jarUrl}, Plugin.class.getClassLoader())) {
                Class<?> cls = probe.loadClass(className);
                Object instance = cls.getDeclaredConstructor().newInstance();
                if (instance instanceof Plugin p) return p.manifest();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
