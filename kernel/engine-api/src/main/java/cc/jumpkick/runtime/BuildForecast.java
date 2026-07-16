// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A build's pre-flight forecast (the {@code forecast-request} reply): which module dirs will do
 * real work, whether the merged workspace lock is stale (disables the fully-cached shortcut and
 * the dirty hint — the engine re-locks and re-forecasts), whether the workspace declares no
 * modules, and any graph-resolution errors (non-empty ⇒ nothing else is meaningful).
 */
public record BuildForecast(Set<Path> dirtyDirs, boolean lockStale, boolean empty, List<String> errors) {
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** True when nothing needs to rebuild and the lock is trustworthy — the "all up to date" shortcut. */
    public boolean fullyCached() {
        return dirtyDirs.isEmpty() && !lockStale;
    }
}
