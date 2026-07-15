// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.manifest;

import build.jumpkick.config.JkBuildParseException;
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
public final class PluginDescriptors {

    private PluginDescriptors() {}

    public static PluginDescriptor parse(String toml, String displayPath) {
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

        Map<String, PluginDescriptor.SchemaKey> schema =
                parseSchemaKeys(result.getTable("schema"), displayPath + ".schema");

        // [sub-schema.<name>]: named key sets for nested-table groups (build types, signing).
        Map<String, Map<String, PluginDescriptor.SchemaKey>> subSchemas = new LinkedHashMap<>();
        TomlTable subSchemaRoot = result.getTable("sub-schema");
        if (subSchemaRoot != null) {
            for (String name : subSchemaRoot.keySet()) {
                Object raw = subSchemaRoot.get(name);
                if (!(raw instanceof TomlTable spec)) {
                    throw new JkBuildParseException(displayPath + ".sub-schema." + name + " must be a table");
                }
                subSchemas.put(name, parseSchemaKeys(spec, displayPath + ".sub-schema." + name));
            }
        }

        // [sub-tables.<table>]: nested-table groups on the owned table; optionally variant axes.
        Map<String, PluginDescriptor.SubTable> subTables = new LinkedHashMap<>();
        TomlTable subTableRoot = result.getTable("sub-tables");
        if (subTableRoot != null) {
            for (String name : subTableRoot.keySet()) {
                Object raw = subTableRoot.get(name);
                if (!(raw instanceof TomlTable spec)) {
                    throw new JkBuildParseException(displayPath + ".sub-tables." + name + " must be a table");
                }
                String where = displayPath + ".sub-tables." + name;
                String schemaRef = spec.getString("schema");
                if (schemaRef == null || !subSchemas.containsKey(schemaRef)) {
                    throw new JkBuildParseException(
                            where + " requires schema = \"<name>\" naming a declared [sub-schema.<name>]");
                }
                // A same-named [schema] key is the REFERENCE spelling (signing = "release") — legal,
                // because TOML itself forbids one key being both a string and a table: a module
                // either declares [<table>.<group>.<name>] definitions or sets the string.
                if (schema.containsKey(name) && schema.get(name).type() != PluginDescriptor.SchemaKey.Type.STRING) {
                    throw new JkBuildParseException(where + " collides with a non-string [schema] key");
                }
                if (spec.contains("variant-axis") || spec.contains("dimensioned")) {
                    throw new JkBuildParseException(where + ": variant axes are core's [variants]"
                            + " section now — sub-tables are named definition groups only");
                }
                subTables.put(name, new PluginDescriptor.SubTable(name, schemaRef));
            }
        }

        PluginDescriptor.Contributions contributions = parseContributions(result, schema.keySet(), displayPath);
        PluginDescriptor.Code code = parseCode(result, displayPath);
        PluginDescriptor.Packaging packaging = parsePackaging(result, displayPath);
        PluginDescriptor.Scaffold scaffold = parseScaffold(result, displayPath);
        List<PluginDescriptor.GradleImport> gradleImports = parseGradleImports(result, displayPath);
        return new PluginDescriptor(
                id, table, version, jkCompat, schema, contributions, code, packaging, scaffold, gradleImports,
                subSchemas, subTables);
    }

    /** Typed schema keys from one table of {@code key = { type = "…", … }} specs. */
    private static Map<String, PluginDescriptor.SchemaKey> parseSchemaKeys(TomlTable schemaTable, String where) {
        Map<String, PluginDescriptor.SchemaKey> schema = new LinkedHashMap<>();
        if (schemaTable == null) return schema;
        for (String key : schemaTable.keySet()) {
            Object raw = schemaTable.get(key);
            if (!(raw instanceof TomlTable spec)) {
                throw new JkBuildParseException(where + "." + key + " must be a table (type = \"…\", …)");
            }
            String typeRaw = spec.getString("type");
            if (typeRaw == null) {
                throw new JkBuildParseException(where + "." + key + " requires `type`");
            }
            var type = PluginDescriptor.SchemaKey.Type.parse(typeRaw, where + "." + key);
            boolean required = Boolean.TRUE.equals(spec.getBoolean("required"));
            Object defaultValue = defaultFor(spec, type, where + "." + key);
            schema.put(key, new PluginDescriptor.SchemaKey(
                    key, type, required, defaultValue, spec.getString("example"), spec.getString("hint"),
                    Boolean.TRUE.equals(spec.getBoolean("secret"))));
        }
        return schema;
    }

