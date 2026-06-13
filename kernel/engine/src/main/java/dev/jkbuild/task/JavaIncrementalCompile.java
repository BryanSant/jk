// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.WorkerJavac;
import dev.jkbuild.compile.incremental.ClassAbi;
import dev.jkbuild.compile.incremental.ClassDependencies;
import dev.jkbuild.compile.incremental.JavaClasspathAbi;
import dev.jkbuild.util.Hashing;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassReader;


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

/**
 * Precise incremental Java compilation (mirrors {@link KotlinCompile} in shape;
 * the Kotlin worker self-incrementals, javac doesn't, so jk computes the dirty
 * set here). javac always does the actual compilation; this layer decides which
 * sources to recompile and carries the rest over from the CAS.
 *
 * <p>Three tiers (the cheap {@code .jstamp} freshness check sits in front, in the
 * phase):
 * <ol>
 *   <li>action-key hit → restore the whole output from the CAS;</li>
 *   <li>else multi-pass incremental: recompile the changed sources, hash the new
 *       {@link ClassAbi ABI}, and expand to the reverse-dependency closure of the
 *       classes whose ABI actually changed — to a fixed point. A body-only edit
 *       changes no ABI, so no dependents recompile;</li>
 *   <li>else (no usable prior state, classpath/options/release change, removed
 *       sources, or annotation-processor-generated sources) a clean full compile.</li>
 * </ol>
 *
 * <p>State (per-class ABI hash + forward type deps) is persisted under
 * {@code stateDir}; carry-over + change detection reuse the prior
 * {@link ActionCache.ActionRecord} (its {@code inputs}/{@code outputs}/{@code units}).
 */
public final class JavaIncrementalCompile {

    private JavaIncrementalCompile() {}

