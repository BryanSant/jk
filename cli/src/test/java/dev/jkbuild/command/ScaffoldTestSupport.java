// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Test helper for the post-{@code jk new} world. {@code jk new}/{@code init} no
 * longer write a {@code jk.lock} (it's generated on the first build/run), so
 * commands that require a lock but don't auto-lock — {@code check}, {@code why},
 * {@code tree}, {@code test}, {@code explain} — need one established first.
 * {@link #writeEmptyLock} drops in the empty lock {@code jk new} used to create,
 * standing in for "the project has been locked" without a network resolve.
 */
final class ScaffoldTestSupport {

    private ScaffoldTestSupport() {}

    static void writeEmptyLock(Path projectDir) throws IOException {
        LockfileWriter.write(Lockfile.empty(Jk.VERSION), projectDir.resolve("jk.lock"));
    }
}
