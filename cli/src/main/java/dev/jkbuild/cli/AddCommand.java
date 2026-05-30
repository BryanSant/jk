// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.config.JkBuildEditor;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.JkBuild;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk add &lt;coord|path&gt;} — add a dependency (or a local workspace
 * member) to {@code jk.toml}.
 *
 * <p>The argument may be:
 * <ul>
 *   <li>A Maven-coord shorthand: {@code group:artifact:version} (pinned) or
 *       {@code group:artifact@version} (floating caret). The short {@code name}
 *       defaults to the artifactId; override with {@code --name}.</li>
 *   <li>A bare short name (e.g. {@code spring-web}), resolved against the
 *       alias registry; combine with {@code --group}/{@code --ver} for names
 *       not in the registry.</li>
 *   <li>A local workspace member, when the argument begins with {@code :}
 *       ({@code :widget}) or contains a path separator ({@code ./widget},
 *       {@code ../widget}, {@code libs/widget}, {@code widget/}). jk adds a
 *       dependency edge on the sibling — pinned to its {@code [project].version}
 *       — and registers its path in the workspace root's
 *       {@code [workspace].members} (≈ {@code uv add ./lib}).</li>
 * </ul>
 *
 * <p>Pass {@code --ping} to check availability without modifying anything.
 */
