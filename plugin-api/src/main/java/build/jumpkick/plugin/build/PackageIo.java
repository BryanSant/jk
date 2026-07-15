// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What a {@link PackagerSpec.Body} gets: the resolved declared inputs (with coordinate-named
 * runtime entries), the destination artifact path, and progress labelling. The engine already
 * decided whether this run is needed (declared-input action key) and owns where the artifact
 * lives.
 */
public interface PackageIo {

    /**
     * One runtime classpath entry with its real coordinate-derived file name. {@code container}
     * is the exploded archive dir when the artifact's packaging is a container (an Android AAR:
     * {@code classes.jar}, {@code res/}, {@code AndroidManifest.xml}, {@code R.txt} inside) —
     * null for a plain jar. {@code jar} is the host-classpath entry (a container's
     * {@code classes.jar}); null when the container carries no classes.
     */
    record RuntimeEntry(String fileName, Path jar, boolean snapshot, Path container) {

        /** Back-compat: a plain-jar entry. */
        public RuntimeEntry(String fileName, Path jar, boolean snapshot) {
            this(fileName, jar, snapshot, null);
        }
    }

    Path classesDir();

    /** The module's project dir — for packager inputs declared as {@link In#projectFiles}. */
    Path moduleDir();

    /** Lock-ordered production RUNTIME entries — {@link In#runtimeEntries()}. */
    List<RuntimeEntry> runtimeEntries();

    build.jumpkick.plugin.PluginConfig config();

    ProjectFacts project();

    /** A chained step's output root — {@link In#stepOutput}; empty when the step did not run. */
    Optional<Path> stepOutput(String step);

    /**
     * An engine-supplied extra: a manifest-contributed {@code packager-dependency} artifact (by
     * its artifact id), or a named file the engine prepares (e.g. {@code sbom}).
     */
    Optional<Path> extra(String name);

    /** Where the produced artifact must land (the module's main-artifact path). */
    Path artifactPath();

    /**
     * A resolved secret value (signing credentials — {@code env:}-indirected config the engine
     * resolved and diverted off the flat config so it never reaches describe payloads, tokens, or
     * logs). Package specs only. NEVER echo a secret into labels or errors.
     */
    default Optional<String> secret(String key) {
        return Optional.empty();
    }

    /** The JDK this build runs on — for {@link #tool} forks (signing, keytool, …). */
    Path javaHome();

    /** A {@code bin/<name>} fork off {@link #javaHome()}. */
    default StepExec.ToolRun tool(String bin) {
        return new StepExec.ToolRun(javaHome(), bin);
    }

    /** A fork of an arbitrary executable (a fetched native tool). */
    default StepExec.ToolRun tool(Path executable) {
        return new StepExec.ToolRun(executable);
    }

    /** Convenience for the common case. */
    default StepExec.ToolRun java() {
        return tool("java");
    }

    /** Progress label surfaced in the build UI. */
    void label(String text);
}
