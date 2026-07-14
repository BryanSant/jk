// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import dev.jkbuild.jdk.JdkVendor;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AOT caches (JEP 514) for jk's short-lived worker JVMs — javac and the kotlinc worker — so the
 * JIT-warmup tail is paid once per key instead of on every fork. Measured on Temurin 25: a
 * single-file javac compile drops ~40-55% depending on GC; kotlinc's warmup is heavier still.
 *
 * <p>The shape mirrors the engine's sidecar trainer ({@code docs/engine.md}), scaled down: the
 * first fork that finds no cache for its key proceeds untrained and kicks off a <em>background</em>
 * training run (a synthetic compile recorded with {@code -XX:AOTCacheOutput}, assembled at its
 * clean exit, atomically renamed into place); every later fork maps the cache. Training never
 * blocks a build, and a corrupt/mismatched cache is a silent no-op ({@code AOTMode=auto}) with its
 * chatter suppressed ({@code -Xlog:aot=off}).
 *
 * <p>Keys are exact-match by construction — the cache embeds pre-parsed metadata of the exact JDK
 * build and code compiled against the GC in effect, so the key hashes the JDK's canonical home +
 * vendor + full version <em>plus the effective GC name</em> (a GC-mismatched cache validated fine
 * but mapped nothing in measurement). A toolchain JDK or Kotlin-version bump changes the key; the
 * old cache is swept when the new key's cache lands. Caches live in {@code ~/.jk/state/aot/}.
 *
 * <p>Kill switch: {@code -Djk.worker.aot=off} (or env {@code JK_WORKER_AOT=off}) disables both use
 * and training.
 */
public final class WorkerAot {

    private WorkerAot() {}

    /** Ceiling for one training run (fork + synthetic compile + assembly); typical is ~2s. */
    private static final long TRAINING_TIMEOUT_SECONDS = 120;

    /** A claim file older than this is a crashed trainer's leftover — reclaimable. */
    private static final long CLAIM_STALE_MILLIS = 10 * 60 * 1_000;

    /**
     * Retention per tool: the newest caches by last-use mtime survive a publish sweep. Multiple
     * keys are legitimately live at once (two projects on different toolchain JDKs, two Kotlin
     * versions) — a keep-only-current sweep would make alternating builds retrain forever.
     */
    private static final int KEEP_PER_TOOL = 4;

    /** Caches (and failure markers) untouched this long are dead keys — reclaim the disk. */
    private static final long UNUSED_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000;

    /** In-JVM double-spawn guard (the claim file guards across processes). */
    private static final Set<Path> TRAINING = ConcurrentHashMap.newKeySet();

    /** Builds the full trainer command line; {@code aotOutput} is where the JVM assembles the cache. */
    public interface TrainerCommand {
        List<String> build(Path aotOutput, Path scratchDir) throws IOException;
    }

    static boolean enabled() {
        String prop = System.getProperty("jk.worker.aot", System.getenv().getOrDefault("JK_WORKER_AOT", ""));
        return !"off".equalsIgnoreCase(prop);
    }

    /** Where worker AOT caches live: {@code <state>/aot/}. */
    static Path dir() {
        return JkDirs.state().resolve("aot");
    }

    // ---- javac ----------------------------------------------------------------------------

    /**
     * Flags to append to a {@code javac} command line (after {@link JvmOptions#launcherFlags}):
     * {@code -J-XX:AOTCache=…} when a cache exists for this JDK + effective GC, else empty — and in
     * the empty case, when the JDK qualifies (HotSpot 25+), a background trainer is kicked off so
     * the NEXT compile maps a cache. Never blocks, never throws.
     */
    public static List<String> javacFlags(Path javaHome) {
        if (!enabled() || javaHome == null) return List.of();
        try {
            List<String> workerFlags = JvmOptions.launcherFlags(1);
            JdkId id = jdkId(javaHome);
            if (id == null) return List.of();
            Path cache = dir().resolve("javac-" + key(id, effectiveGc(workerFlags), "") + ".aot");
            if (Files.exists(cache)) {
                touch(cache); // retention is by last use; the JVM mapping a cache never updates mtime
                return List.of("-J-XX:AOTCache=" + cache, "-J-Xlog:aot=off");
            }
            if (eligible(id) && !Files.exists(noaotMarker(cache))) {
                trainAsync("javac (" + id.vendor() + " " + id.version() + ")", cache, (aotOutput, scratch) -> {
                    Path corpus = writeJavacCorpus(scratch);
                    Path javac = javaHome.resolve("bin")
                            .resolve(dev.jkbuild.jdk.HostPlatform.isWindows() ? "javac.exe" : "javac");
                    List<String> cmd = new ArrayList<>();
                    cmd.add(javac.toString());
                    cmd.addAll(workerFlags); // train under the flags real compiles run with
                    cmd.add("-J-XX:AOTCacheOutput=" + aotOutput);
                    cmd.add("-d");
                    cmd.add(scratch.resolve("out").toString());
                    try (var sources = Files.list(corpus)) {
                        sources.forEach(p -> cmd.add(p.toString()));
                    }
                    return cmd;
                });
            }
        } catch (RuntimeException e) {
            // AOT is an accelerator, never a dependency — any surprise here must not fail a compile.
        }
        return List.of();
    }

