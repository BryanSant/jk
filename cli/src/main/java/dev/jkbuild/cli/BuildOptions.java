// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

/**
 * Flags shared by the build-family verbs — {@code build}, {@code run}, {@code install}, {@code
 * native}, {@code image} — all of which drive the same {@link dev.jkbuild.runtime.BuildPipeline}.
 */
public final class BuildOptions {

    public boolean skipTests;
}
