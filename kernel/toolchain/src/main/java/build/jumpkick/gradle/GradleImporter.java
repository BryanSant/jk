// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.gradle;

import build.jumpkick.compat.ImportReport;
import build.jumpkick.kotlin.KotlinResolver;
import build.jumpkick.model.Dependency;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.RepositorySpec;
import build.jumpkick.model.Scope;
import build.jumpkick.model.VersionSelector;
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
 * Best-effort scanner for {@code build.gradle} / {@code build.gradle.kts} (PRD §24.3). Recognises
 * declarative idioms — {@code plugins { id("...") }}, {@code dependencies { implementation("g:a:v")
 * }}, {@code java { toolchain }}, {@code repositories { maven { url ... } }}, top-level {@code
 * group} / {@code version} — and emits {@link ImportReport} entries for anything that looks like
 * programmatic Gradle (custom tasks, {@code subprojects {}}, {@code afterEvaluate}, configuration
 * injection).
 *
 * <p>No Gradle script is actually evaluated; this is a string-level matcher. The slice C/D Maven
 * importer is the truth-table side of jk import; this side openly admits it can't faithfully
 * convert arbitrary Gradle.
 */
public final class GradleImporter {

    public record Result(JkBuild jkBuild, ImportReport report) {}

    // No regex for comment stripping — naive `//` would eat the `//` inside URL
    // literals. See stripComments below.

    // String literal: "foo" or 'bar', preserving the quoted body.
    private static final String STR = "(?:\"([^\"\\n]*)\"|'([^'\\n]*)')";

    private static final Pattern GROUP_ASSIGN = Pattern.compile("(?m)^\\s*group\\s*[=]?\\s*" + STR);
    private static final Pattern VERSION_ASSIGN = Pattern.compile("(?m)^\\s*version\\s*[=]?\\s*" + STR);
    // description = "..." / description "..." (optionally project.description).
    private static final Pattern DESCRIPTION_ASSIGN =
            Pattern.compile("(?m)^\\s*(?:project\\.)?description\\s*[=]?\\s*" + STR);
    private static final Pattern ROOT_NAME = Pattern.compile("(?m)^\\s*rootProject\\.name\\s*=\\s*" + STR);

    // plugins { id("...") version "..." ; id 'foo' ; kotlin("jvm") ; java ; application }
    private static final Pattern PLUGIN_ID = Pattern.compile("id\\s*\\(?\\s*" + STR + "\\s*\\)?");
    private static final Pattern PLUGIN_KOTLIN = Pattern.compile("kotlin\\s*\\(\\s*" + STR + "\\s*\\)");
    // kotlin("jvm") version "2.3.21" — version is groups 3/4 (after the plugin arg).
    private static final Pattern KOTLIN_PLUGIN_VERSION =
            Pattern.compile("kotlin\\s*\\(\\s*" + STR + "\\s*\\)\\s*version\\s*" + STR);
    // id("org.jetbrains.kotlin.jvm") [version "2.3.21"]
    private static final Pattern KOTLIN_ID = Pattern.compile("id\\s*\\(?\\s*[\"']org\\.jetbrains\\.kotlin[^\"']*[\"']");
    private static final Pattern KOTLIN_ID_VERSION =
            Pattern.compile("id\\s*\\(\\s*[\"']org\\.jetbrains\\.kotlin[^\"']*[\"']\\s*\\)\\s*version\\s*" + STR);

    /** One installed plugin's Gradle-import mapping: which table + config key a plugin id feeds. */
    private record PluginImportRule(String manifestId, String versionTo, String missingVersionWarning) {}

    /** Gradle plugin id → import rule, from every installed manifest's [[import.gradle-plugin]]. */
    private static Map<String, PluginImportRule> pluginImportRules() {
        Map<String, PluginImportRule> rules = new java.util.LinkedHashMap<>();
        for (var manifest : build.jumpkick.plugin.manifest.PluginTableRegistry.manifests()) {
            for (var rule : manifest.gradleImports()) {
                rules.put(
                        rule.id(),
                        new PluginImportRule(manifest.id(), rule.versionTo(), rule.missingVersionWarning()));
            }
        }
        return rules;
    }

