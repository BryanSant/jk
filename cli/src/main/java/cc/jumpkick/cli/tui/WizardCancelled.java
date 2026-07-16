// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

/** Sentinel thrown by the key loop on Ctrl+C / SIGINT; caught inside {@link Wizard#run}. */
public final class WizardCancelled extends RuntimeException {
    public WizardCancelled() {
        super();
    }
}
