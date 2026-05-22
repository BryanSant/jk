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
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        return new BuildJk(project, deps, repos);
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
        return new BuildJk.Project(group, artifact, version, jdk);
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
            VersionSelector selector = parseVersion(module, value, scope);
            result.add(new Dependency(module, selector));
        }
        return result;
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
