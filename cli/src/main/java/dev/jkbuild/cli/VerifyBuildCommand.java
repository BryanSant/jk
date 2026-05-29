// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JarPackager;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk verify} — re-build the project into a clean
 * scratch directory and compare the produced jar's SHA-256 against the
 * existing {@code target/<artifact>-<version>.jar} (PRD §23.7,
 * impl-plan §7 step 3).
 *
 * <p>Goal shape: {@code parse-build} → {@code rebuild-scratch} (CPU) →
 * {@code compare-hashes} (SYNC).
 */
@Command(name = "verify",
        description = "Rebuild from scratch and diff that jar vs target/")
public final class VerifyBuildCommand implements Callable<Integer> {

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    private static final GoalKey<Lockfile> LOCK = GoalKey.of("lock", Lockfile.class);
    private static final GoalKey<Path> EXISTING_JAR = GoalKey.of("existing-jar", Path.class);
    private static final GoalKey<Path> SCRATCH = GoalKey.of("scratch", Path.class);
    private static final GoalKey<Path> REBUILT_JAR = GoalKey.of("rebuilt-jar", Path.class);
    private static final GoalKey<String> EXISTING_HASH = GoalKey.of("existing-hash", String.class);
    private static final GoalKey<String> REBUILT_HASH = GoalKey.of("rebuilt-hash", String.class);

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            System.err.println("jk verify: jk.toml and jk.lock required in " + dir);
            return 2;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + jk.lock");
                    JkBuild project = JkBuildParser.parse(buildFile);
                    Lockfile lock = LockfileReader.read(lockFile);
                    Path existingJar = BuildLayout.of(dir, project).mainJar();
                    if (!Files.exists(existingJar)) {
                        ctx.error("missing-jar", "no existing jar at " + existingJar
                                + " — run `jk build` first.");
                        throw new RuntimeException("missing existing jar");
                    }
                    ctx.put(PROJECT, project);
                    ctx.put(LOCK, lock);
                    ctx.put(EXISTING_JAR, existingJar);
                    ctx.put(SCRATCH, Files.createTempDirectory("jk-verify-"));
                    ctx.progress(1);
                })
                .build();

        Phase rebuild = Phase.builder("rebuild-scratch")
                .kind(PhaseKind.CPU)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("rebuild into scratch");
                    JkBuild project = ctx.require(PROJECT);
                    Lockfile lock = ctx.require(LOCK);
                    Path scratch = ctx.require(SCRATCH);
                    Path classesA = scratch.resolve("classes");
                    Path jarA = scratch.resolve(project.project().artifact()
                            + "-" + project.project().version() + ".jar");
                    try {
                        buildOnce(dir, project, lock, classesA, jarA, cache);
                    } catch (IOException e) {
                        ctx.error("build", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.put(REBUILT_JAR, jarA);
                    ctx.progress(1);
                })
                .build();

        Phase compare = Phase.builder("compare-hashes")
                .requires("rebuild-scratch")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("sha256 both jars");
                    String existingHash = Hashing.sha256Hex(
                            Files.readAllBytes(ctx.require(EXISTING_JAR)));
                    String rebuildHash = Hashing.sha256Hex(
                            Files.readAllBytes(ctx.require(REBUILT_JAR)));
                    ctx.put(EXISTING_HASH, existingHash);
                    ctx.put(REBUILT_HASH, rebuildHash);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("verify-build")
                .addPhase(parseBuild)
                .addPhase(rebuild)
                .addPhase(compare)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        goal.get(SCRATCH).ifPresent(VerifyBuildCommand::deleteRecursively);

        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("missing-jar".equals(d.code())) return 66;
            }
            return 1;
        }

        String existing = goal.get(EXISTING_HASH).orElseThrow();
        String rebuilt = goal.get(REBUILT_HASH).orElseThrow();
        if (!global.outputIsJson()) {
            System.out.println("Existing: " + existing);
            System.out.println("Rebuilt : " + rebuilt);
        }
        if (existing.equals(rebuilt)) {
            if (!global.outputIsJson()) System.out.println("Reproducible.");
            return 0;
        }
        System.err.println("Not reproducible — see diff above.");
        return 1;
    }

    private void buildOnce(Path projectDir, JkBuild project, Lockfile lock,
                           Path classesOut, Path jarOut, Path cache) throws IOException {
        Path srcMain = projectDir.resolve("src/main/java");
        Path resMain = projectDir.resolve("src/main/resources");
        List<Path> sources = CompileCommand.collectJavaSources(srcMain);
        Cas cas = new Cas(cache);
        List<Path> classpath = new ClasspathResolver(cas)
                .classpathFor(lock, ClasspathResolver.COMPILE_MAIN);
        int release = project.project().javaRelease();

        Files.createDirectories(classesOut);
        if (!sources.isEmpty()) {
            CompileResult result = new JavacDriver().compile(CompileRequest.builder()
                    .sources(sources)
                    .classpath(classpath)
                    .outputDir(classesOut)
                    .release(release)
                    .javaHome(CompileToolchain.resolveJavaHome(projectDir))
                    .build());
            if (!result.success()) {
                throw new IOException("scratch rebuild failed: see compile diagnostics");
            }
        }
        if (Files.isDirectory(resMain)) {
            try (var stream = Files.walk(resMain)) {
                for (Path src : (Iterable<Path>) stream::iterator) {
                    if (Files.isDirectory(src)) continue;
                    Path relative = resMain.relativize(src);
                    Path target = classesOut.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(src, target);
                }
            }
        }
        new JarPackager().packageJar(JarPackager.JarRequest.of(classesOut, jarOut));
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
