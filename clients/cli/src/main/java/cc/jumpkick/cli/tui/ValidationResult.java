// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

public sealed interface ValidationResult {

    record Ok() implements ValidationResult {
        public static final Ok INSTANCE = new Ok();
    }

    record Error(String message) implements ValidationResult {}

    static ValidationResult ok() {
        return Ok.INSTANCE;
    }

    static ValidationResult error(String message) {
        return new Error(message);
    }
}
