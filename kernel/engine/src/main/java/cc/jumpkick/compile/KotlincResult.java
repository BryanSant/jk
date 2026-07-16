// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Outcome of a Kotlin compilation. {@code output} is whatever kotlinc printed. */
public record KotlincResult(boolean success, String output) {

    public KotlincResult {
        Objects.requireNonNull(output, "output");
    }

    public List<String> errorLines() {
        List<String> errors = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.startsWith("error:")) errors.add(line);
        }
        return errors;
    }
}
