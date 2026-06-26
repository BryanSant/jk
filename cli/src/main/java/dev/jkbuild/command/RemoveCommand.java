// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildEditor;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk remove &lt;name&gt;} — remove a dependency from {@code jk.toml} by its short name (the
 * manifest key).
 *
 * <p>A Maven-coord shorthand ({@code group:artifact} or {@code group:artifact:version}) is also
 * accepted as a migration aid: the artifactId is extracted and used as the short name. The
 * recommended form is the bare short name.
 */
public final class RemoveCommand implements CliCommand {

    @Override
    public String name() {
        return "remove";
    }

    @Override
    public String description() {
        return "Remove a dependency, or workspace module, from jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Test scope", "--test"),
                Opt.flag("Runtime scope", "--runtime"),
                Opt.flag("Provided scope", "--provided"),
                Opt.flag("Annotation processor scope", "--processor"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "name",
                Arity.ONE,
                "Short name (manifest key) of the dependency to remove.\n"
                        + "A group:artifact[:version] coord works too (uses artifactId)."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean test = in.isSet("test");
        boolean runtime = in.isSet("runtime");
        boolean provided = in.isSet("provided");
        boolean processor = in.isSet("processor");
        String nameArg = in.positionals().get(0);

        Path dir = global.workingDir();
        Path file = dir.resolve("jk.toml");
        if (!Files.exists(file)) {
            System.err.println("jk remove: no jk.toml in current directory");
            return 2;
        }
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0) + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            System.err.println("jk remove: --test / --runtime / --provided / --processor are mutually exclusive");
            return 64;
        }
        Scope scope = test
                ? Scope.TEST
                : runtime ? Scope.RUNTIME : provided ? Scope.PROVIDED : processor ? Scope.PROCESSOR : Scope.MAIN;
        String name;
        try {
            name = shortNameOf(nameArg);
        } catch (IllegalArgumentException e) {
            System.err.println("jk remove: " + e.getMessage());
            return 64;
        }

        String original = Files.readString(file);
        String updated;
        try {
            updated = JkBuildEditor.removeDependency(original, scope, name);
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println("jk remove: " + e.getMessage());
            return 1;
        }
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        System.out.println(Theme.colorize("✗", Theme.active().darkGray())
                + " Removed "
                + Theme.colorize(name, Theme.active().activeStep())
                + " from "
                + Theme.colorize("dependencies", Theme.active().cyan())
                + "."
                + Theme.colorize(scope.canonical(), Theme.active().cyan()));
        return 0;
    }

    /**
     * Extract the short name from the user's argument. A bare token is returned unchanged; a {@code
     * group:artifact[:version]} coord collapses to its artifactId.
     */
    private static String shortNameOf(String arg) {
        if (arg == null || arg.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        int first = arg.indexOf(':');
        if (first < 0) return arg;
        int second = arg.indexOf(':', first + 1);
        String artifact = second < 0 ? arg.substring(first + 1) : arg.substring(first + 1, second);
        if (artifact.isBlank()) {
            throw new IllegalArgumentException("could not extract artifactId from: " + arg);
        }
        return artifact;
    }
}