@Command(name = "add", description = "Add a dependency, or a local workspace member, to jk.toml")
public final class AddCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "[dep|path]",
            description = "group:artifact:version (pinned), group:artifact@version (floating), "
                    + "a bare short name combined with --group/--ver, or a local workspace "
                    + "member (:name or a ./ ../ libs/ path).")
    String coord;

    @Option(names = "--name",
            description = "Short name used as the manifest key. Defaults to the artifactId.")
    String nameFlag;

    @Option(names = "--group",
            description = "Maven groupId. Required when the positional argument is a bare short name.")
    String groupFlag;

    @Option(names = "--artifact",
            description = "Maven artifactId. Defaults to the short name.")
    String artifactFlag;

    // We can't use `--version` here: it collides with the global `--version`
    // flag on the parent command. Picocli rejects duplicate option names even
    // when the global lives on a Mixin. `--ver` is the next-best abbreviation.
    @Option(names = "--ver",
            description = "Version selector (e.g. \"3.4.0\", \"=3.4.0\", \"~3.4\"). "
                    + "Required when the positional argument is a bare short name.")
    String versionFlag;

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
        Path dir = global.workingDir();

        // Local workspace sibling (uv's `uv add ./lib`): a dependency edge into
        // the current project plus registration in the workspace root's
        // [workspace].members. Anything else — a Maven coord or a registry
        // alias — is resolved by ParsedDep.parse below.
        if (isLocalPathArg(coord)) {
            Scope scope = resolveScope();
            if (scope == null) return 64;
            return addMember(dir, scope);
        }

        ParsedDep parsed;
        try {
            parsed = ParsedDep.parse(coord, nameFlag, groupFlag, artifactFlag, versionFlag);
        } catch (IllegalArgumentException e) {
            System.err.println("jk add: " + e.getMessage());
            return 64; // EX_USAGE
        }

        if (ping) {
            return runPing(parsed.toCoord());
        }

        Path file = dir.resolve("jk.toml");
        if (!Files.exists(file)) {
            System.err.println("jk add: no jk.toml in current directory");
            return 2; // EX_CONFIG
        }
        Scope scope = resolveScope();
        if (scope == null) return 64;
        String original = Files.readString(file);
        String updated;
        try {
            updated = JkBuildEditor.addDependency(original, scope,
                    parsed.name(), parsed.group(), parsed.artifact(), parsed.versionLiteral());
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println("jk add: " + e.getMessage());
            return 1;
        }
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        System.out.println("Added " + parsed.name() + " ("
                + parsed.group() + ":" + parsed.artifact() + " " + parsed.versionLiteral()
                + ") to dependencies." + scope.canonical());
        System.out.println("Run `jk lock` to resolve (not yet implemented).");
        return 0;
    }

    /** The selected dependency scope, or {@code null} if more than one flag was given. */
    private Scope resolveScope() {
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0)
                + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            System.err.println(
                    "jk add: --test / --runtime / --provided / --processor are mutually exclusive");
            return null;
        }
        return test ? Scope.TEST
                : runtime ? Scope.RUNTIME
                : provided ? Scope.PROVIDED
                : processor ? Scope.PROCESSOR
                : Scope.MAIN;
    }

    /**
     * Whether the positional argument denotes a local workspace member rather
     * than a Maven coord or registry alias. True when it begins with
     * {@code :} (an explicit local marker, e.g. {@code :jackson}) or looks
     * like a filesystem path — i.e. contains a {@code /} or {@code \}
     * separator ({@code ./m}, {@code ../m}, {@code backend/m}, {@code m/},
     * {@code ..\..\m}).
     *
     * <p>A bare name with none of these (e.g. {@code jackson}) is a registry
     * alias and is resolved as a coord — never treated as a path, even if a
     * directory by that name happens to exist. A Maven coord
     * ({@code group:artifact:version}) has its {@code :} after the group, not
     * at the start, so it is not mistaken for a local marker.
     */
    private static boolean isLocalPathArg(String arg) {
        if (arg.isEmpty()) return false;
        return arg.charAt(0) == ':' || arg.indexOf('/') >= 0 || arg.indexOf('\\') >= 0;
    }

    /**
     * Add a local sibling as a dependency of the current project (pinned to
     * the sibling's declared version) and register it in the enclosing
     * workspace root's {@code [workspace].members}.
     */
    private int addMember(Path cwd, Scope scope) throws IOException {
        Path currentToml = cwd.resolve("jk.toml");
        if (!Files.exists(currentToml)) {
            System.err.println("jk add: no jk.toml in current directory");
            return 2;
        }
        // Strip the optional leading ':' marker and normalise Windows-style
        // separators so `:jackson`, `jackson/`, and `..\..\jackson` all resolve.
        String raw = coord.charAt(0) == ':' ? coord.substring(1) : coord;
        raw = raw.replace('\\', '/');
        if (raw.isBlank()) {
            System.err.println("jk add: empty member path");
            return 64;
        }
        Path target = cwd.resolve(raw).normalize();
        Path targetToml = target.resolve("jk.toml");
        if (!Files.exists(targetToml)) {
            System.err.println("jk add: no jk.toml in " + target);
            return 2;
        }
        JkBuild member;
        try {
            member = JkBuildParser.parse(targetToml);
        } catch (RuntimeException e) {
            System.err.println("jk add: " + e.getMessage());
            return 1;
        }
        String group = member.project().group();
        String artifact = member.project().artifact();
        String version = member.project().version();
        String name = (nameFlag != null && !nameFlag.isBlank()) ? nameFlag : artifact;

        // 1. Dependency edge into the current project, pinned to the member's
        //    version — matching how this repo's own members reference siblings.
        String original = Files.readString(currentToml);
        String updated;
        try {
            updated = JkBuildEditor.addDependency(
                    original, scope, name, group, artifact, "=" + version);
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println("jk add: " + e.getMessage());
            return 1;
        }
        Files.writeString(currentToml, updated, StandardCharsets.UTF_8);
        System.out.println("Added " + name + " (" + group + ":" + artifact + " ="
                + version + ") to dependencies." + scope.canonical());

        // 2. Register membership in the enclosing workspace root (cwd itself
        //    when cwd is the root).
        Path root = WorkspaceLocator.findEnclosingWorkspace(cwd).orElse(cwd);
        Path rootToml = root.resolve("jk.toml");
        try {
            if (!target.startsWith(root)) {
                System.err.println("jk add: " + raw
                        + " is outside the workspace root " + root
                        + "; added the dependency but not registering it as a member.");
            } else if (Files.exists(rootToml) && JkBuildParser.parse(rootToml).isWorkspaceRoot()) {
                String rel = root.relativize(target).toString().replace('\\', '/');
                String rootContent = Files.readString(rootToml);
                String newRoot = JkBuildEditor.addWorkspaceMember(rootContent, rel);
                if (!newRoot.equals(rootContent)) {
                    Files.writeString(rootToml, newRoot, StandardCharsets.UTF_8);
                    System.out.println("Registered member '" + rel + "' in workspace " + root);
                }
            }
        } catch (RuntimeException e) {
            System.err.println("jk add: could not register workspace member: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Parsed representation of a dep spec. Carries the four pieces the
     * editor needs ({@code name}, {@code group}, {@code artifact},
     * {@code versionLiteral}) and the floating/pinned distinction for
     * round-trip display.
     */
    record ParsedDep(String name, String group, String artifact,
                     String versionLiteral, boolean floating) {

        static ParsedDep parse(String coord, String nameFlag, String groupFlag,
                               String artifactFlag, String versionFlag) {
            if (coord == null || coord.isBlank()) {
                throw new IllegalArgumentException("dependency argument must not be blank");
            }
            int firstColon = coord.indexOf(':');
            int atSign = coord.indexOf('@');

            if (firstColon < 0 && atSign < 0) {
                // Bare short name. The layered registry (project + user +
                // downloaded + bundled) supplies group + artifact for
                // curated names; the user still picks the version.
                // Explicit flags always override the registry.
                String name = nonBlank(nameFlag, coord);
                var registry = dev.jkbuild.registry.AliasRegistry.layered();
                var registryHit = registry.lookup(coord);
                String group = nonBlank(groupFlag,
                        registryHit.map(dev.jkbuild.registry.AliasRegistry.Module::group).orElse(null));
                String artifact = nonBlank(artifactFlag,
                        registryHit.map(dev.jkbuild.registry.AliasRegistry.Module::artifact).orElse(name));
                if (group == null || group.isBlank()) {
                    StringBuilder msg = new StringBuilder("bare name `")
                            .append(coord).append("` is not in the registry. ");
                    List<String> suggestions = registry.suggestionsFor(coord, 5);
                    if (!suggestions.isEmpty()) {
                        msg.append("Did you mean: ")
                                .append(String.join(", ", suggestions)).append("? ");
                    }
                    msg.append("Either pick a registry name or supply --group ")
                            .append("(and optionally --artifact) explicitly.");
                    throw new IllegalArgumentException(msg.toString());
                }
                if (versionFlag == null || versionFlag.isBlank()) {
                    throw new IllegalArgumentException(
                            "bare name `" + coord + "` requires --ver");
                }
                return new ParsedDep(name, group, artifact, versionFlag, false);
            }

            // Maven-coord shorthand. Three forms:
            //   group:artifact            → version="latest", floating=true
            //   group:artifact@version    → caret-floating
            //   group:artifact:version    → pinned (versionLiteral prefixed with `=`)
            if (firstColon < 0) {
                throw new IllegalArgumentException(
                        "expected group:artifact[@version] or group:artifact:version, got: " + coord);
            }
            int nextColon = coord.indexOf(':', firstColon + 1);
            int versionMark = atSign >= 0 && (nextColon < 0 || atSign < nextColon) ? atSign : nextColon;

            String moduleStr;
            String rawVersion;
            boolean floating;
            if (versionMark < 0) {
                moduleStr = coord;
                rawVersion = "latest";
                floating = true;
            } else if (versionMark == atSign) {
                moduleStr = coord.substring(0, atSign);
                rawVersion = coord.substring(atSign + 1);
                floating = true;
                if (rawVersion.isBlank()) {
                    throw new IllegalArgumentException("empty version after '@' in: " + coord);
                }
            } else {
                moduleStr = coord.substring(0, nextColon);
                rawVersion = coord.substring(nextColon + 1);
                floating = false;
                if (rawVersion.isBlank()) {
                    throw new IllegalArgumentException("empty version after ':' in: " + coord);
                }
            }
            int sep = moduleStr.indexOf(':');
            if (sep < 0 || sep == moduleStr.length() - 1 || sep == 0) {
                throw new IllegalArgumentException(
                        "expected group:artifact in: " + coord);
            }
            String groupFromCoord = moduleStr.substring(0, sep);
            String artifactFromCoord = moduleStr.substring(sep + 1);

            // Flags override the parsed coord. Defaults: name → artifact,
            // artifact → name (if --name was set), group → groupFromCoord.
            String name = nonBlank(nameFlag, artifactFromCoord);
            String group = nonBlank(groupFlag, groupFromCoord);
            String artifact = nonBlank(artifactFlag, artifactFromCoord);
            String versionLiteral = versionFlag != null && !versionFlag.isBlank()
                    ? versionFlag
                    // Pinned colon-form gets an explicit `=` prefix so the parser
                    // reads it as Exact (caret-default in `parseFloating`).
                    : floating ? rawVersion : "=" + rawVersion;
            return new ParsedDep(name, group, artifact, versionLiteral, floating);
        }

        private static String nonBlank(String flagValue, String fallback) {
            return (flagValue == null || flagValue.isBlank()) ? fallback : flagValue;
        }

        /** Best-effort Coordinate for --ping. Strips any `=` selector prefix. */
        Coordinate toCoord() {
            String v = versionLiteral.startsWith("=") || versionLiteral.startsWith("^")
                    || versionLiteral.startsWith("~")
                    ? versionLiteral.substring(1)
                    : versionLiteral;
            return Coordinate.of(group, artifact, v);
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
