// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command.ide;

import java.nio.file.Path;

/**
 * The per-module facts an {@link IdeGenerator} consumes — the thin-client replacement for handing
 * generators a parsed {@code JkBuild} (docs/thin-client-plan.md Milestone B: the model math runs
 * engine-side and ships as these summaries). Layout paths are engine-computed absolutes.
 *
 * @param name the IDE module/project name ({@code project.name})
 * @param javaRelease the declared {@code project.java} bytecode level, or {@code 0} when unset
 * @param mainClass the {@code [project] main} class, or {@code null} for a non-application module
 * @param classesDir jk's main compile output ({@code target/classes})
 * @param testClassesDir jk's test compile output
 * @param jdtClassesDir JDT-LS main output ({@code target/jdt/classes/main}) — isolated from jk's
 * @param jdtTestClassesDir JDT-LS test output
 * @param generatedSourcesDir annotation-processor main source output
 * @param generatedTestSourcesDir annotation-processor test source output
 */
public record IdeModule(
        String name,
        int javaRelease,
        String mainClass,
        Path classesDir,
        Path testClassesDir,
        Path jdtClassesDir,
        Path jdtTestClassesDir,
        Path generatedSourcesDir,
        Path generatedTestSourcesDir) {}
