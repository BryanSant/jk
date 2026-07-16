// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.List;

/**
 * The whole workspace build result.
 *
 * @param errors graph-resolution errors (composite deps); non-empty ⇒ nothing built
 */
public record WorkspaceResult(boolean success, int exitCode, List<ModuleOutcome> modules, List<String> errors) {}