    /**
     * Evaluate the import rules against the plugins block: a rule with {@code version-to} maps
     * the Gradle plugin's inline version into the owned table's config (only that key — schema
     * defaults are exactly what the renderer omits, so the round trip stays minimal); declared
     * without a version, the rule's warning is reported instead. Version-less rules are
     * recognition-only (their construct is absorbed by another contribution, e.g. Boot's BOM
     * auto-import covering dependency-management).
     */
    private static List<build.jumpkick.plugin.PluginConfig> mapPluginTables(
            String pluginsBody, Map<String, PluginImportRule> rules, ImportReport.Builder report) {
        List<build.jumpkick.plugin.PluginConfig> out = new ArrayList<>();
        for (Map.Entry<String, PluginImportRule> e : rules.entrySet()) {
            if (!pluginsBody.contains(e.getKey())) continue;
            PluginImportRule rule = e.getValue();
            if (rule.versionTo() == null) continue; // recognition-only
            Pattern versionPattern = Pattern.compile(
                    "id\\s*\\(?\\s*[\"']" + Pattern.quote(e.getKey()) + "[\"']\\s*\\)?\\s*version\\s*" + STR);
            Matcher m = versionPattern.matcher(pluginsBody);
            if (m.find()) {
                out.add(new build.jumpkick.plugin.PluginConfig(
                        rule.manifestId(), java.util.Map.of(rule.versionTo(), firstNonNull(m.group(1), m.group(2)))));
            } else if (rule.missingVersionWarning() != null) {
                report.warning(rule.missingVersionWarning());
            }
        }
        return out;
    }

    // application { mainClass.set("X") } / mainClass = "X" — groups 1/2 (set) or 3/4 (=).
    private static final Pattern APPLICATION_MAIN_CLASS =
            Pattern.compile("mainClass\\s*(?:\\.set\\s*\\(\\s*" + STR + "\\s*\\)|=\\s*" + STR + ")");
    // Groovy / older DSL: mainClassName = "X".
    private static final Pattern MAIN_CLASS_NAME = Pattern.compile("(?m)^\\s*mainClassName\\s*[=]?\\s*" + STR);

    // A manifest attribute pair inside a `manifest { attributes(...) }` block, in
    // either Kotlin (`"K" to V`) or Groovy (`"K": V`) form. Value is a string
    // literal (groups 3/4) or a bare expression like project.version (group 5).
    private static final Pattern MANIFEST_ATTR =
            Pattern.compile(STR + "\\s*(?:to|:)\\s*(?:" + STR + "|([A-Za-z_][\\w.]*))");

    // dependencies entries: implementation("g:a:v"), testImplementation 'g:a:v',
    // or the unquoted Groovy catalog form `implementation libs.junit.jupiter`.
    private static final Pattern DEP_ENTRY = Pattern.compile("(?m)^\\s*(?<config>[a-zA-Z][a-zA-Z0-9_]*)\\s*"
            + "(?:\\(\\s*(?<paren>.+?)\\s*\\)|"
            + STR
            + "|(?<accessor>[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+))"
            + "\\s*$");

    // A type-safe version-catalog accessor, e.g. libs.junit.platform.launcher.
    private static final Pattern CATALOG_ACCESSOR = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    // platform(libs.spring.bom) — a catalog accessor wrapped in platform(...).
    private static final Pattern PLATFORM_ACCESSOR =
            Pattern.compile("platform\\s*\\(\\s*(" + CATALOG_ACCESSOR.pattern() + ")\\s*\\)");

