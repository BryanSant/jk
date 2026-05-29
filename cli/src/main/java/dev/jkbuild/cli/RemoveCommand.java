// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.JkBuildEditor;
import dev.jkbuild.model.Scope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk remove &lt;name&gt;} — remove a dependency from {@code jk.toml}
 * by its short name (the manifest key).
 *
 * <p>A Maven-coord shorthand ({@code group:artifact} or
 * {@code group:artifact:version}) is also accepted as a migration aid:
 * the artifactId is extracted and used as the short name. The
 * recommended form is the bare short name.
 */
@Command(name = "remove", description = "Remove a dependency from jk.toml")
public final class RemoveCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "[name]",
            description = "Short name of the dependency to remove (the manifest key). "
                    + "A group:artifact[:version] coord is also accepted; the artifactId is used as the name.")
    String nameArg;

    // Inlined scope flags (see AddCommand for the picocli-codegen rationale).
    @Option(names = "--test",      description = "Test scope")
    boolean test;
    @Option(names = "--runtime",   description = "Runtime scope")
    boolean runtime;
    @Option(names = "--provided",  description = "Provided scope")
    boolean provided;
    @Option(names = "--processor", description = "Annotation processor scope")
    boolean processor;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path file = dir.resolve("jk.toml");
        if (!Files.exists(file)) {
            System.err.println("jk remove: no jk.toml in current directory");
            return 2;
        }
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0)
                + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            System.err.println("jk remove: --test / --runtime / --provided / --processor are mutually exclusive");
            return 64;
        }
        Scope scope = test ? Scope.TEST
                : runtime ? Scope.RUNTIME
                : provided ? Scope.PROVIDED
                : processor ? Scope.PROCESSOR
                : Scope.MAIN;
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
        System.out.println("Removed " + name + " from dependencies." + scope.canonical());
        return 0;
    }

    /**
     * Extract the short name from the user's argument. A bare token is
     * returned unchanged; a {@code group:artifact[:version]} coord
     * collapses to its artifactId.
     */
    private static String shortNameOf(String arg) {
        if (arg == null || arg.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        int first = arg.indexOf(':');
        if (first < 0) return arg;
        int second = arg.indexOf(':', first + 1);
        String artifact = second < 0
                ? arg.substring(first + 1)
                : arg.substring(first + 1, second);
        if (artifact.isBlank()) {
            throw new IllegalArgumentException(
                    "could not extract artifactId from: " + arg);
        }
        return artifact;
    }
}
