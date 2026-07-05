// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.util.JkThreads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The workspace build scheduler: runs a DAG of units in dependency order, each level's ready units
 * concurrently. This is the pure orchestration algorithm — how a workspace's modules are scheduled —
 * hoisted out of the CLI so any front-end drives the same concurrency/ordering. Per-unit work and all
 * presentation stay in the caller's {@link UnitTask} (which produces a per-unit result) and {@link
 * LevelSink} (which handles each completed level).
 *
 * @see BuildService
 */
public final class WorkspaceScheduler {

    private WorkspaceScheduler() {}

    /** Build one unit, producing its result. Run concurrently on {@link JkThreads#io()}. */
    @FunctionalInterface
    public interface UnitTask<U, R> {
        R run(U unit);
    }

    /** Handles one completed level. */
    @FunctionalInterface
    public interface LevelSink<U, R> {
        /**
         * Called after every unit in a ready level has completed.
         *
         * @param justCompleted the units of this level, in submission order
         * @param results their results, parallel to {@code justCompleted}
         * @param remaining units not yet scheduled (this level already removed) — for ETA re-projection
         * @return a non-null value to STOP the schedule and return it to {@link #run} (e.g. a failure);
         *     {@code null} to continue with the next ready level
         */
        R after(List<U> justCompleted, List<R> results, List<U> remaining);
    }

    /**
     * Schedule {@code units} respecting {@code edges} (unit dir → the dirs it depends on): repeatedly
     * take every unit whose in-graph dependencies have all completed, run them concurrently on {@link
     * JkThreads#io()} via {@code task}, join in submission order, then hand the level to {@code sink}.
     * Stops and returns the sink's value the first time it is non-null; returns {@code null} when every
     * unit has run.
     *
     * @param dirOf maps a unit to the {@link Path} key used in {@code edges}
     */
    public static <U, R> R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            UnitTask<U, R> task,
            LevelSink<U, R> sink) {
        Set<Path> unitDirs = new HashSet<>();
        for (U u : units) unitDirs.add(dirOf.apply(u));
        Set<Path> done = ConcurrentHashMap.newKeySet();
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
}
