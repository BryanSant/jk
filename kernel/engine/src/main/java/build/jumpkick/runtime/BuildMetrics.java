// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.config.EnvValues;
import build.jumpkick.config.TomlValues;
import build.jumpkick.util.JkDirs;
import build.jumpkick.util.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Persistent running aggregates for every build-related job the engine has run on this machine:
 * invocation count, total wall-clock, and min/max (avg derived) — kept in four tiers: engine-wide,
 * per project, per step, and per project per step. A plugin executes as its worker jar wrapped in
 * an ordinary step, so the step tier is also the per-worker/per-plugin tier.
 *
 * <p>Successful, failed, and cancelled runs each get their own {@link Stats} block: every
 * invocation is counted, but a failed build's duration never pollutes the success numbers the
 * estimator learns from ({@link EffortWeights} and {@code BuildService} read only {@link
 * Entry#ok()}, matching how {@link StepTimings}/{@link Calibration} learn only on success).
 *
 * <p>Unlike the per-unit rates in {@code <cache>/timings.toml}, this is machine history — like
 * {@code calibration.toml} it lives in the state dir ({@code ~/.jk/state/builds/metrics.json}) and
 * survives {@code jk clean}, which is what makes it a useful cold-start prior after the cache is
 * wiped. Stored as JSON via {@link MiniJson} (the engine's single JSON home); reads are memoized
 * per file, the single {@link #record} at request-finish does a locked load-update-write with an
 * atomic replace, and every write path is best-effort — metrics are advisory and never fail a
 * build.
 */
public final class BuildMetrics {

    /** The current on-disk schema version. */
    public static final int SCHEMA = 1;

    /** In-memory key separator; never appears in a sane path or step name. */
    private static final char SEP = '\n';

    private static final ConcurrentHashMap<Path, BuildMetrics> MEMO = new ConcurrentHashMap<>();

    /** Serializes concurrent request-finish folds within this engine process. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    /** Running aggregate of one outcome bucket: count, total, and the observed extremes. */
    public record Stats(long count, long totalMillis, long minMillis, long maxMillis) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0);

        /** Mean duration, or 0 when nothing has been recorded. */
        public long avgMillis() {
            return count == 0 ? 0 : totalMillis / count;
        }

        /** This bucket with one more sample folded in. */
        Stats plus(long millis) {
            long m = Math.max(0, millis);
            return count == 0
                    ? new Stats(1, m, m, m)
                    : new Stats(count + 1, totalMillis + m, Math.min(minMillis, m), Math.max(maxMillis, m));
        }
    }

    /**
     * One aggregate row. Invocation rows carry {@code kind} ({@code build}/{@code test}) and a null
     * {@code step}; step rows carry {@code step} and a null {@code kind}. {@code dir} is the
     * primary project key ({@code ""} = the global tier); {@code coord} is a display label only.
     */
    public record Entry(
            String kind,
            String dir,
            String coord,
            String step,
            Stats ok,
            Stats failed,
            Stats cancelled,
            long updatedMillis) {}

    /** One step's outcome within a finished run; {@code status} is a {@code StepStatus} name. */
    public record StepSample(String dir, String step, String status, long millis) {}

    /** What the engine maps a finished build record into — the store's only input shape. */
    public record Outcome(
            String kind,
            String dir,
            String coord,
            boolean success,
            boolean cancelled,
            long millis,
            List<StepSample> steps) {
        public Outcome {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** key = kind + SEP + dir (dir "" = global). */
    private final Map<String, Entry> invocations;

    /** key = dir + SEP + step (dir "" = global). */
    private final Map<String, Entry> steps;

    private BuildMetrics(Map<String, Entry> invocations, Map<String, Entry> steps) {
        this.invocations = invocations;
        this.steps = steps;
    }

    /** The default store location: state, beside {@code calibration.toml}, survives {@code jk clean}. */
    public static Path defaultFile() {
        return JkDirs.builds().resolve("metrics.json");
    }

    /** Read-only store for {@code file}, memoized for the process. Missing/unreadable → empty. */
    public static BuildMetrics load(Path file) {
        return MEMO.computeIfAbsent(file, BuildMetrics::read);
    }

    /** True when nothing has been recorded yet. */
    public boolean isEmpty() {
        return invocations.isEmpty() && steps.isEmpty();
    }

    /** The invocation aggregate for {@code (kind, dir)}; {@code dir ""} = the global tier. */
    public Optional<Entry> invocation(String kind, String dir) {
        return Optional.ofNullable(invocations.get(kind + SEP + dir));
    }

    /** The step aggregate for {@code (dir, step)}; {@code dir ""} = the global tier. */
    public Optional<Entry> step(String dir, String step) {
        return Optional.ofNullable(steps.get(dir + SEP + step));
    }

    /** Every row, stable-ordered: invocation rows by (kind, dir), then step rows by (dir, step). */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(invocations.size() + steps.size());
        invocations.values().stream()
                .sorted(Comparator.comparing(Entry::kind).thenComparing(Entry::dir))
                .forEach(out::add);
        steps.values().stream()
                .sorted(Comparator.comparing(Entry::dir).thenComparing(Entry::step))
                .forEach(out::add);
        return out;
    }

    /**
     * Fold one finished run into the on-disk store — a single locked load-update-write at
     * request-finish, stamping every touched row with {@code nowMillis}. Updates the project tier
     * and the global tier together; step samples with status {@code SKIPPED} (or any non-terminal
     * status) teach nothing and are ignored. Best-effort: any failure is swallowed.
     *
     * @return this run's <strong>build number</strong> — the project's total run count (across all
     *     kinds) after this fold, a durable monotonic per-project sequence the journal stamps onto
     *     the record so the dashboard can show {@code #412}. {@code 0} when nothing was recorded (a
     *     malformed outcome, or a swallowed failure) — callers treat 0 as "unnumbered".
     */
    public static long record(Path file, Outcome o, long nowMillis) {
        if (o == null || o.kind() == null || o.dir() == null || o.dir().isEmpty()) return 0;
        LOCK.lock();
        try {
            BuildMetrics cur = read(file);
            Map<String, Entry> inv = new LinkedHashMap<>(cur.invocations);
            Map<String, Entry> ph = new LinkedHashMap<>(cur.steps);

            foldInvocation(inv, o.kind(), o.dir(), o.coord(), o, nowMillis);
            foldInvocation(inv, o.kind(), "", null, o, nowMillis);
            for (StepSample s : o.steps()) {
                if (s.step() == null || s.step().isEmpty()) continue;
                String bucket = bucketOf(s.status());
                if (bucket == null) continue;
                foldStep(ph, s.dir() == null ? o.dir() : s.dir(), s.step(), bucket, s.millis(), nowMillis);
                foldStep(ph, "", s.step(), bucket, s.millis(), nowMillis);
            }

            write(file, inv, ph);
            MEMO.remove(file); // next load() in this process sees the update
            return projectRunCount(inv, o.dir());
        } catch (IOException | RuntimeException ignored) {
            // advisory state — never fail the build over it
            return 0;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * The project's build number: total runs recorded for {@code dir} across every kind after the
     * current fold. Monotonic for a live project — its rows are refreshed on each build, so age
     * eviction spares them — which is what makes it a stable per-project sequence.
     */
    private static long projectRunCount(Map<String, Entry> inv, String dir) {
        long n = 0;
        for (Entry e : inv.values()) {
            if (dir.equals(e.dir())) n += e.ok().count() + e.failed().count() + e.cancelled().count();
        }
        return n;
    }

    private static void foldInvocation(
            Map<String, Entry> inv, String kind, String dir, String coord, Outcome o, long nowMillis) {
        String k = kind + SEP + dir;
        Entry e = inv.getOrDefault(
                k, new Entry(kind, dir, coord, null, Stats.EMPTY, Stats.EMPTY, Stats.EMPTY, nowMillis));
        Stats ok = e.ok(), failed = e.failed(), cancelled = e.cancelled();
        if (o.success()) ok = ok.plus(o.millis());
        else if (o.cancelled()) cancelled = cancelled.plus(o.millis());
        else failed = failed.plus(o.millis());
        // A freshly-learned coord upgrades a row that predates one (label only, never a key).
        String label = coord != null ? coord : e.coord();
        inv.put(k, new Entry(kind, dir, label, null, ok, failed, cancelled, nowMillis));
    }

    private static void foldStep(
            Map<String, Entry> ph, String dir, String step, String bucket, long millis, long nowMillis) {
        String k = dir + SEP + step;
        Entry e = ph.getOrDefault(
                k, new Entry(null, dir, null, step, Stats.EMPTY, Stats.EMPTY, Stats.EMPTY, nowMillis));
        Stats ok = e.ok(), failed = e.failed(), cancelled = e.cancelled();
        switch (bucket) {
            case "ok" -> ok = ok.plus(millis);
            case "failed" -> failed = failed.plus(millis);
            default -> cancelled = cancelled.plus(millis);
        }
        ph.put(k, new Entry(null, dir, null, step, ok, failed, cancelled, nowMillis));
    }

    /** Maps a {@code StepStatus} name to a stats bucket; null = don't record (SKIPPED, non-terminal). */
    private static String bucketOf(String status) {
        if (status == null) return null;
        return switch (status) {
            case "SUCCESS" -> "ok";
            case "FAIL" -> "failed";
            case "CANCELLED" -> "cancelled";
            default -> null;
        };
    }

    // --- GC -----------------------------------------------------------------

    /** Bounds for {@link #prune}: a byte ceiling and a max row age. */
    public record Limits(long maxBytes, long maxAgeMillis) {
        static final long DEFAULT_MAX_MB = 10;
        static final long DEFAULT_MAX_AGE_DAYS = 730; // 2 years

        /**
         * Resolve from {@code JK_METRICS_MAX_SIZE_MB} / {@code JK_METRICS_MAX_AGE_DAYS} env vars,
         * else the {@code [metrics] max-size-mb} / {@code max-age-days} keys in the user config,
         * else the defaults (10 MB / 2 years).
         */
        public static Limits resolve(Path userConfig, Function<String, String> env) {
            long mb = envLong(env, "JK_METRICS_MAX_SIZE_MB")
                    .orElseGet(() -> tomlLong(userConfig, "max-size-mb").orElse(DEFAULT_MAX_MB));
            long days = envLong(env, "JK_METRICS_MAX_AGE_DAYS")
                    .orElseGet(() -> tomlLong(userConfig, "max-age-days").orElse(DEFAULT_MAX_AGE_DAYS));
            return new Limits(Math.max(0, mb) * 1024L * 1024L, Math.max(0, days) * 86_400_000L);
        }
    }

    /** What a {@link #prune} pass evicted. */
    public record PruneReport(int evictedByAge, int evictedBySize, int kept, long finalBytes) {
        static final PruneReport EMPTY = new PruneReport(0, 0, 0, 0);
    }

    /**
     * Evict stale/overflowing rows: first anything older than {@code maxAge}, then — if the
     * rendered file still exceeds {@code maxBytes} — the oldest rows until it fits. Global rows are
     * refreshed by every build, so age eviction naturally spares them while a project you stopped
     * building ages out. Rewrites the file unless {@code dryRun}.
     */
    public static PruneReport prune(Path file, Limits limits, long nowMillis, boolean dryRun) {
        if (!Files.isRegularFile(file)) return PruneReport.EMPTY;
        LOCK.lock();
        try {
            BuildMetrics cur = read(file);
            Map<String, Entry> inv = new LinkedHashMap<>(cur.invocations);
            Map<String, Entry> ph = new LinkedHashMap<>(cur.steps);

            int byAge = 0;
            if (limits.maxAgeMillis() > 0) {
                byAge += evictOlderThan(inv, nowMillis - limits.maxAgeMillis());
                byAge += evictOlderThan(ph, nowMillis - limits.maxAgeMillis());
            }

            int bySize = 0;
            long bytes = renderedBytes(inv, ph);
            int rows = inv.size() + ph.size();
            if (limits.maxBytes() > 0 && bytes > limits.maxBytes() && rows > 0) {
                long avg = Math.max(1, bytes / rows);
                int keep = (int) Math.min(rows, Math.max(0, limits.maxBytes() / avg));
                if (keep < rows) {
                    // Drop the oldest (smallest updatedMillis) first, across both families.
                    List<Map.Entry<Long, Runnable>> victims = new ArrayList<>(rows);
                    inv.forEach((k, e) -> victims.add(Map.entry(e.updatedMillis(), () -> inv.remove(k))));
                    ph.forEach((k, e) -> victims.add(Map.entry(e.updatedMillis(), () -> ph.remove(k))));
                    victims.sort(Map.Entry.comparingByKey());
                    for (int i = 0; i < rows - keep; i++) {
                        victims.get(i).getValue().run();
                        bySize++;
                    }
                }
            }

            if ((byAge > 0 || bySize > 0) && !dryRun) {
                try {
                    if (inv.isEmpty() && ph.isEmpty()) Files.deleteIfExists(file);
                    else write(file, inv, ph);
                    MEMO.remove(file);
                } catch (IOException | RuntimeException ignored) {
                    // advisory — leave the file as-is on failure
                }
            }
            return new PruneReport(byAge, bySize, inv.size() + ph.size(), renderedBytes(inv, ph));
        } finally {
            LOCK.unlock();
        }
    }

    private static int evictOlderThan(Map<String, Entry> m, long cutoffMillis) {
        int evicted = 0;
        var it = m.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.updatedMillis() > 0 && e.updatedMillis() < cutoffMillis) {
                it.remove();
                evicted++;
            }
        }
        return evicted;
    }

    // --- IO -----------------------------------------------------------------

    private static BuildMetrics read(Path file) {
        Map<String, Entry> inv = new LinkedHashMap<>();
        Map<String, Entry> ph = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(file) && MiniJson.parse(Files.readString(file)) instanceof Map<?, ?> root) {
                for (Object row : list(root.get("invocations"))) {
                    Entry e = readEntry(row, true);
                    if (e != null) inv.put(e.kind() + SEP + e.dir(), e);
                }
                for (Object row : list(root.get("steps"))) {
                    Entry e = readEntry(row, false);
                    if (e != null) ph.put(e.dir() + SEP + e.step(), e);
                }
            }
        } catch (Exception ignored) {
            // unreadable/corrupt store → treat as empty
        }
        return new BuildMetrics(inv, ph);
    }

    private static Entry readEntry(Object row, boolean invocation) {
        if (!(row instanceof Map<?, ?> o)) return null;
        String kind = str(o.get("kind"));
        String dir = str(o.get("dir"));
        String step = str(o.get("step"));
        if (dir == null || (invocation ? kind == null : step == null)) return null;
        return new Entry(
                invocation ? kind : null,
                dir,
                str(o.get("coord")),
                invocation ? null : step,
                stats(o.get("ok")),
                stats(o.get("failed")),
                stats(o.get("cancelled")),
                lng(o.get("updated")));
    }

    private static Stats stats(Object v) {
        if (!(v instanceof Map<?, ?> o)) return Stats.EMPTY;
        return new Stats(lng(o.get("count")), lng(o.get("totalMillis")), lng(o.get("minMillis")), lng(o.get("maxMillis")));
    }

    private static Map<String, Object> render(Map<String, Entry> inv, Map<String, Entry> ph) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", (long) SCHEMA);
        List<Object> invRows = new ArrayList<>(inv.size());
        new TreeMap<>(inv).values().forEach(e -> invRows.add(renderEntry(e)));
        root.put("invocations", invRows);
        List<Object> phRows = new ArrayList<>(ph.size());
        new TreeMap<>(ph).values().forEach(e -> phRows.add(renderEntry(e)));
        root.put("steps", phRows);
        return root;
    }

    private static Map<String, Object> renderEntry(Entry e) {
        Map<String, Object> o = new LinkedHashMap<>();
        if (e.kind() != null) o.put("kind", e.kind());
        o.put("dir", e.dir());
        if (e.coord() != null) o.put("coord", e.coord());
        if (e.step() != null) o.put("step", e.step());
        o.put("ok", renderStats(e.ok()));
        o.put("failed", renderStats(e.failed()));
        o.put("cancelled", renderStats(e.cancelled()));
        o.put("updated", e.updatedMillis());
        return o;
    }

    private static Map<String, Object> renderStats(Stats s) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("count", s.count());
        o.put("totalMillis", s.totalMillis());
        o.put("minMillis", s.minMillis());
        o.put("maxMillis", s.maxMillis());
        return o;
    }

    private static long renderedBytes(Map<String, Entry> inv, Map<String, Entry> ph) {
        return MiniJson.writePretty(render(inv, ph)).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void write(Path file, Map<String, Entry> inv, Map<String, Entry> ph) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".metrics-", ".tmp");
        try {
            Files.writeString(tmp, MiniJson.writePretty(render(inv, ph)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static List<?> list(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }

    private static long lng(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static OptionalLong envLong(Function<String, String> env, String name) {
        Optional<Long> v = EnvValues.longValue(env, name);
        return v.isPresent() ? OptionalLong.of(v.get()) : OptionalLong.empty();
    }

    private static OptionalLong tomlLong(Path userConfig, String key) {
        // The [metrics] table of the user-global ~/.jk/config.toml; missing/malformed → empty.
        Optional<Long> v = TomlValues.parse(userConfig)
                .map(toml -> toml.getTable("metrics"))
                .flatMap(t -> TomlValues.optLong(t, key));
        return v.isPresent() ? OptionalLong.of(v.get()) : OptionalLong.empty();
    }

    /** Test seam: drop the per-process load memo so a freshly-written file is re-read. */
    static void clearMemo() {
        MEMO.clear();
    }
}
