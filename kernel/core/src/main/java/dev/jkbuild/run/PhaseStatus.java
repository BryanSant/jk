// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

/**
 * Terminal state of a phase. PENDING → RUNNING → one of the terminal
 * values. SKIPPED is for phases that opt out via {@link Phase#shouldRun}
 * (not present yet — reserved for future use).
 */
public enum PhaseStatus {
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
