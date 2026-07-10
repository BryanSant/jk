// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.repo.RepoGroup;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Builds a source-dependency target directory into a jar + POM, dispatching on the build system it
 * finds on disk:
 *
 * <ul>
 *   <li><b>jk</b> ({@code jk.toml}) → {@link LocalProjectBuilder} (resolve → compile → jar → POM,
 *       compile-only). Coordinate/version from {@code [project]}; version overridable (git deps
 *       supply a ref-derived version).
 *   <li><b>Gradle</b> ({@code build.gradle[.kts]} / {@code settings.gradle[.kts]}) → best-effort
 *       {@code assemble -x test}.
 *   <li><b>Maven</b> ({@code pom.xml}) → best-effort {@code package -Dmaven.test.skip=true}.
 * </ul>
 *
 * <p>Foreign (Gradle/Maven) builds are a <b>best-effort convenience</b>: prefer the project's own
 * wrapper ({@code ./gradlew} / {@code ./mvnw}), else the tool on {@code PATH}, else fail fast. All
 * tool output is discarded; any nonzero exit or missing/ambiguous artifact fails fast. Tests are
 * never run. The GAV is derived (Maven from {@code pom.xml}, Gradle from a {@code properties}
 * query), and a <b>GAV-only POM</b> (no transitive dependencies) is synthesized — the foreign jar is
 * a leaf. A user who needs a foreign project's tests to run, or its transitive deps resolved, should
 * vendor it as a jk {@code [workspace]} module instead (jk-only).
 */
final class SourceProjectBuilder {

    private SourceProjectBuilder() {}

    /** The built artifact: coordinate/version plus the on-disk jar path and POM text. */
    record Built(String group, String artifact, String version, Path jar, String pomXml) {
        String coordinate() {
            return group + ":" + artifact + ":" + version;
        }
    }

    /**
     * Build the project rooted at {@code projectDir}. For a jk target, {@code versionOverride} (when
     * non-null) replaces the {@code [project]} version — git deps pass their ref-derived version;
     * path deps pass {@code null}. It is ignored for foreign targets, which declare their own
     * version.
     */
    static Built build(
            Path projectDir,
            String versionOverride,
            Path javaHome,
            Cas cas,
            RepoGroup repos,
            String jkVersion)
            throws IOException, InterruptedException {

        if (Files.isRegularFile(projectDir.resolve("jk.toml"))) {
            JkBuild project = JkBuildParser.parse(Files.readString(projectDir.resolve("jk.toml")));
            String group = project.project().group();
            String artifact = project.project().name();
            String version = versionOverride != null ? versionOverride : project.project().version();
            LocalProjectBuilder.Built b =
                    LocalProjectBuilder.build(projectDir, project, group, artifact, version, javaHome, cas, repos, jkVersion);
            return new Built(b.group(), b.artifact(), b.version(), b.jar(), b.pomXml());
        }
        if (isGradleProject(projectDir)) {
            return buildGradle(projectDir, javaHome);
        }
        if (Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            return buildMaven(projectDir, javaHome);
        }
        throw new IOException(projectDir
                + ": no jk.toml, build.gradle(.kts), or pom.xml — cannot build this dependency target"
                + " (only jk, Gradle, and Maven projects are supported)");
    }

    private static boolean isGradleProject(Path dir) {
        return Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"))
                || Files.isRegularFile(dir.resolve("settings.gradle"))
                || Files.isRegularFile(dir.resolve("settings.gradle.kts"));
    }

    // ---- Gradle ----------------------------------------------------------------

    private static Built buildGradle(Path projectDir, Path javaHome) throws IOException, InterruptedException {
        String tool = resolveTool(projectDir, "gradlew", "gradle");

        // GAV: `gradle properties` prints `group: <g>` and `version: <v>`.
        RunResult props = run(
                projectDir, javaHome, List.of(tool, "properties", "-q", "--console=plain"), true);
        if (props.exitCode() != 0) {
            throw new IOException("gradle properties failed (exit " + props.exitCode() + ") in " + projectDir);
        }
        String group = gradleProperty(props.stdout(), "group");
        String version = gradleProperty(props.stdout(), "version");
        requireGav(group, "group", projectDir);
        requireGav(version, "version", projectDir);

        // Build (tests never run): `assemble` compiles + jars main; `-x test` is belt-and-braces.
        RunResult build = run(
                projectDir, javaHome, List.of(tool, "assemble", "-x", "test", "-q", "--console=plain"), false);
        if (build.exitCode() != 0) {
            throw new IOException("gradle assemble failed (exit " + build.exitCode() + ") in " + projectDir);
        }

        Path jar = selectMainJar(projectDir.resolve("build/libs"), projectDir);
        String artifact = artifactFromJar(jar.getFileName().toString(), version);
        return new Built(group, artifact, version, jar, leafPom(group, artifact, version));
    }

