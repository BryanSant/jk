// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import java.nio.file.Path;

/** The result of one module's build (see {@link WorkspaceBuildListener#onModuleFinish}). */
public record ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis) {}
