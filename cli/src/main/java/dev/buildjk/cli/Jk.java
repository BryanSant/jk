// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * jk CLI entrypoint. Verbs are registered as subcommands; each one is a
 * {@link java.util.concurrent.Callable} returning a {@code sysexits.h}-style
 * exit code (PRD §6).
 */
@Command(
        name = "jk",
        mixinStandardHelpOptions = true,
        version = "jk " + Jk.VERSION,
        description = "Single-binary build tool for Java and Kotlin.",
        subcommands = {
                InitCommand.class,
                AddCommand.class,
                RemoveCommand.class,
                LockCommand.class,
                TreeCommand.class,
                WhyCommand.class,
                SyncCommand.class,
                FetchCommand.class,
        })
public final class Jk implements Runnable {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        System.exit(new CommandLine(new Jk()).execute(args));
    }

    @Override
    public void run() {
        // No subcommand: print help and exit non-zero per the CLI usage convention.
        new CommandLine(this).usage(System.out);
    }
}
