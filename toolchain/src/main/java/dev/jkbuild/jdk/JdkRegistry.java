// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

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
 * Catalog of JDKs already on disk. The default root is the directory
 * IntelliJ uses ({@code ~/.jdks} on Linux/Windows,
 * {@code ~/Library/Java/JavaVirtualMachines} on macOS); tests override
 * via the constructor. Each immediate subdirectory that contains a
 * {@code bin/java} (or {@code Contents/Home/bin/java} on macOS) is an
 * installed JDK whose identifier is the directory name (e.g.
 * {@code temurin-21.0.5}).
 */
public final class JdkRegistry {

    private final Path jdksRoot;

    public JdkRegistry() {
        this(IntellijJdkDir.root());
    }

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
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p -> {
                        Path home = IntellijJdkDir.javaHome(p);
                        if (Files.isDirectory(home.resolve("bin"))) {
                            result.add(new InstalledJdk(p.getFileName().toString(), home));
                        }
                    });
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
        if (find(identifier).isEmpty()) return false;
        Path installDir = jdksRoot.resolve(identifier);
        deleteRecursively(installDir);
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
