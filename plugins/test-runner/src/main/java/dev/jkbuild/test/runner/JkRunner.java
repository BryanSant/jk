// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.protocol.ProtocolWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/**
 * Child-JVM entry point for {@code jk test}. Three modes, selected by flags:
 *
 * <ol>
 *   <li><b>One-shot</b> (default): {@code --scan-classpath=<dir>} — discover and execute everything
 *       under the classpath root. Used when the parent wants a single fork with no work-stealing.
 *   <li><b>Discovery</b>: {@code --list-only --scan-classpath=<dir>} — walk the test plan but
 *       execute nothing. Emit one {@link EventType#DISCOVERED} per top-level test class then exit.
 *       The parent uses this list to seed the pull-queue for parallel runs.
 *   <li><b>Pull worker</b>: {@code --pull --worker=N --scan-classpath=<dir>} — load engines via
 *       {@link java.util.ServiceLoader}, then loop on stdin: {@code RUN <fqcn>} runs that class,
 *       {@code DONE} or EOF exits. After each class we emit {@link EventType#READY} so the parent
 *       knows to send the next.
 * </ol>
 *
 * <p>Exit codes: 0 = success, 1 = at least one test failed, 2 = arg/engine error. The wire
 * protocol on stdout matches the schema described in {@link EventType}; lines without the worker's
 * protocol prefix are user test output and pass through to the parent's stdout.
 */
public final class JkRunner implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-test-runner", "##JK:");
    }

    @Override
    public int run(List<String> argList, ProtocolWriter out) {
        Args parsed;
        try {
            parsed = Args.parse(argList.toArray(new String[0]));
        } catch (IllegalArgumentException e) {
            System.err.println("jk-test-runner: " + e.getMessage());
            System.err.println("usage: jk-test-runner --scan-classpath=<dir> "
                    + "[--list-only] [--pull --worker=<id>] [--filter=<regex>]");
            return 2;
        }

        try (var writer = new JsonEventWriter(out)) {
            if (parsed.listOnly) {
                runListOnly(parsed, writer);
                return 0;
            } else if (parsed.pull) {
                return runPullMode(parsed, writer);
            } else {
                return runOneShot(parsed, writer);
            }
        } catch (LinkageError e) {
            // A JUnit Platform that's missing or too old for the TestEngine SPI
            // this runner is built against surfaces as a NoClassDefFound /
            // NoSuchMethod against org.junit.platform.* — give an actionable
            // hint instead of a raw stack trace. (Anything else is the user's
            // own test code and falls through to the generic handler.)
            String where = String.valueOf(e.getMessage());
            if (where.contains("junit/platform") || where.contains("junit.platform")) {
                System.err.println("jk-test-runner: incompatible JUnit Platform on the test classpath — "
                        + e.getClass().getSimpleName()
                        + ": "
                        + e.getMessage());
                System.err.println("  jk drives the JUnit Platform TestEngine SPI; ensure "
                        + "org.junit.platform:junit-platform-engine is on the test classpath "
                        + "with a compatible version.");
                return 2;
            }
            System.err.println("jk-test-runner: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            return 2;
        } catch (Throwable t) {
            System.err.println("jk-test-runner: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            return 2;
        }
    }

    // --- mode 1: one-shot ----------------------------------------------------

    /**
     * Discover + execute everything reachable from {@code --scan-classpath}, emitting events as we
     * go. Returns the exit code (0 on green).
     */
    private static int runOneShot(Args args, JsonEventWriter writer) {
        var streaming = new StreamingListener(writer, args.workerId);
        var request = baseRequest(args);
        var engines = java.util.ServiceLoader.load(org.junit.platform.engine.TestEngine.class);
        long planStart = System.nanoTime();
        for (var engine : engines) {
            var uid = org.junit.platform.engine.UniqueId.root("[engine]", engine.getId());
            var descriptor = engine.discover(request, uid);
            emitDiscovery(descriptor, streaming);
            var execRequest = makeExecutionRequest(descriptor, streaming);
            engine.execute(execRequest);
        }
        long planMs = Math.max(0, (System.nanoTime() - planStart) / 1_000_000);
        streaming.emitPlanFinished(planMs);
        return streaming.hasFailures() ? 1 : 0;
    }

    // --- mode 2: discovery ---------------------------------------------------

    /**
     * Walk the test plan and emit one {@link EventType#DISCOVERED} per top-level test class, plus a
     * {@link EventType#DISCOVERY_TOTAL} with the {@code (classes, tests)} totals. Uses engine
     * discovery only (not execute) so this completes in 100–300 ms even for big suites.
     */
    private static void runListOnly(Args args, JsonEventWriter writer) {
        var streaming = new StreamingListener(writer, args.workerId);
        var request = baseRequest(args);
        var engines = java.util.ServiceLoader.load(org.junit.platform.engine.TestEngine.class);
        for (var engine : engines) {
            var uid = org.junit.platform.engine.UniqueId.root("[engine]", engine.getId());
            var descriptor = engine.discover(request, uid);
            emitDiscovery(descriptor, streaming);
        }
    }

    /**
     * Shared discovery emission used by both one-shot and list-only modes: walk the engine
     * descriptor tree once, emit a DISCOVERED per class, then a DISCOVERY_TOTAL with cumulative
     * counts. Single source of truth so the wire shape is identical regardless of which mode
     * invoked it.
     */
    private static void emitDiscovery(
            org.junit.platform.engine.TestDescriptor root, StreamingListener listener) {
        var counts = new int[] {0, 0}; // [classes, tests]
        for (var child : root.getChildren()) {
            walkAndEmit(child, listener, counts);
        }
        listener.emitDiscoveryTotal(counts[0], counts[1]);
    }

    /**
     * Depth-first walk over the TestDescriptor tree. Emits a DISCOVERED event for every
     * class-shaped CONTAINER and bumps the test counter for every TEST leaf. Counts mirror what a
     * full execution would find, but without running anything.
     */
    private static void walkAndEmit(
            org.junit.platform.engine.TestDescriptor node,
            StreamingListener listener,
            int[] counts) {
        boolean isContainer = node.getType() == org.junit.platform.engine.TestDescriptor.Type.CONTAINER;
        boolean isTest = node.getType() == org.junit.platform.engine.TestDescriptor.Type.TEST;
        node.getSource().ifPresent(src -> {
            if (src instanceof org.junit.platform.engine.support.descriptor.ClassSource cs && isContainer) {
                listener.emitDiscovered(cs.getClassName());
                counts[0]++;
            }
        });
        if (isTest) counts[1]++;
        for (var child : node.getChildren()) {
            walkAndEmit(child, listener, counts);
        }
    }

    // --- mode 3: pull worker -------------------------------------------------

    /**
     * Long-lived worker. Loads engines once and reuses them for the whole lifetime so JIT/heap stay
     * warm across the classes the parent feeds us. Protocol on stdin (one line per command):
     *
     * <pre>
     *   RUN com.example.FooTest
     *   DONE
     * </pre>
     */
    private static int runPullMode(Args args, JsonEventWriter writer) throws Exception {
        var streaming = new StreamingListener(writer, args.workerId);
        // Load engines once and reuse across all classes — keeps JIT warm.
        var engines = java.util.ServiceLoader.load(org.junit.platform.engine.TestEngine.class)
                .stream().map(java.util.ServiceLoader.Provider::get).toList();

        streaming.emitReady();

        try (var stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.equals("DONE")) break;
                if (!line.startsWith("RUN ")) {
                    System.err.println("jk-test-runner: ignoring unknown command: " + line);
                    continue;
                }
                String className = line.substring(4).trim();
                var classRequest = discoveryRequest(
                        List.of(DiscoverySelectors.selectClass(className)),
                        List.of());
                for (var engine : engines) {
                    var uid = org.junit.platform.engine.UniqueId.root("[engine]", engine.getId());
                    var descriptor = engine.discover(classRequest, uid);
                    if (descriptor.getChildren().isEmpty()) continue; // engine has nothing for this class
                    var execRequest = org.junit.platform.engine.ExecutionRequest.create(
                            descriptor, streaming, EmptyConfigParams.INSTANCE);
                    engine.execute(execRequest);
                }
                streaming.emitReady();
            }
        }
        return streaming.hasFailures() ? 1 : 0;
    }

    // --- shared --------------------------------------------------------------

    private static org.junit.platform.engine.EngineDiscoveryRequest baseRequest(Args args) {
        var selectors = new ArrayList<org.junit.platform.engine.DiscoverySelector>(
                DiscoverySelectors.selectClasspathRoots(Set.of(args.scanClasspath)));
        var filters = new ArrayList<org.junit.platform.engine.DiscoveryFilter<?>>();
        if (args.filter != null && !args.filter.isEmpty()) {
            filters.add(ClassNameFilter.includeClassNamePatterns(args.filter));
        }
        return discoveryRequest(List.copyOf(selectors), List.copyOf(filters));
    }

    private static org.junit.platform.engine.EngineDiscoveryRequest discoveryRequest(
            List<org.junit.platform.engine.DiscoverySelector> selectors,
            List<org.junit.platform.engine.DiscoveryFilter<?>> filters) {
        return (org.junit.platform.engine.EngineDiscoveryRequest)
                java.lang.reflect.Proxy.newProxyInstance(
                        JkRunner.class.getClassLoader(),
                        new Class<?>[] {org.junit.platform.engine.EngineDiscoveryRequest.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getSelectorsByType" -> {
                                @SuppressWarnings("unchecked")
                                var type = (Class<org.junit.platform.engine.DiscoverySelector>) args[0];
                                yield selectors.stream()
                                        .filter(type::isInstance)
                                        .map(type::cast)
                                        .toList();
                            }
                            case "getFiltersByType" -> {
                                @SuppressWarnings("unchecked")
                                var type = (Class<org.junit.platform.engine.DiscoveryFilter<?>>) args[0];
                                yield filters.stream()
                                        .filter(type::isInstance)
                                        .map(type::cast)
                                        .toList();
                            }
                            case "getConfigurationParameters" -> EmptyConfigParams.INSTANCE;
                            // JUnit 6+ added getOutputDirectoryCreator() to EngineDiscoveryRequest.
                            // Provide a no-op via a Proxy of the return type so any version works.
                            case "getOutputDirectoryCreator" -> noOpOutputDirectoryCreator();
                            default -> {
                                // Invoke a default method if present; return null for anything else.
                                if (method.isDefault()) {
                                    yield java.lang.invoke.MethodHandles
                                            .privateLookupIn(
                                                    method.getDeclaringClass(),
                                                    java.lang.invoke.MethodHandles.lookup())
                                            .unreflectSpecial(method, method.getDeclaringClass())
                                            .bindTo(proxy)
                                            .invokeWithArguments(args);
                                }
                                yield null;
                            }
                        });
    }

    /**
     * Returns a no-op proxy of {@code OutputDirectoryCreator} (added in JUnit Platform 6.x) when
     * that type is on the classpath, or {@code null} on JUnit 5.x where the type doesn't exist.
     *
     * <p>JUnit 6.1.1 declares: {@code Path getRootDirectory()} and
     * {@code Path createOutputDirectory(TestDescriptor) throws IOException}. We return the system
     * temp dir as the root so any code that reads it doesn't get a NPE, and null for individual
     * test output directories (tests that write output find no output dir, which is safe).
     */
    private static Object noOpOutputDirectoryCreator() {
        try {
            Class<?> creatorType = Class.forName("org.junit.platform.engine.OutputDirectoryCreator");
            java.nio.file.Path tmpRoot =
                    java.nio.file.Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
            return java.lang.reflect.Proxy.newProxyInstance(
                    creatorType.getClassLoader(),
                    new Class<?>[] {creatorType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getRootDirectory" -> tmpRoot;
                        case "createOutputDirectory" -> null; // no per-test output dir
                        default -> {
                            if (method.getReturnType() == java.util.Optional.class)
                                yield java.util.Optional.empty();
                            if (method.isDefault())
                                yield java.lang.invoke.MethodHandles
                                        .privateLookupIn(
                                                method.getDeclaringClass(),
                                                java.lang.invoke.MethodHandles.lookup())
                                        .unreflectSpecial(method, method.getDeclaringClass())
                                        .bindTo(proxy)
                                        .invokeWithArguments(args);
                            yield null;
                        }
                    });
        } catch (ClassNotFoundException e) {
            return null; // JUnit 5.x — OutputDirectoryCreator doesn't exist yet
        }
    }

    /**
     * Creates an {@link org.junit.platform.engine.ExecutionRequest} compatible with whatever JUnit
     * Platform version is on the test classpath.
     *
     * <ul>
     *   <li>JUnit 5.x: uses the public 3-arg constructor.
     *   <li>JUnit 6.x: uses the 6-arg {@code create()} factory, which also requires a
     *       {@code NamespacedHierarchicalStore} (for extension state) and a
     *       {@code CancellationToken}. Both are created reflectively so we compile cleanly against
     *       the 1.8-baseline and still work at runtime on 6.x.
     * </ul>
     */
    @SuppressWarnings("deprecation")
    private static org.junit.platform.engine.ExecutionRequest makeExecutionRequest(
            org.junit.platform.engine.TestDescriptor descriptor,
            org.junit.platform.engine.EngineExecutionListener listener) {
        try {
            String ep = "org.junit.platform.engine";
            Class<?> cancelClass = Class.forName(ep + ".CancellationToken");
            Class<?> storeClass  = Class.forName(ep + ".support.store.NamespacedHierarchicalStore");
            Class<?> outDirClass = Class.forName(ep + ".OutputDirectoryCreator");

            // CancellationToken.disabled() — a token that never cancels.
            Object cancellation = cancelClass.getMethod("disabled").invoke(null);
            // Jupiter requires requestLevelStore.getParent() to be present —
            // create a launcher-level root store first, then a child for this request.
            Object parentStore = storeClass.getConstructor(storeClass).newInstance((Object) null);
            Object store = storeClass.getMethod("newChild").invoke(parentStore);
            Object outDir = noOpOutputDirectoryCreator();

            var factory = org.junit.platform.engine.ExecutionRequest.class.getMethod(
                    "create",
                    org.junit.platform.engine.TestDescriptor.class,
                    org.junit.platform.engine.EngineExecutionListener.class,
                    org.junit.platform.engine.ConfigurationParameters.class,
                    outDirClass, storeClass, cancelClass);
            return (org.junit.platform.engine.ExecutionRequest) factory.invoke(
                    null, descriptor, listener, EmptyConfigParams.INSTANCE,
                    outDir, store, cancellation);
        } catch (Exception ignored) {
            // JUnit 5.x: 3-arg public constructor is fine, no store required.
            return new org.junit.platform.engine.ExecutionRequest(
                    descriptor, listener, EmptyConfigParams.INSTANCE);
        }
    }

    /** Parsed CLI args. */
    private record Args(Path scanClasspath, String filter, boolean listOnly, boolean pull, int workerId) {

        static Args parse(String[] argv) {
            Path scan = null;
            String filter = null;
            boolean listOnly = false;
            boolean pull = false;
            int workerId = 0;
            for (var a : argv) {
                if (a.startsWith("--scan-classpath=")) {
                    scan = Path.of(a.substring("--scan-classpath=".length()));
                } else if (a.startsWith("--filter=")) {
                    filter = a.substring("--filter=".length());
                } else if (a.equals("--list-only")) {
                    listOnly = true;
                } else if (a.equals("--pull")) {
                    pull = true;
                } else if (a.startsWith("--worker=")) {
                    workerId = Integer.parseInt(a.substring("--worker=".length()));
                } else if (a.equals("--fail-fast")) {
                    // accepted but currently a no-op — wired in a follow-up
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            if (scan == null) {
                throw new IllegalArgumentException("--scan-classpath=<dir> is required");
            }
            if (listOnly && pull) {
                throw new IllegalArgumentException("--list-only and --pull are mutually exclusive");
            }
            return new Args(scan, filter, listOnly, pull, workerId);
        }
    }

    // --- inner helpers -------------------------------------------------------

    /** ConfigurationParameters implementation that returns empty/false for all keys. */
    private static final class EmptyConfigParams
            implements org.junit.platform.engine.ConfigurationParameters {
        static final EmptyConfigParams INSTANCE = new EmptyConfigParams();

        @Override public java.util.Optional<String> get(String key) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<Boolean> getBoolean(String key) { return java.util.Optional.empty(); }
        @Override public java.util.Set<String> keySet() { return java.util.Set.of(); }
    }
}
