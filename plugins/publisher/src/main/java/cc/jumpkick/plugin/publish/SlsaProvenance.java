// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.plugin.protocol.Jsonl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hand-rolled SLSA v1 in-toto Statement JSON (PRD §23.4, impl-plan §4).
 *
 * <p>Schema: {@code https://in-toto.io/Statement/v1} carrying a {@code
 * https://slsa.dev/provenance/v1} predicate. Output is unsigned — Sigstore-bundling the statement
 * (DSSE envelope inside a Sigstore Bundle) is layered on top by passing the same statement bytes
 * through a {@link SigstoreSigner}, but jk emits the bare statement first so it round-trips with
 * non-Sigstore consumers.
 *
 * <p>The schema is small and frozen; using a library buys little. Each field that goes into the
 * JSON is escaped per the JSON spec.
 */
public final class SlsaProvenance {

    /** A subject is one artifact being attested to. Maven uploads typically attest the main jar. */
    public record Subject(String name, String sha256) {
        public Subject {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /**
     * All parameters the statement encodes. {@link #externalParameters} / {@link #internalParameters}
     * may be empty.
     */
    public record BuildContext(
            String builderId,
            String buildType,
            String invocationId,
            Instant startedOn,
            Instant finishedOn,
            Map<String, String> externalParameters,
            Map<String, String> internalParameters) {

        public BuildContext {
            Objects.requireNonNull(builderId, "builderId");
            Objects.requireNonNull(buildType, "buildType");
            externalParameters = externalParameters == null ? Map.of() : Map.copyOf(externalParameters);
            internalParameters = internalParameters == null ? Map.of() : Map.copyOf(internalParameters);
        }
    }

    private SlsaProvenance() {}

    /** Render an in-toto v1 SLSA Provenance statement as UTF-8 JSON bytes. */
    public static byte[] generate(List<Subject> subjects, BuildContext context) {
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(context, "context");
        if (subjects.isEmpty()) {
            throw new IllegalArgumentException("at least one subject is required");
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        kv(sb, "_type", "https://in-toto.io/Statement/v1");
        sb.append(',');

        sb.append("\"subject\":[");
        for (int i = 0; i < subjects.size(); i++) {
            if (i > 0) sb.append(',');
            Subject s = subjects.get(i);
            sb.append('{');
            kv(sb, "name", s.name());
            sb.append(",\"digest\":{");
            kv(sb, "sha256", s.sha256());
            sb.append('}');
            sb.append('}');
        }
        sb.append(']');
        sb.append(',');

        kv(sb, "predicateType", "https://slsa.dev/provenance/v1");
        sb.append(',');

        sb.append("\"predicate\":{");

        sb.append("\"buildDefinition\":{");
        kv(sb, "buildType", context.buildType());
        sb.append(',');
        sb.append("\"externalParameters\":");
        renderStringMap(sb, context.externalParameters());
        sb.append(',');
        sb.append("\"internalParameters\":");
        renderStringMap(sb, context.internalParameters());
        sb.append(',');
        sb.append("\"resolvedDependencies\":[]");
        sb.append('}');
        sb.append(',');

        sb.append("\"runDetails\":{");
        sb.append("\"builder\":{");
        kv(sb, "id", context.builderId());
        sb.append('}');
        sb.append(',');
        sb.append("\"metadata\":{");
        if (context.invocationId() != null) {
            kv(sb, "invocationId", context.invocationId());
            sb.append(',');
        }
        kv(sb, "startedOn", context.startedOn().toString());
        sb.append(',');
        kv(sb, "finishedOn", context.finishedOn().toString());
        sb.append('}');
        sb.append('}');

        sb.append('}'); // predicate
        sb.append('}'); // statement

        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void renderStringMap(StringBuilder sb, Map<String, String> values) {
        sb.append('{');
        Map<String, String> ordered = new LinkedHashMap<>(values);
        int i = 0;
        for (Map.Entry<String, String> e : ordered.entrySet()) {
            if (i++ > 0) sb.append(',');
            kv(sb, e.getKey(), e.getValue());
        }
        sb.append('}');
    }

    private static void kv(StringBuilder sb, String key, String value) {
        appendJsonString(sb, key);
        sb.append(':');
        appendJsonString(sb, value);
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append(Jsonl.quote(s));
    }
}
