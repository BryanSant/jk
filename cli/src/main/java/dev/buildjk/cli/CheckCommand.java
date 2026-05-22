// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.compile.ClasspathResolver;
import dev.buildjk.compile.CompileRequest;
import dev.buildjk.compile.CompileResult;
import dev.buildjk.compile.JavacDriver;
import dev.buildjk.compile.KotlincDriver;
import dev.buildjk.compile.KotlincRequest;
import dev.buildjk.compile.KotlincResult;
import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Profile;
import dev.buildjk.model.Profiles;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk check} — type-check sources without producing artifacts.
 *
 * <p>v0.2 first slice: Java only, single source set ({@code src/main/java}),
 * Maven-resolved classpath from {@code jk.lock}. Kotlin and annotation
 * processors join in later slices.
 */
@Command(name = "check", description = "Type-check without producing artifacts")
public final class CheckCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--profile", paramLabel = "<name>",
            description = "Build profile to apply. Default: auto (ci if CI=true, else none).")
    String profileName;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the CAS cache directory. Default: ~/.jk/cache.")
    Path cacheDir;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("build.jk");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk check: no build.jk in " + dir);
            return 2;
        }
        if (!Files.exists(lockFile)) {
            System.err.println("jk check: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }

        BuildJk project;
        try {
            project = BuildJkParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk check: " + e.getMessage());
            return 2;
        }
        Lockfile lock = LockfileReader.read(lockFile);

        List<Path> javaSources = collectJavaSources(dir.resolve("src/main/java"));
        List<Path> ktSources = collectKotlinSources(dir);
        if (javaSources.isEmpty() && ktSources.isEmpty()) {
            System.out.println("jk check: no sources in src/main/{java,kotlin}");
            return 0;
        }

        Path cache = cacheDir != null
                ? cacheDir
                : Path.of(System.getProperty("user.home"), ".jk", "cache");
        Cas cas = new Cas(cache);
        List<Path> classpath = new ClasspathResolver(cas)
                .classpathFor(lock, ClasspathResolver.COMPILE_MAIN);

        Profile profile = resolveProfile(project.profiles(), profileName);

        int release = parseReleaseFromJdk(project.project().jdk());

        Path scratch = Files.createTempDirectory("jk-check-");
        Path javaHome = CompileToolchain.resolveJavaHome(dir);
        try {
            if (!javaSources.isEmpty()) {
                CompileRequest request = CompileRequest.builder()
                        .sources(javaSources)
                        .classpath(classpath)
                        .outputDir(scratch)
                        .release(release)
                        .extraOptions(profile == null ? List.of() : profile.javacArgs())
                        .javaHome(javaHome)
                        .build();
                CompileResult result = new JavacDriver().compile(request);
                for (CompileResult.Diagnostic d : result.diagnostics()) {
                    System.err.println(d.render());
                }
                if (!result.success() || result.hasErrors()) return 1;
            }
            if (!ktSources.isEmpty()) {
                List<Path> kotlincCp = new ArrayList<>(classpath);
                kotlincCp.add(scratch);
                Path kotlinHome = CompileToolchain.resolveKotlinHome(cache);
                KotlincResult result = new KotlincDriver().compile(
                        KotlincRequest.builder()
                                .sources(ktSources)
                                .classpath(kotlincCp)
                                .outputDir(scratch)
                                .jvmTarget(kotlinJvmTarget(release))
                                .kotlinHome(kotlinHome)
                                .build());
                if (!result.success()) {
                    System.err.print(result.output());
                    return 1;
                }
            }
        } finally {
            deleteRecursively(scratch);
        }
        int total = javaSources.size() + ktSources.size();
        System.out.println("jk check: ok (" + total + " source"
                + (total == 1 ? "" : "s") + ")");
        return 0;
    }

    /**
     * Kotlin 2.2.0 doesn't yet emit bytecode targeting Java 25+; cap at
     * the latest LTS (21). Resulting bytecode runs on the project's actual
     * JDK fine because Java is bytecode-backward-compatible.
     */
    static int kotlinJvmTarget(int release) {
        return Math.min(release, 21);
    }

    static List<Path> collectKotlinSources(Path projectDir) throws IOException {
        List<Path> out = new ArrayList<>();
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/kotlin"), ".kt"));
        // Also pick up .kt files placed under src/main/java/, a common
        // shortcut Maven users take. kotlinc handles both layouts.
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/java"), ".kt"));
        return out;
    }

    private static List<Path> collectFilesWithExtension(Path root, String extension) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .forEach(result::add);
        }
        return result;
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) return;
        try (Stream<Path> stream = Files.walk(target)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    static List<Path> collectJavaSources(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(sources::add);
        }
        return sources;
    }

    /**
     * Pick the active profile. Explicit {@code --profile} wins. Otherwise
     * the {@code ci} profile is auto-selected when running on CI.
     */
    static Profile resolveProfile(Profiles profiles, String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) {
            return profiles.resolve(explicitName);
        }
        String auto = Profiles.autoSelect(System.getenv());
        if (auto != null && profiles.contains(auto)) {
            return profiles.resolve(auto);
        }
        return null;
    }

    /**
     * Parse a release number from the {@code project.jdk} pin. Accepts
     * {@code "25"} or SDKMAN-style {@code "25.0.3-tem"}. Defaults to 25
     * if absent or unparseable.
     */
    static int parseReleaseFromJdk(String jdk) {
        if (jdk == null || jdk.isBlank()) return 25;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < jdk.length(); i++) {
            char c = jdk.charAt(i);
            if (Character.isDigit(c)) digits.append(c);
            else break;
        }
        if (digits.length() == 0) return 25;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 25;
        }
    }
}
