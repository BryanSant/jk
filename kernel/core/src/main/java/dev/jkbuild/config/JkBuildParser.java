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
import dev.jkbuild.model.PathSource;
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
import java.util.Locale;
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
 *       optional {@code jdk}, {@code java}/{@code kotlin}, {@code description}.
 *   <li>{@code [application]} — optional; presence marks the project as an application. {@code
 *       main}, {@code shadow-jar}.
 *   <li>{@code [native]} — optional; presence marks the project as native-image-eligible. {@code
 *       main-class}, {@code name}, {@code args}, {@code graal}, {@code always}.
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
        LibraryCatalog effective = catalog.withProjectOverrides(parseProjectLibraries(result));
        Workspace workspace = parseWorkspace(result, effective);
        JkBuild.Dependencies deps = parseDependencies(result, workspace, effective);
        List<RepositorySpec> repos = parseRepositories(result);
        Profiles profiles = parseProfiles(result);
        Features features = parseFeatures(result);
        Map<String, String> manifest = parseManifest(result);
        List<PluginDeclaration> plugins = parsePlugins(result);
        Optional<JkBuild.Application> application = parseApplication(result);
        Optional<JkBuild.NativeConfig> nativeConfig = parseNativeConfig(result);
        JkBuild.Build build = parseBuild(result);
        JkBuild.FormatConfig format = parseFormat(result);
        return new JkBuild(
                project, deps, repos, profiles, features, workspace, manifest, plugins, application, nativeConfig,
                build, format);
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
        int java = parseJavaRelease(project);
        VersionSelector kotlin = parseKotlinVersion(project);
        requireSupportedMajor("project.java", java);
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
        // m2install defaults to false: ~/.jk/cache is the primary artifact store. Set
        // m2install = true to additionally mirror into ~/.m2 for Maven/Gradle interop.
        boolean m2install = Boolean.TRUE.equals(project.getBoolean("m2install"));
        // project.layout = "simple"|"traditional"|"auto" (preferred). `compact` is no longer
        // supported — a stray `compact = true` in an old jk.toml is simply inert.
        JkBuild.Layout layout;
        if (project.contains("layout")) {
            String layoutRaw = project.getString("layout");
            try {
                layout = JkBuild.Layout.parse(layoutRaw);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(e.getMessage());
            }
        } else {
            layout = JkBuild.Layout.AUTO;
        }
        return new JkBuild.Project(group, name, version, jdk, java, kotlin, sourcesMode, description, m2install, layout);
    }

    /**
     * {@code project.jdk} is a JDK spec string following the same rules as {@code .jdk-version},
     * except a vendorless bare major is allowed: a vendor+major ({@code "temurin-25"}) or a bare
     * major ({@code "25"}) pins the feature release; a point release ({@code "25.0.3"}) is rejected
     * because jk keeps the patch current behind the major pointer. For convenience an unquoted
     * integer ({@code jdk = 25}) is accepted too and treated as that bare major. The keyword forms
     * {@code lts}/{@code stable}/{@code latest}/{@code native} — the same ones {@code --jdk}/{@code
     * JK_JDK}/{@code .jdk-version} accept, resolved by {@code JdkKeywords} at install/activate time —
     * are also accepted as-is here. Absent/blank → {@code null} (unset, defaults to the LTS release
     * downstream).
     */
    private static String parseJdkSpec(TomlTable project) {
        String spec = parseVersionSpec(project, "jdk", "project.jdk", "\"temurin-25\" or \"25\"");
        if (spec == null || isVersionKeyword(spec)) return spec;
        int major = JkBuild.Project.majorOf(spec);
        if (major == 0) {
            throw new JkBuildParseException(
                    "project.jdk = \"" + spec + "\" must include a major version (e.g. \"temurin-25\" or \"25\")");
        }
        requireSupportedMajor("project.jdk", major);
        return spec;
    }

    /**
     * {@code [native].graal} selects the GraalVM whose {@code bin/native-image} {@code jk native}
     * uses. Same shape as {@code project.jdk} — a bare major ({@code 25} or {@code "25"}), a
     * vendor-hinted spec ({@code "graalvm-25"} or bare {@code "graalvm"}), or the legacy keyword
     * {@code "native"} (kept for backward compatibility; equivalent to {@code "graalvm"}). A point
     * release is rejected; jk keeps the patch current. Resolution and any auto-install happen at
     * native-build time (see the CLI's {@code GraalResolver}), so this parser only normalizes the
     * spec. Absent/blank → {@code null} (caller defaults to {@code "graalvm"} when {@code [native]}
     * itself is declared).
     */
    private static String parseGraalSpec(TomlTable native_) {
        return parseVersionSpec(native_, "graal", "[native].graal", "\"graalvm-25\", \"25\", or \"native\"");
    }

    /**
     * Shared spec-string parser for {@code jdk}/{@code graal}: accepts an unquoted integer or a
     * string, passes the {@code lts}/{@code stable}/{@code latest}/{@code native} keyword forms
     * through as-is (no major/point-release notion applies to a keyword), and otherwise rejects a
     * point release. {@code null} when {@code key} is absent or blank.
     */
    private static String parseVersionSpec(TomlTable table, String key, String pathLabel, String exampleHint) {
        if (!table.contains(key)) return null;
        Object raw = table.get(key);
        String spec;
        if (raw instanceof Long l) {
            spec = Long.toString(l);
        } else if (raw instanceof String s) {
            spec = s.trim();
        } else {
            throw new JkBuildParseException(pathLabel + " must be a string, e.g. " + exampleHint);
        }
        if (spec.isEmpty()) return null;
        if (isVersionKeyword(spec)) return spec;
        if (JkBuild.Project.hasPointRelease(spec)) {
            throw new JkBuildParseException(pathLabel
                    + " = \""
                    + spec
                    + "\" must not pin a point release — use \"<vendor>-<major>\" or "
                    + "\"<major>\" (e.g. "
                    + exampleHint
                    + "); jk keeps the patch current.");
        }
        return spec;
    }

    /**
     * {@code lts}/{@code stable}/{@code latest}/{@code native} — recognized here (duplicated, not
     * imported) so this JDK-agnostic parser doesn't take a dependency on {@code :toolchain-jdk};
     * mirrors {@code JdkKeywords.isKeyword}, which is the actual resolver for these keywords at
     * install/activate time.
     */
    private static boolean isVersionKeyword(String spec) {
        String norm = spec.toLowerCase(Locale.ROOT);
        return norm.equals("lts") || norm.equals("stable") || norm.equals("latest") || norm.equals("native");
    }

    /**
     * {@code project.java} accepts either an unquoted TOML integer or a quoted numeric string
     * (coerced). Absent → {@code 0} ({@code javaRelease()} falls back to the {@code jdk} major).
     */
    private static int parseJavaRelease(TomlTable project) {
        if (!project.contains("java")) return 0;
        Object raw = project.get("java");
        long value;
        if (raw instanceof Long l) {
            value = l;
        } else if (raw instanceof String s) {
            try {
                value = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new JkBuildParseException("project.java must be an integer, got: \"" + s + "\"");
            }
        } else {
            throw new JkBuildParseException("project.java must be an integer");
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new JkBuildParseException("project.java out of range: " + value);
        }
        return (int) value;
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
        addScopeDeps(byScope, root, Scope.TEST,      workspace, catalog);
        addScopeDeps(byScope, root, Scope.PROVIDED,  workspace, catalog);
        addScopeDeps(byScope, root, Scope.PROCESSOR, workspace, catalog);
        addScopeDeps(byScope, root, Scope.EXPORT,    workspace, catalog);
        addScopeDeps(byScope, root, Scope.RUNTIME,   workspace, catalog);
        // [platform-dependencies] — BOM imports (version constraints, not classpath entries).
        addScopeDeps(byScope, root, Scope.PLATFORM,  workspace, catalog);
        // Dev-loop scopes (spring-boot plan §3.2): run-time only / run+test, never packaged.
        addScopeDeps(byScope, root, Scope.DEV,       workspace, catalog);
        addScopeDeps(byScope, root, Scope.TEST_DEV,  workspace, catalog);

        return new JkBuild.Dependencies(byScope);
    }

    private static void addScopeDeps(
            EnumMap<Scope, List<Dependency>> byScope,
            TomlTable root, Scope scope,
            Workspace workspace, LibraryCatalog catalog) {
        TomlTable table = root.getTable(scope.tomlSection());
        if (table == null) return;
        List<Dependency> parsed = parseScopeTable(
                table, new ArrayList<>(table.keySet()), scope, workspace, catalog);
        if (!parsed.isEmpty()) byScope.put(scope, parsed);
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
                throw new JkBuildParseException(scope.tomlSection()
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
     * Resolve a {@code name = "value"} string shorthand. Two forms are recognised:
     *
     * <ul>
     *   <li>Git URL — value starts with {@code git://} or {@code https://}: a git dependency with
     *       URL-embedded ref/subdir parsing. When no ref is embedded, {@code branch = "main"} is
     *       implied.
     *   <li>Version spec — anything else: looked up in the bundled catalog by {@code name} and
     *       treated as a floating version selector (the Cargo-style {@code name = "1.2.3"} form).
     * </ul>
     *
     * <p>A leading {@code .} or {@code /} is a local-path shorthand — a consume-only path dependency
     * ({@link Dependency#pathByName}), built compile/package-only. A local sibling that should be
     * built fully (with tests) belongs in {@code [workspace] modules} instead.
     */
    private static Dependency parseShorthandEntry(String name, String value, Scope scope, LibraryCatalog catalog) {
        String displayPath = scope.tomlSection() + "." + name;
        if (value.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty value string");
        }

        // Local-path shorthand: a relative (`./x`, `../x`) or absolute (`/x`) path is a consume-only
        // path dependency. `isVersionSpecOrKeyword` already excludes `.`/`/`-leading strings, so this
        // never shadows a version spec.
        if (value.startsWith(".") || value.startsWith("/")) {
            return Dependency.pathByName(name, new PathSource(value));
        }

        // Git URL shorthand: starts with "git://" or "https://".
        if (value.startsWith("git://") || value.startsWith("https://")) {
            EmbeddedUrlParts parts = splitEmbeddedUrl(value);
            GitRefSpec ref;
            boolean shallow;
            if (parts.refSpec() != null) {
                ref = parseUrlEmbeddedRefSpec(parts.refSpec());
                shallow = false;
            } else {
                // No ref embedded → default to branch "main", full clone.
                ref = new GitRefSpec.Branch("main");
                shallow = false;
            }
            String canonical = GitUrl.canonicalize(parts.baseUrl());
            GitSource source = new GitSource(canonical, parts.baseUrl(), ref, parts.subdir(), true, false, shallow);
            return Dependency.gitByName(name, source);
        }

        // Version spec or reserved keyword → catalog lookup.
        // A reserved keyword (latest/stable/lts/…) or a string that starts with a
        // version-spec character (digit, ^, ~, =, >, <) is always a catalog dep.
        if (isVersionSpecOrKeyword(value)) {
            LibraryCatalog.Module mod = catalog.lookup(name)
                    .orElseThrow(() -> new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog)));
            VersionSelector selector = VersionSelector.parseFloating(value);
            return Dependency.of(name, mod.moduleKey(), selector);
        }

        // Ambiguous string: not a recognised version spec, not a git URL. There is no
        // deferred-path fallback anymore — this is always an unknown-library error.
        throw new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog));
    }

    /**
     * Returns {@code true} when {@code value} should always be treated as a version spec or
     * reserved keyword — never as a filesystem path — regardless of what the filesystem contains.
     *
     * <ul>
     *   <li>Reserved keywords: {@code latest}, {@code stable}, {@code lts}, {@code preview},
     *       {@code nightly}.
     *   <li>Version spec operators: leading {@code ^} (caret), {@code ~} (tilde), {@code =}
     *       (exact), {@code >}, {@code <}.
     *   <li>Bare version numbers: leading digit (e.g. {@code 1.2.3}, {@code 2.0}).
     * </ul>
     */
    static boolean isVersionSpecOrKeyword(String value) {
        if (value.isEmpty()) return false;
        return switch (value) {
            case "latest", "stable", "lts", "preview", "nightly" -> true;
            default -> {
                char first = value.charAt(0);
                yield Character.isDigit(first)
                        || first == '^' || first == '~' || first == '='
                        || first == '>' || first == '<';
            }
        };
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
        String displayPath = scope.tomlSection() + "." + name;
        boolean hasWorkspace = entry.contains("workspace");
        boolean hasVersion = entry.contains("version");
        boolean hasGit = entry.contains("git");
        boolean hasSha256 = entry.contains("sha256");
        // A standalone `path` (not the git sub-directory modifier, which only applies alongside
        // `git`) is a consume-only path dependency.
        boolean hasPath = entry.contains("path") && !hasGit;

        int sourceCount = (hasVersion ? 1 : 0)
                + (hasGit ? 1 : 0)
                + (hasWorkspace ? 1 : 0)
                + (hasSha256 ? 1 : 0)
                + (hasPath ? 1 : 0);
        // The only legal multi-source pairing: sha256 + version (version records the coordinate).
        boolean sha256WithVersion = hasSha256 && hasVersion && !hasGit && !hasWorkspace;
        // No source at all + a known coordinate = platform-managed: the version comes from an
        // imported [platform-dependencies] BOM at resolve time (spring-boot plan §3.1) —
        // `starter-webmvc = { group = "org.springframework.boot", name = "..." }`.
        boolean platformManaged = sourceCount == 0 && (entry.contains("group") || entry.contains("name"));
        if (sourceCount == 0 && !platformManaged) {
            throw new JkBuildParseException(
                    displayPath + " must set exactly one of `version`, `git`, `path`, `sha256`, or"
                    + " `workspace = true` — or `group`/`name` alone for a version managed by a"
                    + " [platform-dependencies] BOM");
        }
        if (sourceCount > 1 && !sha256WithVersion) {
            throw new JkBuildParseException(displayPath
                    + " sets more than one of `version` / `git` / `path` / `sha256` / `workspace`; "
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
        // A git source still REQUIRES explicit `group` — it's inherently a
        // user-controlled override where defaulting silently would be
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

        if (hasGit) {
            // git deps are always pure discovery: the coordinate (group, name) and
            // version come from the cloned repo's jk.toml. Specifying `group`,
            // `name`, or `version` in the dep entry is an error.
            for (String forbidden : new String[]{"group", "name", "version"}) {
                if (entry.contains(forbidden)) {
                    throw new JkBuildParseException(displayPath
                            + " with `git` must not set `" + forbidden + "` — the coordinate"
                            + " and version are always read from the cloned repo's jk.toml");
                }
            }
            return Dependency.gitByName(name, parseGitSource(entry, displayPath));
        }

        if (hasPath) {
            // Like git, a path dep is pure discovery: the coordinate and version are read from the
            // target project when it's built (its jk.toml for a jk project, or the derived GAV for a
            // Gradle/Maven project). Specifying `group`/`name`/`version` here is an error.
            for (String forbidden : new String[]{"group", "name", "version"}) {
                if (entry.contains(forbidden)) {
                    throw new JkBuildParseException(displayPath
                            + " with `path` must not set `" + forbidden + "` — the coordinate"
                            + " and version are always read from the target project");
                }
            }
            String pathValue = entry.getString("path");
            if (pathValue == null || pathValue.isBlank()) {
                throw new JkBuildParseException(displayPath + ".path must not be blank");
            }
            return Dependency.pathByName(name, new PathSource(pathValue));
        }

        if (platformManaged) {
            if (group == null || group.isBlank()) {
                throw new JkBuildParseException(displayPath
                        + " has no `version` and no resolvable `group` — a platform-managed dep needs"
                        + " an explicit `group` (or a catalog short name)");
            }
            return Dependency.platformManaged(name, group + ":" + artifact);
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
        // emitting a placeholder coord (Dependency.workspace) that
        // WorkspaceMerge can re-resolve against the sibling list.
        if (workspace != null) {
            WorkspaceDependency wd = workspace.dependencies().get(name);
            if (wd != null) {
                return materialize(name, wd);
            }
        }
        // No [workspace.dependencies] match. The parser cannot resolve the
        // sibling here — that requires the full module list, which only
        // WorkspaceMerge / WorkspaceLoader has. Emit a placeholder dep
        // tagged with the short name; WorkspaceMerge resolves it. The
        // unresolved state is encoded as a synthetic workspace:<name>
        // module with a Latest selector; the resolver never sees this
        // because WorkspaceMerge rewrites it first.
        return Dependency.workspace(name);
    }

    private static Dependency materialize(String name, WorkspaceDependency wd) {
        String module = wd.module();
        if (wd.gitSource() != null) {
            return Dependency.git(name, module, wd.gitSource());
        }
        return Dependency.of(name, module, wd.version());
    }

    private static GitSource parseGitSource(TomlTable obj, String displayPath) {
        String urlRaw = obj.getString("git");
        if (urlRaw == null) {
            throw new JkBuildParseException(displayPath + " requires a `git` URL");
        }

        EmbeddedUrlParts parts = splitEmbeddedUrl(urlRaw);

        String tag = obj.getString("tag");
        String branch = obj.getString("branch");
        String rev = obj.getString("rev");
        boolean hasExplicitRef = tag != null || branch != null || rev != null;

        if (parts.refSpec() != null && hasExplicitRef) {
            throw new JkBuildParseException(displayPath
                    + " sets both a URL-embedded ref (`@` or `#` suffix) and an explicit ref"
                    + " key (`tag`, `branch`, or `rev`); use one or the other");
        }

        String explicitPath = obj.getString("path");
        if (parts.subdir() != null && explicitPath != null) {
            throw new JkBuildParseException(displayPath
                    + " sets both a URL-embedded sub-directory (`!` suffix) and an explicit"
                    + " `path` key; use one or the other");
        }
        String path = parts.subdir() != null ? parts.subdir() : explicitPath;

        boolean submodules = obj.getBoolean("submodules", () -> true);
        boolean verifySigned = obj.getBoolean("verify-signed", () -> false);
        if (obj.contains("fetch")) {
            throw new JkBuildParseException(displayPath + ".fetch is no longer supported — every git dependency is"
                    + " resolved once and pinned in jk.lock; a branch ref's tip only moves on an explicit `jk"
                    + " update --git` or `jk fetch`. Remove the `fetch` key.");
        }

        GitRefSpec ref;
        boolean shallow;

        if (parts.refSpec() != null) {
            // URL-embedded refs are always full (deep) clones regardless of ref type.
            ref = parseUrlEmbeddedRefSpec(parts.refSpec());
            shallow = false;
        } else {
            int set = (tag != null ? 1 : 0) + (branch != null ? 1 : 0) + (rev != null ? 1 : 0);
            if (set == 0) {
                throw new JkBuildParseException(
                        displayPath + " must set `tag`, `branch`, or `rev` (or embed the ref in the URL)");
            }
            if (set > 1) {
                throw new JkBuildParseException(
                        displayPath + " must set exactly one of `tag`, `branch`, or `rev`");
            }
            if (tag != null) {
                ref = new GitRefSpec.Tag(tag);
                shallow = true; // explicit tag = → shallow clone
            } else if (branch != null) {
                ref = new GitRefSpec.Branch(branch);
                shallow = false;
            } else {
                ref = new GitRefSpec.Rev(rev);
                shallow = false;
            }
        }

        String canonical = GitUrl.canonicalize(parts.baseUrl());
        return new GitSource(canonical, parts.baseUrl(), ref, path, submodules, verifySigned, shallow);
    }

    /**
     * The result of splitting URL-embedded ref and sub-directory out of a raw git URL. At most one
     * of {@link #refSpec} and {@link #subdir} may be non-null when this is produced from a plain
     * URL, but the parser also handles both.
     *
     * @param baseUrl the git repository URL with no embedded suffix
     * @param subdir  sub-directory inside the repo, from the {@code !path} suffix, or {@code null}
     * @param refSpec raw ref string prefixed by {@code "@"} or {@code "#"}, or {@code null}
     */
    record EmbeddedUrlParts(String baseUrl, String subdir, String refSpec) {}

    /**
     * Parse embedded ref ({@code @name} / {@code #sha}) and sub-directory ({@code !subdir}) out of
     * a raw git URL. Either or both may be absent. The two suffixes may appear in either order:
     *
     * <ul>
     *   <li>{@code url@ref!subdir} — ref before subdir
     *   <li>{@code url!subdir@ref} — subdir before ref
     *   <li>{@code url#sha!subdir} / {@code url!subdir#sha} — sha with subdir
     * </ul>
     *
     * <p>The {@code @} ref delimiter is searched only after the last {@code /} or {@code :} in the
     * URL, so the {@code git@host} userinfo form is not confused for an embedded ref. The {@code #}
     * and {@code !} delimiters are searched from the start of the string (they are not valid in
     * standard git URL paths without encoding).
     */
    static EmbeddedUrlParts splitEmbeddedUrl(String urlRaw) {
        // @ must follow the authority so we don't mistake "git@github.com" for an embedded branch.
        // Anchor at the first '/' after the scheme+host ("://host/"), or after ':' for SCP form.
        int schemeEnd = urlRaw.indexOf("://");
        int pathStart;
        if (schemeEnd >= 0) {
            int hostSlash = urlRaw.indexOf('/', schemeEnd + 3);
            pathStart = hostSlash >= 0 ? hostSlash : urlRaw.length();
        } else {
            int colon = urlRaw.indexOf(':');
            pathStart = colon >= 0 ? colon : 0;
        }
        int atPos = urlRaw.indexOf('@', pathStart);
        int hashPos = urlRaw.indexOf('#');
        int bangPos = urlRaw.indexOf('!');

        // Ignore an @ that appears after a # (it would be inside the SHA string).
        if (hashPos >= 0 && atPos > hashPos) atPos = -1;

        boolean hasRef = atPos >= 0 || hashPos >= 0;
        boolean hasBang = bangPos >= 0;

        if (!hasRef && !hasBang) return new EmbeddedUrlParts(urlRaw, null, null);

        // Determine which delimiter comes first.
        int firstAt = atPos >= 0 ? atPos : Integer.MAX_VALUE;
        int firstHash = hashPos >= 0 ? hashPos : Integer.MAX_VALUE;
        int firstBang = bangPos >= 0 ? bangPos : Integer.MAX_VALUE;
        int first = Math.min(firstAt, Math.min(firstHash, firstBang));

        if (hasBang && bangPos == first) {
            // Pattern: baseUrl!subdir[@ref|#sha]
            String base = urlRaw.substring(0, bangPos);
            String rest = urlRaw.substring(bangPos + 1);
            int atInRest = rest.indexOf('@');
            int hashInRest = rest.indexOf('#');
            if (atInRest >= 0 && (hashInRest < 0 || atInRest < hashInRest)) {
                return new EmbeddedUrlParts(base, rest.substring(0, atInRest), "@" + rest.substring(atInRest + 1));
            } else if (hashInRest >= 0) {
                return new EmbeddedUrlParts(base, rest.substring(0, hashInRest), "#" + rest.substring(hashInRest + 1));
            } else {
                return new EmbeddedUrlParts(base, rest, null); // subdir only, no ref (will error at validation)
            }
        } else {
            // Pattern: baseUrl[@ref|#sha][!subdir]
            int refPos = firstAt < firstHash ? atPos : hashPos;
            char refChar = urlRaw.charAt(refPos);
            String base = urlRaw.substring(0, refPos);
            String refAndRest = urlRaw.substring(refPos + 1);
            int bangInRest = refAndRest.indexOf('!');
            if (bangInRest >= 0) {
                return new EmbeddedUrlParts(
                        base,
                        refAndRest.substring(bangInRest + 1),
                        (refChar == '#' ? "#" : "@") + refAndRest.substring(0, bangInRest));
            } else {
                return new EmbeddedUrlParts(base, null, (refChar == '#' ? "#" : "@") + refAndRest);
            }
        }
    }

    /**
     * Parse the raw ref string extracted from a URL suffix ({@code "@name"} or {@code "#sha"}).
     * A hex-only string (any length) → {@link GitRefSpec.Rev}; a version-like name (starts with a
     * digit, or {@code v}/{@code r} followed by a digit) → {@link GitRefSpec.Tag}; anything else →
     * {@link GitRefSpec.Branch}. All are {@code shallow = false} — URL-embedded refs always do a
     * full clone even when they resolve to a tag.
     */
    private static GitRefSpec parseUrlEmbeddedRefSpec(String prefixedRef) {
        // "#sha" is always a Rev; "@name" is classified by heuristic.
        if (prefixedRef.startsWith("#")) {
            return new GitRefSpec.Rev(prefixedRef.substring(1));
        }
        String name = prefixedRef.substring(1); // strip leading "@"
        if (name.isEmpty()) return new GitRefSpec.Branch(name);
        if (name.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return new GitRefSpec.Rev(name); // "@hexsha" form
        }
        char first = name.charAt(0);
        boolean versionLike = Character.isDigit(first)
                || ((first == 'v' || first == 'r') && name.length() > 1 && Character.isDigit(name.charAt(1)));
        return versionLike ? new GitRefSpec.Tag(name) : new GitRefSpec.Branch(name);
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
                credential = RepositoryToml.credential(t, strictInterp(name));
                objectStore = RepositoryToml.objectStore(t, strictInterp(name));
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
    /**
     * Project-layer {@code ${ENV}} interpolation for {@code [repositories.<name>]}: strict — an unset
     * variable is a parse error so a typo fails loudly rather than silently authenticating
     * anonymously. Field parsing lives in {@link RepositoryToml}.
     */
    private static java.util.function.UnaryOperator<String> strictInterp(String repoName) {
        return raw -> RepositoryToml.interpolate(raw, var -> {
            String val = System.getenv(var);
            if (val == null) {
                throw new JkBuildParseException(
                        "repositories." + repoName + " references unset environment variable ${" + var + "}");
            }
            return val;
        });
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

    private static Workspace parseWorkspace(TomlTable root, LibraryCatalog catalog) {
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
        Map<String, WorkspaceDependency> wsDeps = parseWorkspaceDependencies(workspace, catalog);
        return new Workspace(all, wsDeps);
    }

    private static Map<String, WorkspaceDependency> parseWorkspaceDependencies(
            TomlTable workspace, LibraryCatalog catalog) {
        TomlTable wsDeps = workspace.getTable("dependencies");
        if (wsDeps == null) return Map.of();
        Map<String, WorkspaceDependency> out = new LinkedHashMap<>();
        for (String name : wsDeps.keySet()) {
            Object value = wsDeps.get(List.of(name));
            String displayPath = "workspace.dependencies." + name;
            if (value instanceof String shorthand) {
                out.put(name, parseWorkspaceShorthand(name, shorthand, displayPath, catalog));
            } else if (value instanceof TomlTable entry) {
                out.put(name, parseWorkspaceDepEntry(name, entry, catalog));
            } else {
                throw new JkBuildParseException(displayPath + " must be a version-string shorthand"
                        + " (e.g. \"1.2.3\") or an inline table");
            }
        }
        return out;
    }

    /**
     * String shorthand for a workspace dependency: {@code name = "1.2.3"} (or {@code "^1.0"}, {@code
     * "latest"}, …). The short {@code name} is resolved to a {@code group:artifact} through the
     * bundled catalog, matching the {@code [dependencies]} shorthand. Local-path and git-URL string
     * forms are not accepted here — a shared workspace dep must be a Maven coordinate (use the inline
     * {@code git = "..."} table form for git, or a {@code [workspace] modules} entry for a sibling).
     */
    private static WorkspaceDependency parseWorkspaceShorthand(
            String name, String value, String displayPath, LibraryCatalog catalog) {
        if (value.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty value string");
        }
        if (value.startsWith(".") || value.startsWith("/")
                || value.startsWith("git://") || value.startsWith("https://")) {
            throw new JkBuildParseException(displayPath + " string shorthand must be a version spec"
                    + " (e.g. \"1.2.3\"); for a git source use the inline `{ git = \"...\" }` form,"
                    + " for a local sibling add it to `[workspace] modules`");
        }
        LibraryCatalog.Module mod = catalog.lookup(name)
                .orElseThrow(() -> new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog)));
        return new WorkspaceDependency(mod.group(), mod.artifact(), VersionSelector.parseFloating(value), null);
    }

    private static WorkspaceDependency parseWorkspaceDepEntry(String name, TomlTable entry, LibraryCatalog catalog) {
        String displayPath = "workspace.dependencies." + name;
        boolean hasVersion = entry.contains("version");
        boolean hasGit = entry.contains("git");
        if (entry.contains("path")) {
            throw new JkBuildParseException(displayPath + " uses `path = \"...\"` — this is no longer supported."
                    + " Move `" + name + "` into the root jk.toml's `[workspace] modules = [...]` list directly"
                    + " instead (it becomes an ordinary workspace sibling); remove this"
                    + " [workspace.dependencies." + name + "] entry.");
        }
        int sourceCount = (hasVersion ? 1 : 0) + (hasGit ? 1 : 0);
        if (sourceCount == 0) {
            throw new JkBuildParseException(displayPath + " must set exactly one of `version` or `git`");
        }
        if (sourceCount > 1) {
            throw new JkBuildParseException(
                    displayPath + " sets more than one of `version` / `git`; pick exactly one");
        }
        if (hasGit) {
            // Git workspace deps still carry an explicit coordinate: the shared coordinate is what
            // sibling modules pin against, and it must be known at parse time.
            String gitGroup = entry.getString("group");
            if (gitGroup == null || gitGroup.isBlank()) {
                throw new JkBuildParseException(displayPath + " with `git` must set a `group`");
            }
            String gitArtifact = entry.getString("name");
            if (gitArtifact == null) gitArtifact = name;
            if (gitArtifact.isBlank()) {
                throw new JkBuildParseException(displayPath + ".name must not be blank");
            }
            GitSource source = parseGitSource(entry, displayPath);
            return new WorkspaceDependency(gitGroup, gitArtifact, null, source);
        }
        // Version dep: group/name may be explicit or resolved from the catalog by the short name,
        // exactly like a [dependencies] entry.
        String groupExplicit = entry.getString("group");
        String artifactExplicit = entry.getString("name");
        LibraryCatalog.Module catalogHit = (groupExplicit == null) ? catalog.lookup(name).orElse(null) : null;
        String group = groupExplicit != null ? groupExplicit : (catalogHit != null ? catalogHit.group() : null);
        String artifact = artifactExplicit != null
                ? artifactExplicit
                : (catalogHit != null ? catalogHit.artifact() : name);
        if (group == null || group.isBlank()) {
            throw new JkBuildParseException(
                    displayPath + " must set a `group` (or use a catalog-known short name)");
        }
        if (artifact.isBlank()) {
            throw new JkBuildParseException(displayPath + ".name must not be blank");
        }
        String versionRaw = entry.getString("version");
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + ".version must not be blank");
        }
        return new WorkspaceDependency(group, artifact, VersionSelector.parseFloating(versionRaw), null);
    }

    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------

    /**
     * The optional {@code [application]} table. Its mere presence marks the project as an
     * application ({@link JkBuild#isApplication()}) — {@code Optional.empty()} when absent, never a
     * defaulted-fields sentinel, so presence and "declared but empty" stay distinguishable.
     */
    private static Optional<JkBuild.Application> parseApplication(TomlTable root) {
        TomlTable application = root.getTable("application");
        if (application == null) return Optional.empty();
        String main = application.getString("main");
        boolean shadowJar = Boolean.TRUE.equals(application.getBoolean("shadow-jar"));
        return Optional.of(new JkBuild.Application(main, shadowJar));
    }

    /**
     * The optional {@code [native]} table. Its mere presence marks the project as
     * native-image-eligible ({@link JkBuild#nativeMode()}) — {@code Optional.empty()} when absent,
     * never a defaulted-fields sentinel, so presence and "declared but empty" stay distinguishable.
     * When declared and {@code graal} is omitted, defaults to the vendor-hinted {@code "graalvm"}
     * spec (any installed GraalVM satisfies it; auto-install prefers Oracle GraalVM) rather than
     * leaving it unset.
     */
    private static Optional<JkBuild.NativeConfig> parseNativeConfig(TomlTable root) {
        TomlTable native_ = root.getTable("native");
        if (native_ == null) return Optional.empty();
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
        String graal = parseGraalSpec(native_);
        if (graal == null) graal = "graalvm";
        boolean always = Boolean.TRUE.equals(native_.getBoolean("always"));
        return Optional.of(new JkBuild.NativeConfig(mainClass, name, args, graal, always));
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