    // ---- kotlinc worker -------------------------------------------------------------------

    /**
     * JVM flags to prepend to the kotlinc worker's {@code java} spawn: {@code -XX:AOTCache=…} when a
     * cache exists for (host JDK, effective GC, worker classpath), else empty — kicking off the
     * caller-supplied trainer in the background when the host qualifies. The classpath is part of
     * the key because the Kotlin compiler IS the worker's app classpath: a Kotlin-version bump must
     * retrain. Never blocks, never throws.
     */
    public static List<String> kotlincFlags(Path javaHome, String workerClasspath, TrainerCommand trainer) {
        if (!enabled() || javaHome == null) return List.of();
        try {
            JdkId id = jdkId(javaHome);
            if (id == null) return List.of();
            String gc = effectiveGc(JvmOptions.batchFlags(1)); // must match javaCommand, the actual spawn
            Path cache = dir().resolve("kotlinc-" + key(id, gc, workerClasspath) + ".aot");
            if (Files.exists(cache)) {
                touch(cache); // retention is by last use; the JVM mapping a cache never updates mtime
                return List.of("-XX:AOTCache=" + cache, "-Xlog:aot=off");
            }
            if (eligible(id) && !Files.exists(noaotMarker(cache))) {
                trainAsync("kotlinc worker (" + id.vendor() + " " + id.version() + ")", cache, trainer);
            }
        } catch (RuntimeException e) {
            // Same contract as javacFlags: never let the accelerator fail the build.
        }
        return List.of();
    }

    // ---- keying ---------------------------------------------------------------------------

    /** What the release file says a JDK is; {@code null} when it can't be read. */
    record JdkId(Path home, JdkVendor vendor, String version) {}

    static JdkId jdkId(Path javaHome) {
        Path release = javaHome.resolve("release");
        if (!Files.isRegularFile(release)) return null;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        String version = unquote(props.getProperty("JAVA_VERSION", ""));
        if (version.isEmpty()) return null;
        return new JdkId(javaHome.toAbsolutePath().normalize(), JdkVendor.fromProperties(props), version);
    }