    // java { sourceCompatibility = JavaVersion.VERSION_21 }
    private static final Pattern JAVA_VERSION_TOKEN =
            Pattern.compile("JavaVersion\\.VERSION_([0-9_]+)|JavaLanguageVersion\\.of\\(\\s*([0-9]+)\\s*\\)"
                    + "|sourceCompatibility\\s*[=]?\\s*['\"]?([0-9.]+)['\"]?"
                    + "|jvmToolchain\\s*\\(\\s*([0-9]+)\\s*\\)");

    // repositories: maven { url = uri("https://...") } or maven { url 'https://...' }
    private static final Pattern MAVEN_URL =
            Pattern.compile("maven\\s*\\{[^}]*?url\\s*[=]?\\s*(?:uri\\s*\\(\\s*)?" + STR);

    private GradleImporter() {}

    public static Result importFrom(Path script) throws IOException {
        String text = Files.readString(script);
        String defaultArtifact = defaultArtifactFor(script);
        Path projectDir = script.toAbsolutePath().getParent();
        GradleVersionCatalog catalog =
                GradleVersionCatalog.forProject(projectDir).orElse(null);
        return importFromString(text, defaultArtifact, catalog);
    }

    public static Result importFromString(String text, String defaultArtifact) {
        return importFromString(text, defaultArtifact, null);
    }

    public static Result importFromString(String text, String defaultArtifact, GradleVersionCatalog catalog) {
        String stripped = stripComments(text);
        ImportReport.Builder report = ImportReport.builder();

        String group = firstString(GROUP_ASSIGN, stripped).orElse("com.example");
        String version = firstString(VERSION_ASSIGN, stripped).orElse("0.1.0");
        String description = firstString(DESCRIPTION_ASSIGN, stripped).orElse(null);
        int jdk = detectJdk(stripped).flatMap(GradleImporter::parseInt).orElse(25);

        // plugins block — the Kotlin plugin marks a Kotlin project (and carries
        // its compiler version); ids claimed by an installed jk plugin's [[import.gradle-plugin]]
        // rules map to that plugin's table below; the rest are diagnostics only.
        String pluginsBody = extractBlock(stripped, "plugins").orElse("");
        VersionSelector kotlin = detectKotlinVersion(pluginsBody, report);
        Map<String, PluginImportRule> importRules = pluginImportRules();
        for (Matcher m = PLUGIN_ID.matcher(pluginsBody); m.find(); ) {
            String pluginId = firstNonNull(m.group(1), m.group(2));
            if (pluginId == null || pluginId.isBlank()) continue;
            if (pluginId.startsWith("org.jetbrains.kotlin")) continue; // handled as a Kotlin project
            switch (pluginId) {
                case "java", "java-library", "application" -> {
                    // implicit in jk — nothing to say.
                }
                default -> {
                    if (!importRules.containsKey(pluginId)) {
                        report.warning("Gradle plugin `"
                                + pluginId
                                + "` not yet mapped."
                                + " Plugin-aware mappings (Spring Boot, Quarkus, Spotless, ...) arrive in a later slice.");
                    }
                }
            }
        }

        // Jar-manifest attributes from `jar { manifest { attributes(...) } }`
        // (or tasks.jar / tasks.named("jar")). Main-Class routes to [application].main.
        Map<String, String> manifest = detectManifestAttributes(stripped, group, defaultArtifact, version, report);
        String mainClass = detectMainClass(stripped);
        String manifestMain = manifest.remove("Main-Class");
        if (mainClass == null) mainClass = manifestMain;

        // Plugin-owned tables: each installed jk plugin's [[import.gradle-plugin]] rules map a
        // Gradle plugin id to its table (Boot: the Gradle plugin's version IS the Boot version ->
        // `version`, which auto-imports the BOM so versionless starters stay versionless).
        // Applied-without-version (settings pluginManagement) can't be resolved from this file
        // alone -- the rule's warning asks the user to fill it in.
        List<build.jumpkick.plugin.PluginConfig> pluginConfigs =
                mapPluginTables(pluginsBody, importRules, report);

        Map<Scope, List<Dependency>> deps = parseDependencies(stripped, catalog, report);
        List<RepositorySpec> repos = parseRepositories(stripped, report);
        warnUnsupportedSections(stripped, report);

        // A Kotlin project sets `kotlin` (a version) and leaves `java` at 0 —
        // the two are mutually exclusive. javaRelease() falls back to jdk.
        int java = kotlin != null ? 0 : jdk;
        JkBuild.Project project = JkBuild.Project.builder(group, defaultArtifact, version)
                .jdkMajor(jdk)
                .java(java)
                .kotlin(kotlin)
                .description(description)
                .build();
        JkBuild.Application application = mainClass != null ? new JkBuild.Application(mainClass, false) : null;
        JkBuild.Builder builder = JkBuild.builder(project)
                .dependencies(new JkBuild.Dependencies(deps))
                .repositories(repos)
                .application(application);
        for (build.jumpkick.plugin.PluginConfig config : pluginConfigs) {
            builder.pluginConfig(config);
        }
        JkBuild jkBuild = builder.build();
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        return new Result(jkBuild, report.build());
    }

