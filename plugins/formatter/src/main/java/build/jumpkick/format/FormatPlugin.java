// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.format;

import com.diffplug.spotless.DirtyState;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.java.GoogleJavaFormatStep;
import com.diffplug.spotless.java.PalantirJavaFormatStep;
import com.diffplug.spotless.kotlin.KtfmtStep;
import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.CompositeRecipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;

/**
 * The {@code jk-formatter} worker: formats Java/Kotlin sources through an optional OpenRewrite pass
 * (import optimisation) followed by the Spotless engine. The host forks it with a single spec-file
 * path; the spec is tab-delimited records:
 *
 * <pre>{@code
 * mode          <apply|check>
 * java          <style>  <version>  <jarPathsJoinedByPathSeparator>
 * kotlin        <style>  <version>  <maxWidth>  <jarPaths>
 * rewrite-flags optimize-imports=<bool>
 * rewrite-config  <absolutePathToConfigFile>   (optional)
 * cache-dir     <absolutePathToCacheRoot>       (optional)
 * f             <java|kotlin>  <absoluteFilePath>
 * }</pre>
 *
 * <p>The OpenRewrite pass runs first (when requested), writing changes to disk in apply mode or
 * detecting would-be changes in check mode. Spotless then reads the (possibly OpenRewrite-modified)
 * files for its own formatting step.
 *
 * <p>The formatter implementation jars (palantir-java-format, ktfmt) are resolved by jk and passed
 * in — the worker serves them to Spotless via a classpath {@link Provisioner}, so nothing is
 * downloaded here. OpenRewrite's engine is bundled directly in this fat JAR. Emits one NDJSON
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

        // Per-file stamp cache — skips unchanged files without running the formatter.
        FormatStampCache stampCache =
                spec.cacheDir != null ? new FormatStampCache(spec.cacheDir.resolve("format-stamps")) : null;
        // Config descriptor is the same for every file in this run; compute once.
        String configDesc = stampCache != null
                ? FormatStamp.configDescriptor(
                        spec.javaStyle,
                        spec.javaVersion,
                        spec.kotlinStyle,
                        spec.kotlinVersion,
                        spec.optimizeImports,
                        FormatStamp.workerJarSha(),
                        spec.rewriteConfigFile != null ? spec.rewriteConfigFile.toPath() : null)
                : null;

        // Build the OpenRewrite recipe once (null when no rewrite is requested).
        Recipe rewriteRecipe = spec.hasRewrite() ? buildRecipe(spec) : null;

        Formatter javaFmt = spec.javaJars.isEmpty()
                ? null
                : Formatter.builder()
                        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                        .encoding(StandardCharsets.UTF_8)
                        .steps(List.of(javaStep(spec)))
                        .build();

        Formatter kotlinFmt = spec.kotlinJars.isEmpty()
                ? null
                : Formatter.builder()
                        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                        .encoding(StandardCharsets.UTF_8)
                        .steps(List.of(KtfmtStep.create(
                                spec.kotlinVersion,
                                provisioner(spec.kotlinJars),
                                ktfmtStyle(spec.kotlinStyle),
                                ktfmtOptions(spec.kotlinMaxWidth))))
                        .build();

        int changed = 0, clean = 0, errors = 0;
        try {
            for (FileRef ref : spec.files) {
                Formatter fmt = ref.kotlin ? kotlinFmt : javaFmt;
                if (fmt == null) continue; // no formatter for this language (shouldn't happen)
                // Java 21+ unnamed classes (no type declaration) can't be parsed by
                // palantir/google-java-format — skip them silently.
                if (!ref.kotlin && isUnnamedClass(ref.file)) {
                    clean++;
                    emitFile(out, ref.file, "skipped", null);
                    continue;
                }
                try {
                    // --- Stamp check -------------------------------------------------
                    // Read file bytes once — used for both the stamp key and handed to
                    // OpenRewrite/Spotless on a miss (OS cache makes the 2nd read free).
                    byte[] originalBytes = stampCache != null ? Files.readAllBytes(ref.file.toPath()) : null;
                    String stampKey = originalBytes != null ? FormatStamp.computeKey(originalBytes, configDesc) : null;

                    if (stampKey != null && stampCache.contains(stampKey)) {
                        // Stamp hit: file content is known clean under this config.
                        clean++;
                        emitFile(out, ref.file, "clean", null);
                        continue;
                    }

                    // --- OpenRewrite pass (Java only) --------------------------------
                    boolean rewriteChanged = false;
                    if (!ref.kotlin && rewriteRecipe != null) {
                        rewriteChanged = applyRewrite(rewriteRecipe, ref.file, spec.apply);
                    }

                    // --- Spotless pass -----------------------------------------------
                    DirtyState state = DirtyState.of(fmt, ref.file);
                    boolean spotlessChanged = !state.isClean() && !state.didNotConverge();

                    if (state.didNotConverge()) {
                        errors++;
                        emitFile(out, ref.file, "error", "formatter did not converge");
                    } else if (rewriteChanged || spotlessChanged) {
                        changed++;
                        if (spec.apply && spotlessChanged) state.writeCanonicalTo(ref.file);
                        emitFile(out, ref.file, "changed", null);
                        // In apply mode: stamp the newly formatted content so next run
                        // skips it. (In check mode the file wasn't written, so no stamp.)
                        if (spec.apply && stampCache != null) {
                            byte[] finalBytes = Files.readAllBytes(ref.file.toPath());
                            String finalKey = FormatStamp.computeKey(finalBytes, configDesc);
                            if (finalKey != null) stampCache.record(finalKey);
                        }
                    } else {
                        // File is already clean — stamp current content to skip next time.
                        clean++;
                        emitFile(out, ref.file, "clean", null);
                        if (stampKey != null) stampCache.record(stampKey);
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

        out.emit("{\"t\":\"done\",\"changed\":" + changed + ",\"clean\":" + clean + ",\"errors\":" + errors + "}");
        // In --check mode, an unformatted (changed) file is a failure; errors always are.
        if (errors > 0) return 1;
        if (!spec.apply && changed > 0) return 1;
        return 0;
    }

    // -------------------------------------------------------------------------
    // OpenRewrite helpers
    // -------------------------------------------------------------------------

    /**
     * Assemble the composite OpenRewrite recipe from flags + optional YAML config. Flag-driven
     * built-ins come first; the config file layers additional recipes on top. Returns a single Recipe
     * that represents all active transformations, or {@code null} if nothing is active.
     */
    private static Recipe buildRecipe(Spec spec) throws IOException {
        List<Recipe> recipes = new ArrayList<>();
        if (spec.optimizeImports) recipes.add(new ShortenFullyQualifiedTypeReferences());

        if (spec.rewriteConfigFile != null) {
            try (InputStream is = new FileInputStream(spec.rewriteConfigFile)) {
                Environment env = Environment.builder()
                        .load(new YamlResourceLoader(is, spec.rewriteConfigFile.toURI(), new Properties()))
                        .build();
                env.listRecipes().forEach(d -> recipes.add(env.activateRecipes(d.getName())));
            }
        }
        if (recipes.isEmpty()) return null;
        return recipes.size() == 1 ? recipes.get(0) : new CompositeRecipe(recipes);
    }

    /**
     * Run the recipe against a single Java file. In apply mode the file is written back if the recipe
     * produced changes. Returns whether the file was (or would be) changed.
     */
    private static boolean applyRewrite(Recipe recipe, File file, boolean apply) throws IOException {
        ExecutionContext ctx = new InMemoryExecutionContext(e -> {});
        List<SourceFile> parsed;
        try {
            parsed = JavaParser.fromJavaVersion()
                    .logCompilationWarningsAndErrors(false)
                    .build()
                    .parse(List.of(file.toPath()), file.toPath().getParent(), ctx)
                    .toList();
        } catch (Exception e) {
            // Unparseable file (unnamed class, syntax error, …) — skip silently.
            return false;
        }
        if (parsed.isEmpty()) return false;

        RecipeRun run = recipe.run(new InMemoryLargeSourceSet(parsed), ctx);
        List<Result> results = run.getChangeset().getAllResults();
        if (results.isEmpty()) return false;

        if (apply) {
            for (Result result : results) {
                if (result.getAfter() != null) {
                    Files.writeString(file.toPath(), result.getAfter().printAll(), StandardCharsets.UTF_8);
                }
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Spotless helpers
    // -------------------------------------------------------------------------

    private static void emitFile(ProtocolWriter out, File file, String status, String msg) {
        StringBuilder sb = new StringBuilder("{\"t\":\"file\",\"path\":")
                .append(Ndjson.quote(file.getAbsolutePath()))
                .append(",\"status\":")
                .append(Ndjson.quote(status));
        if (msg != null) sb.append(",\"msg\":").append(Ndjson.quote(msg));
        out.emit(sb.append("}").toString());
    }

    /**
     * The Java step for the chosen style: {@code palantir} → palantir-java-format (PALANTIR); {@code
     * google}/{@code aosp} → google-java-format (GOOGLE/AOSP). The version is whatever jk resolved
     * and put in the spec.
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
    private static boolean isUnnamedClass(File file) throws IOException {
        String src = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        // Strip line and block comments before checking for type declarations.
        String stripped = src.replaceAll("//[^\n]*", "").replaceAll("(?s)/\\*.*?\\*/", " ");
        return !TYPE_DECL.matcher(stripped).find();
    }

    private record FileRef(boolean kotlin, File file) {}

    /** Parsed spec: modes, per-language style/version/jars, rewrite flags, and the file list. */
    private static final class Spec {
        boolean apply = true;
        String javaStyle = "palantir";
        String javaVersion = PalantirJavaFormatStep.defaultVersion();
        Set<File> javaJars = new LinkedHashSet<>();
        String kotlinStyle = "kotlinlang";
        String kotlinVersion = KtfmtStep.defaultVersion();
        int kotlinMaxWidth = 0;
        Set<File> kotlinJars = new LinkedHashSet<>();
        // OpenRewrite fields
        boolean optimizeImports = false;
        File rewriteConfigFile = null;
        // Stamp cache: null when the host didn't pass a cache-dir (no caching).
        Path cacheDir = null;
        final List<FileRef> files = new ArrayList<>();

        boolean hasRewrite() {
            return optimizeImports || rewriteConfigFile != null;
        }

        static Spec parse(Path specFile) throws IOException {
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
                    case "rewrite-flags" -> {
                        // tokens: "optimize-imports=<bool>"
                        for (int i = 1; i < p.length; i++) {
                            String[] kv = p[i].split("=", 2);
                            if (kv.length == 2 && "optimize-imports".equals(kv[0])) {
                                s.optimizeImports = Boolean.parseBoolean(kv[1]);
                            }
                        }
                    }
                    case "rewrite-config" -> s.rewriteConfigFile = new File(p[1]);
                    case "cache-dir" -> s.cacheDir = Path.of(p[1]);
                    case "f" -> s.files.add(new FileRef("kotlin".equals(p[1]), new File(p[2])));
                    default -> {
                        /* ignore unknown records for forward-compat */
                    }
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
