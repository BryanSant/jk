// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import java.nio.file.Path;

/** Thrown when a worker jar is absent from the CAS and no {@code -D} override resolves it. */
public final class WorkerJarNotFoundException extends RuntimeException {

    private final String artifactId;
    private final String sha;
    private final Path path;
    private final String jarProperty;

    WorkerJarNotFoundException(String artifactId, String sha, Path path, String jarProperty) {
        super(artifactId + ".jar is not in the CAS");
        this.artifactId = artifactId;
        this.sha = sha;
        this.path = path;
        this.jarProperty = jarProperty;
    }

    public String artifactId() {
        return artifactId;
    }

    public String sha() {
        return sha;
    }

    public Path path() {
        return path;
    }

    public String jarProperty() {
        return jarProperty;
    }
}
