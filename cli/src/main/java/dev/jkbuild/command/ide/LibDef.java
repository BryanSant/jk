// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command.ide;

import java.nio.file.Path;

/**
 * A resolved external dependency, with real {@code .jar} paths for the IDE to index.
 *
 * @param name coordinate string {@code group:artifact:version}
 * @param fileName sanitized filename stem (no extension), for IDEs that write one file per library
 * @param jarPath repo path with a proper {@code .jar} extension (e.g. {@code ~/.jk/cache/repo/.../lib-1.0.jar})
 * @param sourcesPath repo path for the {@code -sources.jar} (may be {@code null})
 */
public record LibDef(String name, String fileName, Path jarPath, Path sourcesPath) {}
