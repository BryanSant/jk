// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.GitRefSpec;
import dev.buildjk.model.GitSource;
import dev.buildjk.model.Feature;
import dev.buildjk.model.Features;
import dev.buildjk.model.Profile;
import dev.buildjk.model.Profiles;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import dev.buildjk.model.Workspace;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads {@code build.jk} (HOCON) into a {@link BuildJk}.
 *
 * <p>v0.1 scope: project block + {@code dependencies.&lt;scope&gt;} blocks.
 * Each dep is either string-form ({@code "g:a" = "1.0"}) or object-form
 * ({@code "g:a" = { version = "1.0" }}). Object form ignores fields other
 * than {@code version} for now — they're noted as accepted-but-unused
 * pending the corresponding subsystems (from / classifier / features / git).
 *
 * <p>Line-precise diagnostics (PRD §31 #3) decorator lands as a follow-up;
 * for now we propagate Lightbend Config's messages through
 * {@link BuildJkParseException}.
 */
public final class BuildJkParser {

    private static final ConfigParseOptions OPTIONS = ConfigParseOptions.defaults()
            .setSyntax(ConfigSyntax.CONF)
            .setAllowMissing(false);

    /** Guards the one-time {@code bin}-without-{@code main} deprecation warning. */
    private static final AtomicBoolean BIN_DEPRECATION_WARNED = new AtomicBoolean(false);

    private BuildJkParser() {}

    public static BuildJk parse(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            throw new BuildJkParseException("build.jk not found: " + file);
        }
        return parse(Files.readString(file));
    }

    public static BuildJk parse(String hocon) {
        Objects.requireNonNull(hocon, "hocon");
        Config config;
        try {
            config = ConfigFactory.parseString(hocon, OPTIONS).resolve();
        } catch (ConfigException e) {
            throw new BuildJkParseException("failed to parse build.jk: " + e.getMessage(), e);
        }
        BuildJk.Project project = parseProject(config);
        BuildJk.Dependencies deps = parseDependencies(config);
        List<RepositorySpec> repos = parseRepositories(config);
        Profiles profiles = parseProfiles(config);
        Features features = parseFeatures(config);
        Workspace workspace = parseWorkspace(config);
        return new BuildJk(project, deps, repos, profiles, features, workspace);
    }

    private static Workspace parseWorkspace(Config root) {
        if (!root.hasPath("workspace")) return null;
        ConfigObject workspaceObj = root.getObject("workspace");
        ConfigValue membersValue = workspaceObj.get("members");
        if (membersValue == null) {
            return new Workspace(List.of());
        }
        if (membersValue.valueType() != ConfigValueType.LIST) {
            throw new BuildJkParseException("workspace.members must be a list of paths");
        }
        List<String> members = new ArrayList<>();
        for (Object element : (List<?>) membersValue.unwrapped()) {
            members.add(element.toString());
        }
        return new Workspace(members);
    }

    private static Features parseFeatures(Config root) {
        if (!root.hasPath("features")) return Features.empty();
        ConfigObject featuresObject = root.getObject("features");
        Map<String, Feature> byName = new LinkedHashMap<>();
        List<String> defaults = List.of();
        for (Map.Entry<String, ConfigValue> entry : featuresObject.entrySet()) {
            String name = entry.getKey();
            ConfigValue value = entry.getValue();
            if (name.equals("default")) {
                if (value.valueType() != ConfigValueType.LIST) {
                    throw new BuildJkParseException(
                            "features.default must be a list of feature names");
                }
                List<String> result = new ArrayList<>();
                for (Object element : (List<?>) value.unwrapped()) {
                    result.add(element.toString());
                }
                defaults = result;
                continue;
            }
            if (value.valueType() != ConfigValueType.OBJECT) {
                throw new BuildJkParseException(
                        "features." + name + " must be an object with `deps` and/or `features`");
            }
            ConfigObject body = (ConfigObject) value;
            List<String> deps = optionalStringList(body, "deps");
            List<String> nested = optionalStringList(body, "features");
            byName.put(name, new Feature(name, deps, nested));
        }
        return new Features(byName, defaults);
    }

    private static Profiles parseProfiles(Config root) {
        if (!root.hasPath("profiles")) return Profiles.empty();
        ConfigObject profilesObject = root.getObject("profiles");
        Map<String, Profile> byName = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigValue> entry : profilesObject.entrySet()) {
            String name = entry.getKey();
            if (entry.getValue().valueType() != ConfigValueType.OBJECT) {
                throw new BuildJkParseException(
                        "profiles." + name + " must be an object");
            }
            ConfigObject body = (ConfigObject) entry.getValue();
            String inherits = optionalString(body, "inherits");
            List<String> javacArgs = optionalStringList(body, "javac");
            List<String> jvmArgs = optionalStringList(body, "jvm-args");
            byName.put(name, new Profile(name, inherits, javacArgs, jvmArgs));
        }
        return new Profiles(byName);
    }

    private static String optionalString(ConfigObject obj, String key) {
        ConfigValue value = obj.get(key);
        if (value == null) return null;
        if (value.valueType() != ConfigValueType.STRING) {
            throw new BuildJkParseException(
                    "expected `" + key + "` to be a string");
        }
        return (String) value.unwrapped();
    }

    private static List<String> optionalStringList(ConfigObject obj, String key) {
        ConfigValue value = obj.get(key);
        if (value == null) return List.of();
        if (value.valueType() != ConfigValueType.LIST) {
            throw new BuildJkParseException(
                    "expected `" + key + "` to be a list of strings");
        }
        List<String> result = new ArrayList<>();
        for (Object element : (List<?>) value.unwrapped()) {
            result.add(element.toString());
        }
        return result;
    }

    private static List<RepositorySpec> parseRepositories(Config root) {
        if (!root.hasPath("repositories")) return List.of();
        ConfigObject reposObject = root.getObject("repositories");
        List<RepositorySpec> result = new ArrayList<>(reposObject.size());
        for (Map.Entry<String, ConfigValue> entry : reposObject.entrySet()) {
            String name = entry.getKey();
            ConfigValue value = entry.getValue();
            String url;
            if (value.valueType() == ConfigValueType.STRING) {
                url = (String) value.unwrapped();
            } else if (value.valueType() == ConfigValueType.OBJECT) {
                ConfigObject obj = (ConfigObject) value;
                ConfigValue urlValue = obj.get("url");
                if (urlValue == null || urlValue.valueType() != ConfigValueType.STRING) {
                    throw new BuildJkParseException(
                            "repositories." + name + " requires a string `url` field");
                }
                url = (String) urlValue.unwrapped();
            } else {
                throw new BuildJkParseException(
                        "repositories." + name + " must be a URL string or an object with `url`");
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

    private static BuildJk.Project parseProject(Config root) {
        if (!root.hasPath("project")) {
            throw new BuildJkParseException("build.jk must declare a top-level `project` block");
        }
        Config project = root.getConfig("project");
        String group = requireString(project, "group", "project.group");
        String artifact = requireString(project, "artifact", "project.artifact");
        String version = requireString(project, "version", "project.version");
        String jdk = project.hasPath("jdk") ? project.getString("jdk") : null;
        String main = project.hasPath("main") ? project.getString("main") : null;
        boolean shadow = project.hasPath("shadow") && project.getBoolean("shadow");
        boolean nativeImage = project.hasPath("native") && project.getBoolean("native");
        String language = project.hasPath("language") ? project.getString("language") : "java";
        String bin = project.hasPath("bin") ? project.getString("bin") : null;
        if (bin != null && main == null && BIN_DEPRECATION_WARNED.compareAndSet(false, true)) {
            System.err.println("warning: build.jk uses deprecated 'bin' field; rename to 'main'");
        }
        return new BuildJk.Project(group, artifact, version, jdk,
                main, shadow, nativeImage, language, bin);
    }

    private static BuildJk.Dependencies parseDependencies(Config root) {
        if (!root.hasPath("dependencies")) {
            return BuildJk.Dependencies.empty();
        }
        Config deps = root.getConfig("dependencies");
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            String key = scope.canonical();
            if (!deps.hasPath(key)) continue;
            List<Dependency> parsed = parseScopeBlock(deps.getObject(key), scope);
            if (!parsed.isEmpty()) {
                byScope.put(scope, parsed);
            }
        }
        return new BuildJk.Dependencies(byScope);
    }

    private static List<Dependency> parseScopeBlock(ConfigObject block, Scope scope) {
        List<Dependency> result = new ArrayList<>(block.size());
        for (Map.Entry<String, ConfigValue> entry : block.entrySet()) {
            String module = entry.getKey();
            ConfigValue value = entry.getValue();
            if (value.valueType() == ConfigValueType.OBJECT
                    && ((ConfigObject) value).get("git") != null) {
                result.add(parseGitDependency(module, (ConfigObject) value, scope));
            } else {
                VersionSelector selector = parseVersion(module, value, scope);
                result.add(new Dependency(module, selector));
            }
        }
        return result;
    }

    private static Dependency parseGitDependency(String module, ConfigObject obj, Scope scope) {
        String urlRaw = stringField(obj, module, scope, "git");
        String canonical = dev.buildjk.util.GitUrl.canonicalize(urlRaw);
        GitRefSpec ref = parseGitRefSpec(obj, module, scope);
        String path = optionalString(obj, "path");
        boolean submodules = optionalBoolean(obj, "submodules", true);
        boolean verifySigned = optionalBoolean(obj, "verify-signed", false);
        GitSource source = new GitSource(canonical, urlRaw, ref, path, submodules, verifySigned);
        return Dependency.git(module, source);
    }

    private static GitRefSpec parseGitRefSpec(ConfigObject obj, String module, Scope scope) {
        String tag = optionalString(obj, "tag");
        String branch = optionalString(obj, "branch");
        String rev = optionalString(obj, "rev");
        int set = (tag != null ? 1 : 0) + (branch != null ? 1 : 0) + (rev != null ? 1 : 0);
        if (set == 0) {
            throw new BuildJkParseException(
                    "dependencies." + scope.canonical() + ".\"" + module
                            + "\" git declaration must set one of `tag`, `branch`, or `rev`");
        }
        if (set > 1) {
            throw new BuildJkParseException(
                    "dependencies." + scope.canonical() + ".\"" + module
                            + "\" git declaration must set exactly one of `tag`, `branch`, or `rev`");
        }
        if (tag != null) return new GitRefSpec.Tag(tag);
        if (branch != null) return new GitRefSpec.Branch(branch);
        return new GitRefSpec.Rev(rev);
    }

    private static String stringField(ConfigObject obj, String module, Scope scope, String field) {
        ConfigValue v = obj.get(field);
        if (v == null || v.valueType() != ConfigValueType.STRING) {
            throw new BuildJkParseException(
                    "dependencies." + scope.canonical() + ".\"" + module
                            + "\" requires a string `" + field + "` field");
        }
        return (String) v.unwrapped();
    }

    private static boolean optionalBoolean(ConfigObject obj, String key, boolean defaultValue) {
        ConfigValue v = obj.get(key);
        if (v == null) return defaultValue;
        if (v.valueType() != ConfigValueType.BOOLEAN) {
            throw new BuildJkParseException("expected `" + key + "` to be a boolean");
        }
        return (Boolean) v.unwrapped();
    }

    private static VersionSelector parseVersion(String module, ConfigValue value, Scope scope) {
        if (value.valueType() == ConfigValueType.STRING) {
            return VersionSelector.parse((String) value.unwrapped());
        }
        if (value.valueType() == ConfigValueType.OBJECT) {
            ConfigObject obj = (ConfigObject) value;
            ConfigValue version = obj.get("version");
            if (version == null || version.valueType() != ConfigValueType.STRING) {
                throw new BuildJkParseException(
                        "dependencies." + scope.canonical() + ".\"" + module
                                + "\" requires a string `version` field");
            }
            return VersionSelector.parse((String) version.unwrapped());
        }
        throw new BuildJkParseException(
                "dependencies." + scope.canonical() + ".\"" + module
                        + "\" must be a version string or an object with a `version` field");
    }

    private static String requireString(Config project, String relative, String displayPath) {
        if (!project.hasPath(relative)) {
            throw new BuildJkParseException("build.jk is missing required key `" + displayPath + "`");
        }
        return project.getString(relative);
    }
}
