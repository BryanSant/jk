// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cli.tui.Answers;
import dev.buildjk.cli.tui.Theme;
import dev.buildjk.cli.tui.Wizard;
import dev.buildjk.cli.tui.WizardStep;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
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
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk init} — initialize a new jk project.
 *
 * <p>If stdin/stdout is a TTY and no flags were supplied, drops into an
 * interactive wizard (see {@code dev.buildjk.cli.tui}). Otherwise reads from
 * flags with sane defaults. Both paths converge on {@link InitInputs} +
 * {@link InitScaffolder#write(InitInputs)}.
 */
@Command(name = "init", description = "Initialize a new jk project")
public final class InitCommand implements Callable<Integer> {

    @Option(names = "--name", description = "Project name (artifactId + default directory).")
    String name;

    @Option(names = "--group", description = "Maven groupId. Default: 'com.example'.")
    String group;

    @Option(names = "--jdk", description = "JDK version. Default: '25'.")
    String jdk;

    @Option(names = "--lang", description = "Source language: java | kotlin. Default: java.")
    String lang;

    @Option(names = "--main", description = "Fully-qualified main class (omit for a library).")
    String main;

    @Option(names = "--shadow", description = "Bundle as a shadow (fat) jar. Requires --main.")
    boolean shadow;

    @Option(names = "--native", description = "Wire a GraalVM native-image build.")
    boolean nativeImage;

    @Option(names = "--deps", description = "Comma-separated curated deps: lombok, commons-lang, commons-io, guava.")
    String depsCsv;

    @Option(names = "--sample", description = "Generate a sample source tree (default).", negatable = true)
    Boolean sample;

    @Parameters(arity = "0..1", description = "Target directory. Default: current directory or --name subdir.")
    Path directory;

    @Override
    public Integer call() throws IOException {
        if (shadow && (main == null || main.isBlank())) {
            System.err.println("jk init: --shadow requires --main <fqcn>");
            return 64; // EX_USAGE
        }

        Path target = resolveDirectory();
        Files.createDirectories(target);

        Path buildFile = target.resolve("build.jk");
        Path lockFile = target.resolve("jk.lock");
        if (Files.exists(buildFile)) {
            System.err.println("jk init: refusing to overwrite existing build.jk at " + buildFile);
            return 2; // EX_CONFIG
        }
        if (Files.exists(lockFile)) {
            System.err.println("jk init: refusing to overwrite existing jk.lock at " + lockFile);
            return 2;
        }

        if (shouldRunWizard()) {
            Terminal terminal;
            try {
                terminal = TerminalBuilder.builder().system(true).build();
            } catch (IOException e) {
                throw new RuntimeException("failed to open terminal: " + e.getMessage(), e);
            }
            var wizardResult = buildWizard(target).run(terminal);
            if (wizardResult.isEmpty()) {
                System.err.print("\n\033[31m✖ canceled ✖\033[0m\n");
                System.err.flush();
                // Runtime.halt() instead of System.exit(): halt skips shutdown
                // hooks, which is what we want here because JLine registers a
                // terminal-cleanup hook that calls Terminal.close() -> blocks on
                // the NonBlockingReader background thread that's blocked on a
                // stdin read() macOS won't let us interrupt. Our wizard's finally
                // already restored terminal attributes, cursor, and SGR.
                Runtime.getRuntime().halt(130); // 128 + SIGINT
            }
            try (terminal) {
                var inputs = fromAnswers(wizardResult.get(), target);
                InitScaffolder.write(inputs);
                emitSuccessOnTerminal(inputs, terminal);
            } catch (IOException e) {
                throw new RuntimeException("failed to close terminal: " + e.getMessage(), e);
            }
            return 0;
        }

        var inputs = fromFlags(target);
        InitScaffolder.write(inputs);
        emitSuccessPlain(inputs);
        return 0;
    }

    private Path resolveDirectory() {
        if (directory != null) {
            return directory;
        }
        if (name != null && !name.isBlank()) {
            return Path.of(name);
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    /**
     * TTY + no flags + no positional. Tests typically have no console, so
     * this returns false there.
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
                || group != null
                || jdk != null
                || lang != null
                || main != null
                || shadow
                || nativeImage
                || depsCsv != null
                || sample != null
                || directory != null;
    }

    private InitInputs fromFlags(Path target) {
        var resolvedName = (name != null && !name.isBlank())
                ? name
                : defaultName(target);
        var resolvedGroup = (group != null && !group.isBlank()) ? group : "com.example";
        var resolvedJdk = (jdk != null && !jdk.isBlank()) ? jdk : "25";
        var resolvedLang = parseLanguage(lang);
        var resolvedMain = (main != null && !main.isBlank()) ? Optional.of(main) : Optional.<String>empty();
        var resolvedDeps = parseDeps(depsCsv);
        var resolvedSample = sample == null || sample;
        return new InitInputs(
                resolvedGroup, resolvedName, resolvedJdk,
                resolvedMain, shadow, nativeImage,
                resolvedLang, resolvedDeps, resolvedSample, target);
    }

    private static String defaultName(Path target) {
        var leaf = target.toAbsolutePath().normalize().getFileName();
        if (leaf == null) {
            return "app";
        }
        var s = leaf.toString();
        return (s.isBlank() || s.equals(".")) ? "app" : s;
    }

    private static InitInputs.Language parseLanguage(String value) {
        if (value == null || value.isBlank()) {
            return InitInputs.Language.JAVA;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "java" -> InitInputs.Language.JAVA;
            case "kotlin", "kt" -> InitInputs.Language.KOTLIN;
            default -> throw new IllegalArgumentException(
                    "jk init: --lang must be 'java' or 'kotlin', got: " + value);
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

    private static Wizard buildWizard(Path target) {
        return Wizard.builder()
                .title("JK - Create a New Project")
                .step(WizardStep.InputStep.of("name", "Project name (target directory):")
                        .placeholder(target.getFileName() != null ? target.getFileName().toString() : "my-project")
                        .defaultValue(target.getFileName() != null ? target.getFileName().toString() : "my-project")
                        .build())
                .step(WizardStep.InputStep.of("group", "Maven groupId:")
                        .placeholder("com.example")
                        .defaultValue("com.example")
                        .build())
                .step(WizardStep.RadioStep.horizontal("lang", "Project language:")
                        .choice("java", "Java")
                        .choice("kotlin", "Kotlin")
                        .defaultChoice("java")
                        .build())
                .step(WizardStep.InputStep.of("jdk", "JDK version:")
                        .placeholder("25")
                        .defaultValue("25")
                        .build())
                .step(WizardStep.InputStep.of("main", "Main class (FQCN, blank for library):")
                        .placeholder("com.example.App")
                        .build())
                .step(WizardStep.RadioStep.horizontal("shadow", "Bundle as a shadow (fat) jar?")
                        .choice("yes", "Yes")
                        .choice("no", "No")
                        .defaultChoice("no")
                        .when(a -> a.has("main"))
                        .build())
                .step(WizardStep.RadioStep.horizontal("native", "Wire a GraalVM native-image build?")
                        .choice("yes", "Yes")
                        .choice("no", "No")
                        .defaultChoice("no")
                        .build())
                .step(WizardStep.MultiSelectStep.vertical("deps", "Select dependencies to include:")
                        .choice("lombok", "Lombok")
                        .choice("commons-lang", "Apache Commons Lang")
                        .choice("commons-io", "Apache Commons IO")
                        .choice("guava", "Google Guava")
                        .build())
                .step(WizardStep.RadioStep.horizontal("sample", "Add sample code?")
                        .choice("yes", "Yes")
                        .choice("no", "No")
                        .defaultChoice("yes")
                        .build())
                .build();
    }

    private InitInputs fromAnswers(Answers answers, Path target) {
        var resolvedName = answers.has("name") ? answers.get("name") : defaultName(target);
        var resolvedGroup = answers.has("group") ? answers.get("group") : "com.example";
        var resolvedJdk = answers.has("jdk") ? answers.get("jdk") : "25";
        var resolvedLang = "kotlin".equalsIgnoreCase(answers.get("lang"))
                ? InitInputs.Language.KOTLIN
                : InitInputs.Language.JAVA;
        var resolvedMain = answers.has("main") ? Optional.of(answers.get("main")) : Optional.<String>empty();
        var resolvedShadow = "yes".equals(answers.get("shadow"));
        var resolvedNative = "yes".equals(answers.get("native"));
        var resolvedSample = !"no".equals(answers.get("sample"));
        var resolvedDeps = answers.getList("deps");
        // Wizard mode resolves directory to --name if user typed one.
        var finalDir = (directory == null && !resolvedName.isBlank())
                ? Path.of(resolvedName)
                : target;
        return new InitInputs(
                resolvedGroup, resolvedName, resolvedJdk,
                resolvedMain, resolvedShadow, resolvedNative,
                resolvedLang, resolvedDeps, resolvedSample, finalDir);
    }

    private static void emitSuccessOnTerminal(InitInputs inputs, Terminal terminal) {
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

    private static void emitSuccessPlain(InitInputs inputs) {
        System.out.println("Created " + inputs.directory().resolve("build.jk"));
        System.out.println("Created " + inputs.directory().resolve("jk.lock"));
        System.out.println();
        System.out.println("Done. Next:");
        for (var line : nextSteps(inputs)) {
            System.out.println("  " + line);
        }
    }

    private static org.jline.utils.AttributedString headline(String text) {
        return new AttributedStringBuilder()
                .append(text, AttributedStyle.DEFAULT.bold().foreground(0x22, 0xc5, 0x5e))
                .toAttributedString();
    }

    private static List<String> nextSteps(InitInputs inputs) {
        var dirArg = inputs.directory().toString();
        return List.of(
                "cd " + dirArg,
                inputs.isRunnable() ? "jk run" : "jk compile",
                "jk test");
    }

    // Suppress unused warning for the import we keep for clarity.
    @SuppressWarnings("unused")
    private static final List<String> CURATED_IDS = Arrays.asList("lombok", "commons-lang", "commons-io", "guava");
}
