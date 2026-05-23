// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.gradle;

import dev.buildjk.compat.ImportReport;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort scanner for {@code build.gradle} / {@code build.gradle.kts}
 * (PRD §24.3). Recognises declarative idioms — {@code plugins { id("...") }},
 * {@code dependencies { implementation("g:a:v") }}, {@code java { toolchain }},
 * {@code repositories { maven { url ... } }}, top-level {@code group} /
 * {@code version} — and emits {@link ImportReport} entries for anything that
 * looks like programmatic Gradle (custom tasks, {@code subprojects {}},
 * {@code afterEvaluate}, configuration injection).
 *
 * <p>No Gradle script is actually evaluated; this is a string-level matcher.
 * The slice C/D Maven importer is the truth-table side of jk import; this
 * side openly admits it can't faithfully convert arbitrary Gradle.
 */
public final class GradleImporter {

    public record Result(BuildJk buildJk, ImportReport report) {}

    // No regex for comment stripping — naive `//` would eat the `//` inside URL
    // literals. See stripComments below.

    // String literal: "foo" or 'bar', preserving the quoted body.
    private static final String STR = "(?:\"([^\"\\n]*)\"|'([^'\\n]*)')";

    private static final Pattern GROUP_ASSIGN = Pattern.compile(
            "(?m)^\\s*group\\s*[=]?\\s*" + STR);
    private static final Pattern VERSION_ASSIGN = Pattern.compile(
            "(?m)^\\s*version\\s*[=]?\\s*" + STR);
    private static final Pattern ROOT_NAME = Pattern.compile(
            "(?m)^\\s*rootProject\\.name\\s*=\\s*" + STR);

    // plugins { id("...") version "..." ; id 'foo' ; kotlin("jvm") ; java ; application }
    private static final Pattern PLUGIN_ID = Pattern.compile(
            "id\\s*\\(?\\s*" + STR + "\\s*\\)?");
    private static final Pattern PLUGIN_KOTLIN = Pattern.compile(
            "kotlin\\s*\\(\\s*" + STR + "\\s*\\)");

    // dependencies entries: implementation("g:a:v") or testImplementation 'g:a:v'
    private static final Pattern DEP_ENTRY = Pattern.compile(
            "(?m)^\\s*(?<config>[a-zA-Z][a-zA-Z0-9_]*)\\s*"
                    + "(?:\\(\\s*(?<paren>.+?)\\s*\\)|" + STR + ")"
                    + "\\s*$");

    // java { sourceCompatibility = JavaVersion.VERSION_21 }
    private static final Pattern JAVA_VERSION_TOKEN = Pattern.compile(
            "JavaVersion\\.VERSION_([0-9_]+)|JavaLanguageVersion\\.of\\(\\s*([0-9]+)\\s*\\)"
                    + "|sourceCompatibility\\s*[=]?\\s*['\"]?([0-9.]+)['\"]?"
                    + "|jvmToolchain\\s*\\(\\s*([0-9]+)\\s*\\)");

    // repositories: maven { url = uri("https://...") } or maven { url 'https://...' }
    private static final Pattern MAVEN_URL = Pattern.compile(
            "maven\\s*\\{[^}]*?url\\s*[=]?\\s*(?:uri\\s*\\(\\s*)?" + STR);

    private GradleImporter() {}

    public static Result importFrom(Path script) throws IOException {
        String text = Files.readString(script);
        String defaultArtifact = defaultArtifactFor(script);
        return importFromString(text, defaultArtifact);
    }

    public static Result importFromString(String text, String defaultArtifact) {
        String stripped = stripComments(text);
        ImportReport.Builder report = ImportReport.builder();

        String group = firstString(GROUP_ASSIGN, stripped).orElse("com.example");
        String version = firstString(VERSION_ASSIGN, stripped).orElse("0.1.0");
        String jdk = detectJdk(stripped).orElse("25");
        boolean kotlinDsl = false;

        // plugins block — for diagnostics only; kotlin/java/application are accepted.
        String pluginsBody = extractBlock(stripped, "plugins").orElse("");
        for (Matcher m = PLUGIN_KOTLIN.matcher(pluginsBody); m.find();) {
            kotlinDsl = true;
            String pluginName = firstNonNull(m.group(1), m.group(2));
            if (pluginName != null && !pluginName.isBlank()) {
                report.warning("Kotlin plugin `" + pluginName + "` recognised; jk supports Kotlin natively"
                        + " (PRD §3). No build.jk flag is needed.");
            }
        }
        for (Matcher m = PLUGIN_ID.matcher(pluginsBody); m.find();) {
            String pluginId = firstNonNull(m.group(1), m.group(2));
            if (pluginId == null || pluginId.isBlank()) continue;
            switch (pluginId) {
                case "java", "java-library", "application" -> {
                    // implicit in jk — nothing to say.
                }
                default -> report.warning("Gradle plugin `" + pluginId + "` not yet mapped."
                        + " Plugin-aware mappings (Spring Boot, Quarkus, Spotless, ...) arrive in a later slice.");
            }
        }

        Map<Scope, List<Dependency>> deps = parseDependencies(stripped, report);
        List<RepositorySpec> repos = parseRepositories(stripped, report);
        warnUnsupportedSections(stripped, report);

        BuildJk.Project project = new BuildJk.Project(group, defaultArtifact, version, jdk);
        BuildJk buildJk = new BuildJk(project, new BuildJk.Dependencies(deps), repos);
        return new Result(buildJk, report.build());
    }

