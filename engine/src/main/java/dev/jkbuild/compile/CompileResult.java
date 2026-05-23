// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** What {@link JavacDriver} returns. {@code success} mirrors {@code javac}'s exit-zero. */
public record CompileResult(boolean success, List<Diagnostic> diagnostics) {

    public CompileResult {
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    public record Diagnostic(
            Severity severity,
            Path source,
            long line,
            long column,
            String message) {

        public Diagnostic {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
        }

        /** Human-friendly one-line form: {@code error: src/Foo.java:12: ...}. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(severity.name().toLowerCase()).append(": ");
            if (source != null) {
                sb.append(source);
                if (line > 0) {
                    sb.append(':').append(line);
                    if (column > 0) sb.append(':').append(column);
                }
                sb.append(": ");
            }
            sb.append(message);
            return sb.toString();
        }
    }

    public enum Severity {
        ERROR, WARNING, NOTE, OTHER;

        public static Severity fromJavacKind(javax.tools.Diagnostic.Kind kind) {
            return switch (kind) {
                case ERROR -> ERROR;
                case WARNING, MANDATORY_WARNING -> WARNING;
                case NOTE -> NOTE;
                case OTHER -> OTHER;
                default -> OTHER;
            };
        }
    }
}
