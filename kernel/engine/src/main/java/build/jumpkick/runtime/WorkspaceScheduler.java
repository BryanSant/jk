// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.JkThreads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * The workspace build scheduler: runs a DAG of units in dependency order. With no concurrency cap it
 * runs each topological level's ready units concurrently (batch-per-level); with a cap it runs at most
 * {@code maxConcurrency} units in flight at once, admitting the next ready unit as each one finishes
 * (a {@code maxConcurrency} of {@code 1} is a strict serial build). This is the pure orchestration
 * algorithm — how a workspace's modules are scheduled — hoisted out of the CLI so any front-end drives
 * the same concurrency/ordering. Per-unit work and all presentation stay in the caller's {@link
 * UnitTask} (which produces a per-unit result) and {@link LevelSink} (which handles completed units).
 *
 * @see BuildService
 */
public final class WorkspaceScheduler {

    private WorkspaceScheduler() {}

    /** A finished unit's result (or the throwable that ended it) — the bounded path's completion queue item. */
    private record Done<U, R>(U unit, R result, Throwable error) {}

    /** Build one unit, producing its result. Run concurrently on {@link JkThreads#io()}. */
    @FunctionalInterface
    public interface UnitTask<U, R> {
        R run(U unit);
    }

    /** Handles completed units. */
    @FunctionalInterface
    public interface LevelSink<U, R> {
        /**
         * Called after a batch of units has completed. The unbounded scheduler calls this once per
         * topological level (every ready unit of the level, in submission order). The bounded scheduler
         * calls this once per completed unit ({@code justCompleted}/{@code results} are singletons) as
         * each finishes — so {@code remaining} shrinks one unit at a time.
         *
         * @param justCompleted the units that just completed, in submission order
         * @param results their results, parallel to {@code justCompleted}
         * @param remaining units not yet scheduled (the just-completed ones — and, under a cap, any
         *     still in flight — already removed) — for ETA re-projection
         * @return a non-null value to STOP the schedule and return it to {@link #run} (e.g. a failure);
         *     {@code null} to continue with the next ready unit(s)
         */
        R after(List<U> justCompleted, List<R> results, List<U> remaining);
    }

    /** Unbounded schedule (batch-per-level). Equivalent to {@link #run(List, Function, Map, UnitTask, LevelSink, int)} with a cap {@code <= 0}. */
    public static <U, R> R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            UnitTask<U, R> task,
            LevelSink<U, R> sink) {
        return run(units, dirOf, edges, task, sink, 0);
    }

    /**
     * Schedule {@code units} respecting {@code edges} (unit dir → the dirs it depends on) with at most
     * {@code maxConcurrency} units in flight at once.
     *
     * <p>When {@code maxConcurrency <= 0} (unbounded), this repeatedly takes every unit whose in-graph
     * dependencies have all completed, runs them concurrently on {@link JkThreads#io()} via {@code
     * task}, joins in submission order, then hands the whole level to {@code sink} — the original
     * batch-per-level behavior.
     *
     * <p>When {@code maxConcurrency > 0}, it keeps a rolling window of at most that many units in
     * flight <em>across</em> levels: it admits ready, not-yet-started units until the window is full,
     * then — as each unit finishes — hands that one unit to {@code sink} and admits the next ready
     * unit. A cap of {@code 1} is a strict serial build. Dependency order is preserved either way (a
     * unit is admitted only once every in-graph prereq is in the completed set).
     *
     * <p>Stops and returns the sink's value the first time it is non-null (fail-fast); under a cap any
     * units still in flight when that happens are left to drain in the background. Returns {@code null}
     * when every unit has run.
     *
     * @param dirOf maps a unit to the {@link Path} key used in {@code edges}
     */
    public static <U, R> R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            UnitTask<U, R> task,
            LevelSink<U, R> sink,
            int maxConcurrency) {
        Set<Path> unitDirs = new HashSet<>();
        for (U u : units) unitDirs.add(dirOf.apply(u));
        Set<Path> done = ConcurrentHashMap.newKeySet();
        if (maxConcurrency <= 0) {
            // Unbounded: batch-per-level (unchanged from the original scheduler).
            List<U> remaining = new ArrayList<>(units);
            while (!remaining.isEmpty()) {
                List<U> ready = remaining.stream()
                        .filter(u -> edges.getOrDefault(dirOf.apply(u), Set.of()).stream()
                                .filter(unitDirs::contains)
                                .allMatch(done::contains))
                        .toList();
                List<CompletableFuture<R>> futures = new ArrayList<>();
                for (U u : ready) futures.add(CompletableFuture.supplyAsync(() -> task.run(u), JkThreads.io()));
                List<R> results = new ArrayList<>(futures.size());
                for (CompletableFuture<R> f : futures) results.add(f.join());
                for (U u : ready) done.add(dirOf.apply(u));
                remaining.removeAll(ready);
                R stop = sink.after(ready, results, remaining);
                if (stop != null) return stop;
            }
            return null;
        }
        // Bounded: rolling window of at most maxConcurrency in-flight units, admitted across levels.
        List<U> notStarted = new ArrayList<>(units);
        BlockingQueue<Done<U, R>> completed = new LinkedBlockingQueue<>();
        int inFlight = 0;
        while (true) {
            // Admit ready, not-yet-started units until the concurrency window is full.
            while (inFlight < maxConcurrency) {
                U next = null;
                for (U u : notStarted) {
                    boolean ready = edges.getOrDefault(dirOf.apply(u), Set.of()).stream()
                            .filter(unitDirs::contains)
                            .allMatch(done::contains);
                    if (ready) {
                        next = u;
                        break;
                    }
                }
                if (next == null) break; // nothing else admittable right now
                notStarted.remove(next);
                U unit = next;
                CompletableFuture.supplyAsync(() -> task.run(unit), JkThreads.io())
                        .whenComplete((r, ex) -> completed.add(new Done<>(unit, r, ex)));
                inFlight++;
            }
            if (inFlight == 0) return null; // nothing running and nothing admittable — the DAG has drained
            Done<U, R> d;
            try {
                d = completed.take(); // wait for the next unit to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
            inFlight--;
            if (d.error() != null) {
                throw d.error() instanceof CompletionException ce ? ce : new CompletionException(d.error());
            }
            done.add(dirOf.apply(d.unit()));
            R stop = sink.after(List.of(d.unit()), Collections.singletonList(d.result()), List.copyOf(notStarted));
            if (stop != null) return stop; // fail-fast; any in-flight units drain in the background
        }
    }
}