    private static String defaultArtifactFor(Path script) {
        Path parent = script.toAbsolutePath().getParent();
        if (parent != null && parent.getFileName() != null) {
            String name = parent.getFileName().toString();
            if (!name.isEmpty()) return name;
        }
        return "app";
    }

    // --- dependency block ---------------------------------------------------

    private static Map<Scope, List<Dependency>> parseDependencies(String text, ImportReport.Builder report) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        String body = extractBlock(text, "dependencies").orElse(null);
        if (body == null) return byScope;
        for (String rawLine : body.split("\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher m = DEP_ENTRY.matcher(line);
            if (!m.matches()) {
                if (!line.equals("{") && !line.equals("}")) {
                    report.error("dependencies entry not understood: `" + line
                            + "` — jk import is best-effort and only handles string-form deps."
                            + " Re-state the dep in build.jk as `dependencies.main { \"g:a\" = \"=v\" }`.");
                }
                continue;
            }
            String configuration = m.group("config");
            String paren = m.group("paren");
            String s1 = m.group(3);
            String s2 = m.group(4);
            String quoted = firstNonNull(s1, s2);

            Scope scope = mapConfiguration(configuration);
            if (scope == null) {
                report.error("Gradle configuration `" + configuration
                        + "` is not a recognised jk scope; entry dropped (`" + line + "`).");
                continue;
            }

            if (paren != null) {
                // Could be platform("g:a:v"), project(":core"), kotlin("test"), or a bare "g:a:v".
                Matcher platform = Pattern.compile("platform\\s*\\(\\s*" + STR + "\\s*\\)").matcher(paren);
                if (platform.find()) {
                    String coord = firstNonNull(platform.group(1), platform.group(2));
                    addDependency(byScope, Scope.PLATFORM, coord, report);
                    continue;
                }
                Matcher project = Pattern.compile("project\\s*\\(\\s*" + STR + "\\s*\\)").matcher(paren);
                if (project.find()) {
                    String path = firstNonNull(project.group(1), project.group(2));
                    report.warning("Project dependency `" + path + "` on configuration `" + configuration
                            + "` was not mapped. Convert via a jk workspace member reference once you import the sibling module.");
                    continue;
                }
                Matcher kotlinShortcut = Pattern.compile("kotlin\\s*\\(\\s*" + STR + "\\s*\\)").matcher(paren);
                if (kotlinShortcut.find()) {
                    String token = firstNonNull(kotlinShortcut.group(1), kotlinShortcut.group(2));
                    report.warning("Kotlin shortcut `kotlin(\"" + token + "\")` was not mapped to a concrete coord."
                            + " Add the explicit `org.jetbrains.kotlin:kotlin-" + token + ":<version>` to build.jk.");
                    continue;
                }
                Matcher bareString = Pattern.compile("^\\s*" + STR + "\\s*$").matcher(paren);
                if (bareString.find()) {
                    String coord = firstNonNull(bareString.group(1), bareString.group(2));
                    addDependency(byScope, scope, coord, report);
                    continue;
                }
                report.error("complex dependency expression `" + line + "` not understood;"
                        + " re-state as a string-form coord in build.jk.");
            } else if (quoted != null) {
                addDependency(byScope, scope, quoted, report);
            }
        }
        return byScope;
    }

    private static void addDependency(Map<Scope, List<Dependency>> byScope, Scope scope,
                                      String coord, ImportReport.Builder report) {
        if (coord == null || coord.isBlank()) return;
        // Expect g:a:v with optional :classifier@type — strip extras with a warning.
        String[] parts = coord.split(":");
        if (parts.length < 3) {
            report.error("dependency coord `" + coord + "` is not `group:artifact:version`; dropped.");
            return;
        }
        String module = parts[0] + ":" + parts[1];
        String versionToken = parts[2];
        if (parts.length > 3) {
            report.warning("classifier/type on `" + coord + "` dropped; jk support arrives in a later slice.");
        }
        if (versionToken.contains("$")) {
            report.warning("dependency `" + coord + "` uses a Gradle variable for its version;"
                    + " jk wrote `" + versionToken + "` verbatim — resolve the variable manually.");
        }
        VersionSelector selector = VersionSelector.parse(versionToken);
        byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(new Dependency(module, selector));
    }

