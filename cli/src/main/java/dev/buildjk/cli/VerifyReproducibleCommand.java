// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.compile.ClasspathResolver;
import dev.buildjk.compile.CompileRequest;
import dev.buildjk.compile.CompileResult;
import dev.buildjk.compile.JarPackager;
import dev.buildjk.compile.JavacDriver;
import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.util.Hashing;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk verify-reproducible} — re-build the project into a clean
 * scratch directory and compare the produced jar's SHA-256 against the
 * existing {@code target/<artifact>-<version>.jar} (PRD §23.7,
 * impl-plan §7 step 3).
 *
 * <p>The first build is the user's existing checkout. This verb does the
 * second pass and the comparison — if jk's build is reproducible, the
 * two sha256s match.
 */
@Command(name = "verify-reproducible",
        description = "Rebuild into a scratch dir and diff the jar against target/.")
public final class VerifyReproducibleCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the CAS cache directory. Default: ~/.jk/cache.")
    Path cacheDir;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("build.jk");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            System.err.println("jk verify-reproducible: build.jk and jk.lock required in " + dir);
            return 2;
        }

        BuildJk project = BuildJkParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);
        Path existingJar = dir.resolve("target").resolve(
                project.project().artifact() + "-" + project.project().version() + ".jar");
        if (!Files.exists(existingJar)) {
            System.err.println("jk verify-reproducible: no existing jar at " + existingJar
                    + " — run `jk build` first.");
            return 66;
        }

        Path scratch = Files.createTempDirectory("jk-verify-");
        try {
            Path classesA = scratch.resolve("classes");
            Path jarA = scratch.resolve(project.project().artifact()
                    + "-" + project.project().version() + ".jar");
            buildOnce(dir, project, lock, classesA, jarA);

            String existingHash = Hashing.sha256Hex(Files.readAllBytes(existingJar));
            String rebuildHash = Hashing.sha256Hex(Files.readAllBytes(jarA));

            System.out.println("Existing: " + existingHash);
            System.out.println("Rebuilt : " + rebuildHash);
            if (existingHash.equals(rebuildHash)) {
                System.out.println("Reproducible.");
                return 0;
            }
            System.err.println("Not reproducible — see diff above.");
            return 1;
        } finally {
            deleteRecursively(scratch);
        }
    }

    private void buildOnce(Path projectDir, BuildJk project, Lockfile lock,
                           Path classesOut, Path jarOut) throws IOException {
        Path srcMain = projectDir.resolve("src/main/java");
        Path resMain = projectDir.resolve("src/main/resources");
        List<Path> sources = CheckCommand.collectJavaSources(srcMain);
        Path cache = cacheDir != null
                ? cacheDir
                : Path.of(System.getProperty("user.home"), ".jk", "cache");
        Cas cas = new Cas(cache);
        List<Path> classpath = new ClasspathResolver(cas)
                .classpathFor(lock, ClasspathResolver.COMPILE_MAIN);
        int release = CheckCommand.parseReleaseFromJdk(project.project().jdk());

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
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
