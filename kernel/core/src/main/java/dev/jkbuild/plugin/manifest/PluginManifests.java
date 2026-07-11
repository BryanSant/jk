// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import dev.jkbuild.config.JkBuildParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Parses {@code jk-plugin.toml} manifests (build-plugins plan §3.1). Engine-side only — this is
 * a tomlj parse, and the manifest layer never runs client-side (the same method-level
 * native-image reachability discipline as {@code JkBuildParser}).
 */
public final class PluginManifests {

    private PluginManifests() {}

    public static PluginManifest parse(String toml, String displayPath) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(displayPath + " has invalid TOML: "
                    + result.errors().getFirst().getMessage());
        }
        TomlTable plugin = result.getTable("plugin");
        if (plugin == null) {
            throw new JkBuildParseException(displayPath + " is missing the required [plugin] table");
        }
        String id = requireString(plugin, "id", displayPath);
        String table = requireString(plugin, "table", displayPath);
        String version = plugin.getString("version");
        String jkCompat = plugin.getString("jk-compat");

        Map<String, PluginManifest.SchemaKey> schema = new LinkedHashMap<>();
        TomlTable schemaTable = result.getTable("schema");
        if (schemaTable != null) {
            for (String key : schemaTable.keySet()) {
                Object raw = schemaTable.get(key);
                if (!(raw instanceof TomlTable spec)) {
                    throw new JkBuildParseException(
                            displayPath + ".schema." + key + " must be a table (type = \"…\", …)");
                }
                String typeRaw = spec.getString("type");
                if (typeRaw == null) {
                    throw new JkBuildParseException(displayPath + ".schema." + key + " requires `type`");
                }
                var type = PluginManifest.SchemaKey.Type.parse(typeRaw, displayPath + ".schema." + key);
                boolean required = Boolean.TRUE.equals(spec.getBoolean("required"));
                Object defaultValue = defaultFor(spec, type, displayPath + ".schema." + key);
                schema.put(key, new PluginManifest.SchemaKey(
                        key, type, required, defaultValue, spec.getString("example"), spec.getString("hint")));
            }
        }
        PluginManifest.Contributions contributions = parseContributions(result, schema.keySet(), displayPath);
        PluginManifest.Code code = parseCode(result, displayPath);
        PluginManifest.Packaging packaging = parsePackaging(result, displayPath);
        PluginManifest.Scaffold scaffold = parseScaffold(result, displayPath);
        List<PluginManifest.GradleImport> gradleImports = parseGradleImports(result, displayPath);
        return new PluginManifest(
                id, table, version, jkCompat, schema, contributions, code, packaging, scaffold, gradleImports);
    }

    /** The {@code [scaffold]} section — {@code jk new --<flag>} templates (P4, pure data). */
    private static PluginManifest.Scaffold parseScaffold(TomlParseResult result, String displayPath) {
        TomlTable scaffold = result.getTable("scaffold");
        if (scaffold == null) return null;
        String flag = scaffold.getString("flag");
        if (flag == null || flag.isBlank()) {
            throw new JkBuildParseException(displayPath + ".scaffold.flag is required (the jk new --<flag> name)");
        }
        List<PluginManifest.Append> appends = new ArrayList<>();
        for (TomlTable t : tableArray(scaffold, "append", displayPath)) {
            appends.add(new PluginManifest.Append(
                    requireString(t, "template", displayPath + ".scaffold.append"),
                    scaffoldLang(t, displayPath + ".scaffold.append")));
        }
        List<PluginManifest.FileTemplate> files = new ArrayList<>();
        for (TomlTable t : tableArray(scaffold, "file", displayPath)) {
            files.add(new PluginManifest.FileTemplate(
                    requireString(t, "path", displayPath + ".scaffold.file"),
                    requireString(t, "template", displayPath + ".scaffold.file"),
                    scaffoldLang(t, displayPath + ".scaffold.file"),
                    Boolean.TRUE.equals(t.getBoolean("keep-existing"))));
        }
        return new PluginManifest.Scaffold(flag, scaffold.getString("description"), appends, files);
    }

    /**
     * A scaffold entry's {@code when} — the scaffold-local closed set is {@code lang} only
     * (java|kotlin); anything richer belongs in a code hook, same anti-DSL-creep rule as the
     * build conditions.
     */
    private static String scaffoldLang(TomlTable entry, String where) {
        TomlTable when = entry.getTable("when");
        if (when == null) return null;
        if (when.keySet().size() != 1 || !when.keySet().contains("lang")) {
            throw new JkBuildParseException(where + ".when supports exactly one predicate: lang = \"java|kotlin\"");
        }
        String lang = when.getString("lang");
        if (!List.of("java", "kotlin").contains(String.valueOf(lang))) {
            throw new JkBuildParseException(where + ".when.lang must be java or kotlin — got: " + lang);
        }
        return lang;
    }

    /** The {@code [[import.gradle-plugin]]} rules — jk import's plugin-id mappings (P4). */
    private static List<PluginManifest.GradleImport> parseGradleImports(TomlParseResult result, String displayPath) {
        TomlTable importTable = result.getTable("import");
        if (importTable == null) return List.of();
        List<PluginManifest.GradleImport> rules = new ArrayList<>();
        for (TomlTable t : tableArray(importTable, "gradle-plugin", displayPath)) {
            rules.add(new PluginManifest.GradleImport(
                    requireString(t, "id", displayPath + ".import.gradle-plugin"),
                    t.getString("version-to"),
                    t.getString("missing-version-warning")));
        }
        return rules;
    }

    /** The {@code [code]} table — the plugin's worker jar carrying step/packager bodies (P3). */
    private static PluginManifest.Code parseCode(TomlParseResult result, String displayPath) {
        TomlTable code = result.getTable("code");
        if (code == null) return null;
        String worker = code.getString("worker");
        if (worker == null || worker.isBlank()) {
            throw new JkBuildParseException(displayPath + ".code.worker is required (the worker jar's artifactId)");
        }
        String prefix = code.getString("protocol-prefix");
        if (prefix == null || prefix.isBlank()) {
            throw new JkBuildParseException(
                    displayPath + ".code.protocol-prefix is required (the worker's protocol line marker)");
        }
        return new PluginManifest.Code(worker, prefix);
    }

    /** The {@code [packaging]} table — the packager's static artifact descriptor (plan §3.3). */
    private static PluginManifest.Packaging parsePackaging(TomlParseResult result, String displayPath) {
        TomlTable packaging = result.getTable("packaging");
        if (packaging == null) return null;
        String execMode = packaging.getString("exec-mode");
        if (execMode == null) execMode = "classpath";
        if (!List.of("jar", "classpath", "binary").contains(execMode)) {
            throw new JkBuildParseException(
                    displayPath + ".packaging.exec-mode must be jar, classpath, or binary — got: " + execMode);
        }
        return new PluginManifest.Packaging(
                packaging.getString("packager"),
                execMode,
                Boolean.TRUE.equals(packaging.getBoolean("self-contained")),
                Boolean.TRUE.equals(packaging.getBoolean("classes-run")),
                Boolean.TRUE.equals(packaging.getBoolean("main-scan")),
                Boolean.TRUE.equals(packaging.getBoolean("layered-image")));
    }

    // ---- [[contribute.*]] — the declarative layer (P2) --------------------------------------

    private static PluginManifest.Contributions parseContributions(
            TomlParseResult result, java.util.Set<String> schemaKeys, String displayPath) {
        TomlTable contribute = result.getTable("contribute");
        if (contribute == null) return PluginManifest.Contributions.NONE;

        List<PluginManifest.PlatformDependency> platformDeps = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "platform-dependency", displayPath)) {
            String where = displayPath + ".contribute.platform-dependency";
            String coordinate = requireString(t, "coordinate", where);
            Interpolation.validate(coordinate, schemaKeys, where);
            PluginManifest.Condition when = parseCondition(t, where);
            if (when instanceof PluginManifest.Condition.ClasspathHas) {
                // Platform deps inject at parse time, before resolution — there is no
                // classpath to test yet. Fail at load, not mid-build.
                throw new JkBuildParseException(
                        where + ": classpath-has cannot gate a platform-dependency (it is evaluated"
                                + " before resolution)");
            }
            platformDeps.add(new PluginManifest.PlatformDependency(coordinate, when));
        }

        List<PluginManifest.CompilerArgs> compilerArgs = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "compiler-args", displayPath)) {
            String where = displayPath + ".contribute.compiler-args";
            List<String> javac = stringList(t, "javac", where);
            List<String> kotlin = stringList(t, "kotlin", where);
            for (String arg : javac) Interpolation.validate(arg, schemaKeys, where + ".javac");
            for (String arg : kotlin) Interpolation.validate(arg, schemaKeys, where + ".kotlin");
            compilerArgs.add(new PluginManifest.CompilerArgs(javac, kotlin, parseCondition(t, where)));
        }

        List<PluginManifest.KotlinPlugin> kotlinPlugins = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "kotlin-plugin", displayPath)) {
            String where = displayPath + ".contribute.kotlin-plugin";
            String id = requireString(t, "id", where);
            String coordinate = requireString(t, "coordinate", where);
            Interpolation.validate(coordinate, schemaKeys, where);
            List<String> options = stringList(t, "options", where);
            for (String opt : options) Interpolation.validate(opt, schemaKeys, where + ".options");
            kotlinPlugins.add(new PluginManifest.KotlinPlugin(id, coordinate, options, parseCondition(t, where)));
        }

        List<PluginManifest.PackagerDependency> packagerDeps = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "packager-dependency", displayPath)) {
            String where = displayPath + ".contribute.packager-dependency";
            String artifact = requireString(t, "artifact", where);
            String coordinate = requireString(t, "coordinate", where);
            Interpolation.validate(coordinate, schemaKeys, where);
            PluginManifest.Condition when = parseCondition(t, where);
            if (when instanceof PluginManifest.Condition.ClasspathHas) {
                throw new JkBuildParseException(
                        where + ": classpath-has cannot gate a packager-dependency (fetch decisions"
                                + " precede packaging classpath evaluation)");
            }
            packagerDeps.add(new PluginManifest.PackagerDependency(artifact, coordinate, when));
        }

        return new PluginManifest.Contributions(platformDeps, compilerArgs, kotlinPlugins, packagerDeps);
    }

    /**
     * Parse the optional {@code when} inline table into exactly one {@link
     * PluginManifest.Condition} — the closed predicate set; two predicates in one {@code when}
     * (or an unknown one) is a load error, never a silently-false condition.
     */
    private static PluginManifest.Condition parseCondition(TomlTable entry, String where) {
        if (!entry.contains("when")) return null;
        TomlTable when = entry.getTable("when");
        if (when == null) {
            throw new JkBuildParseException(where + ".when must be a table of exactly one predicate");
        }
        if (when.keySet().size() > (when.contains("config") ? 2 : 1)) {
            throw new JkBuildParseException(where + ".when carries more than one predicate — the"
                    + " condition set is closed; anything richer belongs in a code hook");
        }
        if (when.contains("classpath-has")) {
            String module = when.getString("classpath-has");
            if (module == null || module.isBlank()) {
                throw new JkBuildParseException(where + ".when.classpath-has must be \"group:artifact\"");
            }
            return new PluginManifest.Condition.ClasspathHas(module);
        }
        if (when.contains("config")) {
            String key = when.getString("config");
            String equals = when.getString("equals");
            if (key == null || equals == null) {
                throw new JkBuildParseException(
                        where + ".when config predicate needs both `config = \"<key>\"` and `equals = \"<value>\"`");
            }
            return new PluginManifest.Condition.ConfigEquals(key, equals);
        }
        if (when.contains("native-declared")) {
            requireTrue(when, "native-declared", where);
            return new PluginManifest.Condition.NativeDeclared();
        }
        if (when.contains("kotlin-project")) {
            requireTrue(when, "kotlin-project", where);
            return new PluginManifest.Condition.KotlinProject();
        }
        throw new JkBuildParseException(where + ".when has no known predicate (classpath-has, config/equals,"
                + " native-declared, kotlin-project)");
    }

    private static void requireTrue(TomlTable when, String key, String where) {
        if (!Boolean.TRUE.equals(when.getBoolean(key))) {
            throw new JkBuildParseException(where + ".when." + key + " must be `true` (omit the"
                    + " condition entirely for the false case)");
        }
    }

    private static List<TomlTable> tableArray(TomlTable contribute, String key, String displayPath) {
        if (!contribute.contains(key)) return List.of();
        TomlArray arr = contribute.getArray(key);
        if (arr == null) {
            throw new JkBuildParseException(
                    displayPath + ".contribute." + key + " must be an array of tables ([[contribute." + key + "]])");
        }
        List<TomlTable> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof TomlTable t)) {
                throw new JkBuildParseException(displayPath + ".contribute." + key + " entries must be tables");
            }
            out.add(t);
        }
        return out;
    }

    private static List<String> stringList(TomlTable table, String key, String where) {
        if (!table.contains(key)) return List.of();
        TomlArray arr = table.getArray(key);
        if (arr == null) throw new JkBuildParseException(where + "." + key + " must be an array of strings");
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object v = arr.get(i);
            if (!(v instanceof String str)) {
                throw new JkBuildParseException(where + "." + key + " must be an array of strings");
            }
            out.add(str);
        }
        return out;
    }

    private static Object defaultFor(TomlTable spec, PluginManifest.SchemaKey.Type type, String where) {
        if (!spec.contains("default")) return null;
        return switch (type) {
            case STRING -> spec.getString("default");
            case BOOL -> spec.getBoolean("default");
            case INT -> spec.getLong("default");
            case STRING_LIST -> {
                TomlArray arr = spec.getArray("default");
                if (arr == null) throw new JkBuildParseException(where + ".default must be an array");
                List<String> out = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) out.add(String.valueOf(arr.get(i)));
                yield out;
            }
        };
    }

    private static String requireString(TomlTable table, String key, String displayPath) {
        String v = table.getString(key);
        if (v == null || v.isBlank()) {
            throw new JkBuildParseException(displayPath + ".plugin." + key + " is required");
        }
        return v;
    }
}
