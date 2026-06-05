// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.model.Feature;
import dev.jkbuild.model.Features;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Profiles;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.model.ObjectStoreConfig;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.model.Workspace;
import dev.jkbuild.model.Workspace.WorkspaceDependency;
import dev.jkbuild.util.GitUrl;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads {@code jk.toml} into a {@link JkBuild}.
 *
 * <p>v0.7 schema (see {@code docs/artifact-coord-design.md}):
 * <ul>
 *   <li>{@code [project]} — required; {@code group}, {@code name}, {@code version} required;
 *       optional {@code jdk}, {@code main}, {@code java}/{@code kotlin}, {@code shadow},
 *       {@code native}, {@code description}.</li>
 *   <li>{@code [dependencies.<scope>]} — library-as-key sub-tables; each entry is
 *       {@code <lib> = { group, name?, version | path | git | workspace }}.
 *       {@code [dependencies]} with only inline-table children is shorthand for
 *       {@code [dependencies.main]}; mixing flat and sub-scope is a parse error.</li>
 *   <li>{@code [workspace]} — optional; {@code members = [...]} plus an optional
 *       {@code [workspace.dependencies]} table of shared external deps inherited by
 *       members via {@code <name>.workspace = true}.</li>
 *   <li>{@code [repositories]} — optional; per-name URL string or inline table.</li>
 *   <li>{@code [profiles.<name>]} — optional; per-profile {@code inherits}, {@code javac},
 *       {@code jvm-args}.</li>
 *   <li>{@code [features]} — optional; {@code default = [...]} plus
 *       {@code [features.<name>]} sub-tables whose {@code deps} fields are <b>dep
 *       names</b> (not coord strings).</li>
 * </ul>
 */
public final class JkBuildParser {

    private JkBuildParser() {}

