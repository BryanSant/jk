// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginContext;
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

    /**
     * Two-way in-process conversation — the parallel of
     * {@link WorkerProcess#converse}: each protocol line is delivered to
     * {@code onProtocol} together with a {@link WorkerProcess.Conversation}
     * for sending commands back to the plugin's stdin.
     *
     * <p>Implemented by redirecting {@link System#in} for the isolated
     * classloader via a piped stream pair, then running the plugin on a
     * daemon thread while this thread drives the send/receive loop from the
     * caller's side.
     *
     * <p>Parallels the out-of-process path: if the manifest says
     * {@code isolation=process} (or is absent), falls back to
     * {@link WorkerProcess#converse}.
     */
    public static int converse(Path jarPath, List<Path> extraClasspath, List<String> args,
                               java.util.function.BiConsumer<String, WorkerProcess.Conversation> onProtocol,
                               java.util.function.Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        PluginManifest manifest = readManifest(jarPath);
        if (manifest != null && manifest.inProcess()) {
            return converseInProcess(jarPath, extraClasspath, args, manifest, onProtocol, onPassthrough);
        }
        // Out-of-process fallback.
        String prefix = manifest != null ? manifest.protocolPrefix() : "##JK:";
        var fullCmd = new java.util.ArrayList<String>();
        fullCmd.add(ProcessHandle.current().info().command().orElse("java"));
        fullCmd.add("-jar");
        fullCmd.add(jarPath.toAbsolutePath().toString());
        fullCmd.addAll(args);
        return WorkerProcess.converse(fullCmd, prefix, onProtocol, onPassthrough);
    }

    /**
     * Register a plugin's contributed phases into a build goal. The plugin's
     * {@link Plugin#register} is called in an isolated {@link URLClassLoader}
     * parented to the plugin-api classloader so the plugin's deps stay
     * separate from the Host's.
     *
     * <p>If the plugin's manifest declares {@code isolation = "process"}, registration
     * is skipped — the plugin will be invoked later as a forked worker once
     * the process-isolated registration protocol is implemented.
     *
     * @return {@code true} if the plugin was registered in-process;
     *         {@code false} if skipped (process-isolated or not a plugin jar)
     */
    public static boolean register(Path jarPath, PluginContext ctx)
            throws IOException {
        PluginManifest manifest = readManifest(jarPath);
        if (manifest == null) return false;
        if (!manifest.inProcess()) return false; // process-isolated: deferred
        URL jarUrl = jarPath.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jarUrl}, Plugin.class.getClassLoader())) {
            Plugin plugin = loadPlugin(loader, jarPath);
            if (plugin == null) return false;
            plugin.register(ctx);
            return true;
        } catch (Exception e) {
            throw new IOException("Plugin registration failed for " + jarPath + ": " + e.getMessage(), e);
        }
    }

    private static int converseInProcess(Path jarPath, List<Path> extraClasspath,
                                          List<String> args, PluginManifest manifest,
                                          java.util.function.BiConsumer<String, WorkerProcess.Conversation> onProtocol,
                                          java.util.function.Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        // Set up a pipe: caller writes stdin commands → plugin reads from System.in.
        java.io.PipedInputStream pluginStdin = new java.io.PipedInputStream(4096);
        java.io.PipedOutputStream callerStdout = new java.io.PipedOutputStream(pluginStdin);

        // Capture plugin output in a buffer replayed after each flush.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capturedOut = new PrintStream(buf, true, StandardCharsets.UTF_8);

        // Redirect System.in for the plugin thread; reset after.
        java.io.InputStream origIn = System.in;
        System.setIn(pluginStdin);
        int[] exitCode = {-1};

        // Run the plugin on a daemon thread so we can drive the conversation loop here.
        URL jarUrl = jarPath.toUri().toURL();
        var urls = new java.util.ArrayList<URL>();
        urls.add(jarUrl);
        for (Path p : extraClasspath) urls.add(p.toUri().toURL());
        ClassLoader apiLoader = Plugin.class.getClassLoader();

        Thread pluginThread;
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), apiLoader)) {
            Plugin plugin = loadPlugin(loader, jarPath);
            if (plugin == null) {
                System.setIn(origIn);
                if (onPassthrough != null) onPassthrough.accept("jk-plugin-loader: no Plugin in " + jarPath.getFileName());
                return 70;
            }
            ProtocolWriter writer = new ProtocolWriter(capturedOut, manifest.protocolPrefix());
            pluginThread = new Thread(() -> {
                try { exitCode[0] = plugin.run(args, writer); }
                catch (Exception e) { exitCode[0] = 1; }
            }, "jk-plugin-" + manifest.id());
            pluginThread.setDaemon(true);
            pluginThread.start();
        }

        // Drive the conversation: read lines the plugin emits (via capturedOut),
        // dispatch them to onProtocol which may call convo.send().
        WorkerProcess.Conversation convo = new WorkerProcess.Conversation() {
            @Override public void send(String line) {
                try {
                    callerStdout.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    callerStdout.flush();
                } catch (IOException ignored) {}
            }
            @Override public void closeInput() {
                try { callerStdout.close(); } catch (IOException ignored) {}
            }
        };

        // Poll the buffer while the plugin thread is alive.
        while (pluginThread.isAlive() || buf.size() > 0) {
            String chunk = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            for (String line : chunk.split("\n", -1)) {
                if (line.isEmpty()) continue;
                if (line.startsWith(manifest.protocolPrefix())) {
                    onProtocol.accept(line.substring(manifest.protocolPrefix().length()), convo);
                } else if (onPassthrough != null) {
                    onPassthrough.accept(line);
                }
            }
            if (pluginThread.isAlive()) {
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        pluginThread.join();
        System.setIn(origIn);
        return exitCode[0] >= 0 ? exitCode[0] : 1;
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
