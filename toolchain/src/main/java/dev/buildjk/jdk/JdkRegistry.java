// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Catalog of JDKs installed under {@code ~/.jk/jdks/}. Each immediate
 * subdirectory of {@link #jdksRoot()} is an installed JDK; the dir name
 * is its identifier (e.g. {@code 21.0.5-tem-aarch64-linux}).
 */
public final class JdkRegistry {

    private final Path jdksRoot;

    public JdkRegistry(Path jdksRoot) {
        this.jdksRoot = Objects.requireNonNull(jdksRoot, "jdksRoot");
    }

    public Path jdksRoot() {
        return jdksRoot;
    }

    public List<InstalledJdk> list() throws IOException {
        if (!Files.exists(jdksRoot)) return List.of();
        List<InstalledJdk> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(jdksRoot)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p -> result.add(
                            new InstalledJdk(p.getFileName().toString(), p)));
        }
        return result;
    }

    public Optional<InstalledJdk> find(String identifier) throws IOException {
        return list().stream()
                .filter(jdk -> jdk.identifier().equals(identifier))
                .findFirst();
    }

    public Optional<InstalledJdk> findByPrefix(String prefix) throws IOException {
        return list().stream()
                .filter(jdk -> jdk.identifier().startsWith(prefix))
                .findFirst();
    }

    public boolean remove(String identifier) throws IOException {
        Optional<InstalledJdk> jdk = find(identifier);
        if (jdk.isEmpty()) return false;
        deleteRecursively(jdk.get().home());
        return true;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