    /**
     * Parse manifest attributes from a {@code manifest { attributes(...) }} block (inside {@code
     * jar}/{@code tasks.jar}/{@code tasks.named("jar")}). Supports Kotlin ({@code "K" to V}) and
     * Groovy ({@code "K": V}) forms. String-literal values pass through; the common Gradle
     * expressions {@code project.version}/{@code name}/{@code group} resolve to the imported
     * coordinates; other expressions are skipped with a report note. The returned map preserves
     * declaration order and may contain {@code Main-Class} (the caller routes it to {@code
     * [application].main}).
     */
    private static Map<String, String> detectManifestAttributes(
            String text, String group, String artifact, String version, ImportReport.Builder report) {
        String body = extractBlock(text, "manifest").orElse(null);
        if (body == null) return new java.util.LinkedHashMap<>();
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        for (Matcher m = MANIFEST_ATTR.matcher(body); m.find(); ) {
            String key = firstNonNull(m.group(1), m.group(2));
            if (key == null) continue;
            String literal = firstNonNull(m.group(3), m.group(4));
            String value;
            if (literal != null) {
                value = literal;
            } else {
                String expr = m.group(5);
                value = resolveGradleExpr(expr, group, artifact, version);
                if (value == null) {
                    report.warning("manifest attribute `"
                            + key
                            + "` uses the Gradle expression `"
                            + expr
                            + "`, which jk import can't resolve; the attribute was dropped."
                            + " Add it to the [manifest] table in jk.toml if needed.");
                    continue;
                }
            }
            attrs.put(key, value);
        }
        return attrs;
    }

    /** Resolve common Gradle property expressions used in manifest values; null if unknown. */
    private static String resolveGradleExpr(String expr, String group, String artifact, String version) {
        if (expr == null) return null;
        String lower = expr.toLowerCase(Locale.ROOT);
        if (lower.endsWith("version")) return version;
        if (lower.endsWith("name")) return artifact;
        if (lower.endsWith("group")) return group;
        return null;
    }

    /**
     * Detect the Kotlin compiler version from the {@code plugins {}} block. Recognises {@code
     * kotlin("jvm") version "X"} and {@code id("org.jetbrains.kotlin.jvm") version "X"}. When the
     * Kotlin plugin is applied without an explicit version, defaults to a floating {@link
     * KotlinResolver#DEFAULT_VERSION} (pinned later by {@code jk lock}). Returns {@code null} for a
     * non-Kotlin (Java) project.
     */
    private static VersionSelector detectKotlinVersion(String pluginsBody, ImportReport.Builder report) {
        Matcher m = KOTLIN_PLUGIN_VERSION.matcher(pluginsBody);
        if (m.find()) {
            return VersionSelector.parseFloating(firstNonNull(m.group(3), m.group(4)));
        }
        Matcher mid = KOTLIN_ID_VERSION.matcher(pluginsBody);
        if (mid.find()) {
            return VersionSelector.parseFloating(firstNonNull(mid.group(1), mid.group(2)));
        }
        boolean kotlinApplied = PLUGIN_KOTLIN.matcher(pluginsBody).find()
                || KOTLIN_ID.matcher(pluginsBody).find();
        if (kotlinApplied) {
            report.warning("Kotlin plugin recognised without an explicit version; defaulted"
                    + " project.kotlin to "
                    + KotlinResolver.DEFAULT_VERSION
                    + " (floating)."
                    + " `jk lock` pins it — edit jk.toml to change.");
            return VersionSelector.parseFloating(KotlinResolver.DEFAULT_VERSION);
        }
        return null;
    }

