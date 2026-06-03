// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Option;

/**
 * Flags shared by the build-family verbs — {@code build}, {@code run},
 * {@code install}, {@code native}, {@code image} — all of which drive the same
 * {@link dev.jkbuild.runtime.BuildPipeline}. Installed as a picocli mixin so the
 * flag is declared once and behaves identically everywhere.
 */
public final class BuildOptions {

    @Option(names = "--skip-tests",
            description = "Skip compiling and running tests (the test phases are left out of the build).")
    public boolean skipTests;
}
