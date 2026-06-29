// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Feature;
import dev.jkbuild.model.Features;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.ObjectStoreConfig;
import dev.jkbuild.model.PluginDeclaration;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Profiles;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.model.Workspace;
import dev.jkbuild.model.Workspace.WorkspaceDependency;
import dev.jkbuild.util.GitUrl;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Loads {@code jk.toml} into a {@link JkBuild}.
 *
 * <p>v0.7 schema (see {@code docs/artifact-coord-design.md}):
 *
 * <ul>
 *   <li>{@code [project]} — required; {@code group}, {@code name}, {@code version} required;
 *       optional {@code jdk}, {@code main}, {@code java}/{@code kotlin}, {@code shadow}, {@code
 *       native}, {@code description}.
 *   <li>{@code [dependencies]} — MAIN scope; library-as-key flat table, each entry is {@code <lib>
 *       = { group, name?, version | path | git | workspace }}. Additional scopes use top-level
 *       section names: {@code [test-dependencies]}, {@code [provided-dependencies]}, {@code
 *       [processor-dependencies]}, {@code [export-dependencies]}.
 *   <li>{@code [workspace]} — optional; {@code modules = [...]} plus an optional {@code
 *       [workspace.dependencies]} table of shared external deps inherited by modules via {@code
 *       <name>.workspace = true}.
 *   <li>{@code [repositories]} — optional; per-name URL string or inline table.
 *   <li>{@code [profiles.<name>]} — optional; per-profile {@code inherits}, {@code javac}, {@code
 *       jvm-args}.
 *   <li>{@code [features]} — optional; {@code default = [...]} plus {@code [features.<name>]}
 *       sub-tables whose {@code deps} fields are <b>dep names</b> (not coord strings).
 * </ul>
 */
public final class JkBuildParser {

    private JkBuildParser() {}

    /**
     * Memoises {@link #parse(Path)} results for the life of the process, keyed by file identity (path
     * + size + mtime). jk is single-shot, so a manifest's bytes don't change mid-invocation;
     * workspace commands, on the other hand, re-resolve the same handful of manifests over and over
     * (e.g. {@code jk idea} runs {@code WorkspaceClasspath.resolve} twice per module, and each call
     * re-reads the root plus every sibling {@code jk.toml}). That turns an N-module workspace into
     * O(N²) ANTLR parses; caching collapses it to one parse per distinct file. Keying on size+mtime
     * means a manifest that actually changes on disk (a test rewriting it) re-parses cleanly.
     */
    private static final Map<CacheKey, JkBuild> PARSE_CACHE = new ConcurrentHashMap<>();

    private record CacheKey(Path path, long size, FileTime modified) {}

