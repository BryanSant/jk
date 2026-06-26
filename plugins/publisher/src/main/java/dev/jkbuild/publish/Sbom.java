// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates CycloneDX 1.6 and SPDX 2.3 SBOM JSON for a published artifact
 * (PRD §21.2, §23.9).
 *
 * <p>The component graph comes from a project's {@link JkBuild} for the
 * root + an optional {@link Lockfile} for the resolved transitives (with
 * versions and SHA-256 hashes). When no lockfile is supplied, just the
 * root is emitted — a degraded but valid SBOM that still satisfies the
 * "ship something" Central guidance.
 *
 * <p>Hand-rolled JSON for both formats. The schemas are stable, the field
 * set we need is small, and avoiding cyclonedx-core-java + java-spdx-library
 * here keeps the binary lean. The official libraries can step in when we
 * need richer features (deep dependency edges, license normalisation,
 * tool model tracking).
 */
public final class Sbom {

    private Sbom() {}

    /** Render a CycloneDX 1.6 JSON SBOM. */
    public static byte[] cyclonedx(JkBuild project, Lockfile lock) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        kv(sb, "$schema", "http://cyclonedx.org/schema/bom-1.6.schema.json");
        comma(sb);
        kv(sb, "bomFormat", "CycloneDX");
        comma(sb);
        kv(sb, "specVersion", "1.6");
        comma(sb);
        kv(sb, "serialNumber", "urn:uuid:" + UUID.randomUUID());
        comma(sb);
        sb.append("\"version\":1");
        comma(sb);

        sb.append("\"metadata\":{");
        kv(sb, "timestamp", Instant.now().toString());
        comma(sb);
        sb.append("\"tools\":{\"components\":[");
        sb.append('{');
        kv(sb, "type", "application");
        comma(sb);
        kv(sb, "name", "jk");
        sb.append('}');
        sb.append("]}");
        comma(sb);
        sb.append("\"component\":");
        appendCdxComponent(
                sb,
                project.project().group(),
                project.project().name(),
                project.project().version(),
                null);
        sb.append('}');
        comma(sb);

        sb.append("\"components\":[");
        if (lock != null) {
            boolean first = true;
            for (Lockfile.Artifact pkg : lock.artifacts()) {
                String[] ga = splitModule(pkg.name());
                if (!first) sb.append(',');
                first = false;
                appendCdxComponent(sb, ga[0], ga[1], pkg.version(), stripSha256Prefix(pkg.checksum()));
            }
        }
        sb.append(']');

        sb.append('}');
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Render an SPDX 2.3 JSON SBOM. */
    public static byte[] spdx(JkBuild project, Lockfile lock) {
        String docNamespace = "https://buildjk.dev/sbom/" + project.project().group() + "/"
                + project.project().name() + "/" + project.project().version()
                + "/" + UUID.randomUUID();
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        kv(sb, "spdxVersion", "SPDX-2.3");
        comma(sb);
        kv(sb, "dataLicense", "CC0-1.0");
        comma(sb);
        kv(sb, "SPDXID", "SPDXRef-DOCUMENT");
        comma(sb);
        kv(sb, "name", project.project().name() + "-" + project.project().version());
        comma(sb);
        kv(sb, "documentNamespace", docNamespace);
        comma(sb);

        sb.append("\"creationInfo\":{");
        kv(sb, "created", Instant.now().toString());
        comma(sb);
        sb.append("\"creators\":[\"Tool: jk\"]");
        sb.append('}');
        comma(sb);

        sb.append("\"documentDescribes\":[\"SPDXRef-Package-Root\"]");
        comma(sb);

        sb.append("\"packages\":[");
        appendSpdxPackage(
                sb,
                "SPDXRef-Package-Root",
                project.project().group(),
                project.project().name(),
                project.project().version(),
                null);
        if (lock != null) {
            int i = 0;
            for (Lockfile.Artifact pkg : lock.artifacts()) {
                String[] ga = splitModule(pkg.name());
                sb.append(',');
                appendSpdxPackage(
                        sb,
                        "SPDXRef-Package-" + sanitizeId(pkg.name()) + "-" + i++,
                        ga[0],
                        ga[1],
                        pkg.version(),
                        stripSha256Prefix(pkg.checksum()));
            }
        }
        sb.append(']');

        sb.append('}');
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- helpers ----------------------------------------------------------

    private static void appendCdxComponent(
            StringBuilder sb, String group, String artifact, String version, String sha256Hex) {
        String purl = "pkg:maven/" + group + "/" + artifact + "@" + version;
        sb.append('{');
        kv(sb, "type", "library");
        comma(sb);
        kv(sb, "bom-ref", purl);
        comma(sb);
        kv(sb, "group", group);
        comma(sb);
        kv(sb, "name", artifact);
        comma(sb);
        kv(sb, "version", version);
        comma(sb);
        kv(sb, "purl", purl);
        if (sha256Hex != null) {
            comma(sb);
            sb.append("\"hashes\":[{");
            kv(sb, "alg", "SHA-256");
            comma(sb);
            kv(sb, "content", sha256Hex);
            sb.append("}]");
        }
        sb.append('}');
    }

    private static void appendSpdxPackage(
            StringBuilder sb, String spdxId, String group, String artifact, String version, String sha256Hex) {
        sb.append('{');
        kv(sb, "SPDXID", spdxId);
        comma(sb);
        kv(sb, "name", group + ":" + artifact);
        comma(sb);
        kv(sb, "versionInfo", version);
        comma(sb);
        kv(sb, "downloadLocation", "NOASSERTION");
        comma(sb);
        sb.append("\"filesAnalyzed\":false");
        comma(sb);
        sb.append("\"externalRefs\":[{");
        kv(sb, "referenceCategory", "PACKAGE-MANAGER");
        comma(sb);
        kv(sb, "referenceType", "purl");
        comma(sb);
        kv(sb, "referenceLocator", "pkg:maven/" + group + "/" + artifact + "@" + version);
        sb.append("}]");
        if (sha256Hex != null) {
            comma(sb);
            sb.append("\"checksums\":[{");
            kv(sb, "algorithm", "SHA256");
            comma(sb);
            kv(sb, "checksumValue", sha256Hex);
            sb.append("}]");
        }
        sb.append('}');
    }

    private static String[] splitModule(String module) {
        int colon = module.indexOf(':');
        return colon > 0
                ? new String[] {module.substring(0, colon), module.substring(colon + 1)}
                : new String[] {"unknown", module};
    }

    private static String stripSha256Prefix(String checksum) {
        if (checksum == null) return null;
        if (checksum.startsWith("sha256:")) return checksum.substring("sha256:".length());
        return checksum;
    }

    private static String sanitizeId(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '.') sb.append(c);
            else sb.append('-');
        }
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String key, String value) {
        appendJsonString(sb, key);
        sb.append(':');
        appendJsonString(sb, value);
    }

    private static void comma(StringBuilder sb) {
        sb.append(',');
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
