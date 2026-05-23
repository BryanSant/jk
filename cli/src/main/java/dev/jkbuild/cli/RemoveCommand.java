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
 * {@code jk remove &lt;module&gt;} — remove a dependency from {@code jk.toml}.
 *
 * <p>Accepts either {@code group:artifact} or a full
 * {@code group:artifact:version} (the version, if present, is ignored —
 * a module only appears once per scope).
 */
@Command(name = "remove", description = "Remove a dependency from jk.toml")
public final class RemoveCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<module>",
            description = "group:artifact (or group:artifact:version; the version is ignored)")
    String moduleArg;

    // Inlined scope flags (see AddCommand for the picocli-codegen rationale).
    @Option(names = "--test",      description = "Test scope.")
    boolean test;
    @Option(names = "--runtime",   description = "Runtime scope.")
    boolean runtime;
    @Option(names = "--provided",  description = "Provided scope.")
    boolean provided;
    @Option(names = "--processor", description = "Annotation processor scope.")
    boolean processor;

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path file = dir.resolve("jk.toml");
        if (!Files.exists(file)) {
            System.err.println("jk remove: no jk.toml in current directory");
            return 2;
        }
        String module = moduleOnly(moduleArg);
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
        String original = Files.readString(file);
        String updated;
        try {
            updated = JkBuildEditor.removeDependency(original, scope, module);
        } catch (IllegalStateException e) {
            System.err.println("jk remove: " + e.getMessage());
            return 1;
        }
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        System.out.println("Removed " + module + " from dependencies." + scope.canonical());
        return 0;
    }

    private static String moduleOnly(String coord) {
        // Allow either group:artifact or group:artifact:version; keep the first two segments.
        int first = coord.indexOf(':');
        if (first < 0) {
            throw new IllegalArgumentException(
                    "expected group:artifact[:version], got: " + coord);
        }
        int second = coord.indexOf(':', first + 1);
        return second < 0 ? coord : coord.substring(0, second);
    }
}