    /**
     * Extract the application main class from {@code application { mainClass.set("X") }} / {@code
     * mainClass = "X"} (Kotlin DSL) or a top-level {@code mainClassName = "X"} (Groovy). Returns
     * {@code null} when none is declared.
     */
    private static String detectMainClass(String text) {
        String appBody = extractBlock(text, "application").orElse(null);
        if (appBody != null) {
            Matcher m = APPLICATION_MAIN_CLASS.matcher(appBody);
            if (m.find()) {
                String v = firstNonNull(m.group(1), m.group(2), m.group(3), m.group(4));
                if (v != null) return v;
            }
        }
        Matcher anywhere = APPLICATION_MAIN_CLASS.matcher(text);
        if (anywhere.find()) {
            String v = firstNonNull(anywhere.group(1), anywhere.group(2), anywhere.group(3), anywhere.group(4));
            if (v != null) return v;
        }
        Matcher legacy = MAIN_CLASS_NAME.matcher(text);
        if (legacy.find()) {
            return firstNonNull(legacy.group(1), legacy.group(2));
        }
        return null;
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

    private static Map<Scope, List<Dependency>> parseDependencies(
            String text, GradleVersionCatalog catalog, ImportReport.Builder report) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        String body = extractBlock(text, "dependencies").orElse(null);
        if (body == null) return byScope;
        for (String rawLine : body.split("\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher m = DEP_ENTRY.matcher(line);
            if (!m.matches()) {
                if (!line.equals("{") && !line.equals("}")) {
                    report.error("dependencies entry not understood: `"
                            + line
                            + "` — jk import is best-effort and only handles string-form deps."
                            + " Re-state the dep in jk.toml under [dependencies] as `\"g:a\" = { version = \"=v\" }`.");
                }
                continue;
            }
            String configuration = m.group("config");
            String paren = m.group("paren");
            String accessor = m.group("accessor");
            String s1 = m.group(3);
            String s2 = m.group(4);
            String quoted = firstNonNull(s1, s2);

            Scope scope = mapConfiguration(configuration);
            if (scope == null) {
                report.error("Gradle configuration `"
                        + configuration
                        + "` is not a recognised jk scope; entry dropped (`"
                        + line
                        + "`).");
                continue;
            }

            // Unquoted Groovy catalog form: `implementation libs.junit.jupiter`.
            if (accessor != null) {
                resolveCatalogAccessor(accessor, scope, byScope, catalog, report);
                continue;
            }

            if (paren != null) {
                // platform(libs.x) — version-catalog accessor wrapped in platform().
                Matcher platformAccessor = PLATFORM_ACCESSOR.matcher(paren);
                if (platformAccessor.find()) {
                    resolveCatalogAccessor(platformAccessor.group(1), Scope.PLATFORM, byScope, catalog, report);
                    continue;
                }
                // Could be platform("g:a:v"), project(":core"), kotlin("test"), or a bare "g:a:v".
                Matcher platform =
                        Pattern.compile("platform\\s*\\(\\s*" + STR + "\\s*\\)").matcher(paren);
                if (platform.find()) {
                    String coord = firstNonNull(platform.group(1), platform.group(2));
                    addDependency(byScope, Scope.PLATFORM, coord, report);
                    continue;
                }
                Matcher project =
                        Pattern.compile("project\\s*\\(\\s*" + STR + "\\s*\\)").matcher(paren);
                if (project.find()) {
                    String path = firstNonNull(project.group(1), project.group(2));
                    report.warning(
                            "Project dependency `"
                                    + path
                                    + "` on configuration `"
                                    + configuration
                                    + "` was not mapped. Convert via a jk workspace module reference once you import the sibling module.");
                    continue;
                }
                Matcher kotlinShortcut =
                        Pattern.compile("kotlin\\s*\\(\\s*" + STR + "\\s*\\)").matcher(paren);
                if (kotlinShortcut.find()) {
                    String token = firstNonNull(kotlinShortcut.group(1), kotlinShortcut.group(2));
                    report.warning("Kotlin shortcut `kotlin(\""
                            + token
                            + "\")` was not mapped to a concrete coord."
                            + " Add the explicit `org.jetbrains.kotlin:kotlin-"
                            + token
                            + ":<version>` to jk.toml.");
                    continue;
                }
                Matcher bareString = Pattern.compile("^\\s*" + STR + "\\s*$").matcher(paren);
                if (bareString.find()) {
                    String coord = firstNonNull(bareString.group(1), bareString.group(2));
                    addDependency(byScope, scope, coord, report);
                    continue;
                }
                // implementation(libs.junit.platform.launcher) — catalog accessor.
                if (CATALOG_ACCESSOR.matcher(paren.trim()).matches()) {
                    resolveCatalogAccessor(paren.trim(), scope, byScope, catalog, report);
                    continue;
                }
                report.error("complex dependency expression `"
                        + line
                        + "` not understood;"
                        + " re-state as a string-form coord in jk.toml.");
            } else if (quoted != null) {
                addDependency(byScope, scope, quoted, report);
            }
        }
        return byScope;
    }

    private static void addDependency(
            Map<Scope, List<Dependency>> byScope, Scope scope, String coord, ImportReport.Builder report) {
        if (coord == null || coord.isBlank()) return;
        // Expect g:a:v with optional :classifier@type — strip extras with a warning.
        String[] parts = coord.split(":");
        if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
            // Versionless `g:a` -- normal in Boot builds, where the plugin's BOM manages the
            // version. jk models it as platform-managed; [spring-boot] (or an explicit
            // [platform-dependencies] BOM) supplies the pin at resolve time.
            byScope.computeIfAbsent(scope, s -> new ArrayList<>())
                    .add(Dependency.platformManaged(parts[1], coord));
            return;
        }
        if (parts.length < 3) {
            report.error("dependency coord `" + coord + "` is not `group:artifact:version`; dropped.");
            return;
        }
        String module = parts[0] + ":" + parts[1];
        String artifactId = parts[1];
        String versionToken = parts[2];
        if (parts.length > 3) {
            report.warning("classifier/type on `" + coord + "` dropped; jk support arrives in a later slice.");
        }
        if (versionToken.contains("$")) {
            report.warning("dependency `"
                    + coord
                    + "` uses a Gradle variable for its version;"
                    + " jk wrote `"
                    + versionToken
                    + "` verbatim — resolve the variable manually.");
        }
        VersionSelector selector = VersionSelector.parse(versionToken);
        // Default the v0.7 short `name` to the Gradle dep's artifactId.
        byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(Dependency.of(artifactId, module, selector));
    }

