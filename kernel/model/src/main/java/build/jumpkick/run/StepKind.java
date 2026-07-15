// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.run;

/**
 * Workload character. Drives executor selection in the scheduler:
 *
 * <ul>
 *   <li>{@link #SYNC} — runs on the main thread, between async batches. Use for cheap orchestration
 *       (read jk.toml, validate inputs, prepare paths). Never blocks anything but the foreground.
 *   <li>{@link #IO} — dispatched to {@code JkThreads.io()}, a virtual- thread pool sized for
 *       outbound network and disk waits. Use for fetches, file walks, subprocess starts.
 *   <li>{@link #CPU} — dispatched to {@code JkThreads.cpu()}, a worker pool capped at the number of
 *       cores. Use for hashing, compile orchestration, archive extraction.
 * </ul>
 */
public enum StepKind {
    SYNC,
    IO,
    CPU
}