    private static Scope mapConfiguration(String configuration) {
        return switch (configuration) {
            case "implementation", "api", "compile" -> Scope.MAIN;
            case "runtimeOnly", "runtime" -> Scope.RUNTIME;
            case "compileOnly", "compileOnlyApi", "providedRuntime", "providedCompile" -> Scope.PROVIDED;
            case "testImplementation", "testApi", "testCompile",
                 "testRuntimeOnly", "testRuntime", "testCompileOnly" -> Scope.TEST;
            case "annotationProcessor", "kapt", "ksp", "testAnnotationProcessor" -> Scope.PROCESSOR;
            default -> null;
        };
    }

    // --- repositories block -------------------------------------------------

    private static List<RepositorySpec> parseRepositories(String text, ImportReport.Builder report) {
        String body = extractBlock(text, "repositories").orElse(null);
        if (body == null) return List.of();
        Map<String, RepositorySpec> deduped = new LinkedHashMap<>();
        for (Matcher m = MAVEN_URL.matcher(body); m.find();) {
            String url = firstNonNull(m.group(1), m.group(2));
            if (url == null || url.isBlank()) continue;
            // Skip the implicit Central — declaring it adds nothing.
            if (url.startsWith("https://repo.maven.apache.org/")
                    || url.startsWith("https://repo1.maven.org/")) continue;
            try {
                String name = "repo" + (deduped.size() + 1);
                deduped.put(name, new RepositorySpec(name, new URI(url.trim())));
            } catch (URISyntaxException e) {
                report.warning("repository URL `" + url + "` is not a valid URI; skipped.");
            }
        }
        if (body.contains("mavenLocal()")) {
            report.warning("`mavenLocal()` recognised but not mapped — jk reads ~/.m2 by default (PRD §11).");
        }
        return new ArrayList<>(deduped.values());
    }

    // --- java / kotlin toolchain --------------------------------------------

    private static Optional<String> detectJdk(String text) {
        Matcher m = JAVA_VERSION_TOKEN.matcher(text);
        if (!m.find()) return Optional.empty();
        String raw = firstNonNull(m.group(1), m.group(2), m.group(3), m.group(4));
        if (raw == null || raw.isBlank()) return Optional.empty();
        // VERSION_21 / VERSION_1_8 → "21" / "1.8"
        String normalized = raw.replace('_', '.');
        if (normalized.startsWith("1.")) {
            return Optional.of(normalized);
        }
        // Strip a leading "1." vestige if present.
        return Optional.of(normalized);
    }

    // --- catch-all tier 3 warnings ------------------------------------------

    private static final String[] TIER3_KEYWORDS = {
            "subprojects", "allprojects", "afterEvaluate", "beforeEvaluate",
            "configurations", "tasks.register", "task ", "withType", "ext {", "buildscript",
    };

    private static void warnUnsupportedSections(String text, ImportReport.Builder report) {
        for (String kw : TIER3_KEYWORDS) {
            if (text.contains(kw)) {
                report.error("script uses `" + kw.trim()
                        + "` — programmatic Gradle blocks cannot be imported automatically."
                        + " Convert the intent to a jk profile/feature/task by hand.");
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Strip {@code //} line comments and {@code /* * /} block comments while
     * leaving string literals untouched. A regex-based pass would mis-eat the
     * {@code //} inside URL strings like {@code "https://example.com"}.
     */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            // Block comment.
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
                continue;
            }
            // Line comment.
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int eol = text.indexOf('\n', i + 2);
                i = eol < 0 ? n : eol;
                continue;
            }
            // Double-quoted string — copy through, honouring \"escapes\".
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = text.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(text.charAt(i));
                        i++;
                    } else if (d == '"') {
                        break;
                    } else if (d == '\n') {
                        break; // bail out on unterminated literal — Groovy permits.
                    }
                }
                continue;
            }
            // Single-quoted string.
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = text.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(text.charAt(i));
                        i++;
                    } else if (d == '\'' || d == '\n') {
                        break;
                    }
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static Optional<String> firstString(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return Optional.empty();
        String s = firstNonNull(m.group(1), m.group(2));
        return s == null ? Optional.empty() : Optional.of(s);
    }

    /** Extract the contents of the first top-level {@code name { ... }} block by brace matching. */
    private static Optional<String> extractBlock(String text, String name) {
        Pattern header = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*\\{");
        Matcher m = header.matcher(text);
        if (!m.find()) return Optional.empty();
        int open = m.end() - 1; // position of the '{'
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return Optional.of(text.substring(open + 1, i));
                }
            }
        }
        return Optional.empty();
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Read {@code rootProject.name} from a {@code settings.gradle(.kts)} if present. */
    public static Optional<String> readRootProjectName(Path settings) throws IOException {
        if (!Files.exists(settings)) return Optional.empty();
        return firstString(ROOT_NAME, stripComments(Files.readString(settings)));
    }

    @SuppressWarnings("unused") // helper for future locale-sensitive matching
    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