    private static String unquote(String v) {
        String s = v.trim();
        return (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : s;
    }

    /** HotSpot 25+ records mappable caches; Graal hosts and older JDKs never train. */
    static boolean eligible(JdkId id) {
        if (id.vendor() == JdkVendor.ORACLE_GRAALVM || id.vendor() == JdkVendor.GRAALVM_CE) return false;
        try {
            int feature = Integer.parseInt(id.version().split("[.+-]")[0]);
            return feature >= 25;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * The GC selection visible in a flag list ({@code -J}-prefixed or bare), lowercased — {@code
     * "zgc"}, {@code "g1gc"}, {@code "serialgc"}, … — or {@code "default"} when no {@code
     * -XX:+Use*GC} flag is present. Part of the cache key: AOT code is compiled against the
     * training GC's barriers, and a mismatch maps nothing.
     */
    static String effectiveGc(List<String> flags) {
        for (String f : flags) {
            String bare = f.startsWith("-J") ? f.substring(2) : f;
            if (bare.startsWith("-XX:+Use") && bare.endsWith("GC")) {
                return bare.substring("-XX:+Use".length()).toLowerCase(Locale.ROOT);
            }
        }
        return "default";
    }

    static String key(JdkId id, String gc, String extra) {
        return Hashing.sha256Hex(id.home() + "|" + id.vendor().name() + "|" + id.version() + "|" + gc + "|" + extra)
                .substring(0, 16);
    }

    // ---- training -------------------------------------------------------------------------

    /**
     * Kick off one background training run for {@code cache}, claim-guarded twice over: an in-JVM
     * set (this engine) and a sibling {@code .training} claim file (other processes; stale claims
     * from a crashed trainer are reclaimed after {@link #CLAIM_STALE_MILLIS}). The trainer records
     * to a temp sibling and the assembled cache is atomically renamed into place, so readers only
     * ever see a complete file. Trainer output is discarded — success is the cache appearing, and
     * both outcomes get an engine-log line.
     */
    static void trainAsync(String what, Path cache, TrainerCommand trainer) {
        if (trainer == null || !TRAINING.add(cache)) return;
        Path claim = cache.resolveSibling(cache.getFileName() + ".training");
        try {
            Files.createDirectories(cache.getParent());
            if (!claimed(claim)) {
                TRAINING.remove(cache);
                return;
            }
        } catch (IOException e) {
            TRAINING.remove(cache);
            return;
        }
        Thread t = new Thread(() -> runTrainer(what, cache, claim, trainer), "jk-worker-aot-train");
        t.setDaemon(true);
        t.start();
    }

    /** Atomically create the claim file; a fresh existing claim loses, a stale one is replaced. */
    private static boolean claimed(Path claim) throws IOException {
        try {
            Files.createFile(claim);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            try {
                long age = System.currentTimeMillis()
                        - Files.getLastModifiedTime(claim).toMillis();
                if (age < CLAIM_STALE_MILLIS) return false;
                Files.deleteIfExists(claim);
                Files.createFile(claim);
                return true;
            } catch (IOException race) {
                return false; // someone else won the reclaim race — their trainer serves us both
            }
        }
    }

    private static void runTrainer(String what, Path cache, Path claim, TrainerCommand trainer) {
        Path scratch = null;
        Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        try {
            scratch = Files.createTempDirectory("jk-worker-aot-");
            Files.createDirectories(scratch.resolve("out"));
            List<String> cmd = trainer.build(tmp, scratch);
            Process p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            System.err.println("jk engine: AOT-training " + what + " in the background (pid " + p.pid() + ")");
            if (!p.waitFor(TRAINING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                markNoAot(cache); // don't re-attempt on every subsequent compile
                System.err.println("jk engine: AOT training for " + what + " overran; killed");
                return;
            }
            if (p.exitValue() == 0 && Files.exists(tmp)) {
                Files.move(tmp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                sweepTool(cache);
                System.err.println("jk engine: AOT cache ready for " + what + " (" + cache.getFileName() + ")");
            } else {
                markNoAot(cache); // sticky per key — a JDK/Kotlin/GC bump mints a new key and retries
                System.err.println(
                        "jk engine: AOT training for " + what + " produced no cache (exit " + p.exitValue() + ")");
            }
        } catch (IOException e) {
            System.err.println("jk engine: AOT training for " + what + " skipped: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            deleteQuietly(tmp);
            deleteQuietly(tmp.resolveSibling(tmp.getFileName() + ".config")); // interrupted recording
            deleteQuietly(claim);
            if (scratch != null) deleteRecursivelyQuietly(scratch);
            TRAINING.remove(cache);
        }
    }

    /**
     * Retention sweep for one tool's caches, run at publish time. Keeps the {@link #KEEP_PER_TOOL}
     * most-recently-used caches (use = {@link #touch} at flag hand-out) and drops the rest, plus
     * anything — cache or orphaned {@code .noaot} failure marker — untouched for
     * {@link #UNUSED_TTL_MILLIS}. Several keys are legitimately live at once (different toolchain
     * JDKs, Kotlin versions, GC pins); expiring a stale {@code .noaot} also gives a once-failed key
     * a fresh training attempt. Engine caches ({@code engine-<version>-…}) share this directory but
     * are version-lifecycle-owned (EngineClient sweep + VersionStore.prune) — never touched here.
     */
    private static void sweepTool(Path cache) {
        String tool = cache.getFileName().toString();
        tool = tool.substring(0, tool.indexOf('-') + 1); // "javac-" / "kotlinc-"
        long now = System.currentTimeMillis();
        List<Path> primaries = new ArrayList<>();
        List<Path> markers = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(cache.getParent(), tool + "*")) {
            for (Path p : entries) {
                String n = p.getFileName().toString();
                if (n.endsWith(".aot")) primaries.add(p);
                else if (n.endsWith(".aot.noaot")) markers.add(p);
            }
        } catch (IOException ignored) {
            return; // opportunistic: a leftover cache costs disk, not correctness
        }
        primaries.sort(java.util.Comparator.comparingLong(WorkerAot::mtime).reversed());
        for (int i = 0; i < primaries.size(); i++) {
            Path p = primaries.get(i);
            if (p.equals(cache)) continue; // the cache that just landed always survives
            if (i >= KEEP_PER_TOOL || now - mtime(p) > UNUSED_TTL_MILLIS) {
                deleteQuietly(p);
                deleteQuietly(noaotMarker(p));
                deleteQuietly(p.resolveSibling(p.getFileName() + ".config"));
            }
        }
        for (Path m : markers) {
            Path primary = m.resolveSibling(
                    m.getFileName().toString().substring(0, m.getFileName().toString().length() - ".noaot".length()));
            if (!Files.exists(primary) && now - mtime(m) > UNUSED_TTL_MILLIS) deleteQuietly(m);
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0; // unreadable sorts oldest — first in line to be reclaimed
        }
    }

    private static void touch(Path p) {
        try {
            Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // best-effort; worst case the cache looks colder than it is
        }
    }

    /** Sticky "training failed for this key" marker — swept with its cache by {@link #sweepSiblings}. */
    static Path noaotMarker(Path cache) {
        return cache.resolveSibling(cache.getFileName() + ".noaot");
    }

    private static void markNoAot(Path cache) {
        try {
            Files.createFile(noaotMarker(cache));
        } catch (IOException ignored) {
            // best-effort; worst case the next compile retries training
        }
    }

    // ---- javac training corpus --------------------------------------------------------------

    /**
     * A synthetic-but-representative corpus: sealed hierarchies, records, pattern switches,
     * generics, lambdas, and stream pipelines exercise javac's attribution/flow/lowering hot paths.
     * Measured to generalize — the profile recorded here accelerated compiles of unrelated sources
     * by the same factor as in-corpus files.
     */
    private static Path writeJavacCorpus(Path scratch) throws IOException {
        Path dir = Files.createDirectories(scratch.resolve("corpus"));
        for (int i = 0; i < 24; i++) {
            Files.writeString(
                    dir.resolve("C" + i + ".java"),
                    """
                    package demo;
                    import java.util.*;
                    import java.util.function.*;
                    import java.util.stream.*;

                    public class C%1$d {
                        sealed interface Shape%1$d permits Circle%1$d, Square%1$d {}
                        record Circle%1$d(double r) implements Shape%1$d {}
                        record Square%1$d(double s) implements Shape%1$d {}

                        static <T extends Comparable<T>> Optional<T> maxOf(List<T> xs) {
                            return xs.stream().max(Comparator.naturalOrder());
                        }

                        static double area(Shape%1$d s) {
                            return switch (s) {
                                case Circle%1$d c -> Math.PI * c.r() * c.r();
                                case Square%1$d q -> q.s() * q.s();
                            };
                        }

                        public static Map<String, List<Integer>> group(Collection<Integer> in) {
                            return in.stream()
                                    .map(x -> x * %2$d)
                                    .filter(x -> x %% 3 != 0)
                                    .collect(Collectors.groupingBy(
                                            x -> x %% 2 == 0 ? "even" : "odd", Collectors.toList()));
                        }

                        @FunctionalInterface
                        interface Tri<A, B, C_, R> { R apply(A a, B b, C_ c); }

                        static final Tri<Integer, Integer, Integer, Integer> SUM = (a, b, c) -> a + b + c;

                        public static void main(String[] args) {
                            var shapes = List.<Shape%1$d>of(new Circle%1$d(2), new Square%1$d(3));
                            shapes.forEach(s -> System.out.println(area(s)));
                            System.out.println(group(List.of(1, 2, 3, 4, 5)));
                            System.out.println(maxOf(new ArrayList<>(List.of(1, %1$d))).orElse(0)
                                    + SUM.apply(1, 2, 3));
                        }
                    }
                    """
                            .formatted(i, i + 1));
        }
        return dir;
    }

    // ---- small helpers ----------------------------------------------------------------------

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(WorkerAot::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