    /** The {@code [scaffold]} section — {@code jk new --<flag>} templates (P4, pure data). */
    private static PluginDescriptor.Scaffold parseScaffold(TomlParseResult result, String displayPath) {
        TomlTable scaffold = result.getTable("scaffold");
        if (scaffold == null) return null;
        String flag = scaffold.getString("flag");
        if (flag == null || flag.isBlank()) {
            throw new JkBuildParseException(displayPath + ".scaffold.flag is required (the jk new --<flag> name)");
        }
        List<PluginDescriptor.Append> appends = new ArrayList<>();
        for (TomlTable t : tableArray(scaffold, "append", displayPath)) {
            appends.add(new PluginDescriptor.Append(
                    requireString(t, "template", displayPath + ".scaffold.append"),
                    scaffoldLang(t, displayPath + ".scaffold.append")));
        }
        List<PluginDescriptor.FileTemplate> files = new ArrayList<>();
        for (TomlTable t : tableArray(scaffold, "file", displayPath)) {
            files.add(new PluginDescriptor.FileTemplate(
                    requireString(t, "path", displayPath + ".scaffold.file"),
                    requireString(t, "template", displayPath + ".scaffold.file"),
                    scaffoldLang(t, displayPath + ".scaffold.file"),
                    Boolean.TRUE.equals(t.getBoolean("keep-existing"))));
        }
        return new PluginDescriptor.Scaffold(flag, scaffold.getString("description"), appends, files);
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
    private static List<PluginDescriptor.GradleImport> parseGradleImports(TomlParseResult result, String displayPath) {
        TomlTable importTable = result.getTable("import");
        if (importTable == null) return List.of();
        List<PluginDescriptor.GradleImport> rules = new ArrayList<>();
        for (TomlTable t : tableArray(importTable, "gradle-plugin", displayPath)) {
            rules.add(new PluginDescriptor.GradleImport(
                    requireString(t, "id", displayPath + ".import.gradle-plugin"),
                    t.getString("version-to"),
                    t.getString("missing-version-warning")));
        }
        return rules;
    }

    /** The {@code [code]} table — the plugin's worker jar carrying step/packager bodies (P3). */
    private static PluginDescriptor.Code parseCode(TomlParseResult result, String displayPath) {
        TomlTable code = result.getTable("code");
        if (code == null) return null;
        // `worker` names a registered first-party worker jar; a third-party plugin IS its own
        // worker (the [plugins]-declared jar), so the key is optional here — the built-in loader
        // enforces it for manifests shipped inside jk.
        String worker = code.getString("worker");
        if (worker != null && worker.isBlank()) worker = null;
        String prefix = code.getString("protocol-prefix");
        if (prefix == null || prefix.isBlank()) {
            throw new JkBuildParseException(
                    displayPath + ".code.protocol-prefix is required (the worker's protocol line marker)");
        }
        return new PluginDescriptor.Code(worker, prefix);
    }

    /** The {@code [packaging]} table — the packager's static artifact descriptor (plan §3.3). */
    private static PluginDescriptor.Packaging parsePackaging(TomlParseResult result, String displayPath) {
        TomlTable packaging = result.getTable("packaging");
        if (packaging == null) return null;
        List<PluginDescriptor.Packaging.Variant> variants = new ArrayList<>();
        var variantArray = packaging.getArray("variant");
        if (variantArray != null) {
            for (int i = 0; i < variantArray.size(); i++) {
                TomlTable v = variantArray.getTable(i);
                if (v == null) continue;
                TomlTable when = v.getTable("when");
                if (when == null || when.getString("config") == null || when.getString("equals") == null) {
                    throw new JkBuildParseException(displayPath
                            + ".packaging.variant requires when = { config = ..., equals = ... } — packaging"
                            + " resolves before any classpath exists, so only config predicates apply");
                }
                variants.add(new PluginDescriptor.Packaging.Variant(
                        new PluginDescriptor.Condition.ConfigEquals(
                                when.getString("config"), when.getString("equals")),
                        parsePackagingTable(v, displayPath, List.of())));
            }
        }
        return parsePackagingTable(packaging, displayPath, variants);
    }

    private static PluginDescriptor.Packaging parsePackagingTable(
            TomlTable packaging, String displayPath, List<PluginDescriptor.Packaging.Variant> variants) {
        String execMode = packaging.getString("exec-mode");
        if (execMode == null) execMode = "classpath";
        if (!List.of("jar", "classpath", "binary", "device", "none").contains(execMode)) {
            throw new JkBuildParseException(
                    displayPath + ".packaging.exec-mode must be jar, classpath, binary, device, or none — got: "
                            + execMode);
        }
        String deployCommand = packaging.getString("deploy-command");
        if (deployCommand != null && !"device".equals(execMode)) {
            throw new JkBuildParseException(
                    displayPath + ".packaging.deploy-command only applies to exec-mode = \"device\"");
        }
        String extension = packaging.getString("artifact-extension");
        if (extension == null) extension = "jar";
        if (!extension.matches("[a-z0-9]{1,8}")) {
            throw new JkBuildParseException(
                    displayPath + ".packaging.artifact-extension must be a short lowercase extension — got: "
                            + extension);
        }
        return new PluginDescriptor.Packaging(
                packaging.getString("packager"),
                execMode,
                Boolean.TRUE.equals(packaging.getBoolean("self-contained")),
                Boolean.TRUE.equals(packaging.getBoolean("classes-run")),
                Boolean.TRUE.equals(packaging.getBoolean("main-scan")),
                Boolean.TRUE.equals(packaging.getBoolean("layered-image")),
                extension,
                deployCommand == null ? "" : deployCommand,
                variants);
    }

    // ---- [[contribute.*]] — the declarative layer (P2) --------------------------------------

    private static PluginDescriptor.Contributions parseContributions(
            TomlParseResult result, java.util.Set<String> schemaKeys, String displayPath) {
        TomlTable contribute = result.getTable("contribute");
        if (contribute == null) return PluginDescriptor.Contributions.NONE;

        List<PluginDescriptor.PlatformDependency> platformDeps = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "platform-dependency", displayPath)) {
            String where = displayPath + ".contribute.platform-dependency";
            String coordinate = requireString(t, "coordinate", where);
            Interpolation.validate(coordinate, schemaKeys, where);
            PluginDescriptor.Condition when = parseCondition(t, where);
            if (when instanceof PluginDescriptor.Condition.ClasspathHas) {
                // Platform deps inject at parse time, before resolution — there is no
                // classpath to test yet. Fail at load, not mid-build.
                throw new JkBuildParseException(
                        where + ": classpath-has cannot gate a platform-dependency (it is evaluated"
                                + " before resolution)");
            }
            platformDeps.add(new PluginDescriptor.PlatformDependency(coordinate, when));
        }

