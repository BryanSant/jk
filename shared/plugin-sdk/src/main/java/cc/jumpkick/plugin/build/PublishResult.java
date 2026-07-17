// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * The outcome of a {@link PublishExtension#publish} run — the number of artifact {@code files}
 * handled and whether it was a {@code dryRun} (assembled but not uploaded). A failure is signalled by
 * throwing, not by this record.
 */
public record PublishResult(int files, boolean dryRun) {

    /** A completed upload of {@code files} artifacts. */
    public static PublishResult uploaded(int files) {
        return new PublishResult(files, false);
    }

    /** A dry run that assembled {@code files} artifacts without uploading. */
    public static PublishResult dryRun(int files) {
        return new PublishResult(files, true);
    }
}
