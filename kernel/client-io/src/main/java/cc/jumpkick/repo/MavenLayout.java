// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.model.Coordinate;

/**
 * Maps coordinates to Maven repository paths.
 *
 * <p>Layout (per Maven Central convention): {@code
 * <group-with-slashes>/<artifact>/<version>/<artifact>-<version>[-<classifier>].<ext>}
 */
public final class MavenLayout {

    private MavenLayout() {}

    /** Relative path to the primary artifact for this coordinate. */
    public static String artifactPath(Coordinate coord) {
        return basePath(coord) + filename(coord, coord.type());
    }

    /** Relative path to the POM. */
    public static String pomPath(Coordinate coord) {
        return basePath(coord) + filename(coord, "pom");
    }

    /** Relative path to the {@code maven-metadata.xml} for the artifact. */
    public static String metadataPath(Coordinate coord) {
        return coord.group().replace('.', '/') + "/" + coord.artifact() + "/maven-metadata.xml";
    }

    private static String basePath(Coordinate coord) {
        return coord.group().replace('.', '/') + "/" + coord.artifact() + "/" + coord.version() + "/";
    }

    private static String filename(Coordinate coord, String extension) {
        String classifier = coord.classifier() != null ? "-" + coord.classifier() : "";
        return coord.artifact() + "-" + coord.version() + classifier + "." + extension;
    }
}
