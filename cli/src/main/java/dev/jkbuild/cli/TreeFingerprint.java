// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * SHA-256 over a sorted file-list of a tool install. Used by the
 * {@code --verify-linked} opt-in to fingerprint a linked external install
 * so a future invocation can detect that the upstream changed without
 * jk's knowledge.
 *
 * <p>Walk order is deterministic (lexicographic on the relative path);
 * each entry contributes its relative path plus the SHA-256 of its
 * contents. The aggregated digest is the SHA-256 of the concatenated
 * "{@code <path>\0<contents-sha256>\n}" lines.
 */
final class TreeFingerprint {

    private TreeFingerprint() {}

    static String compute(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort((a, b) -> root.relativize(a).toString()
                .compareTo(root.relativize(b).toString()));

        StringBuilder sb = new StringBuilder(files.size() * 96);
        for (Path file : files) {
            String relative = root.relativize(file).toString();
            String contentHash = Hashing.sha256Hex(Files.readAllBytes(file));
            sb.append(relative).append('\0').append(contentHash).append('\n');
        }
        return Hashing.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
