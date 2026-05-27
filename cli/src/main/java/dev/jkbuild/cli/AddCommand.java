// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.config.JkBuildEditor;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Scope;
import dev.jkbuild.repo.MavenLayout;
import dev.jkbuild.model.RepositorySpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk add &lt;coord&gt;} — add a dependency to {@code jk.toml}.
 *
 * <p>Pass {@code --ping} to check availability without modifying anything.
 */
@Command(name = "add", description = "Add a dependency to jk.toml")
public final class AddCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "[dep]",
            description = "group:artifact OR group:artifact@version OR group:artifact:version")
    String coord;

    // Mutually-exclusive scope flags. Default (no flag) = main.
    // Inlined rather than wrapped in @ArgGroup because picocli-codegen 4.7.7
    // can't generate native-image reflection config for that pattern; we
    // enforce exclusivity by hand below.
    @Option(names = "--test",      description = "Test scope")
    boolean test;
    @Option(names = "--runtime",   description = "Runtime scope")
    boolean runtime;
    @Option(names = "--provided",  description = "Provided scope")
    boolean provided;
    @Option(names = "--processor", description = "Annotation processor scope")
    boolean processor;

    @Option(names = "--ping",
            description = "Check whether the dependency is reachable in configured repos without adding it.")
    boolean ping;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException, InterruptedException {
        ParsedDep parsed;
        try {
            parsed = ParsedDep.parse(coord);
        } catch (IllegalArgumentException e) {
            System.err.println("jk add: " + e.getMessage());
            return 64; // EX_USAGE
        }

        if (ping) {
            return runPing(parsed.toCoord());
        }

        Path dir = global.workingDir();
        Path file = dir.resolve("jk.toml");
        if (!Files.exists(file)) {
            System.err.println("jk add: no jk.toml in current directory");
            return 2; // EX_CONFIG
        }
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0)
                + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            System.err.println("jk add: --test / --runtime / --provided / --processor are mutually exclusive");
            return 64;
        }
        Scope scope = test ? Scope.TEST
                : runtime ? Scope.RUNTIME
                : provided ? Scope.PROVIDED
                : processor ? Scope.PROCESSOR
                : Scope.MAIN;
        String original = Files.readString(file);
        String updated;
        try {
            updated = JkBuildEditor.addDependency(
                    original, scope, parsed.module(), parsed.version(), parsed.floating());
        } catch (IllegalStateException e) {
            System.err.println("jk add: " + e.getMessage());
            return 1;
        }
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        String display = parsed.module() + (parsed.floating() ? "@" : ":") + parsed.version();
        System.out.println("Added " + display + " to dependencies." + scope.canonical());
        System.out.println("Run `jk lock` to resolve (not yet implemented).");
        return 0;
    }

    /**
     * Parsed representation of a dep spec: handles all three forms.
     * <ul>
     *   <li>{@code group:artifact}           → module, version="latest", floating=true</li>
     *   <li>{@code group:artifact@version}   → module, version, floating=true</li>
     *   <li>{@code group:artifact:version}   → module, version, floating=false</li>
     * </ul>
     */
    record ParsedDep(String module, String version, boolean floating) {

        static ParsedDep parse(String spec) {
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException("dependency spec must not be blank");
            }
            int firstColon = spec.indexOf(':');
            if (firstColon < 0) {
                throw new IllegalArgumentException(
                        "expected group:artifact[@version] or group:artifact:version, got: " + spec);
            }
            int nextColon = spec.indexOf(':', firstColon + 1);
            int atSign   = spec.indexOf('@', firstColon + 1);

            if (nextColon < 0 && atSign < 0) {
                // group:artifact — no version → latest
                return new ParsedDep(spec, "latest", true);
            }
            if (atSign >= 0 && (nextColon < 0 || atSign < nextColon)) {
                // group:artifact@version
                String ver = spec.substring(atSign + 1);
                if (ver.isBlank()) throw new IllegalArgumentException(
                        "empty version after '@' in: " + spec);
                return new ParsedDep(spec.substring(0, atSign), ver, true);
            }
            // group:artifact:version
            String ver = spec.substring(nextColon + 1);
            if (ver.isBlank()) throw new IllegalArgumentException(
                    "empty version after ':' in: " + spec);
            return new ParsedDep(spec.substring(0, nextColon), ver, false);
        }

        /** Best-effort Coordinate for --ping (requires a real version, not "latest"). */
        dev.jkbuild.model.Coordinate toCoord() {
            int colon = module.indexOf(':');
            return dev.jkbuild.model.Coordinate.of(
                    module.substring(0, colon),
                    module.substring(colon + 1),
                    version);
        }
    }

    private int runPing(Coordinate coord) throws IOException, InterruptedException {
        URI repoBase = RepositorySpec.MAVEN_CENTRAL.url();
        URI pomUri = repoBase.resolve(MavenLayout.pomPath(coord));
        String coordStr = colorCoord(coord);

        var http = new Http();
        var response = http.get(pomUri);

        if (response.statusCode() == 200) {
            URI artifactUri = repoBase.resolve(MavenLayout.artifactPath(coord));
            System.out.println(Theme.colorize("✓", Theme.brightGreen().bold())
                    + " " + coordStr + " is available.");
            System.out.println(osc8Link(artifactUri.toString()));
            return 0;
        }

        System.out.println(Theme.colorize("⚠", Theme.warning())
                + " " + coordStr + " is unavailable.");
        System.out.println("Failed to find " + coordStr + " in any configured repo.");
        return 1;
    }

    private static String colorCoord(Coordinate coord) {
        return Theme.colorize(coord.group(), Theme.activeStep())
                + ":" + Theme.colorize(coord.artifact(), Theme.activeStep().bold())
                + ":" + Theme.colorize(coord.version(), Theme.warning());
    }

    /** OSC 8 hyperlink: the URL is both the link target and the visible text. */
    private static String osc8Link(String url) {
        String coloredUrl = Theme.colorize(url, Theme.activeStep());
        return "\033]8;;" + url + "\007" + coloredUrl + "\033]8;;\007";
    }
}
