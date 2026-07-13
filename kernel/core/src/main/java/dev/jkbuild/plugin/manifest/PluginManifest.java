// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A build plugin's declarative manifest ({@code jk-plugin.toml} — build-plugins plan §3.1): the
 * table it owns, its schema, and (from P2 on) its declarative contributions. Parsed by
 * {@link PluginManifests}; the engine never loads plugin classes to evaluate this layer.
 *
 * @param id the plugin id ({@code [plugin] id})
 * @param table the jk.toml table this plugin owns ({@code [plugin] table})
 * @param version the plugin's own version
 * @param jkCompat the jk version range this plugin supports (informational in P1)
 * @param schema typed keys of the owned table, in declaration order
 */
public record PluginManifest(
        String id,
        String table,
        String version,
        String jkCompat,
        Map<String, SchemaKey> schema,
        Contributions contributions,
        Code code,
        Packaging packaging,
        Scaffold scaffold,
        List<GradleImport> gradleImports,
        Map<String, Map<String, SchemaKey>> subSchemas,
        Map<String, SubTable> subTables) {

    public PluginManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(table, "table");
        schema = schema == null || schema.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(schema));
        contributions = contributions == null ? Contributions.NONE : contributions;
        gradleImports = gradleImports == null ? List.of() : List.copyOf(gradleImports);
        subSchemas = subSchemas == null || subSchemas.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(subSchemas));
        subTables = subTables == null || subTables.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(subTables));
    }

    /** Back-compat: the P4/P5 shape (no sub-schemas / sub-tables). */
    public PluginManifest(
            String id,
            String table,
            String version,
            String jkCompat,
            Map<String, SchemaKey> schema,
            Contributions contributions,
            Code code,
            Packaging packaging,
            Scaffold scaffold,
            List<GradleImport> gradleImports) {
        this(id, table, version, jkCompat, schema, contributions, code, packaging, scaffold, gradleImports,
                Map.of(), Map.of());
    }

    /**
     * One {@code [sub-tables.<name>]} declaration: a named nested-table group on the plugin's own
     * table ({@code [android.signing.<entry>]}), each entry validated against the referenced
     * {@code [sub-schema.<schema>]}. Groups are definition tables referenced by name from a
     * schema key ({@code signing = "release"} — flattened by {@code VariantApply}); variant
     * overlays themselves are core's {@code [variants]} section, not a plugin concern.
     */
    public record SubTable(String table, String schema) {
        public SubTable {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(schema, "schema");
        }
    }

    /** Back-compat: a purely declarative manifest (no code layer, no packaging shape). */
    public PluginManifest(
            String id,
            String table,
            String version,
            String jkCompat,
            Map<String, SchemaKey> schema,
            Contributions contributions) {
        this(id, table, version, jkCompat, schema, contributions, null, null, null, List.of());
    }

    /** Back-compat: the P3 shape (no scaffold, no import rules). */
    public PluginManifest(
            String id,
            String table,
            String version,
            String jkCompat,
            Map<String, SchemaKey> schema,
            Contributions contributions,
            Code code,
            Packaging packaging) {
        this(id, table, version, jkCompat, schema, contributions, code, packaging, null, List.of());
    }

    /**
     * The {@code [scaffold]} section (plan row 9 — pure data): what {@code jk new --<flag>}
     * generates. {@code appends} extend the client-rendered base {@code jk.toml}; {@code files}
     * are the sample sources, written only when the user asked for sample code. Templates are
     * resources next to the manifest; conditions are the closed {@code lang} predicate only.
     */
    public record Scaffold(String flag, String description, List<Append> appends, List<FileTemplate> files) {
        public Scaffold {
            Objects.requireNonNull(flag, "flag");
            appends = appends == null ? List.of() : List.copyOf(appends);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** One {@code [[scaffold.append]]}: a jk.toml fragment, gated on the project language. */
    public record Append(String template, String whenLang) {}

    /**
     * One {@code [[scaffold.file]]}: a sample-source template. {@code keepExisting} skips the
     * file when the target already exists (seed files like {@code application.properties}).
     */
    public record FileTemplate(String path, String template, String whenLang, boolean keepExisting) {}

    /**
     * One {@code [[import.gradle-plugin]]} rule (plan row 10): a Gradle plugin id this plugin's
     * table absorbs on {@code jk import}. {@code versionTo} names the config key the Gradle
     * plugin's declared version becomes (null = the id is recognized and consumed with no
     * config); {@code missingVersionWarning} is reported when {@code versionTo} is set but the
     * Gradle build declares the plugin without an inline version.
     */
    public record GradleImport(String id, String versionTo, String missingVersionWarning) {
        public GradleImport {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * The {@code [code]} table: the plugin ships a worker jar carrying step/packager bodies
     * (build-plugins plan §3.2/3.3). {@code worker} is the jar's artifactId ({@code
     * dev.jkbuild:<worker>} for first-party plugins).
     */
    public record Code(String worker, String protocolPrefix) {}

    /**
     * The {@code [packaging]} table — the packager's static artifact descriptor (plan §3.3):
     * what run/install/image/aot-cache consult INSTEAD of framework-presence branches, readable
     * without executing plugin code.
     *
     * @param packager the code packager's name replacing the main artifact (null = none)
     * @param execMode {@code jar} (self-contained executable jar), {@code classpath}, or {@code binary}
     * @param selfContained install links one artifact, no dependency jars
     * @param classesRun run/dev exec from the classes dir (the packaged layout is not
     *     classpath-able, e.g. Boot's BOOT-INF nesting)
     * @param mainScan the entry point may be discovered by scanning compiled classes when the
     *     project declares none
     * @param layeredImage container images split release/snapshot dependency layers and explode
     *     app classes
     */
    public record Packaging(
            String packager,
            String execMode,
            boolean selfContained,
            boolean classesRun,
            boolean mainScan,
            boolean layeredImage,
            String artifactExtension,
            String deployVerb,
            List<Variant> variants) {

        /** Back-compat: no variants. */
        public Packaging(
                String packager,
                String execMode,
                boolean selfContained,
                boolean classesRun,
                boolean mainScan,
                boolean layeredImage,
                String artifactExtension,
                String deployVerb) {
            this(
                    packager, execMode, selfContained, classesRun, mainScan, layeredImage, artifactExtension,
                    deployVerb, List.of());
        }

        /**
         * A config-conditional descriptor override ({@code [[packaging.variant]]} — an [android]
         * library packages an AAR while an app packages an APK): the first variant whose
         * {@code when} holds replaces the base descriptor wholesale. Conditions are the same
         * closed predicate set contributions use (config-shaped only — packaging resolves before
         * any classpath exists).
         */
        public record Variant(Condition when, Packaging packaging) {}

        /** The effective descriptor for {@code config}: the first matching variant, else this. */
        public Packaging resolve(dev.jkbuild.plugin.PluginConfig config) {
            for (Variant v : variants) {
                if (v.when() instanceof Condition.ConfigEquals c
                        && c.equals().equals(String.valueOf(config.values().get(c.key())))) {
                    return v.packaging();
                }
            }
            return this;
        }

        /** Back-compat: the pre-deploy-verb descriptor shape. */
        public Packaging(
                String packager,
                String execMode,
                boolean selfContained,
                boolean classesRun,
                boolean mainScan,
                boolean layeredImage,
                String artifactExtension) {
            this(packager, execMode, selfContained, classesRun, mainScan, layeredImage, artifactExtension, "");
        }

        /** Back-compat: the P3 descriptor shape (jar-extension artifact). */
        public Packaging(
                String packager,
                String execMode,
                boolean selfContained,
                boolean classesRun,
                boolean mainScan,
                boolean layeredImage) {
            this(packager, execMode, selfContained, classesRun, mainScan, layeredImage, "jar", "");
        }
    }

    /**
     * The declarative layer's build contributions (plan §3.1): pure data evaluated engine-side
     * with zero plugin code — BOM auto-import, default compiler args, Kotlin compiler plugins.
     * Each entry may carry one {@link Condition}; anything conditional beyond the closed
     * predicate set belongs in a code hook (P3), never here.
     */
    public record Contributions(
            List<PlatformDependency> platformDependencies,
            List<CompilerArgs> compilerArgs,
            List<KotlinPlugin> kotlinPlugins,
            List<PackagerDependency> packagerDependencies,
            List<StepDependency> stepDependencies,
            List<ProvidedClasspath> providedClasspath,
            /**
             * {@code [contribute.resolution] jvm-environment} — the Gradle Module Metadata
             * {@code org.gradle.jvm.environment} this plugin's projects resolve KMP variants
             * for ({@code "android"}); null when unset (standard-jvm).
             */
            String jvmEnvironment) {

        public static final Contributions NONE =
                new Contributions(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);

        public Contributions {
            platformDependencies = platformDependencies == null ? List.of() : List.copyOf(platformDependencies);
            compilerArgs = compilerArgs == null ? List.of() : List.copyOf(compilerArgs);
            kotlinPlugins = kotlinPlugins == null ? List.of() : List.copyOf(kotlinPlugins);
            packagerDependencies = packagerDependencies == null ? List.of() : List.copyOf(packagerDependencies);
            stepDependencies = stepDependencies == null ? List.of() : List.copyOf(stepDependencies);
            providedClasspath = providedClasspath == null ? List.of() : List.copyOf(providedClasspath);
        }

        /** Back-compat: the P6 contribution set (no resolution environment). */
        public Contributions(
                List<PlatformDependency> platformDependencies,
                List<CompilerArgs> compilerArgs,
                List<KotlinPlugin> kotlinPlugins,
                List<PackagerDependency> packagerDependencies,
                List<StepDependency> stepDependencies,
                List<ProvidedClasspath> providedClasspath) {
            this(platformDependencies, compilerArgs, kotlinPlugins, packagerDependencies, stepDependencies,
                    providedClasspath, null);
        }

        /** Back-compat: the P3 contribution set (no step dependencies). */
        public Contributions(
                List<PlatformDependency> platformDependencies,
                List<CompilerArgs> compilerArgs,
                List<KotlinPlugin> kotlinPlugins,
                List<PackagerDependency> packagerDependencies) {
            this(platformDependencies, compilerArgs, kotlinPlugins, packagerDependencies, List.of(), List.of());
        }

        /** Back-compat: the P2 contribution set (no packager dependencies). */
        public Contributions(
                List<PlatformDependency> platformDependencies,
                List<CompilerArgs> compilerArgs,
                List<KotlinPlugin> kotlinPlugins) {
            this(platformDependencies, compilerArgs, kotlinPlugins, List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return platformDependencies.isEmpty()
                    && compilerArgs.isEmpty()
                    && kotlinPlugins.isEmpty()
                    && packagerDependencies.isEmpty()
                    && stepDependencies.isEmpty()
                    && providedClasspath.isEmpty();
        }
    }

    /**
     * {@code [[contribute.packager-dependency]]} — an extra artifact the packager needs (Boot's
     * loader, jarmode-tools), fetched engine-side and handed to the packager by {@code artifact}
     * name. Fetch-only: never injected into the project's dependency graph.
     */
    public record PackagerDependency(String artifact, String coordinate, Condition when) {}

    /**
     * {@code [[contribute.step-dependency]]} — a tool artifact a step (or verb) needs, fetched /
     * provisioned engine-side and handed to the worker by {@code artifact} name. Exactly one
     * source:
     *
     * <ul>
     *   <li>{@code coordinate} — a Maven artifact, optionally with a classifier
     *       ({@code group:artifact:version:classifier}; {@code ${host.os}} selects per-OS
     *       natives). {@code transitive = true} resolves the full runtime closure into a lib
     *       directory instead of one jar (JVM tools with real dependency graphs, e.g.
     *       manifest-merger).
     *   <li>{@code sdk-component} — a provisioned SDK component in sdkmanager spelling
     *       ({@code platforms;android-28}, {@code platform-tools}); {@code sdk-path} names a
     *       file inside it ({@code android.jar}, {@code adb}) — absent, the component directory
     *       itself is handed over. The component {@code root} is the SDK root.
     * </ul>
     *
     * <p>Fetch-only: never injected into the project's dependency graph.
     */
    public record StepDependency(
            String artifact, String coordinate, boolean transitive, String sdkComponent, String sdkPath,
            Condition when) {

        public StepDependency(String artifact, String coordinate, Condition when) {
            this(artifact, coordinate, false, null, null, when);
        }
    }

    /**
     * {@code [[contribute.provided-classpath]]} — a declared step-dependency (named by its
     * {@code artifact}) that also joins the module's COMPILE classpath, PROVIDED-posture:
     * javac/kotlinc see it, the runtime/packaging never does (an Android platform jar is the
     * canonical case — steps take it explicitly via {@code --lib}-style flags).
     */
    public record ProvidedClasspath(String dependency, Condition when) {}

    /**
     * {@code [[contribute.platform-dependency]]}: a BOM-style platform-scope dependency,
     * {@code group:artifact:version} with {@code ${…}} interpolation. Injected at parse time —
     * before resolution — so its condition may not use {@code classpath-has}.
     */
    public record PlatformDependency(String coordinate, Condition when) {}

    /**
     * {@code [[contribute.compiler-args]]}: default args for javac / kotlinc, plus {@code ksp} —
     * KSP processor options as {@code key=value} strings (Hilt's superclass-validation toggle,
     * Room's schema location). Each arg is added only when the user's own args don't already
     * carry it (user wins).
     */
    public record CompilerArgs(List<String> javac, List<String> kotlin, List<String> ksp, Condition when) {
        public CompilerArgs {
            javac = javac == null ? List.of() : List.copyOf(javac);
            kotlin = kotlin == null ? List.of() : List.copyOf(kotlin);
            ksp = ksp == null ? List.of() : List.copyOf(ksp);
        }

        /** Back-compat (pre-{@code ksp}). */
        public CompilerArgs(List<String> javac, List<String> kotlin, Condition when) {
            this(javac, kotlin, List.of(), when);
        }
    }

    /**
     * {@code [[contribute.kotlin-plugin]]}: a Kotlin compiler plugin — BTA plugin id, jar
     * coordinate ({@code ${kotlin.version}} keeps it lockstep with the compiler), plugin options.
     */
    public record KotlinPlugin(String id, String coordinate, List<String> options, Condition when) {
        public KotlinPlugin {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * The CLOSED condition vocabulary (plan §3.1): four predicates, no expression language —
     * a manifest that needs more writes a code hook instead. Exactly one predicate per
     * {@code when} table.
     */
    public sealed interface Condition {

        /** {@code when = { classpath-has = "group:artifact" }} — the module is on the resolved classpath. */
        record ClasspathHas(String module) implements Condition {}

        /** {@code when = { config = "key", equals = "value" }} — the owned table's key equals a value. */
        record ConfigEquals(String key, String equals) implements Condition {}

        /** {@code when = { native-declared = true }} — the project declares {@code [native]}. */
        record NativeDeclared() implements Condition {}

        /** {@code when = { kotlin-project = true }} — the project declares Kotlin. */
        record KotlinProject() implements Condition {}
    }

    /**
     * One schema key: its type ({@code string | bool | int | string-list}), whether the table
     * must declare it, and the value applied when absent ({@code null} = stay absent — the
     * tri-state pattern). {@code example} and {@code hint} feed the required-key error message
     * so schema-driven validation keeps the hand-written diagnostics' quality.
     */
    public record SchemaKey(
            String name, Type type, boolean required, Object defaultValue, String example, String hint,
            boolean secret) {

        /** Back-compat: a non-secret key. */
        public SchemaKey(String name, Type type, boolean required, Object defaultValue, String example, String hint) {
            this(name, type, required, defaultValue, example, hint, false);
        }

        public enum Type {
            STRING,
            BOOL,
            INT,
            STRING_LIST;

            public static Type parse(String raw, String where) {
                return switch (raw) {
                    case "string" -> STRING;
                    case "bool" -> BOOL;
                    case "int" -> INT;
                    case "string-list" -> STRING_LIST;
                    default -> throw new IllegalArgumentException(
                            where + ": unknown schema type `" + raw + "` (string|bool|int|string-list)");
                };
            }
        }

        /** The default's runtime shape, normalized to the {@code PluginConfig} value vocabulary. */
        public Object normalizedDefault() {
            if (defaultValue instanceof List<?> l) return List.copyOf(l.stream().map(String::valueOf).toList());
            return defaultValue;
        }
    }
}
