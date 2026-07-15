// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.run;

/**
 * Terminal state of a step. PENDING → RUNNING → one of the terminal values. SKIPPED is for steps
 * that opt out via {@link Step#shouldRun} (not present yet — reserved for future use).
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAIL,
    CANCELLED,
    SKIPPED;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }
}
