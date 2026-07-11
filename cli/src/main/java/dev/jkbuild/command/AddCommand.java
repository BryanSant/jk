// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.config.JkBuildEditor;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.repo.MavenLayout;
import dev.jkbuild.tool.JarManifest;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk add &lt;coord|path&gt;} — add a dependency (or a local workspace module) to {@code
 * jk.toml}.
 *
 * <p>The argument may be:
 *
 * <ul>
 *   <li>A Maven-coord shorthand: {@code group:artifact:version} (pinned) or {@code
 *       group:artifact@version} (floating caret). The short {@code library} handle defaults to the
 *       artifactId; override with {@code --library}. The artifactId itself can be overridden with
 *       {@code --name}.
 *   <li>A bare short name (e.g. {@code spring-web}), optionally with an {@code @version} suffix
 *       ({@code spring-web@3.4.0}), resolved against the library catalog. The version defaults to
 *       {@code latest} when omitted. Combine with {@code --group}/{@code --ver} for names not in
 *       the catalog.
 *   <li>A local workspace module, when the argument begins with {@code :} ({@code :widget}) or
 *       contains a path separator ({@code ./widget}, {@code ../widget}, {@code libs/widget}, {@code
 *       widget/}). jk adds a dependency edge on the sibling — pinned to its {@code
 *       [project].version} — and registers its path in the workspace root's {@code
 *       [workspace].modules} (≈ {@code uv add ./lib}).
 * </ul>
 *
 * <p>Pass {@code --ping} to check availability without modifying anything.
 */
public final class AddCommand implements CliCommand {

    private String coord;
    private String libraryFlag;
    private String groupFlag;
    private String nameFlag;
    private String versionFlag;
    private boolean test;
    private boolean runtime;
    private boolean provided;
    private boolean processor;
    private boolean ping;
    private GlobalOptions global;

    @Override
    public String name() {
        return "add";
    }

