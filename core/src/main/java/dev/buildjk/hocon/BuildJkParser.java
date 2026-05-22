// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import dev.buildjk.model.BuildJk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads {@code build.jk} (HOCON) into a {@link BuildJk}.
 *
 * <p>v0.1 scope: just the {@code project} block. Line-precise diagnostics
 * decorator (PRD §31 #3) lands in a follow-up — for now we propagate
 * Lightbend Config's messages through {@link BuildJkParseException}.
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
        return fromConfig(config);
    }

    private static BuildJk fromConfig(Config config) {
        if (!config.hasPath("project")) {
            throw new BuildJkParseException("build.jk must declare a top-level `project` block");
        }
        Config project = config.getConfig("project");
        String group = requireString(project, "project.group");
        String artifact = requireString(project, "project.artifact");
        String version = requireString(project, "project.version");
        String jdk = project.hasPath("jdk") ? project.getString("jdk") : null;
        return new BuildJk(new BuildJk.Project(group, artifact, version, jdk));
    }

    private static String requireString(Config root, String path) {
        // The supplied `root` is the project sub-config; strip the "project." prefix.
        String relative = path.startsWith("project.") ? path.substring("project.".length()) : path;
        if (!root.hasPath(relative)) {
            throw new BuildJkParseException("build.jk is missing required key `" + path + "`");
        }
        return root.getString(relative);
    }
}
