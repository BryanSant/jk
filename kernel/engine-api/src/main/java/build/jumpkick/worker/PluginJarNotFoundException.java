// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.plugin;

import java.nio.file.Path;
import java.util.List;

/** Thrown when a worker jar is absent from the local and central repos and no {@code -D} override resolves it. */
public final class PluginJarNotFoundException extends RuntimeException {

    private final String artifactId;
    private final String coordinate;
    private final List<Path> pathsChecked;
    private final String jarProperty;

    PluginJarNotFoundException(String artifactId, String coordinate, List<Path> pathsChecked, String jarProperty) {
        super(buildMessage(artifactId, coordinate, pathsChecked, jarProperty));
        this.artifactId = artifactId;
        this.coordinate = coordinate;
        this.pathsChecked = List.copyOf(pathsChecked);
        this.jarProperty = jarProperty;
    }

    private static String buildMessage(
            String artifactId, String coordinate, List<Path> pathsChecked, String jarProperty) {
        var sb = new StringBuilder();
        sb.append(artifactId)
                .append(".jar not found for coordinate ")
                .append(coordinate)
                .append('\n');
        sb.append("Paths checked:\n");
        for (Path p : pathsChecked) {
            sb.append("  ").append(p).append('\n');
        }
        sb.append("Run `./gradlew :").append(artifactId.replace("jk-", "")).append(":installLocal`");
        sb.append(" or set -D").append(jarProperty).append(" to override.");
        return sb.toString();
    }

    public String artifactId() {
        return artifactId;
    }

    public String coordinate() {
        return coordinate;
    }

    public List<Path> pathsChecked() {
        return pathsChecked;
    }

    public String jarProperty() {
        return jarProperty;
    }
}