    public static JkBuild parse(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            throw new JkBuildParseException("jk.toml not found: " + file);
        }
        return parse(Files.readString(file));
    }

    public static JkBuild parse(String toml) {
        return parse(toml, LibraryCatalog.layered());
    }

    /**
     * Test seam: parse against a synthetic library catalog instead of the
     * default layered one. The manifest's own {@code [libraries]} table is
     * still layered on top via {@link LibraryCatalog#withProjectOverrides}.
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
        return new JkBuild(project, deps, repos, profiles, features, workspace, manifest);
    }

    /**
     * Parse the optional top-level {@code [manifest]} table — string-valued
     * custom jar-manifest attributes (e.g. {@code "Implementation-Title"}).
     * {@code Main-Class} is intentionally <em>not</em> read here; it derives
     * from {@code project.main}.
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
                throw new JkBuildParseException(
                        "manifest.Main-Class is not allowed; set project.main instead");
            }
            attrs.put(key, value);
        }
        return attrs;
    }

    /**
     * Parse the optional top-level {@code [libraries]} table. Empty map when
     * absent. Validated through {@link LibraryCatalog#parseLibrariesTable} so
     * the schema matches the bundled and user files.
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
        int jdk = intOrZero(project, "jdk", "project.jdk");
        int java = intOrZero(project, "java", "project.java");
        VersionSelector kotlin = parseKotlinVersion(project);
        requireSupportedMajor("project.jdk", jdk);
        requireSupportedMajor("project.java", java);
        String main = project.getString("main");
        boolean shadow = Boolean.TRUE.equals(project.getBoolean("shadow"));
        // native = false/absent → DISABLED, native = true → SUPPORTED,
        // native = "always" → ALWAYS (TOML boolean vs string).
        Object nativeRaw = project.get("native");
        JkBuild.NativeMode nativeMode;
        if ("always".equalsIgnoreCase(nativeRaw instanceof String s ? s : "")) {
            nativeMode = JkBuild.NativeMode.ALWAYS;
        } else if (Boolean.TRUE.equals(nativeRaw)) {
            nativeMode = JkBuild.NativeMode.SUPPORTED;
        } else {
            nativeMode = JkBuild.NativeMode.DISABLED;
        }
        String description = project.getString("description");
        // application defaults to "has a main class"; an explicit key overrides
        // (e.g. application = false for a runnable project that shouldn't be
        // make-installed). m2install defaults to false.
        boolean application = project.contains("application")
                ? Boolean.TRUE.equals(project.getBoolean("application"))
                : main != null;
        boolean m2install = Boolean.TRUE.equals(project.getBoolean("m2install"));
        return new JkBuild.Project(group, name, version, jdk, java, kotlin,
                main, shadow, nativeMode, description, application, m2install);
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
     * {@code project.kotlin} is a Kotlin compiler version selector (string),
     * parsed the same way as a floating dependency version: bare {@code 2.3.21}
     * → caret, {@code =2.3.21} pins. Absent → {@code null} (a Java project).
     */
    private static VersionSelector parseKotlinVersion(TomlTable project) {
        if (!project.contains("kotlin")) return null;
        String raw = project.getString("kotlin");
        if (raw == null) {
            throw new JkBuildParseException(
                    "project.kotlin must be a version string, e.g. \"2.3.21\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * jk only supports JDK 17 and above (LTS + latest). Reject any older
     * value at parse time so users learn the constraint up front instead
     * of in the middle of a resolve.
     */
    private static void requireSupportedMajor(String path, int value) {
        if (value == 0) return;
        if (value < 17) {
            throw new JkBuildParseException(path + " = " + value
                    + " is not supported — jk targets JDK 17 and above "
                    + "(LTS: 17, 21, 25, … plus the latest release).");
        }
    }

    // ---------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------

    private static JkBuild.Dependencies parseDependencies(
            TomlTable root, Workspace workspace, LibraryCatalog catalog) {
        TomlTable deps = root.getTable("dependencies");
        if (deps == null) return JkBuild.Dependencies.empty();
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);

        // Classify the children of [dependencies]:
        //   - inline tables (TomlTable that came from a `name = { ... }` line)
        //     → flat-form dep entries belonging to the default scope (main)
        //   - sub-tables (TomlTable named after a scope) → per-scope groups
        // tomlj does not distinguish inline vs sub-table at the TomlTable
        // level, so we detect by key: a scope name (one of the six) maps to
        // a sub-table; everything else is a flat dep entry.
        List<String> flatDepKeys = new ArrayList<>();
        List<String> subScopeKeys = new ArrayList<>();
        for (String key : deps.keySet()) {
            if (isScopeName(key)) {
                subScopeKeys.add(key);
            } else {
                flatDepKeys.add(key);
            }
        }
        if (!flatDepKeys.isEmpty() && !subScopeKeys.isEmpty()) {
            throw new JkBuildParseException(
                    "mixed flat and sub-scope dep tables are ambiguous in [dependencies]; "
                            + "move flat entries under [dependencies.main] or remove the sub-scope tables");
        }

        if (!flatDepKeys.isEmpty()) {
            // Default-scope shorthand: [dependencies] with only inline-table
            // children is treated as [dependencies.main].
            List<Dependency> parsed = parseScopeTable(deps, flatDepKeys, Scope.MAIN, workspace, catalog);
            if (!parsed.isEmpty()) byScope.put(Scope.MAIN, parsed);
        }

        for (String scopeKey : subScopeKeys) {
            Scope scope = scopeOf(scopeKey);
            TomlTable scopeTable = deps.getTable(scopeKey);
            if (scopeTable == null) {
                throw new JkBuildParseException(
                        "dependencies." + scopeKey + " must be a table of name → dep entries");
            }
            List<Dependency> parsed = parseScopeTable(scopeTable,
                    new ArrayList<>(scopeTable.keySet()), scope, workspace, catalog);
            if (!parsed.isEmpty()) byScope.put(scope, parsed);
        }
        return new JkBuild.Dependencies(byScope);
    }

    private static boolean isScopeName(String key) {
        for (Scope s : Scope.values()) {
            if (s.canonical().equals(key)) return true;
        }
        return false;
    }

    private static Scope scopeOf(String canonical) {
        for (Scope s : Scope.values()) {
            if (s.canonical().equals(canonical)) return s;
        }
        throw new JkBuildParseException("unknown dependency scope: " + canonical);
    }

    private static List<Dependency> parseScopeTable(
            TomlTable scopeTable, List<String> keys, Scope scope,
            Workspace workspace, LibraryCatalog catalog) {
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
                throw new JkBuildParseException(
                        "dependencies." + scope.canonical() + "." + name
                                + " must be an inline table (e.g. { group = \"...\", version = \"...\" })"
                                + " or a version-string shorthand for a catalog-known name");
            }
            result.add(parseDepEntry(name, entry, scope, workspace, catalog));
        }
        return result;
    }

    /**
     * Resolve a {@code name = "version-spec"} shorthand by looking the
     * short name up in the bundled library catalog.
     */
    private static Dependency parseShorthandEntry(
            String name, String versionRaw, Scope scope, LibraryCatalog catalog) {
        String displayPath = "dependencies." + scope.canonical() + "." + name;
        if (versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty version string");
        }
        LibraryCatalog.Module mod = catalog.lookup(name).orElseThrow(() ->
                new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog)));
        VersionSelector selector = VersionSelector.parseFloating(versionRaw);
        return Dependency.of(name, mod.moduleKey(), selector);
    }

    /**
     * Compose the error shown when a short name doesn't resolve. Appends
     * a "did you mean" line when the catalog has plausible alternatives —
     * particularly useful for major-version-split families like Jackson 2
     * vs 3, where typing the unprefixed name silently fails by design.
     */
    private static String unknownLibraryMessage(String displayPath, String name, LibraryCatalog catalog) {
        StringBuilder msg = new StringBuilder(displayPath)
                .append(" — unknown short name `").append(name).append("`. ");
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
            String name, TomlTable entry, Scope scope,
            Workspace workspace, LibraryCatalog catalog) {
        String displayPath = "dependencies." + scope.canonical() + "." + name;
        boolean hasWorkspace = entry.contains("workspace");
        boolean hasVersion = entry.contains("version");
        boolean hasPath = entry.contains("path");
        boolean hasGit = entry.contains("git");
        boolean hasSha256 = entry.contains("sha256");

        int sourceCount = (hasVersion ? 1 : 0) + (hasPath ? 1 : 0)
                + (hasGit ? 1 : 0) + (hasWorkspace ? 1 : 0) + (hasSha256 ? 1 : 0);
        // `version` alongside `git` is the one legal pairing: it overrides the
        // version derived from the ref (docs/git-source-deps.md §"Discovery with
        // override"). Every other multi-source combination is ambiguous.
        boolean gitWithVersionOverride = hasGit && hasVersion && !hasPath && !hasWorkspace && !hasSha256;
        if (sourceCount == 0) {
            throw new JkBuildParseException(displayPath
                    + " must set exactly one of `version`, `path`, `git`, `sha256`, or `workspace = true`");
        }
        if (sourceCount > 1 && !gitWithVersionOverride) {
            throw new JkBuildParseException(displayPath
                    + " sets more than one of `version` / `path` / `git` / `sha256` / `workspace`; "
                    + "pick exactly one");
        }

        if (hasWorkspace) {
            Boolean ws = entry.getBoolean("workspace");
            if (!Boolean.TRUE.equals(ws)) {
                throw new JkBuildParseException(displayPath
                        + ".workspace must be `true` (the only legal value)");
            }
            // workspace = true is mutually exclusive with group/name too.
            if (entry.contains("group") || entry.contains("name")) {
                throw new JkBuildParseException(displayPath
                        + " with `workspace = true` must not set `group` or `name`");
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
        LibraryCatalog.Module catalogHit = (groupExplicit == null)
                ? catalog.lookup(name).orElse(null) : null;

        String group = groupExplicit != null ? groupExplicit
                : (catalogHit != null ? catalogHit.group() : null);
        String artifact = artifactExplicit != null ? artifactExplicit
                : (catalogHit != null ? catalogHit.artifact() : name);
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
                throw new JkBuildParseException(displayPath
                        + " with `sha256 = ...` must also set `version`");
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
            throw new JkBuildParseException(displayPath
                    + " must set a `group` (or use a catalog-known short name)");
        }
        String versionRaw = entry.getString("version");
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + ".version must not be blank");
        }
        VersionSelector selector = VersionSelector.parseFloating(versionRaw);
        return Dependency.of(name, group + ":" + artifact, selector);
    }

    private static Dependency resolveWorkspaceDep(
            String name, String displayPath, Workspace workspace) {
        // The workspace lookup chain: members are resolved upstream at
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
        // sibling here — that requires the full member list, which only
        // WorkspaceMerge / WorkspaceLoader has. Emit a placeholder dep
        // tagged with the short name; WorkspaceMerge resolves it. We
        // encode the unresolved state via a synthetic module of the form
        // "workspace:<name>" and a Latest selector; the resolver never
        // sees this because WorkspaceMerge rewrites it first.
        return new Dependency(name, "workspace:" + name,
                new VersionSelector.Latest("workspace"), null, null, null, false);
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
            throw new JkBuildParseException(
                    displayPath + " must set one of `tag`, `branch`, or `rev`");
        }
        if (set > 1) {
            throw new JkBuildParseException(
                    displayPath + " must set exactly one of `tag`, `branch`, or `rev`");
        }
        GitRefSpec ref;
        if (tag != null) ref = new GitRefSpec.Tag(tag);
        else if (branch != null) ref = new GitRefSpec.Branch(branch);
        else ref = new GitRefSpec.Rev(rev);
        String path = obj.getString("path");
        boolean submodules = obj.getBoolean("submodules", () -> true);
        boolean verifySigned = obj.getBoolean("verify-signed", () -> false);
        return new GitSource(canonical, urlRaw, ref, path, submodules, verifySigned);
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
                    throw new JkBuildParseException(
                            "repositories." + name + " requires a string `url` field");
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
                throw new JkBuildParseException(
                        "repositories." + name + " has malformed URL: " + url, e);
            }
        }
        return result;
    }

    /**
     * Optional object-store config on a {@code [repositories.<name>]} table for
     * {@code s3://}/{@code gs://} backends: {@code region}, {@code endpoint},
     * {@code access-key}, {@code secret-key}, {@code session-token}. All
     * support {@code ${ENV}} interpolation (so keys aren't committed literally);
     * any unset field falls back to the AWS environment / default chain.
     */
    private static Optional<ObjectStoreConfig> parseObjectStore(String name, TomlTable t) {
        String region = interpolateEnv(name, t.getString("region"));
        String endpoint = interpolateEnv(name, t.getString("endpoint"));
        String accessKey = interpolateEnv(name, t.getString("access-key"));
        String secretKey = interpolateEnv(name, t.getString("secret-key"));
        String sessionToken = interpolateEnv(name, t.getString("session-token"));
        ObjectStoreConfig cfg = new ObjectStoreConfig(
                blankToNull(region), blankToNull(endpoint),
                blankToNull(accessKey), blankToNull(secretKey), blankToNull(sessionToken));
        return cfg.isEmpty() ? Optional.empty() : Optional.of(cfg);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Optional inline credential on a {@code [repositories.<name>]} table:
     * {@code token = "..."} (bearer) or {@code username}/{@code password}
     * (basic). Values support {@code ${ENV}} interpolation so secrets need not
     * be committed literally; an unset referenced variable is an error so a
     * typo fails loudly rather than silently authenticating anonymously.
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
                throw new JkBuildParseException("repositories." + repoName
                        + " references unset environment variable ${" + var + "}");
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
                throw new JkBuildParseException(
                        "features." + key + " must be a table with `deps` and/or `features`");
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
        List<String> members = optionalStringList(workspace, "members", "workspace.members");
        Map<String, WorkspaceDependency> wsDeps = parseWorkspaceDependencies(workspace);
        return new Workspace(members, wsDeps);
    }

    private static Map<String, WorkspaceDependency> parseWorkspaceDependencies(TomlTable workspace) {
        TomlTable wsDeps = workspace.getTable("dependencies");
        if (wsDeps == null) return Map.of();
        Map<String, WorkspaceDependency> out = new LinkedHashMap<>();
        for (String name : wsDeps.keySet()) {
            Object value = wsDeps.get(List.of(name));
            if (!(value instanceof TomlTable entry)) {
                throw new JkBuildParseException(
                        "workspace.dependencies." + name + " must be an inline table");
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
            throw new JkBuildParseException(displayPath
                    + " must set exactly one of `version`, `path`, or `git`");
        }
        if (sourceCount > 1) {
            throw new JkBuildParseException(displayPath
                    + " sets more than one of `version` / `path` / `git`; pick exactly one");
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
