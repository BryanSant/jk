// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import build.jumpkick.cache.Cas;
import build.jumpkick.compile.CompileRequest;
import build.jumpkick.compile.CompileResult;
import build.jumpkick.compile.JavacRunner;
import build.jumpkick.compile.ForkedJavac;
import build.jumpkick.compile.incremental.ClassAbi;
import build.jumpkick.compile.incremental.ClassDependencies;
import build.jumpkick.util.Hashing;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Precise incremental Java compilation (mirrors {@link KotlinCompile} in shape; the Kotlin worker
 * self-incrementals, javac doesn't, so jk computes the dirty set here). javac always does the
 * actual compilation; this layer decides which sources to recompile and carries the rest over from
 * the CAS.
 *
 * <p>Three tiers (the cheap {@code .jstamp} freshness check sits in front, in the step):
 *
 * <ol>
 *   <li>action-key hit → restore the whole output from the CAS;
 *   <li>else multi-pass incremental: recompile the changed sources, hash the new {@link ClassAbi
 *       ABI}, and expand to the reverse-dependency closure of the classes whose ABI actually
 *       changed — to a fixed point. A body-only edit changes no ABI, so no dependents recompile;
 *   <li>else (no usable prior state, classpath/options/release change, removed sources, or
 *       annotation-processor-generated sources) a clean full compile.
 * </ol>
 *
 * <p>State (per-class ABI hash + forward type deps) is persisted under {@code stateDir}; carry-over
 * + change detection reuse the prior {@link ActionCache.ActionRecord} (its {@code inputs}/{@code
 * outputs}/{@code units}).
 */
public final class JavaIncrementalCompile {

    private JavaIncrementalCompile() {}

    /** Outcome of a {@link #run}; {@code diagnostics} carries javac's messages. */
    public record Result(
            boolean success, String outcome, String actionKey, List<CompileResult.Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }
    }

    /** What {@link #run} would do for a set of inputs, without compiling. */
    public enum Outcome {
        CACHE_HIT,
        INCREMENTAL,
        FULL
    }

    /**
     * A dry-run prediction ({@code jk explain}): {@code sourceCount} is the total source count for a
     * {@link Outcome#CACHE_HIT} or {@link Outcome#FULL}, and the changed-source count for an {@link
     * Outcome#INCREMENTAL} (partial) compile.
     */
    public record Prediction(Outcome outcome, String actionKey, int sourceCount) {}

    /**
     * Persisted per-class facts (key = internal class name, e.g. {@code a/Foo$Bar}). {@code
     * constants} records whether the class defines an inlinable compile-time constant — its consumers
     * inline the value with no bytecode edge, so removing (or changing) such a class needs a
     * conservative recompile. Absent in pre-{@code constants} state files → deserializes to {@code
     * false}; self-heals as classes recompile.
     */
    public record ClassFacts(String abi, List<String> deps, boolean constants) {
        public ClassFacts {
            deps = List.copyOf(deps);
        }
    }

    /**
     * Annotation-processor setup. A project known to run <em>source-generating</em> processors
     * compiles via the {@code jk-java-compiler} worker (in-process javac under the project JDK) so
     * generated-file → originating-source provenance can be captured.
     *
     * <p>{@code workerJar} is a <em>lazy</em> resolver: it is invoked only once the project has
     * proven it needs the worker (the {@code sourceGenAps} flag), so a project whose processors are
     * bytecode-only (e.g. Lombok) — or any first build — never pays the worker lookup. The supplier
     * returns {@code null} when no worker is available, which keeps the plain subprocess-javac path.
     */
    public record ApSetup(Supplier<Path> workerJar, Path generatedSourceDir) {}

    public static Result run(
            String taskId,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            Path stateDir)
            throws IOException {
        return run(taskId, request, jkVersion, useCache, cas, actionCache, stateDir, null);
    }

    public static Result run(
            String taskId,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            ApSetup ap)
            throws IOException {
        return run(
                taskId,
                request,
                jkVersion,
                useCache,
                cas,
                actionCache,
                stateDir,
                javacCompiler(new JavacRunner(), request.javaHome(), request.processorPath()),
                ap);
    }

    /** Test seam: inject the full-compile ({@code javac}) backend. */
    static Result run(
            String taskId,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            Compiler javacBackend,
            ApSetup ap)
            throws IOException {
        Path out = request.outputDir();
        Files.createDirectories(out);
        if (request.sources().isEmpty()) {
            return new Result(true, "no-sources", "", List.of());
        }

        String key = ActionKey.forJavac(taskId, request, jkVersion);

        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent()) {
                actionCache.restore(hit.get(), out);
                return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of());
            }
        }

        Optional<ActionCache.ActionRecord> prior = useCache ? actionCache.lastFor(taskId) : Optional.empty();
        Map<String, ClassFacts> abi = useCache ? loadState(stateDir) : new HashMap<>();

        // A project only routes through the worker once a prior build has *proven* it
        // runs source-generating processors (the "orphan" signal). Until then — and
        // for Lombok-style in-bytecode processors that emit no .java — the plain
        // javac path is used and no worker need be present.
        ApFlags flags = useCache ? loadApFlags(stateDir) : ApFlags.NONE;
        // Resolve the worker lazily and only when this project has proven it runs
        // source-generating processors — so Lombok-style bytecode-only processors
        // and first builds never trigger the worker lookup (which would fail on a
        // jk build that didn't bundle the worker). A null resolution (no worker
        // available) falls back to the plain subprocess-javac path.
        Path workerJar = (ap != null && flags.sourceGenAps()) ? ap.workerJar().get() : null;
        boolean useWorker = workerJar != null;
        Compiler compiler = useWorker
                ? workerCompiler(workerJar, ap.generatedSourceDir(), request.javaHome(), request.processorPath())
                : javacBackend;

        // Worker mode only goes incremental when the prior build was isolating (every
        // generated file had exactly one originating source); an aggregating processor
        // reads the whole source set, so a subset recompile would produce a stale
        // aggregate → stay full.
        boolean canInc = canIncrement(request, prior, abi) && (!useWorker || flags.isolating());
        if (canInc) {
            return incremental(
                    taskId, request, key, cas, actionCache, stateDir, out, prior.get(), abi, compiler, flags);
        }
        return full(taskId, request, key, cas, actionCache, stateDir, out, compiler, flags, useCache);
    }

    /**
     * Predict, without compiling, what {@link #run} would do for {@code request} — for {@code jk
     * explain}. Uses the same gates as {@code run}: an action-cache hit on the javac key → {@link
     * Outcome#CACHE_HIT}; else a usable prior record + state (and a non-aggregating processor setup)
     * → {@link Outcome#INCREMENTAL} with the changed-source count; else {@link Outcome#FULL}.
     */
    public static Prediction predict(
            String taskId, CompileRequest request, String jkVersion, ActionCache actionCache, Path stateDir)
            throws IOException {
        String key = ActionKey.forJavac(taskId, request, jkVersion);
        if (request.sources().isEmpty() || actionCache.lookup(key).isPresent()) {
            return new Prediction(Outcome.CACHE_HIT, key, request.sources().size());
        }
        Optional<ActionCache.ActionRecord> prior = actionCache.lastFor(taskId);
        Map<String, ClassFacts> abi = loadState(stateDir);
        ApFlags flags = loadApFlags(stateDir);
        boolean canInc = canIncrement(request, prior, abi) && (!flags.sourceGenAps() || flags.isolating());
        if (canInc) {
            return new Prediction(
                    Outcome.INCREMENTAL,
                    key,
                    changedSources(request, prior.get().inputs()).size());
        }
        return new Prediction(Outcome.FULL, key, request.sources().size());
    }

    // ---- decide -----------------------------------------------------------

    /** Incremental only on a pure modify/add change with usable prior state. */
    private static boolean canIncrement(
            CompileRequest request, Optional<ActionCache.ActionRecord> prior, Map<String, ClassFacts> abi)
            throws IOException {
        if (prior.isEmpty() || abi.isEmpty()) return false;
        ActionCache.ActionRecord p = prior.get();
        if (p.units().isEmpty()) return false; // pre-incremental record
        Map<String, String> in = p.inputs();
        // release/options change → full (they affect every class).
        if (!String.valueOf(request.release()).equals(in.getOrDefault("release", null))) return false;
        if (!String.join(",", request.extraOptions()).equals(in.getOrDefault("options", ""))) return false;
        // A classpath change (a dependency bump: its CAS path/content changed) has no
        // fine-grained ABI-diff mechanism, so a source referencing the dependency could go
        // stale → recompile the whole module. (CAS paths encode content, so an unchanged
        // classpath hashes identically here; only a real change forces the full rebuild.)
        if (!classpathUnchanged(request, in)) return false;
        // A processor-path change has no ABI-diff mechanism (a new processor can
        // regenerate anything) → full. (CAS paths encode content, so a processor
        // version bump shows up as a different path here.)
        if (!processorPathUnchanged(request, in)) return false;
        // Source removals are handled incrementally (incremental() deletes the
        // removed classes and recompiles their referencers), so they no longer
        // force a full build.
        return true;
    }

    private static boolean processorPathUnchanged(CompileRequest request, Map<String, String> in) {
        Set<String> now = new TreeSet<>();
        for (Path pp : request.processorPath())
            now.add("pp:" + pp.toAbsolutePath().normalize());
        Set<String> prior = new TreeSet<>();
        for (String k : in.keySet()) if (k.startsWith("pp:")) prior.add(k);
        return now.equals(prior);
    }

    private static boolean classpathUnchanged(CompileRequest request, Map<String, String> in) {
        Set<String> now = new TreeSet<>();
        for (Path cp : request.classpath())
            now.add("cp:" + cp.toAbsolutePath().normalize());
        Set<String> prior = new TreeSet<>();
        for (String k : in.keySet()) if (k.startsWith("cp:")) prior.add(k);
        return now.equals(prior);
    }

    // ---- full -------------------------------------------------------------

    private static Result full(
            String taskId,
            CompileRequest request,
            String key,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            Path out,
            Compiler compiler,
            ApFlags flags,
            boolean storeResult)
            throws IOException {
        deleteClasses(out); // a full compile starts clean so removed classes don't linger
        CompileOut co = compiler.compile(
                request.sources(), request.classpath(), out, request.release(), request.extraOptions());
        if (!co.result().success() || co.result().hasErrors()) {
            return new Result(false, "errors", key, co.result().diagnostics());
        }
        Analysis a = analyze(out, request.sources(), co.generated(), Set.of());
        Map<String, List<String>> units = unitsOf(a, out);
        // Bypassing runs neither read nor write the action cache (see KotlinCompile.run).
        if (storeResult) store(taskId, key, request, out, cas, actionCache, units);
        // Remodule whether this project source-generates (so the next build routes
        // through the worker) and whether those processors are isolating.
        boolean sgap = flags.sourceGenAps() || a.hasGenerated() || a.orphans();
        saveApFlags(stateDir, new ApFlags(sgap, !a.orphans() && a.isolatingSafe()));
        if (a.orphans()) {
            // Generated sources present but no provenance (compiled without the worker):
            // we can't attribute them, so drop state → this build is correct (full) and
            // the now-set sourceGenAps flag routes the next build through the worker,
            // which DOES capture provenance and can track incrementally.
            Files.deleteIfExists(stateFile(stateDir));
        } else {
            saveState(stateDir, factsOf(a));
        }
        return new Result(true, "compiled", key, co.result().diagnostics());
    }

    // ---- incremental ------------------------------------------------------

    private static Result incremental(
            String taskId,
            CompileRequest request,
            String key,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            Path out,
            ActionCache.ActionRecord prior,
            Map<String, ClassFacts> abi,
            Compiler compiler,
            ApFlags flags)
            throws IOException {
        // Carry over: lay down the prior full output, then recompile dirty waves on top.
        actionCache.restore(prior, out);

        Map<String, ClassFacts> facts = new HashMap<>(abi);
        Map<String, List<String>> units = new HashMap<>();
        prior.units().forEach((s, rels) -> units.put(s, new ArrayList<>(rels)));

        // Carried-over class outputs (incl. prior-build generated classes). A .class
        // under one of these rel-paths that this build neither owns (input source) nor
        // regenerated (provenance) is a carry-over, not an untrackable orphan.
        Set<String> knownRelPaths = new HashSet<>();
        prior.units().values().forEach(knownRelPaths::addAll);
        // Generated-file → originating-source provenance accumulated across waves
        // (only populated in worker mode; empty for plain Java).
        Map<Path, Set<Path>> provenance = new TreeMap<>();

        // Removed sources: delete their carried-over outputs (incl. any generated
        // classes), drop their state, and seed the referencers of the now-vanished
        // classes so consumers recompile — surfacing any dangling references. A
        // removed constant holder has no bytecode edge to its inliners, so fall back
        // to recompiling everything still present.
        Set<String> removedClasses = new TreeSet<>();
        boolean removedConstantHolder = false;
        for (Map.Entry<String, List<String>> e : prior.units().entrySet()) {
            if (current(request, e.getKey()) != null) continue; // source still present
            for (String rel : e.getValue()) {
                String name = nameOf(rel);
                ClassFacts f = facts.remove(name);
                if (f == null || f.constants()) removedConstantHolder = true;
                removedClasses.add(name);
                Files.deleteIfExists(out.resolve(rel));
            }
            units.remove(e.getKey());
        }

        // Seed: the directly edited sources.
        Set<Path> seed = new HashSet<>(changedSources(request, prior.inputs()));
        if (removedConstantHolder) {
            seed.addAll(request.sources());
        } else if (!removedClasses.isEmpty()) {
            seed.addAll(referencers(removedClasses, facts, units, request.sources()));
        }
        Set<Path> compiled = new HashSet<>();
        Deque<Path> frontier = new ArrayDeque<>(seed);
        List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();

        while (!frontier.isEmpty()) {
            List<Path> wave = new ArrayList<>();
            while (!frontier.isEmpty()) {
                Path s = frontier.poll();
                if (compiled.add(s)) wave.add(s);
            }
            if (wave.isEmpty()) break;

            // Drop this wave's prior classes so a recompile that removes a (nested)
            // class doesn't leave it behind.
            for (Path s : wave) {
                for (String rel : units.getOrDefault(srcKey(s), List.of())) {
                    Files.deleteIfExists(out.resolve(rel));
                }
            }

            CompileOut co = compiler.compile(
                    wave, withOutputDir(request.classpath(), out), out, request.release(), request.extraOptions());
            diagnostics.addAll(co.result().diagnostics());
            if (!co.result().success() || co.result().hasErrors()) {
                return new Result(false, "errors", key, diagnostics);
            }
            provenance.putAll(co.generated());

            Analysis a = analyze(out, request.sources(), provenance, knownRelPaths);
            if (a.orphans) {
                // AP-generated sources with no provenance surfaced mid-build → fall back
                // to a clean full compile (which also flips on worker mode for next time).
                return full(taskId, request, key, cas, actionCache, stateDir, out, compiler, flags, true);
            }
            if (!a.isolatingSafe) {
                // A generated file maps to >1 originating source → aggregating processor.
                // This wave compiled a subset, so any aggregate it produced is stale →
                // recompile the whole source set via the worker (correct aggregate).
                return full(taskId, request, key, cas, actionCache, stateDir, out, compiler, flags, true);
            }

            Set<String> abiChanged = new TreeSet<>();
            Map<String, ClassInfo> waveByName = new HashMap<>();
            for (Path s : wave) {
                List<ClassInfo> now = a.bySource.getOrDefault(s, List.of());
                Set<String> nowNames = new HashSet<>();
                for (ClassInfo ci : now) {
                    nowNames.add(ci.name);
                    waveByName.put(ci.name, ci);
                    ClassFacts old = facts.get(ci.name);
                    if (old == null || !old.abi().equals(ci.abi)) abiChanged.add(ci.name);
                    facts.put(ci.name, new ClassFacts(ci.abi, ci.deps, ci.constants()));
                }
                for (String rel : units.getOrDefault(srcKey(s), List.of())) {
                    String name = nameOf(rel);
                    if (!nowNames.contains(name)) { // a class this source no longer produces
                        abiChanged.add(name);
                        facts.remove(name);
                    }
                }
                List<String> rels = new ArrayList<>();
                for (ClassInfo ci : now) rels.add(ci.relPath);
                units.put(srcKey(s), rels);
            }

            // A changed class that inlines into dependents (constant holder) — or a
            // class that vanished (can't inspect) — can't be tracked by bytecode
            // edges, so conservatively recompile everything still pending.
            boolean conservative = false;
            for (String name : abiChanged) {
                ClassInfo ci = waveByName.get(name);
                if (ci == null || ci.constants()) {
                    conservative = true;
                    break;
                }
            }
            Set<Path> next = conservative
                    ? new HashSet<>(request.sources())
                    : referencers(abiChanged, facts, units, request.sources());
            for (Path s : next) {
                if (!compiled.contains(s)) frontier.add(s);
            }
        }

        store(taskId, key, request, out, cas, actionCache, units);
        saveState(stateDir, facts);
        // Reaching here means we never tripped the aggregating bail, so isolating holds.
        saveApFlags(stateDir, new ApFlags(flags.sourceGenAps() || !provenance.isEmpty(), flags.isolating()));
        return new Result(true, "compiled", key, diagnostics);
    }

    /** Sources whose classes reference any ABI-changed type, via the forward-dep graph. */
    private static Set<Path> referencers(
            Set<String> abiChanged,
            Map<String, ClassFacts> facts,
            Map<String, List<String>> units,
            List<Path> sources) {
        if (abiChanged.isEmpty()) return Set.of();
        Map<String, Path> nameToSource = new HashMap<>();
        Map<String, Path> keyToSource = new HashMap<>();
        for (Path s : sources) keyToSource.put(srcKey(s), s);
        units.forEach((srcK, rels) -> {
            Path s = keyToSource.get(srcK);
            if (s != null) for (String rel : rels) nameToSource.put(nameOf(rel), s);
        });
        Set<Path> result = new HashSet<>();
        facts.forEach((name, f) -> {
            for (String dep : f.deps()) {
                if (abiChanged.contains(dep)) {
                    Path s = nameToSource.get(name);
                    if (s != null) result.add(s);
                    break;
                }
            }
        });
        return result;
    }

    // ---- analysis ---------------------------------------------------------

    private record ClassInfo(String name, String relPath, String abi, List<String> deps, boolean constants) {}

    private record Analysis(
            Map<Path, List<ClassInfo>> bySource, boolean orphans, boolean isolatingSafe, boolean hasGenerated) {}

    /**
     * Read every {@code .class} under {@code out}, hash its ABI + deps, attribute it to a source.
     * Input sources match by SourceFile-attr suffix; annotation-processor <em>generated</em> classes
     * (whose SourceFile names a non-input file) are attributed to their originating input source via
     * {@code provenance} (generated {@code .java} → originating {@code .java}), so they fold into the
     * same dirty-set/ABI graph.
     *
     * @param provenance generated source → originating source(s), this build's waves
     * @param knownRelPaths class outputs carried over from the prior build (so a carried-over
     *     generated class isn't mistaken for an orphan)
     */
    private static Analysis analyze(
            Path out, List<Path> sources, Map<Path, Set<Path>> provenance, Set<String> knownRelPaths)
            throws IOException {
        // Suffix index: "pkg/dir/File.java" → source path.
        Map<String, Path> bySuffix = new HashMap<>();
        for (Path s : sources) {
            String norm = s.toAbsolutePath().normalize().toString().replace(File.separatorChar, '/');
            bySuffix.put(norm, s);
        }
        Map<Path, List<ClassInfo>> bySource = new HashMap<>();
        boolean orphans = false;
        boolean isolatingSafe = true;
        boolean hasGenerated = false;
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!p.toString().endsWith(".class")) continue;
                byte[] bytes = Files.readAllBytes(p);
                ClassNode cn = new ClassNode();
                new ClassReader(bytes).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                String relPath = out.relativize(p).toString().replace(File.separatorChar, '/');
                String pkgDir = cn.name.contains("/") ? cn.name.substring(0, cn.name.lastIndexOf('/') + 1) : "";
                String sourceRel = pkgDir + (cn.sourceFile != null ? cn.sourceFile : simpleName(cn.name) + ".java");
                Path source = matchSource(bySuffix, sourceRel);
                if (source == null) {
                    // Not an input source — an annotation-processor-generated class?
                    Set<Path> origins = originatorsOf(provenance, sourceRel);
                    if (origins.size() == 1) {
                        source = origins.iterator().next();
                        hasGenerated = true;
                    } else if (origins.size() > 1) {
                        isolatingSafe = false; // aggregating: caller recompiles the full set
                        hasGenerated = true;
                        continue;
                    } else if (knownRelPaths.contains(relPath)) {
                        continue; // carried over; facts kept from prior state
                    } else {
                        orphans = true; // generated with no provenance (non-worker path)
                        continue;
                    }
                }
                bySource.computeIfAbsent(source, k -> new ArrayList<>())
                        .add(new ClassInfo(
                                cn.name,
                                relPath,
                                ClassAbi.hash(bytes),
                                List.copyOf(ClassDependencies.referencedTypes(bytes)),
                                ClassAbi.definesInlinableConstant(bytes)));
            }
        }
        return new Analysis(bySource, orphans, isolatingSafe, hasGenerated);
    }

    private static Path matchSource(Map<String, Path> bySuffix, String sourceRel) {
        for (Map.Entry<String, Path> e : bySuffix.entrySet()) {
            if (e.getKey().endsWith("/" + sourceRel) || e.getKey().equals(sourceRel)) return e.getValue();
        }
        return null;
    }

    /** Originating input sources for a generated {@code pkg/Foo.java}, via provenance. */
    private static Set<Path> originatorsOf(Map<Path, Set<Path>> provenance, String generatedRel) {
        Set<Path> origins = new HashSet<>();
        for (Map.Entry<Path, Set<Path>> e : provenance.entrySet()) {
            String key = e.getKey().toString().replace(File.separatorChar, '/');
            if (key.endsWith("/" + generatedRel) || key.equals(generatedRel)) {
                origins.addAll(e.getValue());
            }
        }
        return origins;
    }

    private static Map<String, ClassFacts> factsOf(Analysis a) {
        Map<String, ClassFacts> facts = new HashMap<>();
        a.bySource
                .values()
                .forEach(list ->
                        list.forEach(ci -> facts.put(ci.name(), new ClassFacts(ci.abi(), ci.deps(), ci.constants()))));
        return facts;
    }

    private static Map<String, List<String>> unitsOf(Analysis a, Path out) {
        Map<String, List<String>> units = new HashMap<>();
        a.bySource.forEach((source, list) -> {
            List<String> rels = new ArrayList<>();
            for (ClassInfo ci : list) rels.add(ci.relPath());
            units.put(srcKey(source), rels);
        });
        return units;
    }

    // ---- store + state ----------------------------------------------------

    /** Snapshot {@code out} into the CAS and record outputs + per-source units under {@code key}. */
    private static void store(
            String taskId,
            String key,
            CompileRequest request,
            Path out,
            Cas cas,
            ActionCache actionCache,
            Map<String, List<String>> units)
            throws IOException {
        Map<String, String> outputs = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (FreshnessStamp.isStampFile(file.getFileName().toString())) continue;
                // Streamed hash + copy into the CAS (same pattern as ActionCache.store; never link).
                String hex = Hashing.sha256Hex(file);
                cas.putFile(file, hex);
                outputs.put(out.relativize(file).toString().replace(File.separatorChar, '/'), hex);
            }
        }
        actionCache.storeWithOutputs(taskId, key, ActionKey.snapshotInputs(request), outputs, units);
    }

    private static Path stateFile(Path stateDir) {
        return stateDir.resolve("java-incremental.txt");
    }

    // Format: <internalName>\t<abiHex>\t<0|1>\t<dep1,dep2,...>
    private static Map<String, ClassFacts> loadState(Path stateDir) throws IOException {
        Path f = stateFile(stateDir);
        if (!Files.isRegularFile(f)) return new HashMap<>();
        try {
            Map<String, ClassFacts> out = new LinkedHashMap<>();
            try (BufferedReader br =
                    new BufferedReader(new StringReader(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\t", 4);
                    if (parts.length < 3) continue;
                    List<String> deps =
                            (parts.length >= 4 && !parts[3].isEmpty()) ? List.of(parts[3].split(",", -1)) : List.of();
                    out.put(parts[0], new ClassFacts(parts[1], deps, "1".equals(parts[2])));
                }
            }
            return out;
        } catch (RuntimeException corrupt) {
            return new HashMap<>();
        }
    }

    private static void saveState(Path stateDir, Map<String, ClassFacts> facts) throws IOException {
        Files.createDirectories(stateDir);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ClassFacts> e : new TreeMap<>(facts).entrySet()) {
            ClassFacts v = e.getValue();
            sb.append(e.getKey())
                    .append('\t')
                    .append(v.abi())
                    .append('\t')
                    .append(v.constants() ? '1' : '0')
                    .append('\t')
                    .append(String.join(",", v.deps()))
                    .append('\n');
        }
        Files.write(stateFile(stateDir), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- annotation-processor compiler routing ----------------------------

    /** A javac invocation; returns its result plus any generated-file provenance. */
    interface Compiler {
        CompileOut compile(List<Path> sources, List<Path> classpath, Path outputDir, int release, List<String> options);
    }

    record CompileOut(CompileResult result, Map<Path, Set<Path>> generated) {}

    /** Plain subprocess javac: no provenance (APs, if any, run but aren't tracked). */
    static Compiler javacCompiler(JavacRunner runner, Path javaHome, List<Path> processorPath) {
        return (sources, classpath, outputDir, release, options) -> {
            CompileResult r;
            try {
                r = runner.compile(CompileRequest.builder()
                        .sources(sources)
                        .classpath(classpath)
                        .outputDir(outputDir)
                        .release(release)
                        .extraOptions(options)
                        .javaHome(javaHome)
                        .processorPath(processorPath)
                        .build());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return new CompileOut(r, Map.of());
        };
    }

    /** In-process javac in the worker: captures generated-file → originating-source. */
    private static Compiler workerCompiler(
            Path workerJar, Path generatedSourceDir, Path javaHome, List<Path> processorPath) {
        return (sources, classpath, outputDir, release, options) -> {
            ForkedJavac.Result wr = ForkedJavac.compile(new ForkedJavac.Request(
                    javaHome,
                    workerJar,
                    sources,
                    classpath,
                    processorPath,
                    outputDir,
                    generatedSourceDir,
                    release,
                    options));
            // The worker now returns structured diagnostics (severity + located
            // message), so warnings carry the right severity and route to warn.
            return new CompileOut(new CompileResult(wr.success(), wr.diagnostics()), wr.generated());
        };
    }

    /** Per-project AP facts persisted alongside the incremental state. */
    private record ApFlags(boolean sourceGenAps, boolean isolating) {
        static final ApFlags NONE = new ApFlags(false, false);
    }

    private static Path apFlagsFile(Path stateDir) {
        return stateDir.resolve("java-ap.txt");
    }

    // Format: two lines — "sourceGenAps=true" and "isolating=false"
    private static ApFlags loadApFlags(Path stateDir) throws IOException {
        Path f = apFlagsFile(stateDir);
        if (!Files.isRegularFile(f)) return ApFlags.NONE;
        try {
            boolean sourceGenAps = false, isolating = false;
            for (String line : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (line.startsWith("sourceGenAps=")) sourceGenAps = "true".equals(line.substring(13));
                else if (line.startsWith("isolating=")) isolating = "true".equals(line.substring(10));
            }
            return new ApFlags(sourceGenAps, isolating);
        } catch (RuntimeException corrupt) {
            return ApFlags.NONE;
        }
    }

    private static void saveApFlags(Path stateDir, ApFlags flags) throws IOException {
        Files.createDirectories(stateDir);
        String content = "sourceGenAps=" + flags.sourceGenAps() + "\nisolating=" + flags.isolating() + "\n";
        Files.write(apFlagsFile(stateDir), content.getBytes(StandardCharsets.UTF_8));
    }

    // ---- small helpers ----------------------------------------------------

    private static List<Path> changedSources(CompileRequest request, Map<String, String> priorInputs)
            throws IOException {
        List<Path> changed = new ArrayList<>();
        for (Path s : request.sources()) {
            String prior = priorInputs.get(srcKey(s));
            String now = Hashing.sha256Hex(Files.readAllBytes(s));
            if (prior == null || !prior.equals(now)) changed.add(s);
        }
        return changed;
    }

    private static String current(CompileRequest request, String sourceKey) {
        for (Path s : request.sources()) if (srcKey(s).equals(sourceKey)) return sourceKey;
        return null;
    }

    private static Set<String> sourceKeys(Map<String, String> inputs) {
        Set<String> out = new TreeSet<>();
        for (String k : inputs.keySet()) {
            if (k.startsWith("cp:") || k.startsWith("pp:") || k.equals("release") || k.equals("options")) continue;
            out.add(k);
        }
        return out;
    }

    private static List<Path> withOutputDir(List<Path> classpath, Path out) {
        List<Path> cp = new ArrayList<>(classpath);
        cp.add(out); // so a wave sees carried-over + earlier-wave classes
        return cp;
    }

    private static void deleteClasses(Path out) throws IOException {
        if (!Files.isDirectory(out)) return;
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".class")) Files.deleteIfExists(p);
            }
        }
    }

    private static String srcKey(Path source) {
        return source.toAbsolutePath().normalize().toString();
    }

    private static String nameOf(String relPath) {
        return relPath.endsWith(".class") ? relPath.substring(0, relPath.length() - ".class".length()) : relPath;
    }

    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }
}
