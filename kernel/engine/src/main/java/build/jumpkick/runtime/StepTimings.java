// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.config.EnvValues;
import build.jumpkick.config.TomlValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * A learned ledger of how long each module's build steps actually take, so the progress bar can
 * weight steps by real wall-clock instead of static guesses.
 *
 * <p>Everything is kept in the bar's existing <em>weight</em> unit (≈150 ms each), and only the
 * <em>per-unit rate</em> is learned — the per-step fixed floor stays static in {@link
 * EffortWeights}. So a step's expected weight is {@code floor + perUnit × count}, where {@code
 * count} is the cheap up-front signal (lexical test count, incremental changed-source count).
 * Learning a rate rather than an absolute makes the estimate resilient to churn: add/remove tests
 * and the count rescales it; per-unit drift is smoothed by an EWMA. With no history a step has no
 * entry here and the caller falls back to the static Step-1 estimate, so <em>cold == Step 1</em>.
 *
 * <p>Each entry carries the wall-clock millis it was last updated, so a {@code jk} GC cycle can
 * {@link #prune} it: entries older than the configured max age are evicted, and if the file still
 * exceeds the size cap the oldest entries go until it fits — a project you stopped building doesn't
 * bloat a file read on every build.
 *
 * <p>Stored as TOML ({@code <cache>/timings.toml}, an array of {@code [[timing]]} tables) — jk
 * bundles a TOML parser and hand-writes TOML for the lockfile, so this adds no new dependency.
 * Reads (memoized per cache root) happen at build start; the single {@link #record} at build end
 * does a load-update-write, so concurrent module builds never race a write.
 */
public final class StepTimings {

    /**
     * EWMA recency: recent-weighted but smoothed, so one GC pause / cold-disk run can't wreck a rate.
     */
    public static final double DEFAULT_ALPHA = 0.4;

    private static final ConcurrentHashMap<Path, StepTimings> MEMO = new ConcurrentHashMap<>();

    /** A learned step rate plus when it was last refreshed (epoch millis; 0 = unknown/legacy). */
    private record Entry(double perUnit, long updatedMillis) {}

    /** key = {@code dir step} → learned rate + timestamp. */
    private final Map<String, Entry> entries;

    private StepTimings(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /**
     * Read-only ledger for {@code cache}, memoized for the process. Missing/unreadable → empty
     * (cold).
     */
    public static StepTimings load(Path cache) {
        return MEMO.computeIfAbsent(cache, StepTimings::read);
    }

    /** True when nothing has been learned yet (cold) — the caller can't show a trustworthy ETA. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * True when this ledger has at least one learned rate for any of {@code dirs} — i.e. the project
     * has useful timings, so a countdown ETA is trustworthy (vs. a cold project, whose estimate is a
     * static guess and should count up instead).
     */
    public boolean hasTimingsFor(java.util.Collection<String> dirs) {
        for (String d : dirs) {
            String prefix = d + ' ';
            for (String k : entries.keySet()) {
                if (k.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    /**
     * The learned per-unit weight for a module's step, or empty when unseen (cold → caller's
     * fallback).
     */
    public OptionalDouble perUnit(String dir, String step) {
        Entry e = entries.get(key(dir, step));
        return e == null ? OptionalDouble.empty() : OptionalDouble.of(e.perUnit());
    }

    /**
     * The median learned per-unit rate across <em>all</em> modules for {@code step} — a cross-module
     * fallback so a module never seen before borrows this machine's typical rate for that step (e.g.
     * ~per-test wall-clock) instead of the static Step-1 constant, which is calibrated for the worst
     * case and runs ~10× hot for fast unit suites. Empty only when no module has ever recorded this
     * step, in which case the caller falls back to the static estimate. Median (not mean) so one
     * pathological module can't skew it.
     */
    public OptionalDouble medianPerUnit(String step) {
        String suffix = ' ' + step;
        double[] rates = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                .mapToDouble(e -> e.getValue().perUnit())
                .sorted()
                .toArray();
        if (rates.length == 0) return OptionalDouble.empty();
        int n = rates.length;
        return OptionalDouble.of(n % 2 == 1 ? rates[n / 2] : (rates[n / 2 - 1] + rates[n / 2]) / 2.0);
    }

    /**
     * The median learned per-unit rate for {@code step} across just {@code dirs} — the modules of one
     * project/workspace. A tighter prior than the whole-host {@link #medianPerUnit(String)} for a
     * not-yet-built module, since sibling modules share frameworks, fixtures, and setup cost. Empty
     * when {@code dirs} is null/empty or none of them has recorded this step (caller falls to the
     * host median). Median (not mean) so one pathological sibling can't skew it.
     */
    public OptionalDouble medianPerUnit(String step, java.util.Collection<String> dirs) {
        if (dirs == null || dirs.isEmpty()) return OptionalDouble.empty();
        double[] rates = dirs.stream()
                .distinct()
                .map(d -> entries.get(key(d, step)))
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Entry::perUnit)
                .sorted()
                .toArray();
        if (rates.length == 0) return OptionalDouble.empty();
        int n = rates.length;
        return OptionalDouble.of(n % 2 == 1 ? rates[n / 2] : (rates[n / 2 - 1] + rates[n / 2]) / 2.0);
    }

    /** A measured per-unit rate for one step of one module, to fold into the ledger. */
    public record Sample(String dir, String step, double observedPerUnit) {}

    /**
     * Fold this build's samples into the on-disk ledger with an EWMA and persist — a single
     * load-update-write at build end, stamping each touched entry with {@code nowMillis}. A brand-new
     * (dir, step) seeds at its observed value. Best-effort: any IO failure is swallowed (timings are
     * advisory).
     */
    public static void record(Path cache, List<Sample> samples, double alpha, long nowMillis) {
        if (samples == null || samples.isEmpty()) return;
        Map<String, Entry> m = new HashMap<>(read(cache).entries);
        for (Sample s : samples) {
            if (s.observedPerUnit() < 0) continue;
            String k = key(s.dir(), s.step());
            Entry prev = m.get(k);
            double next =
                    prev == null ? s.observedPerUnit() : alpha * s.observedPerUnit() + (1 - alpha) * prev.perUnit();
            m.put(k, new Entry(next, nowMillis));
        }
        try {
            write(cache.resolve("timings.toml"), m);
            MEMO.remove(cache); // next load() in this process sees the update
        } catch (IOException | RuntimeException ignored) {
            // advisory cache — never fail the build over it
        }
    }

    // --- GC -----------------------------------------------------------------

    /** Bounds for {@link #prune}: a byte ceiling and a max entry age. */
    public record Limits(long maxBytes, long maxAgeMillis) {
        static final long DEFAULT_MAX_MB = 100;
        static final long DEFAULT_MAX_AGE_DAYS = 730; // 2 years

        /**
         * Resolve from {@code JK_TIMINGS_MAX_SIZE_MB} / {@code JK_TIMINGS_MAX_AGE_DAYS} env vars, else
         * the {@code [cache] timings-max-size-mb} / {@code timings-max-age-days} keys in the user
         * config, else the defaults (100 MB / 2 years).
         */
        public static Limits resolve(Path userConfig, Function<String, String> env) {
            long mb = envLong(env, "JK_TIMINGS_MAX_SIZE_MB")
                    .orElseGet(() -> tomlLong(userConfig, "timings-max-size-mb").orElse(DEFAULT_MAX_MB));
            long days = envLong(env, "JK_TIMINGS_MAX_AGE_DAYS")
                    .orElseGet(
                            () -> tomlLong(userConfig, "timings-max-age-days").orElse(DEFAULT_MAX_AGE_DAYS));
            return new Limits(Math.max(0, mb) * 1024L * 1024L, Math.max(0, days) * 86_400_000L);
        }
    }

    /** What a {@link #prune} pass evicted. */
    public record PruneReport(int evictedByAge, int evictedBySize, int kept, long finalBytes) {
        static final PruneReport EMPTY = new PruneReport(0, 0, 0, 0);
    }

    /**
     * Evict stale/overflowing entries: first anything older than {@code maxAge} (skipping unknown-age
     * legacy entries), then — if the rendered file still exceeds {@code maxBytes} — the oldest
     * entries until it fits (uniform average-bytes-per-entry × kept ≤ max). Rewrites the file unless
     * {@code dryRun}.
     */
    public static PruneReport prune(Path cache, Limits limits, long nowMillis, boolean dryRun) {
        Path file = cache.resolve("timings.toml");
        if (!Files.isRegularFile(file)) return PruneReport.EMPTY;
        Map<String, Entry> m = new HashMap<>(read(cache).entries);
        int total = m.size();

        int byAge = 0;
        if (limits.maxAgeMillis() > 0) {
            var it = m.entrySet().iterator();
            while (it.hasNext()) {
                Entry e = it.next().getValue();
                if (e.updatedMillis() > 0 && nowMillis - e.updatedMillis() > limits.maxAgeMillis()) {
                    it.remove();
                    byAge++;
                }
            }
        }

        int bySize = 0;
        long bytes = renderedBytes(m);
        if (limits.maxBytes() > 0 && bytes > limits.maxBytes() && !m.isEmpty()) {
            long avg = Math.max(1, bytes / m.size());
            int keep = (int) Math.min(m.size(), Math.max(0, limits.maxBytes() / avg));
            if (keep < m.size()) {
                // Drop the oldest (smallest updatedMillis) first.
                List<Map.Entry<String, Entry>> sorted = new ArrayList<>(m.entrySet());
                sorted.sort(
                        java.util.Comparator.comparingLong(en -> en.getValue().updatedMillis()));
                for (int i = 0; i < sorted.size() - keep; i++) {
                    m.remove(sorted.get(i).getKey());
                    bySize++;
                }
            }
        }

        if ((byAge > 0 || bySize > 0) && !dryRun) {
            try {
                if (m.isEmpty()) Files.deleteIfExists(file);
                else write(file, m);
                MEMO.remove(cache);
            } catch (IOException | RuntimeException ignored) {
                // advisory — leave the file as-is on failure
            }
        }
        return new PruneReport(byAge, bySize, m.size(), renderedBytes(m));
    }

    // --- IO -----------------------------------------------------------------

    private static StepTimings read(Path cache) {
        Map<String, Entry> m = new HashMap<>();
        Path f = cache.resolve("timings.toml");
        try {
            if (Files.isRegularFile(f)) {
                TomlParseResult toml = Toml.parse(f);
                TomlArray arr = toml.getArray("timing");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        TomlTable e = arr.getTable(i);
                        String dir = e.getString("dir");
                        String step = e.getString("step");
                        Double pu = e.contains("per-unit-weight") ? e.getDouble("per-unit-weight") : null;
                        long updated =
                                e.contains("updated") && e.getLong("updated") != null ? e.getLong("updated") : 0L;
                        if (dir != null && step != null && pu != null && pu >= 0) {
                            m.put(key(dir, step), new Entry(pu, updated));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // unreadable/corrupt ledger → treat as cold
        }
        return new StepTimings(m);
    }

    private static String render(Map<String, Entry> m) {
        StringBuilder out = new StringBuilder(256);
        m.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            int sep = e.getKey().indexOf(' ');
            String dir = e.getKey().substring(0, sep);
            String step = e.getKey().substring(sep + 1);
            out.append("[[timing]]\n")
                    .append("dir             = ")
                    .append(quote(dir))
                    .append('\n')
                    .append("step           = ")
                    .append(quote(step))
                    .append('\n')
                    .append("per-unit-weight = ")
                    .append(round3(e.getValue().perUnit()))
                    .append('\n')
                    .append("updated         = ")
                    .append(e.getValue().updatedMillis())
                    .append('\n')
                    .append('\n');
        });
        return out.toString();
    }

    private static long renderedBytes(Map<String, Entry> m) {
        return render(m).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void write(Path file, Map<String, Entry> m) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".timings-", ".tmp");
        try {
            Files.writeString(tmp, render(m), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static OptionalLong envLong(Function<String, String> env, String name) {
        return toOptionalLong(EnvValues.longValue(env, name));
    }

    private static OptionalLong tomlLong(Path userConfig, String key) {
        // The [cache] table of the user-global ~/.jk/config.toml; missing/malformed → empty.
        Optional<Long> v = TomlValues.parse(userConfig)
                .map(toml -> toml.getTable("cache"))
                .flatMap(cache -> TomlValues.optLong(cache, key));
        return toOptionalLong(v);
    }

    private static OptionalLong toOptionalLong(Optional<Long> v) {
        return v.isPresent() ? OptionalLong.of(v.get()) : OptionalLong.empty();
    }

    private static String key(String dir, String step) {
        return dir + ' ' + step;
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Test seam: drop the per-process load memo so a freshly-written file is re-read. */
    static void clearMemo() {
        MEMO.clear();
    }
}