    /**
     * Resolve a version-catalog accessor (e.g. {@code libs.junit.platform.launcher} or {@code
     * libs.bundles.testing}) against the located catalog and add the resulting coordinate(s). The
     * leading segment is the catalog name and is stripped before lookup. Unresolvable accessors —
     * missing catalog, unknown alias, or a version/plugin accessor that isn't a dependency — are
     * reported.
     */
    private static void resolveCatalogAccessor(
            String accessor,
            Scope scope,
            Map<Scope, List<Dependency>> byScope,
            GradleVersionCatalog catalog,
            ImportReport.Builder report) {
        if (catalog == null) {
            report.error("dependency `"
                    + accessor
                    + "` references a Gradle version catalog, but no"
                    + " gradle/libs.versions.toml was found (searched the project dir and its parent)."
                    + " Declare the coordinate directly in jk.toml.");
            return;
        }
        int firstDot = accessor.indexOf('.');
        String rest = accessor.substring(firstDot + 1); // drop the catalog name (e.g. "libs.")

        if (rest.startsWith("bundles.")) {
            String bundle = rest.substring("bundles.".length());
            Optional<List<String>> coords = catalog.resolveBundle(bundle);
            if (coords.isEmpty() || coords.get().isEmpty()) {
                report.error("bundle `"
                        + accessor
                        + "` was not found in the version catalog"
                        + " (or none of its libraries resolved); dropped.");
                return;
            }
            for (String coord : coords.get()) {
                addDependency(byScope, scope, coord, report);
            }
            return;
        }
        if (rest.startsWith("versions.") || rest.startsWith("plugins.")) {
            report.warning("catalog accessor `"
                    + accessor
                    + "` refers to a version/plugin, not a"
                    + " library; jk import only maps library and bundle accessors. Skipped.");
            return;
        }

        Optional<String> coord = catalog.resolveLibrary(rest);
        if (coord.isEmpty()) {
            report.error("library `"
                    + accessor
                    + "` was not found in the version catalog"
                    + " (or has no resolvable version); dropped. Declare it directly in jk.toml.");
            return;
        }
        addDependency(byScope, scope, coord.get(), report);
    }

