// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.discovery.JkProbe;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.discovery.LocalToolProbe;
import dev.jkbuild.discovery.Probes;
import dev.jkbuild.util.JkThreads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Flat view of every JDK on the host. Backed by the {@link Probes}
 * default chain, so JDKs from {@code $JAVA_HOME}, the IntelliJ JDK dir
 * ({@code ~/.jdks/}), SDKMAN, JBang, mise, asdf, jenv, Homebrew, and the
 * system package-manager locations all show up as equal peers.
 *
 * <p>When a single install appears under multiple probes (e.g.
 * {@code $JAVA_HOME} pointing at an SDKMAN install), the first probe in
 * the chain wins — the dedup key is the canonical real path of the
 * install's {@code home}.
 *
 * <p>{@link #jdksRoot()} remains the directory {@code jk jdk install}
 * writes new downloads into — by default the IntelliJ JDK dir. Removal
 * via {@link #remove} is restricted to installs that live under that
 * root; JDKs surfaced by external probes (SDKMAN etc.) are read-only
 * from {@code jk}'s perspective.
 */
public final class JdkRegistry {

    private final Path jdksRoot;
    private final List<LocalToolProbe> probes;

    /** Production: jk's own JDK dir as the write target + the default probe chain. */
    public JdkRegistry() {
        this(JkDirs.jdks(), Probes.defaultChain());
    }

    /**
     * Test-friendly: walk only the given {@code jdksRoot} (via a single
     * {@link JkProbe}). External-tool probes (SDKMAN, mise, IntelliJ, system)
     * are not consulted, so a test pointing at a {@code @TempDir} sees only
     * what the test placed there.
     */
    public JdkRegistry(Path jdksRoot) {
        this(jdksRoot, List.of(new JkProbe(jdksRoot)));
    }

    /** Full control — both the write target and the probe chain. */
    public JdkRegistry(Path jdksRoot, List<LocalToolProbe> probes) {
        this.jdksRoot = Objects.requireNonNull(jdksRoot, "jdksRoot");
        this.probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
    }

    /** The directory {@code jk jdk install} writes new downloads into. */
    public Path jdksRoot() {
        return jdksRoot;
    }

    /**
     * Every JDK the probe chain finds, deduplicated by canonical home path
     * (first probe wins). Listed in probe-chain order.
     */
    public List<InstalledJdk> list() throws IOException {
        List<InstalledJdk> result = new ArrayList<>();
        for (JdkHit hit : listHits()) {
            result.add(new InstalledJdk(identifierFor(hit.home()), hit.home()));
        }
        return result;
    }

    /**
     * Richer view of {@link #list()} — keeps the resolved {@link JdkVendor}
     * and probe source alongside each install.
     *
     * <p>Probes run concurrently on {@link JkThreads#io()} (each probe is
     * essentially a {@code Files.list} + per-candidate release-file parse —
     * IO-bound). Final ordering matches the probe chain's declared order
     * (first-source-wins on dedup), not arrival order, so results are
     * deterministic regardless of timing.
     */
    public List<JdkHit> listHits() {
        // Dispatch all probes at once.
        List<CompletableFuture<List<JdkHit>>> futures = new ArrayList<>(probes.size());
        for (LocalToolProbe probe : probes) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return probe.discoverAllJdks();
                } catch (IOException e) {
                    return List.<JdkHit>of();
                }
            }, JkThreads.io()));
        }
        // Walk the futures in probe-chain order so dedup picks the same
        // "winner" every run regardless of which future finished first.
        Map<Path, JdkHit> hits = new LinkedHashMap<>();
        for (CompletableFuture<List<JdkHit>> f : futures) {
            for (JdkHit hit : f.join()) {
                if (!isSupportedHit(hit)) continue;
                hits.putIfAbsent(hit.home(), hit);
            }
        }
        return new ArrayList<>(hits.values());
    }

    /**
     * Drop installs below the supported floor ({@link SupportedJdk#MIN_MAJOR}).
     * Hits whose {@code release} file is unreadable / lacks a parseable
     * version are kept on the benefit of the doubt — they may be valid
     * installs we can't classify, and silently hiding them would be worse
     * than letting the user see an unknown row in {@code jk jdk list}.
     */
    private static boolean isSupportedHit(JdkHit hit) {
        Integer m = majorOfVersion(hit.version());
        if (m == null) return true;
        return SupportedJdk.isSupported(m);
    }

    private static Integer majorOfVersion(String version) {
        if (version == null || version.isEmpty()) return null;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        if (end == 0) return null;
        try {
            int n = Integer.parseInt(version.substring(0, end));
            // Legacy "1.x" → x is the real major (only matters for inputs we'd
            // reject anyway, but classify them honestly).
            if (n != 1 || end == version.length()) return n;
            int i = end + 1;
            int j = i;
            while (j < version.length() && Character.isDigit(version.charAt(j))) j++;
            if (j == i) return n;
            return Integer.parseInt(version.substring(i, j));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Optional<InstalledJdk> find(String identifier) throws IOException {
        return list().stream()
                .filter(jdk -> jdk.identifier().equals(identifier))
                .findFirst();
    }

    public Optional<InstalledJdk> findByPrefix(String prefix) throws IOException {
        return list().stream()
                .filter(jdk -> jdk.identifier().startsWith(prefix))
                .findFirst();
    }

    /**
     * Spec-driven lookup: parses inputs like {@code 25}, {@code temurin-25},
     * {@code corretto-25.0.3}, or {@code 26.0.1-librca} into
     * {@code (major, exactVersion?, hints[])} (via
     * {@link JdkSelector#parseFlexible(String)}) and returns the first
     * installed JDK whose version + vendor metadata satisfies every
     * constraint. "First" is probe-chain order — the same ordering
     * {@link #list()} uses — so callers get a deterministic
     * "natural precedence" pick.
     */
    public Optional<InstalledJdk> findBySpec(String spec) {
        return findHitBySpec(spec)
                .map(hit -> new InstalledJdk(identifierFor(hit.home()), hit.home()));
    }

    /**
     * Same matcher as {@link #findBySpec}, but returns the raw {@link JdkHit}
     * so callers can read vendor + version metadata for display purposes
     * without re-parsing the install's {@code release} file.
     */
    public Optional<JdkHit> findHitBySpec(String spec) {
        return findHitBySpec(spec, null);
    }

    /**
     * Source-scoped variant of {@link #findHitBySpec(String)} — only considers
     * hits whose {@link JdkHit#source()} matches {@code sourceFilter}. Pass
     * {@code null} or empty to disable the source filter. Used by
     * {@code jk jdk uninstall} where the user must qualify which probe's
     * copy of a JDK to remove (e.g. {@code intellij/temurin-26.0.1} vs
     * {@code sdkman/25.0.3-tem}).
     */
    public Optional<JdkHit> findHitBySpec(String spec, String sourceFilter) {
        if (spec == null || spec.isBlank()) return Optional.empty();
        JdkSelector.FlexibleQuery query = JdkSelector.parseFlexible(spec);
        for (JdkHit hit : listHits()) {
            if (sourceFilter != null && !sourceFilter.isEmpty()
                    && !sourceFilter.equals(hit.source())) {
                continue;
            }
            if (matchesSpec(hit, query)) return Optional.of(hit);
        }
        return Optional.empty();
    }

    /**
     * Delete an install regardless of where it lives. Used by
     * {@code jk jdk uninstall <source>/<spec>}, where the user has
     * explicitly qualified which copy to remove — so the
     * "external installs are read-only" guard {@link #remove} applies no
     * longer fits. {@link IntellijJdkDir#installDirOf} still handles the
     * macOS {@code Contents/Home} unwrap. Returns {@code true} when the
     * directory existed and was deleted.
     */
    public boolean purge(InstalledJdk jdk) throws IOException {
        Objects.requireNonNull(jdk, "jdk");
        Path installDir = IntellijJdkDir.installDirOf(jdk.home());
        if (!Files.exists(installDir)) return false;
        deleteRecursively(installDir);
        return true;
    }

    private static boolean matchesSpec(JdkHit hit, JdkSelector.FlexibleQuery query) {
        if (query.major().isPresent()) {
            Integer hitMajor = majorOf(hit.version());
            if (hitMajor == null || !hitMajor.equals(query.major().get())) return false;
        }
        if (query.exactVersion().isPresent()) {
            if (hit.version() == null) return false;
            String exact = query.exactVersion().get();
            if (!hit.version().equals(exact) && !hit.version().startsWith(exact + ".")
                    && !hit.version().startsWith(exact + "-")
                    && !hit.version().startsWith(exact + "+")) {
                return false;
            }
        }
        if (!query.hints().isEmpty()) {
            String haystack = hintHaystack(hit.vendor());
            for (String hint : query.hints()) {
                if (!haystack.contains(hint.toLowerCase(java.util.Locale.ROOT))) return false;
            }
        }
        return true;
    }

    private static Integer majorOf(String version) {
        if (version == null || version.isEmpty()) return null;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        if (end == 0) return null;
        try { return Integer.parseInt(version.substring(0, end)); }
        catch (NumberFormatException e) { return null; }
    }

    private static String hintHaystack(JdkVendor v) {
        StringBuilder sb = new StringBuilder();
        sb.append(v.vendor().toLowerCase(java.util.Locale.ROOT)).append(' ');
        sb.append(v.product().toLowerCase(java.util.Locale.ROOT)).append(' ');
        v.jbPrefix().ifPresent(p -> sb.append(p.toLowerCase(java.util.Locale.ROOT)).append(' '));
        v.sdkmanSuffix().ifPresent(s -> sb.append(s.toLowerCase(java.util.Locale.ROOT)).append(' '));
        v.foojayDistro().ifPresent(f -> sb.append(f.toLowerCase(java.util.Locale.ROOT)).append(' '));
        return sb.toString();
    }

    /**
     * Delete the install named {@code identifier}, but only when it lives
     * under {@link #jdksRoot()} — externally-managed JDKs (SDKMAN, mise,
     * system packages, …) are read-only from {@code jk}'s perspective and
     * yield {@code false}.
     */
    public boolean remove(String identifier) throws IOException {
        Optional<InstalledJdk> match = find(identifier);
        if (match.isEmpty()) return false;
        Path home = match.get().home();
        Path installDir = IntellijJdkDir.installDirOf(home);
        // `installDir` is canonical (ProbeSupport.discoverJdk applies
        // toRealPath()). `jdksRoot` may be a symlink path — e.g. macOS
        // /var/folders/... → /private/var/folders/..., or a user's
        // ~/.jk/jdks if their HOME is itself symlinked. Canonicalise it
        // when it exists so the containment check actually works.
        Path canonicalRoot = Files.exists(jdksRoot) ? jdksRoot.toRealPath() : jdksRoot;
        if (!installDir.startsWith(canonicalRoot)) {
            // Not jk-managed; refuse to touch it.
            return false;
        }
        deleteRecursively(installDir);
        return true;
    }

    /**
     * Compute the identifier for a discovered JDK. For installs under
     * {@link #jdksRoot()} this is the install-folder name (matches what
     * the JetBrains catalog publishes). For external installs it's the
     * basename of the install dir (the home itself, or its grandparent on
     * macOS where the real install dir wraps {@code Contents/Home}).
     */
    public static String identifierFor(Path home) {
        return IntellijJdkDir.installDirOf(home).getFileName().toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
