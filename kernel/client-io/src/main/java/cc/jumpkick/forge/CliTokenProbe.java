// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Piggybacks on a native forge CLI to reuse an existing login ({@code gh auth token}, {@code glab
 * auth token}). Best-effort by contract: every failure mode — binary not on {@code PATH}, non-zero
 * exit, empty output, hang — yields {@link Optional#empty()} so the caller falls through to jk's
 * own flow. Never throws.
 */
@FunctionalInterface
public interface CliTokenProbe {

    /** Run {@code argv} and return its trimmed stdout if it looks like a token. */
    Optional<String> token(List<String> argv);

    /** The real implementation: shell out via {@link ProcessBuilder}. */
    CliTokenProbe REAL = argv -> {
        if (argv == null || argv.isEmpty()) return Optional.empty();
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return Optional.empty();
            }
            if (p.exitValue() == 0 && !out.isBlank()) return Optional.of(out);
            return Optional.empty();
        } catch (IOException e) { // command-not-found lands here
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    };
}