    private static Scope mapConfiguration(String configuration) {
        return switch (configuration) {
            case "implementation", "api", "compile" -> Scope.MAIN;
            case "runtimeOnly", "runtime" -> Scope.RUNTIME;
            // Boot's dev-loop configurations (spring-boot plan §3.2) map 1:1 to jk's dev scopes.
            case "developmentOnly" -> Scope.DEV;
            case "testAndDevelopmentOnly" -> Scope.TEST_DEV;
            case "compileOnly", "compileOnlyApi", "providedRuntime", "providedCompile" -> Scope.PROVIDED;
            case "testImplementation", "testApi", "testCompile", "testRuntimeOnly", "testRuntime", "testCompileOnly" ->
                Scope.TEST;
            case "annotationProcessor", "kapt", "ksp", "testAnnotationProcessor" -> Scope.PROCESSOR;
            default -> null;
        };
    }

    // --- repositories block -------------------------------------------------

    private static List<RepositorySpec> parseRepositories(String text, ImportReport.Builder report) {
        String body = extractBlock(text, "repositories").orElse(null);
        if (body == null) return List.of();
        Map<String, RepositorySpec> deduped = new LinkedHashMap<>();
        for (Matcher m = MAVEN_URL.matcher(body); m.find(); ) {
            String url = firstNonNull(m.group(1), m.group(2));
            if (url == null || url.isBlank()) continue;
            // Skip the implicit Central — declaring it adds nothing.
            if (url.startsWith("https://repo.maven.apache.org/") || url.startsWith("https://repo1.maven.org/"))
                continue;
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
        "subprojects",
        "allprojects",
        "afterEvaluate",
        "beforeEvaluate",
        "configurations",
        "tasks.register",
        "task ",
        "withType",
        "ext {",
        "buildscript",
    };

    private static void warnUnsupportedSections(String text, ImportReport.Builder report) {
        for (String kw : TIER3_KEYWORDS) {
            if (text.contains(kw)) {
                report.error("script uses `"
                        + kw.trim()
                        + "` — programmatic Gradle blocks cannot be imported automatically."
                        + " Convert the intent to a jk profile/feature/task by hand.");
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Strip {@code //} line comments and {@code /* * /} block comments while leaving string literals
     * untouched. A regex-based pass would mis-eat the {@code //} inside URL strings like {@code
     * "https://example.com"}.
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

    private static Optional<Integer> parseInt(String s) {
        try {
            return Optional.of(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
