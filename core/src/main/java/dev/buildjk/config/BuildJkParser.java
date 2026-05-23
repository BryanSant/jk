// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.config;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.Feature;
import dev.buildjk.model.Features;
import dev.buildjk.model.GitRefSpec;
import dev.buildjk.model.GitSource;
import dev.buildjk.model.Profile;
import dev.buildjk.model.Profiles;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import dev.buildjk.model.Workspace;
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
 * Loads {@code jk.toml} into a {@link BuildJk}.
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
public final class BuildJkParser {

    private BuildJkParser() {}

    public static BuildJk parse(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            throw new BuildJkParseException("jk.toml not found: " + file);
        }
        return parse(Files.readString(file));
    }

    public static BuildJk parse(String toml) {
        Objects.requireNonNull(toml, "toml");
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new BuildJkParseException(
                    "failed to parse jk.toml: " + result.errors().getFirst().getMessage());
        }
        BuildJk.Project project = parseProject(result);
        Map<String, GitSource> sources = parseSources(result);
        BuildJk.Dependencies deps = parseDependencies(result, sources);
        List<RepositorySpec> repos = parseRepositories(result);
        Profiles profiles = parseProfiles(result);
        Features features = parseFeatures(result);
        Workspace workspace = parseWorkspace(result);
        return new BuildJk(project, deps, repos, profiles, features, workspace);
    }

    private static BuildJk.Project parseProject(TomlTable root) {
        TomlTable project = root.getTable("project");
        if (project == null) {
            throw new BuildJkParseException("jk.toml must declare a top-level `[project]` table");
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
        return new BuildJk.Project(group, artifact, version, jdk,
                main, shadow, nativeImage, language);
    }

    private static Map<String, GitSource> parseSources(TomlTable root) {
        TomlTable sources = root.getTable("sources");
        if (sources == null) return Map.of();
        Map<String, GitSource> result = new LinkedHashMap<>();
        // Source keys are coords like "com.foo:bar" — colon isn't legal in
        // tomlj's dotted-key parser, so look up by literal-key path.
        for (String key : sources.keySet()) {
            Object value = sources.get(java.util.List.of(key));
            if (!(value instanceof TomlTable entry)) {
                throw new BuildJkParseException(
                        "sources.\"" + key + "\" must be an inline table");
            }
            result.put(key, parseGitSource(entry, "sources.\"" + key + "\""));
        }
        return result;
    }

    private static GitSource parseGitSource(TomlTable obj, String displayPath) {
        String urlRaw = obj.getString("git");
        if (urlRaw == null) {
            throw new BuildJkParseException(displayPath + " requires a `git` URL");
        }
        String canonical = dev.buildjk.util.GitUrl.canonicalize(urlRaw);
        String tag = obj.getString("tag");
        String branch = obj.getString("branch");
        String rev = obj.getString("rev");
        int set = (tag != null ? 1 : 0) + (branch != null ? 1 : 0) + (rev != null ? 1 : 0);
        if (set == 0) {
            throw new BuildJkParseException(
                    displayPath + " must set one of `tag`, `branch`, or `rev`");
        }
        if (set > 1) {
            throw new BuildJkParseException(
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

    private static BuildJk.Dependencies parseDependencies(
            TomlTable root, Map<String, GitSource> sources) {
        TomlTable deps = root.getTable("dependencies");
        if (deps == null) return BuildJk.Dependencies.empty();
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            String key = scope.canonical();
            TomlArray arr = deps.getArray(key);
            if (arr == null) continue;
            List<Dependency> parsed = parseScopeArray(arr, scope, sources);
            if (!parsed.isEmpty()) byScope.put(scope, parsed);
        }
        return new BuildJk.Dependencies(byScope);
    }

    private static List<Dependency> parseScopeArray(
            TomlArray arr, Scope scope, Map<String, GitSource> sources) {
        List<Dependency> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String spec)) {
                throw new BuildJkParseException(
                        "dependencies." + scope.canonical() + " must be a list of \"group:artifact:version\" strings");
            }
            result.add(parseDepSpec(spec, scope, sources));
        }
        return result;
    }

    /**
     * Parse a single dep string. Forms:
     * <ul>
     *   <li>{@code "group:artifact:version"} — Maven coord, version passed to
     *       {@link VersionSelector#parse}. May still be overlaid with a source.</li>
     *   <li>{@code "group:artifact"} — source-only; must appear in {@code [sources]}.</li>
     * </ul>
     */
    private static Dependency parseDepSpec(String spec, Scope scope, Map<String, GitSource> sources) {
        if (spec == null || spec.isBlank()) {
            throw new BuildJkParseException(
                    "dependencies." + scope.canonical() + " has a blank entry");
        }
        int firstColon = spec.indexOf(':');
        if (firstColon < 0) {
            throw new BuildJkParseException(
                    "dependencies." + scope.canonical() + ".\"" + spec
                            + "\" must be \"group:artifact[:version]\"");
        }
        int secondColon = spec.indexOf(':', firstColon + 1);
        String module;
        String versionPart;
        if (secondColon < 0) {
            module = spec;
            versionPart = null;
        } else {
            module = spec.substring(0, secondColon);
            versionPart = spec.substring(secondColon + 1);
        }
        if (module.endsWith(":")) {
            throw new BuildJkParseException(
                    "dependencies." + scope.canonical() + ".\"" + spec + "\" has empty artifact");
        }
        GitSource source = sources.get(module);
        if (versionPart == null) {
            if (source == null) {
                throw new BuildJkParseException(
                        "dependencies." + scope.canonical() + ".\"" + module
                                + "\" has no version and no matching [sources] entry");
            }
            return Dependency.git(module, source);
        }
        if (versionPart.isBlank()) {
            throw new BuildJkParseException(
                    "dependencies." + scope.canonical() + ".\"" + spec + "\" has empty version");
        }
        VersionSelector selector = VersionSelector.parse(versionPart);
        if (source != null) {
            // Coord listed in deps AND sources — emit as git-sourced; the
            // version is informational only for git deps.
            return Dependency.git(module, source);
        }
        return new Dependency(module, selector);
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
                    throw new BuildJkParseException(
                            "repositories." + name + " requires a string `url` field");
                }
                url = u;
            } else {
                throw new BuildJkParseException(
                        "repositories." + name + " must be a URL string or an inline table with `url`");
            }
            try {
                result.add(new RepositorySpec(name, URI.create(url)));
            } catch (IllegalArgumentException e) {
                throw new BuildJkParseException(
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
                throw new BuildJkParseException("profiles." + name + " must be a table");
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
                throw new BuildJkParseException(
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
            throw new BuildJkParseException("jk.toml is missing required key `" + displayPath + "`");
        }
        return v;
    }

    private static List<String> optionalStringList(TomlTable table, String key, String displayPath) {
        if (!table.contains(key)) return List.of();
        Object value = table.get(key);
        if (!(value instanceof TomlArray arr)) {
            throw new BuildJkParseException("expected `" + displayPath + "` to be a list of strings");
        }
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new BuildJkParseException("expected `" + displayPath + "` to be a list of strings");
            }
            result.add(s);
        }
        return result;
    }
}