    /** Parse a `key: value` line from `gradle properties` output; null if absent/blank/unspecified. */
    static String gradleProperty(String propertiesOutput, String key) {
        String prefix = key + ":";
        for (String line : propertiesOutput.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(prefix)) {
                String value = trimmed.substring(prefix.length()).strip();
                if (value.isEmpty() || value.equals("unspecified")) return null;
                return value;
            }
        }
        return null;
    }

    /** Strip a trailing {@code -<version>} and {@code .jar} to recover the archive base name. */
    static String artifactFromJar(String jarFileName, String version) {
        String base = jarFileName.endsWith(".jar") ? jarFileName.substring(0, jarFileName.length() - 4) : jarFileName;
        String suffix = "-" + version;
        if (base.endsWith(suffix)) {
            base = base.substring(0, base.length() - suffix.length());
        }
        return base;
    }

    // ---- Maven -----------------------------------------------------------------

    private static Built buildMaven(Path projectDir, Path javaHome) throws IOException, InterruptedException {
        String tool = resolveTool(projectDir, "mvnw", "mvn");

        Gav gav = parseMavenGav(projectDir.resolve("pom.xml"));
        requireGav(gav.group(), "groupId", projectDir);
        requireGav(gav.artifact(), "artifactId", projectDir);
        requireGav(gav.version(), "version", projectDir);

        // `-Dmaven.test.skip=true` skips test *compile* and *run*; `-DskipTests` is belt-and-braces.
        RunResult build = run(
                projectDir, javaHome,
                List.of(tool, "package", "-q", "-DskipTests", "-Dmaven.test.skip=true"), false);
        if (build.exitCode() != 0) {
            throw new IOException("mvn package failed (exit " + build.exitCode() + ") in " + projectDir);
        }

        Path target = projectDir.resolve("target");
        Path preferred = target.resolve(gav.artifact() + "-" + gav.version() + ".jar");
        Path jar = Files.isRegularFile(preferred) ? preferred : selectMainJar(target, projectDir);
        return new Built(gav.group(), gav.artifact(), gav.version(),
                jar, leafPom(gav.group(), gav.artifact(), gav.version()));
    }

    record Gav(String group, String artifact, String version) {}

    /** Read groupId/artifactId/version from a {@code pom.xml}, inheriting group/version from {@code <parent>}. */
    static Gav parseMavenGav(Path pomXml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Element project;
            try (InputStream in = Files.newInputStream(pomXml)) {
                project = factory.newDocumentBuilder().parse(in).getDocumentElement();
            }
            Element parent = firstChild(project, "parent");
            String group = childText(project, "groupId");
            if (group == null && parent != null) group = childText(parent, "groupId");
            String artifact = childText(project, "artifactId");
            String version = childText(project, "version");
            if (version == null && parent != null) version = childText(parent, "version");
            if (version != null && version.contains("${")) {
                throw new IOException(pomXml + ": version `" + version + "` uses an unresolved property"
                        + " — path/git Maven deps must declare a literal version");
            }
            return new Gav(group, artifact, version);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("failed to parse " + pomXml + ": " + e.getMessage(), e);
        }
    }

    /** First direct child element named {@code tag}, or null. */
    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    /** Text of the first direct child element named {@code tag}, or null. */
    private static String childText(Element parent, String tag) {
        Element child = firstChild(parent, tag);
        if (child == null) return null;
        String text = child.getTextContent();
        return text == null || text.isBlank() ? null : text.strip();
    }

    // ---- shared helpers --------------------------------------------------------

    /** GAV-only POM (no {@code <dependencies>}): the foreign jar is a leaf dependency. */
    static String leafPom(String group, String artifact, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                </project>
                """.formatted(group, artifact, version);
    }

    private static void requireGav(String value, String what, Path projectDir) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(projectDir + ": could not determine " + what + " for the dependency target");
        }
    }

    /**
     * Pick the single main jar from {@code libsDir}, ignoring {@code -sources}/{@code -javadoc}/{@code
     * -plain} classifier jars. Fails fast on zero (nothing built) or more than one (multi-artifact /
     * multi-module project — not supported for a source dependency).
     */
    static Path selectMainJar(Path libsDir, Path projectDir) throws IOException {
        if (!Files.isDirectory(libsDir)) {
            throw new IOException(projectDir + ": no output directory " + libsDir + " after build");
        }
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(libsDir)) {
            entries.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".jar")
                        && !n.endsWith("-sources.jar")
                        && !n.endsWith("-javadoc.jar")
                        && !n.endsWith("-plain.jar")) {
                    candidates.add(p);
                }
            });
        }
        if (candidates.isEmpty()) {
            throw new IOException(projectDir + ": no jar produced in " + libsDir);
        }
        if (candidates.size() > 1) {
            List<String> names = candidates.stream().map(p -> p.getFileName().toString()).sorted().toList();
            throw new IOException(projectDir + ": ambiguous build output in " + libsDir + " " + names
                    + " — multi-artifact / multi-module projects are not supported as a source dependency");
        }
        return candidates.get(0);
    }

    /**
     * Prefer the project's wrapper ({@code <wrapper>} / {@code <wrapper>.bat} on Windows) when present
     * and executable; else the {@code <bin>} tool on {@code PATH}; else fail fast.
     */
    static String resolveTool(Path projectDir, String wrapper, String bin) throws IOException {
        String wrapperName = isWindows() ? wrapper + ".bat" : wrapper;
        Path wrapperPath = projectDir.resolve(wrapperName);
        // Maven's Windows wrapper is `mvnw.cmd`; try it as a fallback.
        if (isWindows() && !Files.exists(wrapperPath) && wrapper.equals("mvnw")) {
            wrapperPath = projectDir.resolve("mvnw.cmd");
        }
        if (Files.isRegularFile(wrapperPath)) {
            if (!Files.isExecutable(wrapperPath)) {
                throw new IOException(wrapperPath + " is not executable — `chmod +x` it, or remove it to"
                        + " fall back to the tool on PATH");
            }
            return wrapperPath.toAbsolutePath().toString();
        }
        Path onPath = findOnPath(bin);
        if (onPath != null) {
            return onPath.toAbsolutePath().toString();
        }
        throw new IOException("no " + wrapper + " wrapper in " + projectDir + " and no `" + bin
                + "` on PATH — cannot build this dependency target");
    }

    private static Path findOnPath(String bin) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        List<String> names = isWindows() ? List.of(bin + ".bat", bin + ".cmd", bin + ".exe", bin) : List.of(bin);
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : names) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private record RunResult(int exitCode, String stdout) {}

    /**
     * Run {@code command} in {@code projectDir} with a scrubbed environment (JAVA_HOME set to {@code
     * javaHome}). When {@code captureStdout}, stdout is returned and stderr discarded; otherwise both
     * streams are discarded. This is best-effort: we never surface the tool's console.
     */
    private static RunResult run(Path projectDir, Path javaHome, List<String> command, boolean captureStdout)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile());
        applyEnv(pb.environment(), javaHome);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        if (!captureStdout) {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            return new RunResult(p.waitFor(), "");
        }
        Process p = pb.start();
        // Drain stdout fully before waitFor() to avoid a pipe-buffer deadlock.
        String out;
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            out = buf.toString(StandardCharsets.UTF_8);
        }
        return new RunResult(p.waitFor(), out);
    }

    /** Scrub tool-behavior override vars; set JAVA_HOME and prepend {@code <jdk>/bin} to PATH. */
    private static void applyEnv(Map<String, String> env, Path javaHome) {
        for (String key : new String[]{"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "KOTLIN_HOME", "MAVEN_OPTS", "GRADLE_OPTS"}) {
            env.remove(key);
        }
        if (javaHome != null) {
            String home = javaHome.toAbsolutePath().toString();
            env.put("JAVA_HOME", home);
            String binDir = javaHome.resolve("bin").toAbsolutePath().toString();
            String pathKey = env.containsKey("PATH") ? "PATH" : "PATH";
            String existing = env.getOrDefault(pathKey, "");
            String sep = isWindows() ? ";" : ":";
            env.put(pathKey, existing.isEmpty() ? binDir : binDir + sep + existing);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
