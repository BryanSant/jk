// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.git;

import build.jumpkick.forge.ForgeGitCredentials;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Shared providers for the git-backend parity tests. Every git operation is exercised against both
 * {@link JGitExtension} (always) and {@link GitCliExtension} (only when a usable {@code git} command is
 * present) so the two implementations are held to identical behaviour.
 */
final class GitBackendsTestSupport {
    private GitBackendsTestSupport() {}

    @FunctionalInterface
    interface BackendFactory {
        GitBackend create(Path gitRoot);
    }

    /** JUnit {@code @MethodSource}: (name, factory) for each available backend. */
    static Stream<Arguments> backends() {
        Stream.Builder<Arguments> b = Stream.builder();
        b.add(Arguments.of("jgit", (BackendFactory) gitRoot -> new JGitExtension(gitRoot, new ForgeGitCredentials())));
        GitCliExtension.detect()
                .ifPresent(git -> b.add(Arguments.of(
                        "cli", (BackendFactory) gitRoot -> new GitCliExtension(gitRoot, new ForgeGitCredentials(), git))));
        return b.build();
    }
}
