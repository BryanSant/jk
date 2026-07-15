// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli;

/**
 * Flags shared by the build-family commands — {@code build}, {@code run}, {@code install}, {@code
 * native}, {@code image} — all of which drive the same {@link build.jumpkick.runtime.BuildPipelines}.
 */
public final class BuildOptions {

    public boolean skipTests;
}