    @Override
    public String description() {
        return "Add a dependency, or workspace module, to jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<handle>", "Manifest key; defaults to the dependency name.", "--library"),
                Opt.value("<group>", "Maven groupId. Required for a bare short name.", "--group"),
                Opt.value("<name>", "Maven artifactId; defaults to the library handle.", "--name"),
                // --version collides with the global --version, so jk uses --ver.
                Opt.value("<ver>", "Version selector, e.g. \"3.4.0\", \"~3.4\", \"=3.4.0\".", "--ver"),
                Opt.flag("Test scope", "--test"),
                Opt.flag("Runtime scope", "--runtime"),
                Opt.flag("Provided scope", "--provided"),
                Opt.flag("Annotation processor scope", "--processor"),
                Opt.flag("Check the dep is reachable without adding it.", "--ping"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "dep|path",
                Arity.ONE,
                "A dep: short-name, group:artifact[:version], or @version\n"
                        + "...or a local module (:name or a path like ./foo/bar)."));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.coord = in.positionals().get(0);
        this.libraryFlag = in.value("library").orElse(null);
        this.groupFlag = in.value("group").orElse(null);
        this.nameFlag = in.value("name").orElse(null);
        this.versionFlag = in.value("ver").orElse(null);
        this.test = in.isSet("test");
        this.runtime = in.isSet("runtime");
        this.provided = in.isSet("provided");
        this.processor = in.isSet("processor");
        this.ping = in.isSet("ping");
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();

        // Local path argument: either a regular file (jk add ./libs/foo.jar)
        // or a workspace sibling directory (uv's `uv add ./lib`).
        if (isLocalPathArg(coord)) {
            String normalized = coord.replace('\\', '/');
            String stripped = (normalized.charAt(0) == ':') ? normalized.substring(1) : normalized;
            Path candidate = dir.resolve(stripped).normalize();
            if (Files.isRegularFile(candidate)) {
                Scope scope = resolveScope();
                if (scope == null) return Exit.USAGE;
                return addFile(dir, candidate, scope);
            }
            Scope scope = resolveScope();
            if (scope == null) return Exit.USAGE;
            return addModule(dir, scope);
        }

        ParsedDep parsed;
        try {
            parsed = ParsedDep.parse(coord, libraryFlag, groupFlag, nameFlag, versionFlag);
        } catch (IllegalArgumentException e) {
            CliOutput.err("jk add: " + e.getMessage());
            return Exit.USAGE;
        }

        if (ping) {
            return runPing(parsed.toCoord());
        }

        Path file = dir.resolve("jk.toml");
        if (!Files.exists(file)) {
            CliOutput.err("jk add: no jk.toml in current directory");
            return Exit.CONFIG;
        }
        Scope scope = resolveScope();
        if (scope == null) return Exit.USAGE;
        try {
            EngineEdits.apply(file, "add-dependency", java.util.List.of(
                    scope.canonical(), parsed.library(), parsed.group(), parsed.name(), parsed.versionLiteral()));
        } catch (IOException e) {
            CliOutput.err("jk add: " + e.getMessage());
            return 1;
        }
        String check = Theme.colorize(Glyphs.CHECK, Theme.active().success());
        CliOutput.out(check
                + " Added "
                + Coords.shortName(parsed.library())
                + " ("
                + Coords.gav(parsed.group(), parsed.name(), parsed.versionLiteral())
                + ") to "
                + Theme.colorize("dependency", Theme.active().cyan())
                + "."
                + Theme.colorize(scope.canonical(), Theme.active().cyan()));
        CliOutput.out();
        CliOutput.out("Run "
                + Theme.colorize("jk lock", Theme.active().warning())
                + " to lock your dependencies to hard versions");
        return 0;
    }

    /** The selected dependency scope, or {@code null} if more than one flag was given. */
    private Scope resolveScope() {
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0) + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            CliOutput.err("jk add: --test / --runtime / --provided / --processor are mutually exclusive");
            return null;
        }
        return test
                ? Scope.TEST
                : runtime ? Scope.RUNTIME : provided ? Scope.PROVIDED : processor ? Scope.PROCESSOR : Scope.MAIN;
    }

    /**
     * Whether the positional argument denotes a local workspace module rather than a Maven coord or
     * catalog library. True when it begins with {@code :} (an explicit local marker, e.g. {@code
     * :jackson}) or looks like a filesystem path — i.e. contains a {@code /} or {@code \} separator
     * ({@code ./m}, {@code ../m}, {@code backend/m}, {@code m/}, {@code ..\..\m}).
     *
     * <p>A bare name with none of these (e.g. {@code jackson}) is a catalog library and is resolved
     * as a coord — never treated as a path, even if a directory by that name happens to exist. A
     * Maven coord ({@code group:artifact:version}) has its {@code :} after the group, not at the
     * start, so it is not mistaken for a local marker.
     */
    private static boolean isLocalPathArg(String arg) {
        if (arg.isEmpty()) return false;
        return arg.charAt(0) == ':' || arg.indexOf('/') >= 0 || arg.indexOf('\\') >= 0;
    }

    /**
     * Add a local sibling as a dependency of the current project (pinned to the sibling's declared
     * version) and register it in the enclosing workspace root's {@code [workspace].modules}.
     */
    private int addModule(Path cwd, Scope scope) throws IOException {
        Path currentToml = cwd.resolve("jk.toml");
        if (!Files.exists(currentToml)) {
            CliOutput.err("jk add: no jk.toml in current directory");
            return Exit.CONFIG;
        }
        // Strip the optional leading ':' marker and normalise Windows-style
        // separators so `:jackson`, `jackson/`, and `..\..\jackson` all resolve.
        String raw = coord.charAt(0) == ':' ? coord.substring(1) : coord;
        raw = raw.replace('\\', '/');
        if (raw.isBlank()) {
            CliOutput.err("jk add: empty module path");
            return Exit.USAGE;
        }
        Path target = cwd.resolve(raw).normalize();
        Path targetToml = target.resolve("jk.toml");
        if (!Files.exists(targetToml)) {
            CliOutput.err("jk add: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(target));
            return Exit.CONFIG;
        }
        var module = BuildCommand.projectInfoOrNull(target);
        if (module == null) {
            CliOutput.err("jk add: could not read " + dev.jkbuild.cli.PathDisplay.styledRaw(targetToml));
            return 1;
        }
        String group = module.group();
        String artifact = module.name();
        String version = module.version();
        String name = (libraryFlag != null && !libraryFlag.isBlank()) ? libraryFlag : artifact;

        // 1. Dependency edge into the current project, pinned to the module's
        //    version — matching how this repo's own modules reference siblings.
        try {
            EngineEdits.apply(currentToml, "add-dependency", java.util.List.of(
                    scope.canonical(), name, group, artifact, "=" + version));
        } catch (IOException e) {
            CliOutput.err("jk add: " + e.getMessage());
            return 1;
        }
        CliOutput.out("Added "
                + Coords.shortName(name)
                + " ("
                + Coords.gav(group, artifact, version)
                + ") to ["
                + scope.tomlSection()
                + "]");

        // 2. Register membership in the enclosing workspace root (cwd itself
        //    when cwd is the root).
        Path root = WorkspaceLocator.findEnclosingWorkspace(cwd).orElse(cwd);
        Path rootToml = root.resolve("jk.toml");
        try {
            if (!target.startsWith(root)) {
                CliOutput.err("jk add: "
                        + raw
                        + " is outside the workspace root "
                        + root
                        + "; added the dependency but not registering it as a module.");
            } else if (Files.exists(rootToml)
                    && (BuildCommand.projectInfoOrNull(root) != null
                            && BuildCommand.projectInfoOrNull(root).workspaceRoot())) {
                String rel = root.relativize(target).toString().replace('\\', '/');
                if (EngineEdits.apply(rootToml, "add-workspace-module", java.util.List.of(rel))) {
                    CliOutput.out("Registered module '"
                            + rel
                            + "' in workspace "
                            + dev.jkbuild.cli.PathDisplay.styledRaw(root));
                }
            }
        } catch (RuntimeException e) {
            CliOutput.err("jk add: could not register workspace module: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Add an arbitrary local file as a dependency. The file is immediately stored in the CAS and
     * mirrored into the m2 local repo so {@code jk lock} can resolve it without re-reading the
     * original path.
     *
     * <p>For {@code .jar} files, Maven coordinate metadata is auto-detected from {@code
     * META-INF/maven/.../pom.properties}; flag overrides win. Non-JAR files, or JARs without embedded
     * metadata, require all three of {@code --group}, {@code --name}/{@code --library}, and {@code
     * --ver}.
     */
    private int addFile(Path cwd, Path filePath, Scope scope) throws IOException {
        Path tomlFile = cwd.resolve("jk.toml");
        if (!Files.exists(tomlFile)) {
            CliOutput.err("jk add: no jk.toml in current directory");
            return Exit.CONFIG;
        }

        // Auto-detect coordinates from JAR metadata (best-effort).
        Optional<Coordinate> detected = Optional.empty();
        boolean isJar = filePath.getFileName().toString().toLowerCase().endsWith(".jar");
        if (isJar) {
            try {
                detected = JarManifest.coordinateFrom(filePath);
            } catch (IOException e) {
                // Non-fatal: fall through to flag-based coords.
            }
        }

        // Flags override auto-detected values.
        String group = nonBlankOr(groupFlag, detected.map(Coordinate::group).orElse(null));
        String artifact =
                nonBlankOr(nameFlag, detected.map(Coordinate::artifact).orElse(null));
        String version =
                nonBlankOr(versionFlag, detected.map(Coordinate::version).orElse(null));
        String library = nonBlankOr(libraryFlag, artifact != null ? artifact : null);

        // Validate: all three coordinates are required.
        if (group == null || artifact == null || version == null) {
            if (!isJar) {
                CliOutput.err("jk add: --group, --name, and --ver are required for non-JAR files");
            } else {
                StringBuilder msg = new StringBuilder("jk add: could not detect");
                if (group == null) msg.append(" group");
                if (artifact == null) msg.append(group == null ? "," : "").append(" name");
                if (version == null) msg.append(" version");
                msg.append(" from JAR metadata");
                if (group == null) msg.append("; supply --group");
                if (artifact == null) msg.append("; supply --name");
                if (version == null) msg.append("; supply --ver");
                CliOutput.err(msg.toString());
            }
            return Exit.USAGE;
        }
        if (library == null) library = artifact;

        // Store in the CAS. File deps are resolved straight from the CAS by their sha256 at both
        // lock and build time (see ClasspathResolver), so no Maven-layout mirroring is needed.
        Path cache = JkDirs.cache();
        Files.createDirectories(cache);
        // Streamed hash + hard-link — the jar never has to fit in the CLI's small heap.
        String sha256 = Hashing.sha256Hex(filePath);
        Cas cas = new Cas(cache);
        cas.putByLink(filePath, sha256);

        // Edit jk.toml (engine-side).
        try {
            EngineEdits.apply(tomlFile, "add-file-dependency", java.util.List.of(
                    scope.canonical(), library, group, artifact, version, sha256));
        } catch (IOException e) {
            CliOutput.err("jk add: " + e.getMessage());
            return 1;
        }

        String check = Theme.colorize(Glyphs.CHECK, Theme.active().success());
        String shortSha = sha256.substring(0, Math.min(12, sha256.length()));
        CliOutput.out(check
                + " Added "
                + Coords.shortName(library)
                + " ("
                + Coords.gav(group, artifact, version)
                + ", sha256:"
                + shortSha
                + "...)"
                + " to "
                + Theme.colorize("dependency", Theme.active().cyan())
                + "."
                + Theme.colorize(scope.canonical(), Theme.active().cyan()));
        CliOutput.out();
        CliOutput.out("Run "
                + Theme.colorize("jk lock", Theme.active().warning())
                + " to lock your dependencies to hard versions");
        return 0;
    }

    private static String nonBlankOr(String first, String fallback) {
        return (first != null && !first.isBlank()) ? first : fallback;
    }

    /**
     * Parsed representation of a dep spec. Carries the four pieces the editor needs ({@code name},
     * {@code group}, {@code artifact}, {@code versionLiteral}) and the floating/pinned distinction
     * for round-trip display.
     */
    record ParsedDep(String library, String group, String name, String versionLiteral, boolean floating) {

        static ParsedDep parse(
                String coord, String libraryFlag, String groupFlag, String nameFlag, String versionFlag) {
            if (coord == null || coord.isBlank()) {
                throw new IllegalArgumentException("dependency argument must not be blank");
            }
            int firstColon = coord.indexOf(':');
            int atSign = coord.indexOf('@');

            if (firstColon < 0) {
                // Bare short name, optionally with an `@version` suffix
                // (e.g. `jackson3-core` or `jackson3-core@3.1.0`). The layered
                // library catalog (project + user + downloaded + bundled) supplies
                // group + artifact for curated names. The version comes from
                // --ver, else the `@version` suffix (caret-floating, like the
                // group:artifact@version coord form), else defaults to floating
                // "latest". All are resolved at `jk lock`. Flags override the catalog.
                String libraryKey = atSign >= 0 ? coord.substring(0, atSign) : coord;
                String atVersion = atSign >= 0 ? coord.substring(atSign + 1) : null;
                if (libraryKey.isBlank()) {
                    throw new IllegalArgumentException("empty name before '@' in: " + coord);
                }
                if (atVersion != null && atVersion.isBlank()) {
                    throw new IllegalArgumentException("empty version after '@' in: " + coord);
                }
                String library = nonBlank(libraryFlag, libraryKey);
                var catalog = dev.jkbuild.library.LibraryCatalog.layered(CliOutput.stderr()::println);
                var catalogHit = catalog.lookup(libraryKey);
                String group = nonBlank(
                        groupFlag,
                        catalogHit
                                .map(dev.jkbuild.library.LibraryCatalog.Module::group)
                                .orElse(null));
                String name = nonBlank(
                        nameFlag,
                        catalogHit
                                .map(dev.jkbuild.library.LibraryCatalog.Module::artifact)
                                .orElse(library));
                if (group == null || group.isBlank()) {
                    StringBuilder msg = new StringBuilder("bare name `")
                            .append(libraryKey)
                            .append("` is not in the library catalog. ");
                    List<String> suggestions = catalog.suggestionsFor(libraryKey, 5);
                    if (!suggestions.isEmpty()) {
                        msg.append("Did you mean: ")
                                .append(String.join(", ", suggestions))
                                .append("? ");
                    }
                    msg.append("Either pick an library name or supply --group ")
                            .append("(and optionally --name) explicitly.");
                    throw new IllegalArgumentException(msg.toString());
                }
                boolean hasFlagVersion = versionFlag != null && !versionFlag.isBlank();
                String versionLiteral = hasFlagVersion ? versionFlag : atVersion != null ? atVersion : "latest";
                return new ParsedDep(library, group, name, versionLiteral, !hasFlagVersion);
            }

            // Maven-coord shorthand (has a colon). Three forms:
            //   group:artifact            → version="latest", floating=true
            //   group:artifact@version    → caret-floating
            //   group:artifact:version    → pinned (versionLiteral prefixed with `=`)
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
                throw new IllegalArgumentException("expected group:artifact in: " + coord);
            }
            String groupFromCoord = moduleStr.substring(0, sep);
            String artifactFromCoord = moduleStr.substring(sep + 1);

            // Flags override the parsed coord. Defaults: library → artifactId,
            // name → artifactId (override with --name), group → groupFromCoord.
            String library = nonBlank(libraryFlag, artifactFromCoord);
            String group = nonBlank(groupFlag, groupFromCoord);
            String name = nonBlank(nameFlag, artifactFromCoord);
            String versionLiteral = versionFlag != null && !versionFlag.isBlank()
                    ? versionFlag
                    // Pinned colon-form gets an explicit `=` prefix so the parser
                    // reads it as Exact (caret-default in `parseFloating`).
                    : floating ? rawVersion : "=" + rawVersion;
            return new ParsedDep(library, group, name, versionLiteral, floating);
        }

        private static String nonBlank(String flagValue, String fallback) {
            return (flagValue == null || flagValue.isBlank()) ? fallback : flagValue;
        }

        /** Best-effort Coordinate for --ping. Strips any `=` selector prefix. */
        Coordinate toCoord() {
            String v =
                    versionLiteral.startsWith("=") || versionLiteral.startsWith("^") || versionLiteral.startsWith("~")
                            ? versionLiteral.substring(1)
                            : versionLiteral;
            return Coordinate.of(group, name, v);
        }
    }

    private int runPing(Coordinate coord) throws IOException, InterruptedException {
        URI repoBase = RepositorySpec.MAVEN_CENTRAL.url();
        URI pomUri = repoBase.resolve(MavenLayout.pomPath(coord));
        String coordStr = Coords.gav(coord);

        var http = new Http();
        var response = http.get(pomUri);

        if (response.statusCode() == 200) {
            URI artifactUri = repoBase.resolve(MavenLayout.artifactPath(coord));
            CliOutput.out(Theme.colorize(Glyphs.CHECK, Theme.active().success()) + " " + coordStr + " is available.");
            CliOutput.out(osc8Link(artifactUri.toString()));
            return 0;
        }

        CliOutput.out(Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " " + coordStr + " is unavailable.");
        CliOutput.out("Failed to find " + coordStr + " in any configured repo.");
        return 1;
    }

    /** OSC 8 hyperlink: the URL is both the link target and the visible text. */
    private static String osc8Link(String url) {
        String coloredUrl = Theme.colorize(url, Theme.active().activeStep());
        return Ansi.hyperlink(url, coloredUrl);
    }
}
