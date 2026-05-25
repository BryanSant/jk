// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import dev.jkbuild.util.JkThreads;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk new} — create a new jk project (aliases: {@code init}, {@code create}).
 *
 * <p>If stdin/stdout is a TTY and no flags were supplied, drops into an
 * interactive wizard (see {@code dev.jkbuild.cli.tui}). Otherwise reads from
 * flags with sane defaults. Both paths converge on {@link NewInputs} +
 * {@link NewScaffolder#write(NewInputs)}.
 */
@Command(name = "new", aliases = {"init", "create"}, description = "Create a new jk project")
public final class NewCommand implements Callable<Integer> {

    @Option(names = "--name", description = "Project name (target directory).")
    String name;

    @Option(names = "--artifact", description = "Maven artifactId. Defaults to --name.")
    String artifact;

    @Option(names = "--group", description = "Maven groupId. Default: inferred from ~/.gitconfig, else 'com.example'.")
    String group;

    @Option(names = "--jdk", description = "JDK major version. Default: '25'.")
    String jdk;

    @Option(names = "--lang", description = "Source language: java | kotlin. Default: java.")
    String lang;

    @Option(names = "--executable", description = "Generate an executable project (default is library).", negatable = true)
    Boolean executable;

    @Option(names = "--shadow", description = "Bundle as a shadow (fat) jar. Implies --executable.")
    boolean shadow;

    @Option(names = "--native", description = "Wire a GraalVM native-image build.")
    boolean nativeImage;

    @Option(names = "--deps", description = "Comma-separated curated deps: lombok, jspecify, kotest, commons-lang, commons-io, guava.")
    String depsCsv;

    @Option(names = "--kotlin-compact", description = "Use Kotlin compact project structure (Main.kt at ./src/Main.kt).")
    boolean kotlinCompact;

    @Option(names = "--kotlin-module", description = "Kotlin module name; emitted as project.module in jk.toml.")
    String kotlinModule;

    @Parameters(arity = "0..1", description = "Target directory. Default: current directory or --name subdir.")
    Path directory;

    @Override
    public Integer call() throws IOException {
        Path cwd = Path.of(".").toAbsolutePath().normalize();

        // Fail-fast for `jk new .` when the cwd already has a project.
        // For any other invocation we defer the existing-manifest check to
        // after the target is fully resolved (the project name may come from
        // the wizard or from `--name`).
        if (directory != null && isCurrentDirArg(directory)
                && Files.exists(cwd.resolve("jk.toml"))) {
            String existing = wizardPresetName(directory, cwd).orElseGet(
                    () -> cwd.getFileName() != null ? cwd.getFileName().toString() : "this directory");
            emitProjectExistsError(existing);
            return 2; // EX_CONFIG
        }

        if (shouldRunWizard()) {
            // Pre-warm: JDK discovery (probe chain over the filesystem) and the
            // JDK catalog fetch (XZ download or cache hit) both run in parallel
            // with TerminalBuilder.build() (which probes the terminal's
            // capabilities). All three are IO-bound and independent; overlap
            // saves 50–200ms of perceived startup time before the wizard's
            // first frame. Catalog fetch is best-effort: a network failure
            // collapses to an empty catalog and the wizard offers only what's
            // installed.
            var jdkOptionsFuture = java.util.concurrent.CompletableFuture
                    .supplyAsync(NewJdkOptions::discover, JkThreads.io());
            var catalogFuture = java.util.concurrent.CompletableFuture
                    .supplyAsync(NewCommand::fetchCatalogQuiet, JkThreads.io());
            Terminal terminal;
            try {
                terminal = TerminalBuilder.builder().system(true).build();
            } catch (IOException e) {
                throw new RuntimeException("failed to open terminal: " + e.getMessage(), e);
            }
            var jdkOptions = jdkOptionsFuture.join();
            var catalog = catalogFuture.join();
            var candidates = NewJdkCandidate.build(
                    jdkOptions, catalog, LATEST_LTS_MAJOR,
                    dev.jkbuild.jdk.HostPlatform.currentOs(),
                    dev.jkbuild.jdk.HostPlatform.currentArch());
            if (candidates.isEmpty()) {
                emitNoJdksError();
                return 2; // EX_CONFIG
            }
            // Pre-seed the "name" answer when the user already supplied one
            // via the positional arg. Skipped steps render as settled.
            Answers preset = wizardPresetName(directory, cwd)
                    .map(n -> Answers.of(Map.of("name", (Object) n)))
                    .orElseGet(() -> Answers.of(Map.of()));

            var groupGuess = NewGroupGuess.guess(cwd,
                    Optional.ofNullable(System.getProperty("user.home")).map(Path::of).orElse(null));
            var wizard = buildWizard(candidates, groupGuess);
            var wizardResult = wizard.run(terminal, preset);
            if (wizardResult.isEmpty()) {
                // Cancelled via Ctrl-C. Wizard.printCancellation preserves the
                // cyan active-rail closer and prints the red marker beside it.
                //
                // Runtime.halt() instead of System.exit(): halt skips shutdown
                // hooks, which is what we want here because JLine registers a
                // terminal-cleanup hook that calls Terminal.close() -> blocks on
                // the NonBlockingReader background thread that's blocked on a
                // stdin read() macOS won't let us interrupt. Our wizard's finally
                // already restored terminal attributes, cursor, and SGR.
                Wizard.printCancellation(terminal, "𝘅 Project creation canceled");
                Runtime.getRuntime().halt(130); // 128 + SIGINT
            }
            try (terminal) {
                var picked = pickCandidate(wizardResult.get(), candidates);
                NewJdkCandidate resolved = picked;
                if (!resolved.installed()) {
                    var installed = installCandidate(resolved);
                    if (installed.isEmpty()) return 2;
                    resolved = installed.get();
                }
                // After install, the candidate is always Installed (installCandidate's
                // contract). Unwrap to the underlying Option for fromAnswers.
                var pickedOpt = ((NewJdkCandidate.Installed) resolved).option();
                var inputs = fromAnswers(wizardResult.get(), cwd, pickedOpt);
                if (Files.exists(inputs.directory().resolve("jk.toml"))) {
                    emitProjectExistsError(inputs.name());
                    return 2;
                }
                Files.createDirectories(inputs.directory());
                NewScaffolder.write(inputs);
                emitSuccessOnTerminal(inputs, terminal);
            } catch (IOException e) {
                throw new RuntimeException("failed to close terminal: " + e.getMessage(), e);
            }
            return 0;
        }

        var inputs = fromFlags(cwd);
        if (shadow && inputs.main().isEmpty()) {
            System.err.println("jk new: --shadow requires --executable");
            return 64; // EX_USAGE
        }
        if (Files.exists(inputs.directory().resolve("jk.toml"))) {
            emitProjectExistsError(inputs.name());
            return 2;
        }
        Files.createDirectories(inputs.directory());
        NewScaffolder.write(inputs);
        emitSuccessPlain(inputs);
        return 0;
    }

    /**
     * TTY + no real flags. A bare positional ({@code jk new my-project} or
     * {@code jk new .}) still runs the wizard — the positional only
     * pre-seeds the name.
     */
    private boolean shouldRunWizard() {
        if (!isInteractiveTerminal()) {
            return false;
        }
        return !anyFlagSupplied();
    }

    private static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }

    private boolean anyFlagSupplied() {
        return name != null
                || artifact != null
                || group != null
                || jdk != null
                || lang != null
                || executable != null
                || shadow
                || nativeImage
                || depsCsv != null
                || kotlinCompact
                || kotlinModule != null;
    }

    private NewInputs fromFlags(Path cwd) {
        var presetName = wizardPresetName(directory, cwd);
        var resolvedName = (name != null && !name.isBlank())
                ? name
                : presetName.orElse("untitled");
        var resolvedArtifact = (artifact != null && !artifact.isBlank()) ? artifact : resolvedName;
        var resolvedGroup = (group != null && !group.isBlank())
                ? group
                : NewGroupGuess.guess(cwd,
                        Optional.ofNullable(System.getProperty("user.home")).map(Path::of).orElse(null));
        var resolvedJdk = (jdk != null && !jdk.isBlank()) ? jdk : "25";
        int resolvedJdkMajor = parseJdkMajorOrDefault(resolvedJdk);
        var resolvedLang = parseLanguage(lang);
        var isExecutable = Boolean.TRUE.equals(executable) || shadow || nativeImage;
        var resolvedMain = isExecutable
                ? Optional.of(deriveMainFqcn(resolvedGroup, resolvedLang, kotlinCompact))
                : Optional.<String>empty();
        var resolvedDeps = parseDeps(depsCsv);
        var resolvedKotlinModule = (kotlinModule != null && !kotlinModule.isBlank())
                ? Optional.of(kotlinModule)
                : Optional.<String>empty();
        Path target = resolveTarget(directory, cwd, resolvedName);
        return new NewInputs(
                resolvedGroup, resolvedName, resolvedArtifact,
                resolvedJdk, resolvedJdkMajor,
                Optional.<String>empty(), // flag path doesn't resolve to a specific install
                resolvedMain, shadow, nativeImage,
                resolvedLang, kotlinCompact, resolvedKotlinModule,
                resolvedDeps, true, target);
    }

    /**
     * The wizard's pre-seed value for the "Project name" answer when the
     * user already supplied the answer via the positional arg.
     * <ul>
     *   <li>{@code "."} → cwd's leaf name.</li>
     *   <li>{@code "my-project"} → {@code "my-project"} (the file-name of
     *       the path, in case the user typed a relative/absolute path).</li>
     *   <li>{@code null} (no positional) → empty — wizard asks the user,
     *       defaulting to {@code "untitled"}.</li>
     * </ul>
     * Package-private for unit testing.
     */
    static Optional<String> wizardPresetName(Path directoryArg, Path cwd) {
        if (directoryArg == null) return Optional.empty();
        if (isCurrentDirArg(directoryArg)) {
            var leaf = cwd.getFileName();
            if (leaf == null) return Optional.empty();
            var s = leaf.toString();
            return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
        }
        var leaf = directoryArg.getFileName();
        if (leaf == null) return Optional.empty();
        var s = leaf.toString();
        return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
    }

    /**
     * Final target directory, given the positional arg, the cwd, and the
     * resolved project name. Package-private for unit testing.
     */
    static Path resolveTarget(Path directoryArg, Path cwd, String projectName) {
        if (directoryArg != null) {
            if (isCurrentDirArg(directoryArg)) return cwd;
            return cwd.resolve(directoryArg).normalize();
        }
        // No positional → create a subdir under cwd named after the project.
        return cwd.resolve(projectName);
    }

    /** Match the literal {@code "."} forms the user might type. */
    private static boolean isCurrentDirArg(Path arg) {
        var raw = arg.toString();
        return raw.equals(".") || raw.equals("./") || raw.equals(".\\");
    }

    /**
     * Styled "project already exists" error.
     * <pre>
     *   ⚠ Jk: Failed to initialize a new project. Project &lt;name&gt; already exists.
     * </pre>
     * Yellow for {@code ⚠} and the project name, hot pink for "Jk", soft
     * gray for the rest.
     */
    private static void emitProjectExistsError(String projectName) {
        // 24-bit ANSI. Terminals that ignore CSI escapes will see literal text;
        // terminals that don't speak TrueColor degrade to the nearest indexed
        // color (JLine policy elsewhere in the CLI).
        final String yellow  = "\033[38;2;234;179;8m";   // #eab308
        final String hotPink = "\033[38;2;255;105;180m"; // #ff69b4
        final String gray    = "\033[38;2;156;163;175m"; // #9ca3af
        final String reset   = "\033[0m";
        System.err.println(
                yellow + "⚠" + reset
                        + " " + hotPink + "Jk" + reset
                        + gray + ": Failed to initialize a new project. Project " + reset
                        + yellow + projectName + reset
                        + gray + " already exists." + reset);
    }

    /** Same styling as {@link #emitProjectExistsError} for the no-JDK case. */
    private static void emitNoJdksError() {
        final String yellow  = "\033[38;2;234;179;8m";
        final String hotPink = "\033[38;2;255;105;180m";
        final String gray    = "\033[38;2;156;163;175m";
        final String reset   = "\033[0m";
        System.err.println(
                yellow + "⚠" + reset
                        + " " + hotPink + "Jk" + reset
                        + gray + ": No JDKs found on this system. Run " + reset
                        + yellow + "jk jdk install" + reset
                        + gray + " first, then re-run " + reset
                        + yellow + "jk new" + reset
                        + gray + "." + reset);
    }

    private static NewInputs.Language parseLanguage(String value) {
        if (value == null || value.isBlank()) {
            return NewInputs.Language.JAVA;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "java" -> NewInputs.Language.JAVA;
            case "kotlin", "kt" -> NewInputs.Language.KOTLIN;
            default -> throw new IllegalArgumentException(
                    "jk new: --lang must be 'java' or 'kotlin', got: " + value);
        };
    }

    private static List<String> parseDeps(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (var part : csv.split(",")) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static int parseJdkMajorOrDefault(String jdk) {
        return NewJdkOptions.parseMajor(jdk).orElse(25);
    }

    /** Current Java LTS feature release. Bumped on each new LTS. */
    static final int LATEST_LTS_MAJOR = 25;

    /**
     * Best-effort catalog fetch for the wizard's "Select a JDK" step. Network
     * failures (offline, DNS, 5xx) degrade to an empty optional rather than
     * killing the wizard: the user still sees whatever installs are on disk.
     */
    private static Optional<dev.jkbuild.jdk.JdkCatalog> fetchCatalogQuiet() {
        try {
            return Optional.of(new dev.jkbuild.jdk.JdkCatalogClient().fetch());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolve the wizard's {@code jdk} answer back to a candidate. The
     * candidate's {@code id()} matches one of the entries we surfaced via
     * {@link NewJdkCandidate#filter}, so this is just a lookup.
     */
    private NewJdkCandidate pickCandidate(Answers answers, List<NewJdkCandidate> candidates) {
        var pickedId = answers.get("jdk");
        return candidates.stream()
                .filter(c -> c.id().equals(pickedId))
                .findFirst()
                .orElseGet(() -> candidates.getFirst());
    }

    /**
     * Download + extract an installable candidate. Reuses the same progress
     * UI as {@code jk jdk install}. On success, returns the freshly-resolved
     * installed candidate (so its {@code home()} points at the new JDK).
     * On failure, prints the error and returns empty so the caller exits.
     */
    private Optional<NewJdkCandidate> installCandidate(NewJdkCandidate candidate) {
        if (!(candidate instanceof NewJdkCandidate.Installable installable)) {
            return Optional.of(candidate);
        }
        try {
            var entry = installable.entry();
            var installer = new dev.jkbuild.jdk.JdkInstaller(
                    new dev.jkbuild.http.Http(), new dev.jkbuild.jdk.JdkRegistry());
            // Download (progress bar) then extract (spinner).
            var label = entry.vendor() + " " + entry.product() + " " + entry.majorVersion();
            try (var pb = dev.jkbuild.cli.tui.ProgressBar.show(System.out)) {
                pb.update(0, "Downloading " + label);
                long total = entry.archiveSize();
                var dl = installer.download(entry, bytes -> {
                    int pct = total > 0 ? (int) Math.min(100, bytes * 100L / total) : 0;
                    pb.update(pct, "Downloading " + label);
                });
                pb.finish("✓ Download finished for " + label);
                try (var sp = dev.jkbuild.cli.tui.Spinner.show(System.out, "Installing " + label + "...")) {
                    var installed = installer.extractInstalled(entry, dl);
                    System.out.println("✓ Installed " + label + " → " + installed.home());
                    var opt = new NewJdkOptions.Option(
                            installed.identifier(),
                            installed.identifier() + "  (JDK " + entry.majorVersion() + ")",
                            installed.home(),
                            entry.majorVersion(),
                            "jk");
                    return Optional.of(new NewJdkCandidate.Installed(opt, installable.vendor()));
                }
            }
        } catch (Exception e) {
            System.err.println("jk new: failed to install JDK: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fully-qualified main class name for an executable project.
     * <ul>
     *   <li>Java → {@code <group>.Main}.</li>
     *   <li>Kotlin (non-compact) → {@code <group>.MainKt} (Kotlin emits a
     *       {@code FilenameKt} synthetic class for top-level {@code fun main}).</li>
     *   <li>Kotlin compact → {@code MainKt} (no package).</li>
     * </ul>
     */
    private static String deriveMainFqcn(String group, NewInputs.Language lang, boolean kotlinCompact) {
        return switch (lang) {
            case JAVA -> group + ".Main";
            case KOTLIN -> kotlinCompact ? "MainKt" : group + ".MainKt";
        };
    }

    private static Wizard buildWizard(List<NewJdkCandidate> candidates, String groupGuess) {
        var defaultJdkId = candidates.getFirst().id();

        var javaOptions = WizardStep.MultiSelectStep.vertical("javaOptions", "Additional project options:")
                .choice("lombok", "Use Lombok")
                .choice("jspecify", "Use JSpecify (null-safety)")
                .defaults(java.util.Set.of("lombok", "jspecify"))
                .when(a -> "java".equals(a.get("lang")))
                .build();

        var kotlinOptions = WizardStep.MultiSelectStep.vertical("kotlinOptions", "Additional project options:")
                .choice("compact", "Use compact project structure")
                .choice("module", "Set module name")
                .choice("kotest", "Use Kotest for unit testing")
                .defaults(java.util.Set.of("compact", "module", "kotest"))
                .when(a -> "kotlin".equals(a.get("lang")))
                .build();

        var buildTargets = WizardStep.MultiSelectStep.vertical("targets", "Build output:")
                .choice("jar", "Regular jar")
                .choice("shadow", "Shadow (fat) jar")
                .choice("native", "Native binary")
                .defaults(java.util.Set.of("jar"))
                .when(a -> "executable".equals(a.get("kind")))
                .build();

        // Dynamic choices: filter to GraalVM at latest LTS when the user picked
        // the Native build output; otherwise show every candidate (installed
        // plus auto-installable latest-LTS rows). Built once per render so a
        // toggle on the build-output step refreshes the JDK list immediately.
        // Empty filter result (e.g. Native + offline + no GraalVM on disk)
        // falls back to the full list — the user can still progress and we'll
        // surface the missing-toolchain error later if their pick is wrong.
        var jdkStep = WizardStep.RadioStep.vertical("jdk", "Select a JDK:")
                .choicesFn(answers -> {
                    var nativeSelected = answers.getList("targets").contains("native");
                    var filtered = NewJdkCandidate.filter(candidates, nativeSelected, LATEST_LTS_MAJOR);
                    if (filtered.isEmpty()) filtered = candidates;
                    return filtered.stream()
                            .map(c -> new dev.jkbuild.cli.tui.Choice(c.id(), c.label(), c.hint()))
                            .toList();
                })
                .defaultChoice(defaultJdkId);

        return Wizard.builder()
                .title("JK - Create a New Project")
                .step(WizardStep.InputStep.of("name", "Project name:")
                        .placeholder("untitled")
                        .defaultValue("untitled")
                        .build())
                .step(WizardStep.InputStep.of("group", "Maven groupId:")
                        .placeholder(groupGuess)
                        .defaultValue(groupGuess)
                        .build())
                .step(WizardStep.InputStep.of("artifact", "Maven artifactId:")
                        // Pre-fill the buffer with whatever the user just
                        // entered for the project name — the common case is
                        // "they're the same", and the user can edit.
                        .initialValueFn(a -> a.get("name"))
                        .build())
                .step(WizardStep.RadioStep.horizontal("kind", "Project type:")
                        .choice("executable", "Executable")
                        .choice("library", "Library")
                        .defaultChoice("executable")
                        .build())
                .step(buildTargets)
                .step(jdkStep.build())
                .step(WizardStep.RadioStep.horizontal("lang", "Project language:")
                        .choice("java", "Java")
                        .choice("kotlin", "Kotlin")
                        .defaultChoice("java")
                        .build())
                .step(javaOptions)
                .step(kotlinOptions)
                .build();
    }

    private NewInputs fromAnswers(Answers answers, Path cwd, NewJdkOptions.Option pickedOpt) {
        var resolvedName = answers.has("name") && !answers.get("name").isBlank()
                ? answers.get("name")
                : wizardPresetName(directory, cwd).orElse("untitled");
        var resolvedArtifact = answers.has("artifact") && !answers.get("artifact").isBlank()
                ? answers.get("artifact")
                : resolvedName;
        var resolvedGroup = answers.has("group") && !answers.get("group").isBlank()
                ? answers.get("group")
                : "com.example";

        var resolvedJdk = String.valueOf(pickedOpt.major());
        int resolvedJdkMajor = pickedOpt.major();

        var resolvedLang = "kotlin".equalsIgnoreCase(answers.get("lang"))
                ? NewInputs.Language.KOTLIN
                : NewInputs.Language.JAVA;
        var isExecutable = "executable".equals(answers.get("kind"));

        var targets = answers.getList("targets");
        boolean resolvedShadow = isExecutable && targets.contains("shadow");
        boolean resolvedNative = isExecutable && targets.contains("native");

        boolean resolvedKotlinCompact = false;
        Optional<String> resolvedKotlinModule = Optional.empty();
        var deps = new ArrayList<String>();
        if (resolvedLang == NewInputs.Language.JAVA) {
            var javaOpts = answers.getList("javaOptions");
            if (javaOpts.contains("lombok")) deps.add("lombok");
            if (javaOpts.contains("jspecify")) deps.add("jspecify");
        } else {
            var kotlinOpts = answers.getList("kotlinOptions");
            resolvedKotlinCompact = kotlinOpts.contains("compact");
            if (kotlinOpts.contains("module")) {
                resolvedKotlinModule = Optional.of(resolvedArtifact);
            }
            if (kotlinOpts.contains("kotest")) deps.add("kotest");
        }

        var resolvedMain = isExecutable
                ? Optional.of(deriveMainFqcn(resolvedGroup, resolvedLang, resolvedKotlinCompact))
                : Optional.<String>empty();

        Path target = resolveTarget(directory, cwd, resolvedName);
        return new NewInputs(
                resolvedGroup, resolvedName, resolvedArtifact,
                resolvedJdk, resolvedJdkMajor,
                Optional.of(pickedOpt.id()),
                resolvedMain, resolvedShadow, resolvedNative,
                resolvedLang, resolvedKotlinCompact, resolvedKotlinModule,
                deps, true, target);
    }

    private static void emitSuccessOnTerminal(NewInputs inputs, Terminal terminal) {
        var writer = terminal.writer();
        writer.println(headline("Done. Next:").toAnsi(terminal));
        for (var line : nextSteps(inputs)) {
            writer.println(new AttributedStringBuilder()
                    .append("  ")
                    .append(line, Theme.dim())
                    .toAttributedString()
                    .toAnsi(terminal));
        }
        writer.flush();
    }

    private static void emitSuccessPlain(NewInputs inputs) {
        System.out.println("Created " + inputs.directory().resolve("jk.toml"));
        System.out.println("Created " + inputs.directory().resolve("jk.lock"));
        System.out.println();
        System.out.println("Done. Next:");
        for (var line : nextSteps(inputs)) {
            System.out.println("  " + line);
        }
    }

    private static AttributedString headline(String text) {
        return new AttributedStringBuilder()
                .append(text, Theme.success())
                .toAttributedString();
    }

    private static List<String> nextSteps(NewInputs inputs) {
        var dirArg = inputs.directory().toString();
        return List.of(
                "cd " + dirArg,
                inputs.isRunnable() ? "jk run" : "jk compile",
                "jk test");
    }

    // Suppress unused warning for the import we keep for clarity.
    @SuppressWarnings("unused")
    private static final List<String> CURATED_IDS = Arrays.asList(
            "lombok", "jspecify", "kotest", "commons-lang", "commons-io", "guava");
}