    /** Outcome of a {@link #run}; {@code diagnostics} carries javac's messages. */
    public record Result(boolean success, String outcome, String actionKey,
                         List<CompileResult.Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }
    }

    /**
     * Persisted per-class facts (key = internal class name, e.g. {@code a/Foo$Bar}).
     * {@code constants} records whether the class defines an inlinable compile-time
     * constant — its consumers inline the value with no bytecode edge, so removing
     * (or changing) such a class needs a conservative recompile. Absent in
     * pre-{@code constants} state files → deserializes to {@code false}; self-heals
     * as classes recompile.
     */
    public record ClassFacts(String abi, List<String> deps, boolean constants) {
        public ClassFacts {
            deps = List.copyOf(deps);
        }
    }

    /**
     * Annotation-processor setup. A project known to run <em>source-generating</em>
     * processors compiles via the {@code jk-java-compiler} worker (in-process javac
     * under the project JDK) so generated-file → originating-source provenance can
     * be captured.
     *
     * <p>{@code workerJar} is a <em>lazy</em> resolver: it is invoked only once the
     * project has proven it needs the worker (the {@code sourceGenAps} flag), so a
     * project whose processors are bytecode-only (e.g. Lombok) — or any first build —
     * never pays the worker lookup. The supplier returns {@code null} when no worker
     * is available, which keeps the plain subprocess-javac path.
     */
    public record ApSetup(Supplier<Path> workerJar, Path generatedSourceDir) {}

    public static Result run(String taskId, CompileRequest request, String jkVersion,
                             boolean useCache, Cas cas, ActionCache actionCache, Path stateDir)
            throws IOException {
        return run(taskId, request, jkVersion, useCache, cas, actionCache, stateDir, null);
    }

    public static Result run(String taskId, CompileRequest request, String jkVersion, boolean useCache,
                             Cas cas, ActionCache actionCache, Path stateDir, ApSetup ap)
            throws IOException {
        return run(taskId, request, jkVersion, useCache, cas, actionCache, stateDir, new JavacDriver(), ap);
    }

    /** Test seam: inject the javac driver. */
    static Result run(String taskId, CompileRequest request, String jkVersion, boolean useCache,
                      Cas cas, ActionCache actionCache, Path stateDir, JavacDriver driver, ApSetup ap)
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

        Optional<ActionCache.ActionRecord> prior =
                useCache ? actionCache.lastFor(taskId) : Optional.empty();
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
                : javacCompiler(driver, request.javaHome(), request.processorPath());

        // Worker mode only goes incremental when the prior build was isolating (every
        // generated file had exactly one originating source); an aggregating processor
        // reads the whole source set, so a subset recompile would produce a stale
        // aggregate → stay full.
        boolean canInc = canIncrement(request, prior, abi) && (!useWorker || flags.isolating());
        if (canInc) {
            return incremental(taskId, request, key, cas, actionCache, stateDir, out,
                    prior.get(), abi, compiler, flags);
        }
        return full(taskId, request, key, cas, actionCache, stateDir, out, compiler, flags);
    }

    // ---- decide -----------------------------------------------------------

    /** Incremental only on a pure modify/add change with usable prior state. */
    private static boolean canIncrement(CompileRequest request,
                                        Optional<ActionCache.ActionRecord> prior,
                                        Map<String, ClassFacts> abi) throws IOException {
        if (prior.isEmpty() || abi.isEmpty()) return false;
        ActionCache.ActionRecord p = prior.get();
        if (p.units().isEmpty()) return false;               // pre-incremental record
        Map<String, String> in = p.inputs();
        // release/options change → full (they affect every class). A *classpath*
        // change is fine: the incremental loop diffs dependency ABIs (§ phase 3).
        if (!String.valueOf(request.release()).equals(in.getOrDefault("release", null))) return false;
        if (!String.join(",", request.extraOptions()).equals(in.getOrDefault("options", ""))) return false;
        // A processor-path change has no ABI-diff mechanism (a new processor can
        // regenerate anything) → full. (CAS paths encode content, so a processor
        // version bump shows up as a different path here.)
        if (!processorPathUnchanged(request, in)) return false;
        // Source removals are handled incrementally (incremental() deletes the
        // removed classes and recompiles their referencers), so they no longer
        // force a full build.
        return true;
    }

    private static boolean classpathPathsUnchanged(CompileRequest request, Map<String, String> in) {
        Set<String> nowCp = new TreeSet<>();
        for (Path cp : request.classpath()) nowCp.add("cp:" + cp.toAbsolutePath().normalize());
        Set<String> priorCp = new TreeSet<>();
        for (String k : in.keySet()) if (k.startsWith("cp:")) priorCp.add(k);
        return nowCp.equals(priorCp);
    }

    private static boolean processorPathUnchanged(CompileRequest request, Map<String, String> in) {
        Set<String> now = new TreeSet<>();
        for (Path pp : request.processorPath()) now.add("pp:" + pp.toAbsolutePath().normalize());
        Set<String> prior = new TreeSet<>();
        for (String k : in.keySet()) if (k.startsWith("pp:")) prior.add(k);
        return now.equals(prior);
    }

    // ---- full -------------------------------------------------------------

    private static Result full(String taskId, CompileRequest request, String key, Cas cas,
                               ActionCache actionCache, Path stateDir, Path out,
                               Compiler compiler, ApFlags flags) throws IOException {
        deleteClasses(out);   // a full compile starts clean so removed classes don't linger
        CompileOut co = compiler.compile(request.sources(), request.classpath(), out,
                request.release(), request.extraOptions());
        if (!co.result().success() || co.result().hasErrors()) {
            return new Result(false, "errors", key, co.result().diagnostics());
        }
        Analysis a = analyze(out, request.sources(), co.generated(), Set.of());
        Map<String, List<String>> units = unitsOf(a, out);
        store(taskId, key, request, out, cas, actionCache, units);
        saveUnion(stateDir, JavaClasspathAbi.union(
                request.classpath(), cas, cas.root().resolve("cp-abi-snapshots")));
        // Remember whether this project source-generates (so the next build routes
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

    private static Result incremental(String taskId, CompileRequest request, String key, Cas cas,
                                      ActionCache actionCache, Path stateDir, Path out,
                                      ActionCache.ActionRecord prior, Map<String, ClassFacts> abi,
                                      Compiler compiler, ApFlags flags) throws IOException {
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
            if (current(request, e.getKey()) != null) continue;   // source still present
            for (String rel : e.getValue()) {
                String name = nameOf(rel);
                ClassFacts f = facts.remove(name);
                if (f == null || f.constants()) removedConstantHolder = true;
                removedClasses.add(name);
                Files.deleteIfExists(out.resolve(rel));
            }
            units.remove(e.getKey());
        }

        // Seed: the directly edited sources, plus the sources that reference any
        // dependency class whose ABI changed since last build (classpath diff).
        Set<Path> seed = new HashSet<>(changedSources(request, prior.inputs()));
        if (removedConstantHolder) {
            seed.addAll(request.sources());
        } else if (!removedClasses.isEmpty()) {
            seed.addAll(referencers(removedClasses, facts, units, request.sources()));
        }
        Path cpCacheDir = cas.root().resolve("cp-abi-snapshots");
        Map<String, JavaClasspathAbi.DepFacts> priorUnion = loadUnion(stateDir);
        boolean haveBaseline = priorUnion != null;
        // CAS jars are content-stable, so when the classpath paths are unchanged
        // and carry no directory entries, no dependency class can have changed.
        boolean cpStable = haveBaseline
                && classpathPathsUnchanged(request, prior.inputs())
                && noDirectoryEntries(request.classpath());
        Map<String, JavaClasspathAbi.DepFacts> currentUnion;
        if (cpStable) {
            currentUnion = priorUnion;
        } else {
            currentUnion = JavaClasspathAbi.union(request.classpath(), cas, cpCacheDir);
            if (!haveBaseline) {
                seed.addAll(request.sources());   // no baseline to diff against → conservative, once
            } else {
                DepDiff dd = diffDeps(priorUnion, currentUnion);
                if (dd.conservative) seed.addAll(request.sources());
                else seed.addAll(referencers(dd.changed, facts, units, request.sources()));
            }
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

            CompileOut co = compiler.compile(wave, withOutputDir(request.classpath(), out), out,
                    request.release(), request.extraOptions());
            diagnostics.addAll(co.result().diagnostics());
            if (!co.result().success() || co.result().hasErrors()) {
                return new Result(false, "errors", key, diagnostics);
            }
            provenance.putAll(co.generated());

            Analysis a = analyze(out, request.sources(), provenance, knownRelPaths);
            if (a.orphans) {
                // AP-generated sources with no provenance surfaced mid-build → fall back
                // to a clean full compile (which also flips on worker mode for next time).
                return full(taskId, request, key, cas, actionCache, stateDir, out, compiler, flags);
            }
            if (!a.isolatingSafe) {
                // A generated file maps to >1 originating source → aggregating processor.
                // This wave compiled a subset, so any aggregate it produced is stale →
                // recompile the whole source set via the worker (correct aggregate).
                return full(taskId, request, key, cas, actionCache, stateDir, out, compiler, flags);
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
                    if (!nowNames.contains(name)) {        // a class this source no longer produces
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
                if (ci == null || ci.constants()) { conservative = true; break; }
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
        saveUnion(stateDir, currentUnion);
        // Reaching here means we never tripped the aggregating bail, so isolating holds.
        saveApFlags(stateDir, new ApFlags(flags.sourceGenAps() || !provenance.isEmpty(), flags.isolating()));
        return new Result(true, "compiled", key, diagnostics);
    }

    /** Sources whose classes reference any ABI-changed type, via the forward-dep graph. */
    private static Set<Path> referencers(Set<String> abiChanged, Map<String, ClassFacts> facts,
                                         Map<String, List<String>> units, List<Path> sources) {
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

    private record Analysis(Map<Path, List<ClassInfo>> bySource, boolean orphans,
                            boolean isolatingSafe, boolean hasGenerated) {}

    /**
     * Read every {@code .class} under {@code out}, hash its ABI + deps, attribute it
     * to a source. Input sources match by SourceFile-attr suffix; annotation-processor
     * <em>generated</em> classes (whose SourceFile names a non-input file) are attributed
     * to their originating input source via {@code provenance} (generated {@code .java} →
     * originating {@code .java}), so they fold into the same dirty-set/ABI graph.
     *
     * @param provenance   generated source → originating source(s), this build's waves
     * @param knownRelPaths class outputs carried over from the prior build (so a
     *                      carried-over generated class isn't mistaken for an orphan)
     */
    private static Analysis analyze(Path out, List<Path> sources,
                                    Map<Path, Set<Path>> provenance,
                                    Set<String> knownRelPaths) throws IOException {
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
                        isolatingSafe = false;   // aggregating: caller recompiles the full set
                        hasGenerated = true;
                        continue;
                    } else if (knownRelPaths.contains(relPath)) {
                        continue;                // carried over; facts kept from prior state
                    } else {
                        orphans = true;          // generated with no provenance (non-worker path)
                        continue;
                    }
                }
                bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(
                        new ClassInfo(cn.name, relPath, ClassAbi.hash(bytes),
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
        a.bySource.values().forEach(list ->
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
    private static void store(String taskId, String key, CompileRequest request, Path out, Cas cas,
                              ActionCache actionCache, Map<String, List<String>> units) throws IOException {
        Map<String, String> outputs = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (FreshnessStamp.isStampFile(file.getFileName().toString())) continue;
                byte[] bytes = Files.readAllBytes(file);
                String hex = Hashing.sha256Hex(bytes);
                cas.putByLink(file, hex);
                outputs.put(out.relativize(file).toString().replace(File.separatorChar, '/'), hex);
            }
        }
        actionCache.storeWithOutputs(taskId, key, ActionKey.snapshotInputs(request), outputs, units);
    }

    private record DepDiff(Set<String> changed, boolean conservative) {}

    /** Dependency classes whose ABI changed (or vanished) since the prior build. */
    private static DepDiff diffDeps(Map<String, JavaClasspathAbi.DepFacts> prior,
                                    Map<String, JavaClasspathAbi.DepFacts> current) {
        Set<String> changed = new TreeSet<>();
        boolean conservative = false;
        for (Map.Entry<String, JavaClasspathAbi.DepFacts> e : prior.entrySet()) {
            JavaClasspathAbi.DepFacts before = e.getValue();
            JavaClasspathAbi.DepFacts after = current.get(e.getKey());
            if (after == null || !after.abi().equals(before.abi())) {
                changed.add(e.getKey());
                // A changed dep that inlines constants leaves no bytecode edge → conservative.
                if (before.constants() || (after != null && after.constants())) conservative = true;
            }
        }
        return new DepDiff(changed, conservative);
    }

    private static boolean noDirectoryEntries(List<Path> classpath) {
        for (Path p : classpath) if (Files.isDirectory(p)) return false;
        return true;
    }

    /** The prior classpath ABI union, or {@code null} when no baseline has been recorded yet. */
    private static Map<String, JavaClasspathAbi.DepFacts> loadUnion(Path stateDir) throws IOException {
        Path f = unionFile(stateDir);
        if (!Files.isRegularFile(f)) return null;
        try {
            return JavaClasspathAbi.readDepFacts(Files.readAllBytes(f));
        } catch (RuntimeException corrupt) {
            return null;
        }
    }

    private static void saveUnion(Path stateDir, Map<String, JavaClasspathAbi.DepFacts> union)
            throws IOException {
        Files.createDirectories(stateDir);
        Files.write(unionFile(stateDir),
                JavaClasspathAbi.writeDepFacts(new TreeMap<>(union)).getBytes(StandardCharsets.UTF_8));
    }

    private static Path unionFile(Path stateDir) {
        return stateDir.resolve("java-cp-abi.txt");
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
            try (BufferedReader br = new BufferedReader(
                    new StringReader(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\t", 4);
                    if (parts.length < 3) continue;
                    List<String> deps = (parts.length >= 4 && !parts[3].isEmpty())
                            ? List.of(parts[3].split(",", -1)) : List.of();
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
            sb.append(e.getKey()).append('\t').append(v.abi()).append('\t')
              .append(v.constants() ? '1' : '0').append('\t')
              .append(String.join(",", v.deps())).append('\n');
        }
        Files.write(stateFile(stateDir), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- annotation-processor compiler routing ----------------------------

    /** A javac invocation; returns its result plus any generated-file provenance. */
    private interface Compiler {
        CompileOut compile(List<Path> sources, List<Path> classpath, Path outputDir,
                           int release, List<String> options);
    }

    private record CompileOut(CompileResult result, Map<Path, Set<Path>> generated) {}

    /** Plain subprocess javac: no provenance (APs, if any, run but aren't tracked). */
    private static Compiler javacCompiler(JavacDriver driver, Path javaHome, List<Path> processorPath) {
        return (sources, classpath, outputDir, release, options) -> {
            CompileResult r = driver.compile(CompileRequest.builder()
                    .sources(sources)
                    .classpath(classpath)
                    .outputDir(outputDir)
                    .release(release)
                    .extraOptions(options)
                    .javaHome(javaHome)
                    .processorPath(processorPath)
                    .build());
            return new CompileOut(r, Map.of());
        };
    }

    /** In-process javac in the worker: captures generated-file → originating-source. */
    private static Compiler workerCompiler(Path workerJar, Path generatedSourceDir,
                                           Path javaHome, List<Path> processorPath) {
        return (sources, classpath, outputDir, release, options) -> {
            WorkerJavac.Result wr = WorkerJavac.compile(new WorkerJavac.Request(
                    javaHome, workerJar, sources, classpath, processorPath,
                    outputDir, generatedSourceDir, release, options));
            List<CompileResult.Diagnostic> diags = new ArrayList<>();
            for (String m : wr.diagnostics()) {
                diags.add(new CompileResult.Diagnostic(
                        m.startsWith("ERROR") ? CompileResult.Severity.ERROR : CompileResult.Severity.OTHER,
                        null, 0, 0, m));
            }
            return new CompileOut(new CompileResult(wr.success(), diags), wr.generated());
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
                else if (line.startsWith("isolating="))   isolating   = "true".equals(line.substring(10));
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
            if (k.startsWith("cp:") || k.startsWith("pp:")
                    || k.equals("release") || k.equals("options")) continue;
            out.add(k);
        }
        return out;
    }

    private static List<Path> withOutputDir(List<Path> classpath, Path out) {
        List<Path> cp = new ArrayList<>(classpath);
        cp.add(out);   // so a wave sees carried-over + earlier-wave classes
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
