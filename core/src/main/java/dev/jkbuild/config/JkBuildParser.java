// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Feature;
import dev.jkbuild.model.Features;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Profiles;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.model.Workspace;
import dev.jkbuild.util.GitUrl;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads {@code jk.toml} into a {@link JkBuild}.
 *
 * <p>Schema (PRD §5):
 * <ul>
 *   <li>{@code [project]} — required; {@code group}, {@code artifact}, {@code version} required;
 *       optional {@code jdk}, {@code main}, {@code language}, {@code shadow}, {@code native}.</li>
 *   <li>{@code [dependencies]} — optional; per-scope arrays of {@code "group:artifact:version"}
 *       strings. The version part is everything after the second colon and parses via
 *       {@link VersionSelector#parse(String)}. A coord written as {@code "group:artifact"}
 *       (no version) must have a matching entry in {@code [sources]}.</li>
 *   <li>{@code [sources]} — optional; per-coord git overrides. Merged into matching deps'
 *       {@link Dependency#gitSource()}.</li>
 *   <li>{@code [repositories]} — optional; per-name URL string or inline table.</li>
 *   <li>{@code [profiles.<name>]} — optional; per-profile {@code inherits}, {@code javac},
 *       {@code jvm-args}.</li>
 *   <li>{@code [features]} — optional; {@code default = [...]} and {@code [features.<name>]}
 *       subtables.</li>
 *   <li>{@code [workspace]} — optional; {@code members = [...]}.</li>
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
        Objects.requireNonNull(toml, "toml");
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(
                    "failed to parse jk.toml: " + result.errors().getFirst().getMessage());
        }
        JkBuild.Project project = parseProject(result);
        Map<String, GitSource> sources = parseSources(result);
        JkBuild.Dependencies deps = parseDependencies(result, sources);
        List<RepositorySpec> repos = parseRepositories(result);
        Profiles profiles = parseProfiles(result);
        Features features = parseFeatures(result);
        Workspace workspace = parseWorkspace(result);
        return new JkBuild(project, deps, repos, profiles, features, workspace);
    }

    private static JkBuild.Project parseProject(TomlTable root) {
        TomlTable project = root.getTable("project");
        if (project == null) {
            throw new JkBuildParseException("jk.toml must declare a top-level `[project]` table");
        }
        String group = requireString(project, "group", "project.group");
        String artifact = requireString(project, "artifact", "project.artifact");
        String version = requireString(project, "version", "project.version");
        String jdk = project.getString("jdk");
        String main = project.getString("main");
        boolean shadow = Boolean.TRUE.equals(project.getBoolean("shadow"));
        boolean nativeImage = Boolean.TRUE.equals(project.getBoolean("native"));
        String language = project.getString("language");
        if (language == null || language.isBlank()) language = "java";
        return new JkBuild.Project(group, artifact, version, jdk,
                main, shadow, nativeImage, language);
    }

    private static Map<String, GitSource> parseSources(TomlTable root) {
        TomlTable sources = root.getTable("sources");
        if (sources == null) return Map.of();
        Map<String, GitSource> result = new LinkedHashMap<>();
        // Source keys are coords like "com.foo:bar" — colon isn't legal in
        // tomlj's dotted-key parser, so look up by literal-key path.
        for (String key : sources.keySet()) {
            Object value = sources.get(List.of(key));
            if (!(value instanceof TomlTable entry)) {
                throw new JkBuildParseException(
                        "sources.\"" + key + "\" must be an inline table");
            }
            result.put(key, parseGitSource(entry, "sources.\"" + key + "\""));
        }
        return result;
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

    private static JkBuild.Dependencies parseDependencies(
            TomlTable root, Map<String, GitSource> sources) {
        TomlTable deps = root.getTable("dependencies");
        if (deps == null) return JkBuild.Dependencies.empty();
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            String key = scope.canonical();
            TomlArray arr = deps.getArray(key);
            if (arr == null) continue;
            List<Dependency> parsed = parseScopeArray(arr, scope, sources);
            if (!parsed.isEmpty()) byScope.put(scope, parsed);
        }
        return new JkBuild.Dependencies(byScope);
    }

    private static List<Dependency> parseScopeArray(
            TomlArray arr, Scope scope, Map<String, GitSource> sources) {
        List<Dependency> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String spec)) {
                throw new JkBuildParseException(
                        "dependencies." + scope.canonical() + " must be a list of \"group:artifact:version\" strings");
            }
            result.add(parseDepSpec(spec, scope, sources));
        }
        return result;
    }

    /**
     * Parse a single dep string. Forms:
     * <ul>
     *   <li>{@code "group:artifact:version"} — pinned. Version must be a
     *       bare literal ({@code 1.2.3}) or a plain {@code =1.2.3}.
     *       Decorations ({@code ^}, {@code ~}, ranges, {@code latest}) are
     *       rejected — they belong on the {@code @} form.</li>
     *   <li>{@code "group:artifact@version"} — floating (preferred).
     *       Version is parsed via {@link VersionSelector#parseFloating};
     *       bare versions default to caret.</li>
     *   <li>{@code "group:artifact"} — source-only; must appear in
     *       {@code [sources]}.</li>
     * </ul>
     */
    private static Dependency parseDepSpec(String spec, Scope scope, Map<String, GitSource> sources) {
        if (spec == null || spec.isBlank()) {
            throw new JkBuildParseException(
                    "dependencies." + scope.canonical() + " has a blank entry");
        }
        int firstColon = spec.indexOf(':');
        if (firstColon < 0) {
            throw new JkBuildParseException(
                    "dependencies." + scope.canonical() + ".\"" + spec
                            + "\" must be \"group:artifact[:version]\" or \"group:artifact@version\"");
        }
        // Look for the next `:` or `@` after the group/artifact separator;
        // whichever comes first determines the form.
        int nextColon = spec.indexOf(':', firstColon + 1);
        int atSign = spec.indexOf('@', firstColon + 1);

        String module;
        String versionPart;
        boolean floating;

        if (nextColon < 0 && atSign < 0) {
            // Source-only: "group:artifact" with no version.
            module = spec;
            versionPart = null;
            floating = false;
        } else if (atSign >= 0 && (nextColon < 0 || atSign < nextColon)) {
            // @-form: group:artifact@version
            module = spec.substring(0, atSign);
            versionPart = spec.substring(atSign + 1);
            floating = true;
        } else {
            // :-form: group:artifact:version
            module = spec.substring(0, nextColon);
            versionPart = spec.substring(nextColon + 1);
            floating = false;
        }

        if (module.endsWith(":") || module.endsWith("@")) {
            throw new JkBuildParseException(
                    "dependencies." + scope.canonical() + ".\"" + spec + "\" has empty artifact");
        }
        GitSource source = sources.get(module);
        if (versionPart == null) {
            if (source == null) {
                throw new JkBuildParseException(
                        "dependencies." + scope.canonical() + ".\"" + module
                                + "\" has no version and no matching [sources] entry");
            }
            return Dependency.git(module, source);
        }
        if (versionPart.isBlank()) {
            throw new JkBuildParseException(
                    "dependencies." + scope.canonical() + ".\"" + spec + "\" has empty version");
        }

        VersionSelector selector;
        if (floating) {
            selector = VersionSelector.parseFloating(versionPart);
        } else {
            // :-form: forbid decorations. Allow plain `=1.2.3` for symmetry
            // with the lockfile-emitted form.
            String trimmed = versionPart.trim();
            if (trimmed.startsWith("^") || trimmed.startsWith("~")
                    || trimmed.startsWith(">") || trimmed.startsWith("<")
                    || trimmed.contains(",") || "latest".equalsIgnoreCase(trimmed)) {
                throw new JkBuildParseException(
                        "dependencies." + scope.canonical() + ".\"" + spec
                                + "\" — the `:` form is for pinned versions only. "
                                + "Use \"" + module + "@" + versionPart
                                + "\" to declare a floating constraint.");
            }
            selector = VersionSelector.parse(versionPart);
        }

        if (source != null) {
            // Coord listed in deps AND sources — emit as git-sourced; the
            // version is informational only for git deps.
            return Dependency.git(module, source);
        }
        return new Dependency(module, selector, /* pinned */ !floating);
    }

    private static List<RepositorySpec> parseRepositories(TomlTable root) {
        TomlTable repos = root.getTable("repositories");
        if (repos == null) return List.of();
        List<RepositorySpec> result = new ArrayList<>(repos.size());
        for (String name : repos.keySet()) {
            Object value = repos.get(name);
            String url;
            if (value instanceof String s) {
                url = s;
            } else if (value instanceof TomlTable t) {
                String u = t.getString("url");
                if (u == null) {
                    throw new JkBuildParseException(
                            "repositories." + name + " requires a string `url` field");
                }
                url = u;
            } else {
                throw new JkBuildParseException(
                        "repositories." + name + " must be a URL string or an inline table with `url`");
            }
            try {
                result.add(new RepositorySpec(name, URI.create(url)));
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(
                        "repositories." + name + " has malformed URL: " + url, e);
            }
        }
        return result;
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
        return new Workspace(members);
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
