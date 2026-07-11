// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Minimal CycloneDX 1.5 SBOM generator, fed straight from the lockfile — every component carries
 * the exact coordinates and SHA-256 jk already pinned, so the document is deterministic and costs
 * nothing to produce. Boot jars embed it (spring-boot plan §3.8); any packager can reuse it — the
 * lockfile is the single source of truth for "what is actually inside this artifact".
 *
 * <p>Serial numbers are deliberately omitted (they are random UUIDs by spec and would churn a
 * reproducible jar); consumers that need one can derive it from the artifact digest.
 */
public final class CycloneDxSbom {

    /** One resolved dependency: exact coordinates + the locked jar SHA-256 (nullable). */
    public record Component(String group, String artifact, String version, String sha256) {

        public Component {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
        }

        String purl() {
            return "pkg:maven/" + group + "/" + artifact + "@" + version;
        }
    }

    private CycloneDxSbom() {}

    /** The CycloneDX JSON document for an application and its resolved runtime components. */
    public static byte[] write(String group, String artifact, String version, List<Component> components) {
        StringBuilder sb = new StringBuilder(1024 + components.size() * 256);
        sb.append("{\n");
        sb.append("  \"bomFormat\": \"CycloneDX\",\n");
        sb.append("  \"specVersion\": \"1.5\",\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"metadata\": {\n");
        sb.append("    \"component\": {\n");
        sb.append("      \"type\": \"application\",\n");
        sb.append("      \"bom-ref\": ").append(quote("pkg:maven/" + group + "/" + artifact + "@" + version));
        sb.append(",\n");
        sb.append("      \"group\": ").append(quote(group)).append(",\n");
        sb.append("      \"name\": ").append(quote(artifact)).append(",\n");
        sb.append("      \"version\": ").append(quote(version)).append("\n");
        sb.append("    }\n");
        sb.append("  },\n");
        sb.append("  \"components\": [");
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\n");
            sb.append("      \"type\": \"library\",\n");
            sb.append("      \"bom-ref\": ").append(quote(c.purl())).append(",\n");
            sb.append("      \"group\": ").append(quote(c.group())).append(",\n");
            sb.append("      \"name\": ").append(quote(c.artifact())).append(",\n");
            sb.append("      \"version\": ").append(quote(c.version())).append(",\n");
            sb.append("      \"purl\": ").append(quote(c.purl()));
            if (c.sha256() != null && !c.sha256().isBlank()) {
                sb.append(",\n      \"hashes\": [{ \"alg\": \"SHA-256\", \"content\": ")
                        .append(quote(c.sha256()))
                        .append(" }]");
            }
            sb.append("\n    }");
        }
        sb.append(components.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** JSON string literal with the escapes coordinates can actually contain. */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }
}
