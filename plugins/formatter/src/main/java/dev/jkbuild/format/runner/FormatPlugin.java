// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.format.runner;

import com.diffplug.spotless.DirtyState;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.java.GoogleJavaFormatStep;
import com.diffplug.spotless.java.PalantirJavaFormatStep;
import com.diffplug.spotless.kotlin.KtfmtStep;
import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.plugin.protocol.ProtocolWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code jk-formatter} worker: formats Java/Kotlin sources through the
 * Spotless engine. The host forks it with a single spec-file path; the spec is
 * tab-delimited records:
 *
 * <pre>{@code
 *   mode    <apply|check>
 *   java    <style>  <version>  <jarPathsJoinedByPathSeparator>
 *   kotlin  <style>  <version>  <maxWidth>  <jarPaths>
 *   f       <java|kotlin>  <absoluteFilePath>
 * }</pre>
 *
 * <p>The formatter implementation jars (palantir-java-format, ktfmt) are
 * resolved by jk and passed in — the worker serves them to Spotless via a
 * classpath {@link Provisioner}, so nothing is downloaded here. Emits one NDJSON
 * record per file plus a final summary, all prefixed with {@code ##JKFMT:}.
 */
public final class FormatPlugin implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-formatter", "##JKFMT:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        if (args.isEmpty()) {
            System.err.println("jk-formatter: expected spec file path");
            return 2;
        }
        Spec spec = Spec.parse(Path.of(args.get(0)));

        Formatter javaFmt = spec.javaJars.isEmpty() ? null : Formatter.builder()
                .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                .encoding(StandardCharsets.UTF_8)
                .steps(List.of(javaStep(spec)))
                .build();

        Formatter kotlinFmt = spec.kotlinJars.isEmpty() ? null : Formatter.builder()
                .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                .encoding(StandardCharsets.UTF_8)
                .steps(List.of(KtfmtStep.create(
                        spec.kotlinVersion, provisioner(spec.kotlinJars),
                        ktfmtStyle(spec.kotlinStyle), ktfmtOptions(spec.kotlinMaxWidth))))
                .build();

        int changed = 0, clean = 0, errors = 0;
        try {
            for (FileRef ref : spec.files) {
                Formatter fmt = ref.kotlin ? kotlinFmt : javaFmt;
                if (fmt == null) continue;   // no formatter for this language (shouldn't happen)
                // Java 21+ unnamed classes (no type declaration) can't be parsed by
                // palantir/google-java-format — skip them silently.
                if (!ref.kotlin && isUnnamedClass(ref.file)) {
                    clean++;
                    emitFile(out, ref.file, "skipped", null);
                    continue;
                }
                try {
                    DirtyState state = DirtyState.of(fmt, ref.file);
                    if (state.isClean()) {
                        clean++;
                        emitFile(out, ref.file, "clean", null);
                    } else if (state.didNotConverge()) {
                        errors++;
                        emitFile(out, ref.file, "error", "formatter did not converge");
                    } else {
                        changed++;
                        if (spec.apply) state.writeCanonicalTo(ref.file);
                        emitFile(out, ref.file, "changed", null);
                    }
                } catch (Exception e) {
                    errors++;
                    emitFile(out, ref.file, "error", e.getMessage());
                }
            }
        } finally {
            if (javaFmt != null) javaFmt.close();
            if (kotlinFmt != null) kotlinFmt.close();
        }

        out.emit("{\"t\":\"done\",\"changed\":" + changed + ",\"clean\":" + clean
                + ",\"errors\":" + errors + "}");
        // In --check mode, an unformatted (changed) file is a failure; errors always are.
        if (errors > 0) return 1;
        if (!spec.apply && changed > 0) return 1;
        return 0;
    }

    private static void emitFile(ProtocolWriter out, File file, String status, String msg) {
        StringBuilder sb = new StringBuilder("{\"t\":\"file\",\"path\":")
                .append(Ndjson.quote(file.getAbsolutePath()))
                .append(",\"status\":").append(Ndjson.quote(status));
        if (msg != null) sb.append(",\"msg\":").append(Ndjson.quote(msg));
        out.emit(sb.append("}").toString());
    }

    /**
     * The Java step for the chosen style: {@code palantir} → palantir-java-format
     * (PALANTIR); {@code google}/{@code aosp} → google-java-format (GOOGLE/AOSP).
     * The version is whatever jk resolved and put in the spec.
     */
    private static FormatterStep javaStep(Spec spec) {
        Provisioner prov = provisioner(spec.javaJars);
        if ("palantir".equalsIgnoreCase(spec.javaStyle)) {
            return PalantirJavaFormatStep.create(spec.javaVersion, "PALANTIR", /* formatJavadoc */ false, prov);
        }
        return GoogleJavaFormatStep.create(spec.javaVersion, spec.javaStyle.toUpperCase(Locale.ROOT), prov);
    }

    /** A classpath Provisioner: hands Spotless the pre-resolved jars jk passed in. */
    private static Provisioner provisioner(Set<File> jars) {
        return (withTransitives, coords) -> jars;
    }

    private static KtfmtStep.Style ktfmtStyle(String style) {
        return switch (style.toLowerCase(Locale.ROOT)) {
            case "kotlinlang" -> KtfmtStep.Style.KOTLINLANG;
            case "google" -> KtfmtStep.Style.GOOGLE;
            case "meta" -> KtfmtStep.Style.META;
            default -> throw new IllegalArgumentException("unknown kotlin style: " + style);
        };
    }

    private static KtfmtStep.KtfmtFormattingOptions ktfmtOptions(int maxWidth) {
        var opts = new KtfmtStep.KtfmtFormattingOptions();
        if (maxWidth > 0) opts.setMaxWidth(maxWidth);
        return opts;
    }

    // Matches any top-level type declaration (class/interface/enum/record/@interface).
    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface|enum|record)\\s+\\w|@interface\\s+\\w");

    /** True if the file has no top-level type declaration (Java 21+ unnamed class). */
    private static boolean isUnnamedClass(File file) throws java.io.IOException {
        String src = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        // Strip line and block comments before checking for type declarations.
        String stripped = src.replaceAll("//[^\n]*", "")
                             .replaceAll("(?s)/\\*.*?\\*/", " ");
        return !TYPE_DECL.matcher(stripped).find();
    }

    private record FileRef(boolean kotlin, File file) {}

    /** Parsed spec: modes, per-language style/version/jars, and the file list. */
    private static final class Spec {
        boolean apply = true;
        String javaStyle = "palantir";
        String javaVersion = PalantirJavaFormatStep.defaultVersion();
        Set<File> javaJars = new LinkedHashSet<>();
        String kotlinStyle = "kotlinlang";
        String kotlinVersion = KtfmtStep.defaultVersion();
        int kotlinMaxWidth = 0;
        Set<File> kotlinJars = new LinkedHashSet<>();
        final List<FileRef> files = new ArrayList<>();

        static Spec parse(Path specFile) throws java.io.IOException {
            Spec s = new Spec();
            for (String line : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] p = line.split("\t", -1);
                switch (p[0]) {
                    case "mode" -> s.apply = !"check".equals(p[1]);
                    case "java" -> {
                        s.javaStyle = p[1];
                        s.javaVersion = p[2];
                        s.javaJars = jars(p[3]);
                    }
                    case "kotlin" -> {
                        s.kotlinStyle = p[1];
                        s.kotlinVersion = p[2];
                        s.kotlinMaxWidth = Integer.parseInt(p[3]);
                        s.kotlinJars = jars(p[4]);
                    }
                    case "f" -> s.files.add(new FileRef("kotlin".equals(p[1]), new File(p[2])));
                    default -> { /* ignore unknown records for forward-compat */ }
                }
            }
            return s;
        }

        private static Set<File> jars(String joined) {
            Set<File> out = new LinkedHashSet<>();
            if (joined == null || joined.isBlank()) return out;
            for (String path : joined.split(File.pathSeparator)) {
                if (!path.isBlank()) out.add(new File(path));
            }
            return out;
        }
    }
}
