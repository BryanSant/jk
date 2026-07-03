// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command.ide;

import dev.jkbuild.jdk.IntellijSdkRegistrar;
import dev.jkbuild.model.JkBuild;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The IDE-agnostic, resolved view of a workspace, computed once by {@link IdeSupport#build} and
 * shared by every {@link IdeGenerator}. Everything here is IDE-neutral; each generator turns it into
 * that IDE's on-disk files.
 *
 * @param wsRoot canonicalized workspace root
 * @param rootBuild the root {@code jk.toml} model
 * @param modules workspace modules by directory (empty for a single-project build)
 * @param allModules unified set: {@code modules}, or {@code {wsRoot: rootBuild}} for a single project
 * @param allLibs every external dependency, by coordinate {@code group:artifact:version}
 * @param siblingRefs per-module cross-module dependency edges
 * @param libEntries per-module external-library references (raw jk scopes)
 * @param processorJars per-module annotation-processor JARs
 * @param sdkRefs per-module resolved JDK handle
 * @param defaultSdk the project-default JDK handle
 * @param sdkEntries stable SDK entries to register into IntelliJ's {@code jdk.table.xml}
 * @param cacheDir {@code --cache-dir} override, or {@code null}
 * @param jdksDir {@code --jdks-dir} override, or {@code null}
 * @param ideConfigDir {@code --ide-config-dir} override, or {@code null}
 */
public record IdeModel(
        Path wsRoot,
        JkBuild rootBuild,
        Map<Path, JkBuild> modules,
        Map<Path, JkBuild> allModules,
        Map<String, LibDef> allLibs,
        Map<Path, List<ModuleRef>> siblingRefs,
        Map<Path, List<LibEntry>> libEntries,
        Map<Path, List<Path>> processorJars,
        Map<Path, SdkRef> sdkRefs,
        SdkRef defaultSdk,
        List<IntellijSdkRegistrar.SdkEntry> sdkEntries,
        Path cacheDir,
        Path jdksDir,
        Path ideConfigDir) {}
