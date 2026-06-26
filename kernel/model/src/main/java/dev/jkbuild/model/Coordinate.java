// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Objects;

/**
 * Maven coordinate: {@code groupId:artifactId:version[:classifier@type]}.
 * Type defaults to {@code jar}; classifier defaults to absent.
 */
public record Coordinate(String group, String artifact, String version, String classifier, String type) {

    public Coordinate {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(type, "type");
        if (group.isBlank()) throw new IllegalArgumentException("group must not be blank");
        if (artifact.isBlank()) throw new IllegalArgumentException("artifact must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("version must not be blank");
    }

    public static Coordinate of(String group, String artifact, String version) {
        return new Coordinate(group, artifact, version, null, "jar");
    }

    /**
     * Parse a coordinate spec of the form {@code group:artifact:version[:classifier][@type]}.
     */
    public static Coordinate parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        String type = "jar";
        int at = spec.indexOf('@');
        String body = spec;
        if (at >= 0) {
            type = spec.substring(at + 1);
            body = spec.substring(0, at);
        }
        String[] parts = body.split(":", -1);
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException(
                    "coordinate must be group:artifact:version[:classifier][@type], got: " + spec);
        }
        String classifier = parts.length == 4 ? parts[3] : null;
        return new Coordinate(parts[0], parts[1], parts[2], classifier, type);
    }

    /** Canonical {@code group:artifact:version} form (omits classifier and type). */
    public String toGav() {
        return group + ":" + artifact + ":" + version;
    }

    /** Module identifier without version: {@code group:artifact}. */
    public String module() {
        return group + ":" + artifact;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(toGav());
        if (classifier != null) sb.append(':').append(classifier);
        if (!"jar".equals(type)) sb.append('@').append(type);
        return sb.toString();
    }
}
