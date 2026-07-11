// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.SubprocessJavacStrategy;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.util.PathUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Spring Boot AOT processing (spring-boot plan §3.4) — jk's equivalent of the Gradle plugin's
 * {@code processAot} + {@code compileAot}:
 *
 * <ol>
 *   <li>Fork {@code java -cp <production classpath> o.s.b.SpringApplicationAotProcessor <main>
 *       <gen-sources> <gen-resources> <gen-classes> <group> <artifact>} — refreshes the
 *       application context at build time and emits AOT-generated sources, GraalVM hint resources,
 *       and pre-generated proxy classes.
 *   <li>Compile the generated sources with the project's javac into the same generated-classes
 *       dir.
 * </ol>
 *
 * <p>Outputs land under {@code target/aot/} — boot-jar packaging merges {@code classes} +
 * {@code resources} into {@code BOOT-INF/classes}, and the native-image classpath adds them so
 * GraalVM sees the generated {@code META-INF/native-image} configs. The processor runs against the
 * production RUNTIME classpath (dev-scope deps like DevTools must not shape the frozen context).
 */
final class SpringAotRunner {

    private static final String PROCESSOR = "org.springframework.boot.SpringApplicationAotProcessor";

    private SpringAotRunner() {}

    /** The generated dirs to merge into packaging / the native classpath: [classes, resources]. */
    record AotOutput(Path classesDir, Path resourcesDir) {

        List<Path> dirs() {
            return List.of(classesDir, resourcesDir);
        }
    }

    /** Well-known output locations (also read by the native phase): {@code target/aot/…}. */
    static AotOutput outputAt(BuildLayout layout) {
        Path root = layout.moduleTargetDir().resolve("aot");
        return new AotOutput(root.resolve("classes"), root.resolve("resources"));
    }

    /**
     * Run the AOT processor + compile its generated sources. {@code classes} is the compiled app
     * classes dir; {@code startClass} the resolved application main; {@code aotArgs} are
     * application arguments the context refresh sees (profile baking). Throws with the process
     * output on failure — a broken context refresh must fail the build loudly.
     *
     * <p>Action-cached: the output is a pure function of the app classes, the production
     * classpath, the entry point, and the processor args — a no-change rebuild restores
     * {@code target/aot} from the CAS instead of re-refreshing the context.
     */
    static AotOutput process(
            Path projectDir,
            Path cache,
            Path lockFile,
            Path javaHome,
            JkBuild project,
            BuildLayout layout,
            Path classes,
            String startClass,
            List<String> aotArgs,
            int release)
            throws IOException, InterruptedException {
        Path aotRoot = layout.moduleTargetDir().resolve("aot");
        AotOutput out = outputAt(layout);

        List<Path> classpath = productionClasspath(projectDir, cache, lockFile, project, classes);

        dev.jkbuild.task.ActionCache actionCache =
                new dev.jkbuild.task.ActionCache(new Cas(cache), cache.resolve("actions"));
        java.util.Map<String, String> inputs = new java.util.TreeMap<>();
        inputs.put("classes", dev.jkbuild.task.ClasspathFingerprint.entry(classes));
        inputs.put("cp", dev.jkbuild.task.ClasspathFingerprint.of(classpath));
        inputs.put("start", startClass);
        inputs.put("args", String.join("\u0000", aotArgs));
        inputs.put("release", Integer.toString(release));
        String taskId = dev.jkbuild.task.ActionKey.qualifiedTaskId("spring-aot", aotRoot);
        String actionKey =
                dev.jkbuild.task.ActionKey.forArtifact(taskId, dev.jkbuild.util.JkVersion.VERSION, List.of(
                        "classes:" + inputs.get("classes"),
                        "cp:" + inputs.get("cp"),
                        "start:" + startClass,
                        "args:" + inputs.get("args"),
                        "release:" + release));
        var hit = actionCache.lookup(actionKey);
        if (hit.isPresent()) {
            try {
                actionCache.restore(hit.get(), aotRoot);
                return out;
            } catch (IOException e) {
                // A missing CAS blob (pruned cache) falls through to a fresh run.
            }
        }

        PathUtil.deleteRecursively(aotRoot); // stale generated code must never survive a re-run
        Path genSources = Files.createDirectories(aotRoot.resolve("sources"));
        Files.createDirectories(out.classesDir());
        Files.createDirectories(out.resourcesDir());

        List<String> command = new ArrayList<>();
        command.add(javaHome.resolve("bin")
                .resolve(dev.jkbuild.jdk.HostPlatform.isWindows() ? "java.exe" : "java")
                .toString());
        command.add("-cp");
        command.add(joinPaths(classpath));
        command.add(PROCESSOR);
        command.add(startClass);
        command.add(genSources.toString());
        command.add(out.resourcesDir().toString());
        command.add(out.classesDir().toString());
        command.add(project.project().group());
        command.add(project.project().name());
        command.addAll(aotArgs);

        Process process = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Spring AOT processing failed (exit " + exit + "):\n" + tail(output.toString()));
        }

        // Compile the generated sources into the generated-classes dir (alongside the
        // processor-emitted proxy classes), against app classes + the same classpath.
        List<Path> aotSources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(genSources)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(aotSources::add);
        }
        if (!aotSources.isEmpty()) {
            List<Path> compileCp = new ArrayList<>(classpath);
            compileCp.add(out.classesDir());
            CompileResult result = new SubprocessJavacStrategy()
                    .compile(new CompileRequest(
                            aotSources, compileCp, out.classesDir(), release, List.of("-parameters"), javaHome));
            if (!result.success()) {
                StringBuilder diag = new StringBuilder("compiling Spring AOT generated sources failed:\n");
                result.diagnostics().forEach(d -> diag.append(d.message()).append('\n'));
                throw new IOException(diag.toString());
            }
        }
        actionCache.store(taskId, actionKey, inputs, aotRoot);
        return out;
    }

    /** App classes + lockfile RUNTIME deps + workspace siblings — the production classpath. */
    private static List<Path> productionClasspath(
            Path projectDir, Path cache, Path lockFile, JkBuild project, Path classes) throws IOException {
        List<Path> classpath = new ArrayList<>();
        classpath.add(classes);
        if (Files.exists(lockFile)) {
            ClasspathResolver resolver = new ClasspathResolver(new Cas(cache));
            classpath.addAll(resolver.classpathFor(
                    dev.jkbuild.lock.LockfileReader.read(lockFile), ClasspathResolver.RUNTIME));
        }
        try {
            WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(
                    projectDir, project, java.util.Set.of(dev.jkbuild.model.Scope.EXPORT, dev.jkbuild.model.Scope.MAIN));
            for (Path jar : siblings.jars()) {
                if (!classpath.contains(jar)) classpath.add(jar);
            }
            for (Path sibLock : siblings.siblingLockfiles()) {
                try {
                    Lockfile sib = dev.jkbuild.lock.LockfileReader.read(sibLock);
                    for (Path p : new ClasspathResolver(new Cas(cache)).classpathFor(sib, ClasspathResolver.RUNTIME)) {
                        if (!classpath.contains(p)) classpath.add(p);
                    }
                } catch (Exception ignored) {
                    /* best-effort, mirrors shadow packaging */
                }
            }
        } catch (Exception ignored) {
            /* no workspace — fine */
        }
        return classpath;
    }

    private static String joinPaths(List<Path> paths) {
        String sep = System.getProperty("path.separator");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(paths.get(i).toAbsolutePath());
        }
        return sb.toString();
    }

    /** The last ~40 lines — context-refresh stacks are long; the cause is at the bottom. */
    private static String tail(String output) {
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - 40);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