        List<PluginDescriptor.CompilerArgs> compilerArgs = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "compiler-args", displayPath)) {
            String where = displayPath + ".contribute.compiler-args";
            List<String> javac = stringList(t, "javac", where);
            List<String> kotlin = stringList(t, "kotlin", where);
            List<String> ksp = stringList(t, "ksp", where);
            for (String arg : javac) Interpolation.validate(arg, schemaKeys, where + ".javac");
            for (String arg : kotlin) Interpolation.validate(arg, schemaKeys, where + ".kotlin");
            for (String arg : ksp) Interpolation.validate(arg, schemaKeys, where + ".ksp");
            compilerArgs.add(new PluginDescriptor.CompilerArgs(javac, kotlin, ksp, parseCondition(t, where)));
        }

        List<PluginDescriptor.KotlinPlugin> kotlinPlugins = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "kotlin-plugin", displayPath)) {
            String where = displayPath + ".contribute.kotlin-plugin";
            String id = requireString(t, "id", where);
            String coordinate = requireString(t, "coordinate", where);
            Interpolation.validate(coordinate, schemaKeys, where);
            List<String> options = stringList(t, "options", where);
            for (String opt : options) Interpolation.validate(opt, schemaKeys, where + ".options");
            kotlinPlugins.add(new PluginDescriptor.KotlinPlugin(id, coordinate, options, parseCondition(t, where)));
        }

        List<PluginDescriptor.PackagerDependency> packagerDeps = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "packager-dependency", displayPath)) {
            String where = displayPath + ".contribute.packager-dependency";
            String artifact = requireString(t, "artifact", where);
            String coordinate = requireString(t, "coordinate", where);
            Interpolation.validate(coordinate, schemaKeys, where);
            PluginDescriptor.Condition when = parseCondition(t, where);
            if (when instanceof PluginDescriptor.Condition.ClasspathHas) {
                throw new JkBuildParseException(
                        where + ": classpath-has cannot gate a packager-dependency (fetch decisions"
                                + " precede packaging classpath evaluation)");
            }
            packagerDeps.add(new PluginDescriptor.PackagerDependency(artifact, coordinate, when));
        }

        List<PluginDescriptor.StepDependency> stepDeps = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "step-dependency", displayPath)) {
            String where = displayPath + ".contribute.step-dependency";
            String artifact = requireString(t, "artifact", where);
            String coordinate = t.getString("coordinate");
            String sdkComponent = t.getString("sdk-component");
            String sdkPath = t.getString("sdk-path");
            if ((coordinate == null) == (sdkComponent == null)) {
                throw new JkBuildParseException(
                        where + " needs exactly one of `coordinate` (a Maven artifact) or"
                                + " `sdk-component` (a provisioned SDK component)");
            }
            if (coordinate != null) Interpolation.validate(coordinate, schemaKeys, where);
            if (sdkComponent != null) Interpolation.validate(sdkComponent, schemaKeys, where);
            if (sdkPath != null && sdkComponent == null) {
                throw new JkBuildParseException(where + ": sdk-path only applies to an sdk-component entry");
            }
            boolean transitive = Boolean.TRUE.equals(t.getBoolean("transitive"));
            if (transitive && coordinate == null) {
                throw new JkBuildParseException(where + ": transitive only applies to a coordinate entry");
            }
            PluginDescriptor.Condition when = parseCondition(t, where);
            if (when instanceof PluginDescriptor.Condition.ClasspathHas) {
                throw new JkBuildParseException(
                        where + ": classpath-has cannot gate a step-dependency (tool fetches are"
                                + " decided from config/facts, not the resolved classpath)");
            }
            stepDeps.add(new PluginDescriptor.StepDependency(artifact, coordinate, transitive, sdkComponent, sdkPath,
                    when));
        }

        List<PluginDescriptor.ProvidedClasspath> provided = new ArrayList<>();
        for (TomlTable t : tableArray(contribute, "provided-classpath", displayPath)) {
            String where = displayPath + ".contribute.provided-classpath";
            String dependency = requireString(t, "dependency", where);
            boolean declared = stepDeps.stream().anyMatch(sd -> sd.artifact().equals(dependency));
            if (!declared) {
                throw new JkBuildParseException(where + ": `" + dependency
                        + "` does not name a declared [[contribute.step-dependency]] artifact");
            }
            provided.add(new PluginDescriptor.ProvidedClasspath(dependency, parseCondition(t, where)));
        }

        // [contribute.resolution] — a single table (not an array): the GMM jvm-environment
        // this plugin's projects select KMP runtime variants for.
        String jvmEnvironment = null;
        TomlTable resolution = contribute.getTable("resolution");
        if (resolution != null) {
            jvmEnvironment = resolution.getString("jvm-environment");
            if (jvmEnvironment != null
                    && !jvmEnvironment.equals("android")
                    && !jvmEnvironment.equals("standard-jvm")) {
                throw new JkBuildParseException(displayPath
                        + ".contribute.resolution: jvm-environment must be `android` or `standard-jvm`"
                        + " — got: " + jvmEnvironment);
            }
        }

        return new PluginDescriptor.Contributions(
                platformDeps, compilerArgs, kotlinPlugins, packagerDeps, stepDeps, provided, jvmEnvironment);
    }

    /**
     * Parse the optional {@code when} inline table into exactly one {@link
     * PluginDescriptor.Condition} — the closed predicate set; two predicates in one {@code when}
     * (or an unknown one) is a load error, never a silently-false condition.
     */
    private static PluginDescriptor.Condition parseCondition(TomlTable entry, String where) {
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
            return new PluginDescriptor.Condition.ClasspathHas(module);
        }
        if (when.contains("config")) {
            String key = when.getString("config");
            String equals = when.getString("equals");
            if (key == null || equals == null) {
                throw new JkBuildParseException(
                        where + ".when config predicate needs both `config = \"<key>\"` and `equals = \"<value>\"`");
            }
            return new PluginDescriptor.Condition.ConfigEquals(key, equals);
        }
        if (when.contains("native-declared")) {
            requireTrue(when, "native-declared", where);
            return new PluginDescriptor.Condition.NativeDeclared();
        }
        if (when.contains("kotlin-project")) {
            requireTrue(when, "kotlin-project", where);
            return new PluginDescriptor.Condition.KotlinProject();
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

    private static Object defaultFor(TomlTable spec, PluginDescriptor.SchemaKey.Type type, String where) {
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