    public static JkBuild parse(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            throw new JkBuildParseException("jk.toml not found: " + file);
        }
        CacheKey key = new CacheKey(file.toAbsolutePath().normalize(), attrs.size(), attrs.lastModifiedTime());
        JkBuild cached = PARSE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        JkBuild parsed = parse(Files.readString(file));
        PARSE_CACHE.put(key, parsed);
        return parsed;
    }

    public static JkBuild parse(String toml) {
        return parse(toml, LibraryCatalog.layered());
    }

    /**
     * Test seam: parse against a synthetic library catalog instead of the default layered one. The
     * manifest's own {@code [libraries]} table is still layered on top via {@link
     * LibraryCatalog#withProjectOverrides}.
     */
    public static JkBuild parse(String toml, LibraryCatalog catalog) {
        Objects.requireNonNull(toml, "toml");
        Objects.requireNonNull(catalog, "catalog");
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(
                    "failed to parse jk.toml: " + result.errors().getFirst().getMessage());
        }
        JkBuild.Project project = parseProject(result);
        Workspace workspace = parseWorkspace(result);
        LibraryCatalog effective = catalog.withProjectOverrides(parseProjectLibraries(result));
        JkBuild.Dependencies deps = parseDependencies(result, workspace, effective);
        List<RepositorySpec> repos = parseRepositories(result);
        Profiles profiles = parseProfiles(result);
        Features features = parseFeatures(result);
        Map<String, String> manifest = parseManifest(result);
        List<PluginDeclaration> plugins = parsePlugins(result);
        JkBuild.NativeConfig nativeConfig = parseNativeConfig(result);
        JkBuild.Build build = parseBuild(result);
        JkBuild.FormatConfig format = parseFormat(result);
        return new JkBuild(
                project, deps, repos, profiles, features, workspace, manifest, plugins, nativeConfig, build, format);
    }

    /**
     * Parse the optional {@code [format]} table — the styles {@code jk format} uses. {@code style} is
     * a cross-language preset; {@code java} / {@code kotlin} are per-language overrides. All are
     * plain strings, validated downstream by {@code jk format} (the model + parser stay
     * tool-agnostic). Absent → EMPTY.
     */
    private static JkBuild.FormatConfig parseFormat(TomlTable root) {
        TomlTable format = root.getTable("format");
        if (format == null) return JkBuild.FormatConfig.EMPTY;
        Boolean optimizeImports = format.contains("optimize-imports") ? format.getBoolean("optimize-imports") : null;
        return new JkBuild.FormatConfig(
                stringOrThrow(format, "style", "format.style"),
                stringOrThrow(format, "java", "format.java"),
                stringOrThrow(format, "kotlin", "format.kotlin"),
                optimizeImports);
    }

    /** Read an optional string key; present-but-non-string is a parse error; absent → null. */
    private static String stringOrThrow(TomlTable table, String key, String path) {
        if (!table.contains(key)) return null;
        String value = table.getString(key);
        if (value == null) {
            throw new JkBuildParseException(path + " must be a string");
        }
        return value;
    }

    /**
     * Parse the optional top-level {@code [manifest]} table — string-valued custom jar-manifest
     * attributes (e.g. {@code "Implementation-Title"}). {@code Main-Class} is intentionally
     * <em>not</em> read here; it derives from {@code project.main}.
     */
    private static Map<String, String> parseManifest(TomlParseResult root) {
        TomlTable table = root.getTable("manifest");
        if (table == null) return Map.of();
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        for (String key : table.keySet()) {
            String value = table.getString(key);
            if (value == null) {
                throw new JkBuildParseException("manifest." + key + " must be a string");
            }
            if (key.equalsIgnoreCase("Main-Class")) {
                throw new JkBuildParseException("manifest.Main-Class is not allowed; set project.main instead");
            }
            attrs.put(key, value);
        }
        return attrs;
    }

    /**
     * Parse the optional top-level {@code [libraries]} table. Empty map when absent. Validated
     * through {@link LibraryCatalog#parseLibrariesTable} so the schema matches the bundled and user
     * files.
     */
    private static java.util.Map<String, LibraryCatalog.Module> parseProjectLibraries(TomlTable root) {
        TomlTable libraries = root.getTable("libraries");
        if (libraries == null) return java.util.Map.of();
        try {
            return LibraryCatalog.parseLibrariesTable(libraries, "jk.toml");
        } catch (IllegalStateException e) {
            throw new JkBuildParseException(e.getMessage(), e);
        }
    }

    private static JkBuild.Project parseProject(TomlTable root) {
        TomlTable project = root.getTable("project");
        if (project == null) {
            throw new JkBuildParseException("jk.toml must declare a top-level `[project]` table");
        }
        String group = requireString(project, "group", "project.group");
        String name = requireString(project, "name", "project.name");
        String version = requireString(project, "version", "project.version");
        String jdk = parseJdkSpec(project);
        String graal = parseGraalSpec(project);
        int java = intOrZero(project, "java", "project.java");
        VersionSelector kotlin = parseKotlinVersion(project);
        requireSupportedMajor("project.java", java);
        String main = project.getString("main");
        boolean shadow = Boolean.TRUE.equals(project.getBoolean("shadow"));
        // native = true           → ALWAYS    (eligible: `jk native` builds it; `jk install` of an app
        // builds+deploys
        // it)
        // native = "always"       → ALWAYS    (same as true)
        // native = false          → DISABLED  (never build a native artifact)
        // native absent           → SUPPORTED (not eligible — `jk native` skips it)
        // Only ALWAYS is native-eligible; `jk build` never builds native artifacts.
        Object nativeRaw = project.get("native");
        JkBuild.NativeMode nativeMode;
        if ("always".equalsIgnoreCase(nativeRaw instanceof String s ? s : "") || Boolean.TRUE.equals(nativeRaw)) {
            nativeMode = JkBuild.NativeMode.ALWAYS;
        } else if (Boolean.FALSE.equals(nativeRaw)) {
            nativeMode = JkBuild.NativeMode.DISABLED;
        } else {
            // absent → SUPPORTED: eligible for `jk native` cascade but not auto-built
            nativeMode = JkBuild.NativeMode.SUPPORTED;
        }
        // sources = true        → PUBLISH  (assembled during `jk publish` only)
        // sources = "always"   → ALWAYS   (built as package-sources phase + published)
        // sources absent/false → DISABLED (no sources jar)
        Object sourcesRaw = project.get("sources");
        JkBuild.SourcesMode sourcesMode;
        if ("always".equalsIgnoreCase(sourcesRaw instanceof String s ? s : "")) {
            sourcesMode = JkBuild.SourcesMode.ALWAYS;
        } else if (Boolean.TRUE.equals(sourcesRaw)) {
            sourcesMode = JkBuild.SourcesMode.PUBLISH;
        } else {
            sourcesMode = JkBuild.SourcesMode.DISABLED;
        }
        String description = project.getString("description");
        // application defaults to "has a main class"; an explicit key overrides
        // (e.g. application = false for a runnable project that shouldn't be
        // make-installed). m2install defaults to true: ~/.m2 is the primary artifact store.
        // Set m2install = false for jk-internal worker modules that must land in repos/local/.
        boolean application =
                project.contains("application") ? Boolean.TRUE.equals(project.getBoolean("application")) : main != null;
        boolean m2install = !Boolean.FALSE.equals(project.getBoolean("m2install"));
        // project.layout = "simple"|"traditional"|"auto" (preferred).
        // Legacy: compact = true → "simple", compact = false → keep auto-detect.
        JkBuild.Layout layout;
        if (project.contains("layout")) {
            String layoutRaw = project.getString("layout");
            try {
                layout = JkBuild.Layout.parse(layoutRaw);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(e.getMessage());
            }
        } else if (Boolean.TRUE.equals(project.getBoolean("compact"))) {
            layout = JkBuild.Layout.SIMPLE; // legacy compact = true
        } else {
            layout = JkBuild.Layout.AUTO;
        }
        return new JkBuild.Project(
                group,
                name,
                version,
                jdk,
                graal,
                java,
                kotlin,
                main,
                shadow,
                nativeMode,
                sourcesMode,
                description,
                application,
                m2install,
                layout);
    }

    /**
     * {@code project.jdk} is a JDK spec string following the same rules as {@code .jdk-version},
     * except a vendorless bare major is allowed: a vendor+major ({@code "temurin-25"}) or a bare
     * major ({@code "25"}) pins the feature release; a point release ({@code "25.0.3"}) is rejected
     * because jk keeps the patch current behind the major pointer. For convenience an unquoted
     * integer ({@code jdk = 25}) is accepted too and treated as that bare major. Absent/blank →
     * {@code null} (unset).
     */
    private static String parseJdkSpec(TomlTable project) {
        if (!project.contains("jdk")) return null;
        Object raw = project.get("jdk");
        String spec;
        if (raw instanceof Long l) {
            spec = Long.toString(l);
        } else if (raw instanceof String s) {
            spec = s.trim();
        } else {
            throw new JkBuildParseException("project.jdk must be a string, e.g. \"temurin-25\" or \"25\"");
        }
        if (spec.isEmpty()) return null;
        if (JkBuild.Project.hasPointRelease(spec)) {
            throw new JkBuildParseException("project.jdk = \""
                    + spec
                    + "\" must not pin a point release — use \"<vendor>-<major>\" or "
                    + "\"<major>\" (e.g. \"temurin-25\" or \"25\"); jk keeps the patch current.");
        }
        int major = JkBuild.Project.majorOf(spec);
        if (major == 0) {
            throw new JkBuildParseException(
                    "project.jdk = \"" + spec + "\" must include a major version (e.g. \"temurin-25\" or \"25\")");
        }
        requireSupportedMajor("project.jdk", major);
        return spec;
    }

    /**
     * {@code project.graal} selects the GraalVM whose {@code bin/native-image} {@code jk native}
     * uses. Same shape as {@code project.jdk} — a bare major ({@code 25} or {@code "25"}) or
     * vendor-hinted spec ({@code "graalvm-25"}) — plus the keyword {@code "native"} (latest Oracle
     * GraalVM). A point release is rejected; jk keeps the patch current. Resolution and any
     * auto-install happen at native-build time (see the CLI's {@code GraalResolver}), so this parser
     * only normalizes the spec. Absent/blank → {@code null} (unset).
     */
    private static String parseGraalSpec(TomlTable project) {
        if (!project.contains("graal")) return null;
        Object raw = project.get("graal");
        String spec;
        if (raw instanceof Long l) {
            spec = Long.toString(l);
        } else if (raw instanceof String s) {
            spec = s.trim();
        } else {
            throw new JkBuildParseException(
                    "project.graal must be a string, e.g. \"graalvm-25\", \"25\", or \"native\"");
        }
        if (spec.isEmpty()) return null;
        if (JkBuild.Project.hasPointRelease(spec)) {
            throw new JkBuildParseException("project.graal = \""
                    + spec
                    + "\" must not pin a point release — use \"graalvm-<major>\", "
                    + "\"<major>\", or \"native\"; jk keeps the patch current.");
        }
        return spec;
    }

    private static int intOrZero(TomlTable table, String key, String path) {
        if (!table.contains(key)) return 0;
        Long value = table.getLong(key);
        if (value == null) {
            throw new JkBuildParseException(path + " must be an integer");
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new JkBuildParseException(path + " out of range: " + value);
        }
        return value.intValue();
    }

    /**
     * {@code project.kotlin} is a Kotlin compiler version selector (string), parsed the same way as a
     * floating dependency version: bare {@code 2.3.21} → caret, {@code =2.3.21} pins. Absent → {@code
     * null} (a Java project).
     */
    private static VersionSelector parseKotlinVersion(TomlTable project) {
        if (!project.contains("kotlin")) return null;
        String raw = project.getString("kotlin");
        if (raw == null) {
            throw new JkBuildParseException("project.kotlin must be a version string, e.g. \"2.3.21\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * jk only supports JDK 17 and above (LTS + latest). Reject any older value at parse time so users
     * learn the constraint up front instead of in the middle of a resolve.
     */
    private static void requireSupportedMajor(String path, int value) {
        if (value == 0) return;
        if (value < 17) {
            throw new JkBuildParseException(path
                    + " = "
                    + value
                    + " is not supported — jk targets JDK 17 and above "
                    + "(LTS: 17, 21, 25, … plus the latest release).");
        }
    }

    // ---------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------

    private static JkBuild.Dependencies parseDependencies(
            TomlTable root, Workspace workspace, LibraryCatalog catalog) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);

        // [dependencies] → MAIN scope (all entries are flat deps, no sub-tables)
        TomlTable mainDeps = root.getTable("dependencies");
        if (mainDeps != null) {
            List<Dependency> parsed = parseScopeTable(
                    mainDeps, new ArrayList<>(mainDeps.keySet()), Scope.MAIN, workspace, catalog);
            if (!parsed.isEmpty()) byScope.put(Scope.MAIN, parsed);
        }

        // Top-level scope tables: [test-dependencies], [provided-dependencies], etc.
        addScopeDeps(byScope, root, "test-dependencies",      Scope.TEST,      workspace, catalog);
        addScopeDeps(byScope, root, "provided-dependencies",  Scope.PROVIDED,  workspace, catalog);
        addScopeDeps(byScope, root, "processor-dependencies", Scope.PROCESSOR, workspace, catalog);
        addScopeDeps(byScope, root, "export-dependencies",    Scope.EXPORT,    workspace, catalog);

        return new JkBuild.Dependencies(byScope);
    }

    private static void addScopeDeps(
            EnumMap<Scope, List<Dependency>> byScope,
            TomlTable root, String tableKey, Scope scope,
            Workspace workspace, LibraryCatalog catalog) {
        TomlTable table = root.getTable(tableKey);
        if (table == null) return;
        List<Dependency> parsed = parseScopeTable(
                table, new ArrayList<>(table.keySet()), scope, workspace, catalog);
        if (!parsed.isEmpty()) byScope.put(scope, parsed);
    }

    private static String sectionOf(Scope scope) {
        return switch (scope) {
            case MAIN      -> "dependencies";
            case TEST      -> "test-dependencies";
            case PROVIDED  -> "provided-dependencies";
            case PROCESSOR -> "processor-dependencies";
            case EXPORT    -> "export-dependencies";
            default        -> scope.canonical() + "-dependencies";
        };
    }

    private static List<Dependency> parseScopeTable(
            TomlTable scopeTable, List<String> keys, Scope scope, Workspace workspace, LibraryCatalog catalog) {
        List<Dependency> result = new ArrayList<>(keys.size());
        for (String name : keys) {
            Object value = scopeTable.get(List.of(name));
            if (value instanceof String versionShorthand) {
                // Cargo-style one-liner: `name = "1.0.0"`. Resolve the
                // coord via the bundled catalog; the user provides only
                // the version selector.
                result.add(parseShorthandEntry(name, versionShorthand, scope, catalog));
                continue;
            }
            if (!(value instanceof TomlTable entry)) {
                throw new JkBuildParseException(sectionOf(scope)
                        + "."
                        + name
                        + " must be an inline table (e.g. { group = \"...\", version = \"...\" })"
                        + " or a version-string shorthand for a catalog-known name");
            }
            result.add(parseDepEntry(name, entry, scope, workspace, catalog));
        }
        return result;
    }

    /**
     * Resolve a {@code name = "version-spec"} shorthand by looking the short name up in the bundled
     * library catalog.
     */
    private static Dependency parseShorthandEntry(String name, String versionRaw, Scope scope, LibraryCatalog catalog) {
        String displayPath = sectionOf(scope) + "." + name;
        if (versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty version string");
        }
        LibraryCatalog.Module mod = catalog.lookup(name)
                .orElseThrow(() -> new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog)));
        VersionSelector selector = VersionSelector.parseFloating(versionRaw);
        return Dependency.of(name, mod.moduleKey(), selector);
    }

    /**
     * Compose the error shown when a short name doesn't resolve. Appends a "did you mean" line when
     * the catalog has plausible alternatives — particularly useful for major-version-split families
     * like Jackson 2 vs 3, where typing the unprefixed name silently fails by design.
     */
    private static String unknownLibraryMessage(String displayPath, String name, LibraryCatalog catalog) {
        StringBuilder msg = new StringBuilder(displayPath)
                .append(" — unknown short name `")
                .append(name)
                .append("`. ");
        List<String> suggestions = catalog.suggestionsFor(name, 5);
        if (!suggestions.isEmpty()) {
            msg.append("Did you mean: ").append(String.join(", ", suggestions)).append("? ");
        }
        msg.append("Either spell out the coord as `{ group = \"...\", version = \"...\" }`, ")
                .append("or pick a curated name from the catalog ")
                .append("(see docs/artifact-coord-design.md).");
        return msg.toString();
    }

    private static Dependency parseDepEntry(
            String name, TomlTable entry, Scope scope, Workspace workspace, LibraryCatalog catalog) {
        // `optional = true` withholds the dep from the default resolution; a
        // [features] entry pulls it in by name. Works with every dep form
        // (coord / git / path / workspace / sha256) since it's applied to the
        // parsed result regardless of source.
        boolean optional = Boolean.TRUE.equals(entry.getBoolean("optional"));
        return parseDepEntryForm(name, entry, scope, workspace, catalog).withOptional(optional);
    }

    private static Dependency parseDepEntryForm(
            String name, TomlTable entry, Scope scope, Workspace workspace, LibraryCatalog catalog) {
        String displayPath = sectionOf(scope) + "." + name;
        boolean hasWorkspace = entry.contains("workspace");
        boolean hasVersion = entry.contains("version");
        boolean hasPath = entry.contains("path");
        boolean hasGit = entry.contains("git");
        boolean hasSha256 = entry.contains("sha256");

        int sourceCount = (hasVersion ? 1 : 0)
                + (hasPath ? 1 : 0)
                + (hasGit ? 1 : 0)
                + (hasWorkspace ? 1 : 0)
                + (hasSha256 ? 1 : 0);
        // Two legal multi-source pairings:
        //   git + version: version overrides the ref-derived version (git-source-deps.md).
        //   sha256 + version: version is required alongside sha256 to record the coordinate.
        // Every other multi-source combination is ambiguous.
        boolean gitWithVersionOverride = hasGit && hasVersion && !hasPath && !hasWorkspace && !hasSha256;
        boolean sha256WithVersion = hasSha256 && hasVersion && !hasPath && !hasGit && !hasWorkspace;
        if (sourceCount == 0) {
            throw new JkBuildParseException(
                    displayPath + " must set exactly one of `version`, `path`, `git`, `sha256`, or `workspace = true`");
        }
        if (sourceCount > 1 && !gitWithVersionOverride && !sha256WithVersion) {
            throw new JkBuildParseException(displayPath
                    + " sets more than one of `version` / `path` / `git` / `sha256` / `workspace`; "
                    + "pick exactly one");
        }

        if (hasWorkspace) {
            Boolean ws = entry.getBoolean("workspace");
            if (!Boolean.TRUE.equals(ws)) {
                throw new JkBuildParseException(displayPath + ".workspace must be `true` (the only legal value)");
            }
            // workspace = true is mutually exclusive with group/name too.
            if (entry.contains("group") || entry.contains("name")) {
                throw new JkBuildParseException(
                        displayPath + " with `workspace = true` must not set `group` or `name`");
            }
            return resolveWorkspaceDep(name, displayPath, workspace);
        }

        // For non-workspace deps, group/name may come from the table or
        // fall back to the bundled catalog (which keys off the short name).
        // path/git sources still REQUIRE explicit `group` — they're inherently
        // user-controlled overrides where defaulting silently would be
        // surprising.
        String groupExplicit = entry.getString("group");
        String artifactExplicit = entry.getString("name");
        LibraryCatalog.Module catalogHit =
                (groupExplicit == null) ? catalog.lookup(name).orElse(null) : null;

        String group = groupExplicit != null ? groupExplicit : (catalogHit != null ? catalogHit.group() : null);
        String artifact =
                artifactExplicit != null ? artifactExplicit : (catalogHit != null ? catalogHit.artifact() : name);
        if (artifact != null && artifact.isBlank()) {
            throw new JkBuildParseException(displayPath + ".name must not be blank");
        }

        if (hasSha256) {
            if (groupExplicit == null || groupExplicit.isBlank()) {
                throw new JkBuildParseException(displayPath
                        + " with `sha256 = ...` must set a `group` explicitly "
                        + "(catalog shorthand applies only to version-based deps)");
            }
            String sha256 = entry.getString("sha256");
            if (sha256 == null || sha256.isBlank()) {
                throw new JkBuildParseException(displayPath + ".sha256 must not be blank");
            }
            String versionRaw = entry.getString("version");
            if (versionRaw == null || versionRaw.isBlank()) {
                throw new JkBuildParseException(displayPath + " with `sha256 = ...` must also set `version`");
            }
            return Dependency.file(name, group + ":" + artifact, versionRaw, sha256);
        }

        if (hasPath) {
            if (groupExplicit == null || groupExplicit.isBlank()) {
                throw new JkBuildParseException(displayPath
                        + " with `path = ...` must set a `group` explicitly "
                        + "(catalog shorthand applies only to version-based deps)");
            }
            String path = entry.getString("path");
            if (path == null || path.isBlank()) {
                throw new JkBuildParseException(displayPath + ".path must not be blank");
            }
            return Dependency.path(name, group + ":" + artifact, path);
        }

        if (hasGit) {
            // Discovery is the default: with no `group`, the coordinate is read
            // from the cloned repo's [project] at materialization. An explicit
            // `group` overrides the coordinate (artifact defaults to the dep
            // name, as for version deps); `version` overrides the derived
            // version (docs/git-source-deps.md §"Discovery with override").
            GitSource base = parseGitSource(entry, displayPath);
            String versionOverride = entry.getString("version");
            if (hasVersion && (versionOverride == null || versionOverride.isBlank())) {
                throw new JkBuildParseException(displayPath + ".version must not be blank");
            }
            if (groupExplicit != null && !groupExplicit.isBlank()) {
                // Coordinate fully overridden — pin the module now.
                GitSource source = base.withOverrides(groupExplicit, artifact, versionOverride);
                return Dependency.git(name, groupExplicit + ":" + artifact, source);
            }
            if (artifactExplicit != null) {
                throw new JkBuildParseException(displayPath
                        + " sets `name` without `group`; set `group` too to override the "
                        + "discovered coordinate, or omit both to discover it from the repo");
            }
            // Pure discovery (modulo an optional version override): a synthetic
            // module placeholder that GitSourceResolution rewrites to the
            // discovered coordinate, mirroring the workspace-dep placeholder.
            GitSource source = base.withOverrides(null, null, versionOverride);
            return Dependency.git(name, "git:" + name, source);
        }

        // version-only.
        if (group == null || group.isBlank()) {
            throw new JkBuildParseException(displayPath + " must set a `group` (or use a catalog-known short name)");
        }
        String versionRaw = entry.getString("version");
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + ".version must not be blank");
        }
        VersionSelector selector = VersionSelector.parseFloating(versionRaw);
        return Dependency.of(name, group + ":" + artifact, selector);
    }

    private static Dependency resolveWorkspaceDep(String name, String displayPath, Workspace workspace) {
        // The workspace lookup chain: modules are resolved upstream at
        // merge time (we don't have them here at single-file parse time),
        // so first check [workspace.dependencies], then fall back to
        // emitting a placeholder coord that WorkspaceMerge can re-resolve
        // against the sibling list.
        if (workspace != null) {
            WorkspaceDependency wd = workspace.dependencies().get(name);
            if (wd != null) {
                return materialize(name, wd);
            }
        }
        // No [workspace.dependencies] match. The parser cannot resolve the
        // sibling here — that requires the full module list, which only
        // WorkspaceMerge / WorkspaceLoader has. Emit a placeholder dep
        // tagged with the short name; WorkspaceMerge resolves it. We
        // encode the unresolved state via a synthetic module of the form
        // "workspace:<name>" and a Latest selector; the resolver never
        // sees this because WorkspaceMerge rewrites it first.
        return new Dependency(
                name, "workspace:" + name, new VersionSelector.Latest("workspace"), null, null, null, false);
    }

    private static Dependency materialize(String name, WorkspaceDependency wd) {
        String module = wd.module();
        if (wd.gitSource() != null) {
            return Dependency.git(name, module, wd.gitSource());
        }
        if (wd.pathSource() != null) {
            return Dependency.path(name, module, wd.pathSource());
        }
        return Dependency.of(name, module, wd.version());
    }

    private static GitSource parseGitSource(TomlTable obj, String displayPath) {
        String urlRaw = obj.getString("git");
        if (urlRaw == null) {
            throw new JkBuildParseException(displayPath + " requires a `git` URL");
        }
        String canonical = GitUrl.canonicalize(urlRaw);
        String tag = obj.getString("tag");
        String branch = obj.getString("branch");
        String rev = obj.getString("rev");
        int set = (tag != null ? 1 : 0) + (branch != null ? 1 : 0) + (rev != null ? 1 : 0);
        if (set == 0) {
            throw new JkBuildParseException(displayPath + " must set one of `tag`, `branch`, or `rev`");
        }
        if (set > 1) {
            throw new JkBuildParseException(displayPath + " must set exactly one of `tag`, `branch`, or `rev`");
        }
        GitRefSpec ref;
        if (tag != null) ref = new GitRefSpec.Tag(tag);
        else if (branch != null) ref = new GitRefSpec.Branch(branch);
        else ref = new GitRefSpec.Rev(rev);
        String path = obj.getString("path");
        boolean submodules = obj.getBoolean("submodules", () -> true);
        boolean verifySigned = obj.getBoolean("verify-signed", () -> false);
        String fetch = obj.getString("fetch");
        if (fetch != null && !isValidFetchPolicy(fetch)) {
            throw new JkBuildParseException(displayPath
                    + ".fetch must be \"always\", \"0\", "
                    + "or a duration like \"30m\", \"12h\", \"3d\" (got: "
                    + fetch
                    + ")");
        }
        return new GitSource(canonical, urlRaw, ref, path, submodules, verifySigned).withFetch(fetch);
    }

    /**
     * A git branch-tip freshness policy: {@code "always"}/{@code "0"}, or a duration {@code
     * <n>[smhd]}.
     */
    private static boolean isValidFetchPolicy(String policy) {
        return "always".equals(policy) || "0".equals(policy) || policy.matches("\\d+[smhd]");
    }

    // ---------------------------------------------------------------------
    // Repositories / profiles / features / workspace
    // ---------------------------------------------------------------------

    private static List<RepositorySpec> parseRepositories(TomlTable root) {
        TomlTable repos = root.getTable("repositories");
        if (repos == null) return List.of();
        List<RepositorySpec> result = new ArrayList<>(repos.size());
        for (String name : repos.keySet()) {
            Object value = repos.get(name);
            String url;
            Optional<RepoCredential> credential = Optional.empty();
            Optional<ObjectStoreConfig> objectStore = Optional.empty();
            if (value instanceof String s) {
                url = s;
            } else if (value instanceof TomlTable t) {
                String u = t.getString("url");
                if (u == null) {
                    throw new JkBuildParseException("repositories." + name + " requires a string `url` field");
                }
                url = u;
                credential = parseRepoCredential(name, t);
                objectStore = parseObjectStore(name, t);
            } else {
                throw new JkBuildParseException(
                        "repositories." + name + " must be a URL string or an inline table with `url`");
            }
            try {
                result.add(new RepositorySpec(name, URI.create(url), credential, objectStore));
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("repositories." + name + " has malformed URL: " + url, e);
            }
        }
        return result;
    }

    /**
     * Optional object-store config on a {@code [repositories.<name>]} table for {@code s3://}/{@code
     * gs://} backends: {@code region}, {@code endpoint}, {@code access-key}, {@code secret-key},
     * {@code session-token}. All support {@code ${ENV}} interpolation (so keys aren't committed
     * literally); any unset field falls back to the AWS environment / default chain.
     */
    private static Optional<ObjectStoreConfig> parseObjectStore(String name, TomlTable t) {
        String region = interpolateEnv(name, t.getString("region"));
        String endpoint = interpolateEnv(name, t.getString("endpoint"));
        String accessKey = interpolateEnv(name, t.getString("access-key"));
        String secretKey = interpolateEnv(name, t.getString("secret-key"));
        String sessionToken = interpolateEnv(name, t.getString("session-token"));
        ObjectStoreConfig cfg = new ObjectStoreConfig(
                blankToNull(region),
                blankToNull(endpoint),
                blankToNull(accessKey),
                blankToNull(secretKey),
                blankToNull(sessionToken));
        return cfg.isEmpty() ? Optional.empty() : Optional.of(cfg);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Optional inline credential on a {@code [repositories.<name>]} table: {@code token = "..."}
     * (bearer) or {@code username}/{@code password} (basic). Values support {@code ${ENV}}
     * interpolation so secrets need not be committed literally; an unset referenced variable is an
     * error so a typo fails loudly rather than silently authenticating anonymously.
     */
    private static Optional<RepoCredential> parseRepoCredential(String name, TomlTable t) {
        String token = interpolateEnv(name, t.getString("token"));
        String username = interpolateEnv(name, t.getString("username"));
        String password = interpolateEnv(name, t.getString("password"));
        if (token != null && !token.isBlank()) {
            return Optional.of(new RepoCredential.Bearer(token));
        }
        if (username != null && !username.isBlank()) {
            return Optional.of(new RepoCredential.Basic(username, password == null ? "" : password));
        }
        return Optional.empty();
    }

    private static final java.util.regex.Pattern ENV_REF =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** Expand {@code ${VAR}} against the environment; missing var → parse error. */
    private static String interpolateEnv(String repoName, String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = ENV_REF.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String val = System.getenv(var);
            if (val == null) {
                throw new JkBuildParseException(
                        "repositories." + repoName + " references unset environment variable ${" + var + "}");
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Profiles parseProfiles(TomlTable root) {
        TomlTable profiles = root.getTable("profiles");
        if (profiles == null) return Profiles.empty();
        Map<String, Profile> byName = new LinkedHashMap<>();
        for (String name : profiles.keySet()) {
            TomlTable body = profiles.getTable(name);
            if (body == null) {
                throw new JkBuildParseException("profiles." + name + " must be a table");
            }
            String inherits = body.getString("inherits");
            List<String> javacArgs = optionalStringList(body, "javac", "profiles." + name + ".javac");
            List<String> jvmArgs = optionalStringList(body, "jvm-args", "profiles." + name + ".jvm-args");
            byName.put(name, new Profile(name, inherits, javacArgs, jvmArgs));
        }
        return new Profiles(byName);
    }

    private static Features parseFeatures(TomlTable root) {
        TomlTable features = root.getTable("features");
        if (features == null) return Features.empty();
        List<String> defaults = optionalStringList(features, "default", "features.default");
        Map<String, Feature> byName = new LinkedHashMap<>();
        for (String key : features.keySet()) {
            if (key.equals("default")) continue;
            TomlTable body = features.getTable(key);
            if (body == null) {
                throw new JkBuildParseException("features." + key + " must be a table with `deps` and/or `features`");
            }
            // `deps` and `features` are both lists of names (not coord strings).
            // Resolution against [dependencies.*] happens at activation time.
            List<String> deps = optionalStringList(body, "deps", "features." + key + ".deps");
            List<String> nested = optionalStringList(body, "features", "features." + key + ".features");
            byName.put(key, new Feature(key, deps, nested));
        }
        return new Features(byName, defaults);
    }

    private static Workspace parseWorkspace(TomlTable root) {
        TomlTable workspace = root.getTable("workspace");
        if (workspace == null) return null;
        // `modules` is the documented key; `members` is an undocumented synonym kept
        // for back-compat. When both are present, members are appended after modules.
        List<String> modules = optionalStringList(workspace, "modules", "workspace.modules");
        List<String> members = optionalStringList(workspace, "members", "workspace.members");
        List<String> all = members.isEmpty()
                ? modules
                : java.util.stream.Stream.concat(modules.stream(), members.stream())
                        .toList();
        Map<String, WorkspaceDependency> wsDeps = parseWorkspaceDependencies(workspace);
        return new Workspace(all, wsDeps);
    }

    private static Map<String, WorkspaceDependency> parseWorkspaceDependencies(TomlTable workspace) {
        TomlTable wsDeps = workspace.getTable("dependencies");
        if (wsDeps == null) return Map.of();
        Map<String, WorkspaceDependency> out = new LinkedHashMap<>();
        for (String name : wsDeps.keySet()) {
            Object value = wsDeps.get(List.of(name));
            if (!(value instanceof TomlTable entry)) {
                throw new JkBuildParseException("workspace.dependencies." + name + " must be an inline table");
            }
            out.put(name, parseWorkspaceDepEntry(name, entry));
        }
        return out;
    }

    private static WorkspaceDependency parseWorkspaceDepEntry(String name, TomlTable entry) {
        String displayPath = "workspace.dependencies." + name;
        String group = entry.getString("group");
        if (group == null || group.isBlank()) {
            throw new JkBuildParseException(displayPath + " must set a `group`");
        }
        String artifact = entry.getString("name");
        if (artifact == null) artifact = name;
        if (artifact.isBlank()) {
            throw new JkBuildParseException(displayPath + ".name must not be blank");
        }
        boolean hasVersion = entry.contains("version");
        boolean hasPath = entry.contains("path");
        boolean hasGit = entry.contains("git");
        int sourceCount = (hasVersion ? 1 : 0) + (hasPath ? 1 : 0) + (hasGit ? 1 : 0);
        if (sourceCount == 0) {
            throw new JkBuildParseException(displayPath + " must set exactly one of `version`, `path`, or `git`");
        }
        if (sourceCount > 1) {
            throw new JkBuildParseException(
                    displayPath + " sets more than one of `version` / `path` / `git`; pick exactly one");
        }
        if (hasPath) {
            String path = entry.getString("path");
            if (path == null || path.isBlank()) {
                throw new JkBuildParseException(displayPath + ".path must not be blank");
            }
            return new WorkspaceDependency(group, artifact, null, null, path);
        }
        if (hasGit) {
            GitSource source = parseGitSource(entry, displayPath);
            return new WorkspaceDependency(group, artifact, null, source, null);
        }
        String versionRaw = entry.getString("version");
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + ".version must not be blank");
        }
        VersionSelector selector = VersionSelector.parseFloating(versionRaw);
        return new WorkspaceDependency(group, artifact, selector, null, null);
    }

    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------

    private static JkBuild.NativeConfig parseNativeConfig(TomlTable root) {
        TomlTable native_ = root.getTable("native");
        if (native_ == null) return JkBuild.NativeConfig.EMPTY;
        String mainClass = native_.getString("main-class");
        String name = native_.getString("name");
        List<String> args = new ArrayList<>();
        TomlArray argsArr = native_.getArray("args");
        if (argsArr != null) {
            for (int i = 0; i < argsArr.size(); i++) {
                Object val = argsArr.get(i);
                if (!(val instanceof String s))
                    throw new JkBuildParseException("[native].args must be an array of strings");
                args.add(s);
            }
        }
        if (mainClass != null && mainClass.isBlank()) mainClass = null;
        if (name != null && name.isBlank()) name = null;
        return new JkBuild.NativeConfig(mainClass, name, args);
    }

    /**
     * Parse the optional top-level {@code [build]} table:
     *
     * <ul>
     *   <li>{@code order-after} — workspace modules (by project name or {@code group:artifact}) that
     *       must build before this one, with no classpath/lockfile edge.
     *   <li>{@code test-worker-jars} — workspace modules whose built worker jar is handed to this
     *       module's test JVM via {@code -Djk.<worker>.worker.jar}.
     *   <li>{@code lint} — defaults {@code true}; set {@code false} to suppress javac lint flags.
     * </ul>
     *
     * Absent table/keys yield {@link JkBuild.Build#EMPTY}.
     */
    private static JkBuild.Build parseBuild(TomlTable root) {
        TomlTable build = root.getTable("build");
        if (build == null) return JkBuild.Build.EMPTY;
        List<String> orderAfter = new ArrayList<>();
        TomlArray arr = build.getArray("order-after");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                Object val = arr.get(i);
                if (!(val instanceof String s))
                    throw new JkBuildParseException("[build].order-after must be an array of strings");
                if (!s.isBlank()) orderAfter.add(s);
            }
        }
        List<String> testWorkerJars = new ArrayList<>();
        TomlArray twj = build.getArray("test-worker-jars");
        if (twj != null) {
            for (int i = 0; i < twj.size(); i++) {
                Object val = twj.get(i);
                if (!(val instanceof String s))
                    throw new JkBuildParseException("[build].test-worker-jars must be an array of strings");
                if (!s.isBlank()) testWorkerJars.add(s);
            }
        }
        // `lint` defaults on (surface deprecation/unchecked); `lint = false`
        // suppresses jk's default javac lint flags for users who don't want it.
        boolean lint = !Boolean.FALSE.equals(build.getBoolean("lint"));
        return new JkBuild.Build(orderAfter, testWorkerJars, lint);
    }

    private static final java.util.Set<String> PLUGIN_RESERVED = java.util.Set.of("group", "name", "version");

    private static List<PluginDeclaration> parsePlugins(TomlTable root) {
        TomlTable plugins = root.getTable("plugins");
        if (plugins == null) return List.of();
        List<PluginDeclaration> result = new ArrayList<>();
        for (String alias : plugins.keySet()) {
            Object val = plugins.get(alias);
            if (!(val instanceof TomlTable entry)) {
                throw new JkBuildParseException("plugins." + alias + " must be a table");
            }
            String group = entry.getString("group");
            String name = entry.getString("name");
            String version = entry.getString("version");
            if (group == null || group.isBlank())
                throw new JkBuildParseException("plugins." + alias + " must declare `group`");
            if (name == null || name.isBlank())
                throw new JkBuildParseException("plugins." + alias + " must declare `name`");
            if (version == null || version.isBlank())
                throw new JkBuildParseException("plugins." + alias + " must declare `version`");
            // Every key other than the reserved identity fields becomes plugin config.
            java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
            for (String key : entry.keySet()) {
                if (!PLUGIN_RESERVED.contains(key)) {
                    config.put(key, tomlToJava(entry.get(key)));
                }
            }
            result.add(
                    new PluginDeclaration(alias, group, name, version, java.util.Collections.unmodifiableMap(config)));
        }
        return List.copyOf(result);
    }

    /** Convert a tomlj value to a plain JDK type so plugin-api stays tomlj-free. */
    private static Object tomlToJava(Object value) {
        if (value instanceof TomlTable t) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (String k : t.keySet()) map.put(k, tomlToJava(t.get(k)));
            return java.util.Collections.unmodifiableMap(map);
        }
        if (value instanceof TomlArray arr) {
            java.util.List<Object> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) list.add(tomlToJava(arr.get(i)));
            return List.copyOf(list);
        }
        return value; // String, Long, Double, Boolean — already JDK types
    }

    private static String requireString(TomlTable table, String key, String displayPath) {
        String v = table.getString(key);
        if (v == null) {
            throw new JkBuildParseException("jk.toml is missing required key `" + displayPath + "`");
        }
        return v;
    }

    private static List<String> optionalStringList(TomlTable table, String key, String displayPath) {
        if (!table.contains(key)) return List.of();
        Object value = table.get(key);
        if (!(value instanceof TomlArray arr)) {
            throw new JkBuildParseException("expected `" + displayPath + "` to be a list of strings");
        }
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new JkBuildParseException("expected `" + displayPath + "` to be a list of strings");
            }
            result.add(s);
        }
        return result;
    }
}
