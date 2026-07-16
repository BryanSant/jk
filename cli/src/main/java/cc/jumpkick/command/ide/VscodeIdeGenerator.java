// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jline.utils.AttributedStyle;

/**
 * Emits VS Code project configuration for the <b>redhat.java</b> language server (Eclipse JDT-LS)
 * plus <b>vscjava.vscode-java-debug</b>.
 *
 * <p>Rather than the fragile "invisible project" {@code java.project.*} settings (single output
 * path, no live cross-module source deps), it generates native <b>Eclipse project metadata</b> —
 * {@code .project} / {@code .classpath} / {@code .settings/org.eclipse.jdt.core.prefs} per module —
 * which JDT-LS consumes directly and which supports per-module output dirs, live sibling <i>source</i>
 * references, test/main split, and per-module JDK levels. Workspace-wide bits (JDK runtimes, LS
 * vmargs, disabling the Maven/Gradle importers) go in {@code .vscode/settings.json}.
 *
 * <p>JDT-LS compiles into {@code target/jdt/classes/{main,test}} (see {@link BuildLayout#jdtClassesDir()})
 * — isolated from jk's own {@code target/classes} so the two incremental compilers never fight.
 */
public final class VscodeIdeGenerator implements IdeGenerator {

    /** Eclipse JRE container VM type — JDT-LS binds the {@code JavaSE-<n>} EE to a configured runtime. */
    private static final String STANDARD_VM_TYPE = "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType";

    private static final String JRE_CONTAINER = "org.eclipse.jdt.launching.JRE_CONTAINER";

    /** Highest Java execution-environment name we assume JDT-LS knows; newer levels clamp to this. */
    private static final int MAX_KNOWN_EE = 25;

    @Override
    public IdeTarget target() {
        return IdeTarget.VSCODE;
    }

    @Override
    public List<String> generate(IdeModel model) throws IOException {
        // Eclipse project names are workspace-global and cross-module deps reference them by name —
        // reject duplicates rather than emit a silently-broken classpath.
        assertUniqueNames(model);

        int files = 0;
        for (Map.Entry<Path, IdeModule> me : model.allModules().entrySet()) {
            files += writeModule(model, me.getKey(), me.getValue());
        }

        Path vscodeDir = model.wsRoot().resolve(".vscode");
        Files.createDirectories(vscodeDir);
        write(vscodeDir.resolve("settings.json"), settingsJson(model));
        files++;
        String launch = launchJson(model);
        if (launch != null) {
            write(vscodeDir.resolve("launch.json"), launch);
            files++;
        }
        write(vscodeDir.resolve("extensions.json"), extensionsJson());
        files++;

        // ---- presentation ---------------------------------------------------
        Theme t = Theme.active();
        String check = Theme.colorize(Glyphs.CHECK, t.success());
        CliOutput.out(PipelineWedge.chipLine(
                Glyphs.CHECK,
                "Code",
                GlobalConfig.nerdfont(),
                "The " + Theme.colorize(model.rootName(), t.focused()) + " project is ready"));

        List<String> items = new ArrayList<>();
        items.add(check + " Generated " + files + " project file" + (files == 1 ? "" : "s") + " for "
                + Theme.colorize("redhat.java", t.cyan()));
        boolean ansi = t.isAnsi();
        for (int i = 0; i < items.size(); i++) {
            String connector = i == items.size() - 1 ? (ansi ? "╰─ " : "`- ") : (ansi ? "├─ " : "+- ");
            CliOutput.out(" " + (ansi ? Theme.colorize(connector, t.darkGray()) : connector) + items.get(i));
        }
        CliOutput.out();
        CliOutput.out(" "
                + Theme.colorize(Glyphs.BANG + " Note", t.warning())
                + ": run "
                + Theme.colorize("Java: Clean Java Language Server Workspace", AttributedStyle.DEFAULT.italic())
                + " if VS Code was already open");

        return List.of("VS Code: " + files + " file" + (files == 1 ? "" : "s"));
    }

    // =========================================================================
    // Per-module Eclipse metadata
    // =========================================================================

    private static int writeModule(IdeModel model, Path moduleDir, IdeModule module) throws IOException {
        String name = module.name();
        SdkRef sdk = model.sdkRefs().get(moduleDir);
        int level = sdk != null ? sdk.languageLevel() : module.javaRelease();
        if (level <= 0) level = MAX_KNOWN_EE;

        write(moduleDir.resolve(".project"), dotProject(name));
        write(moduleDir.resolve(".classpath"), dotClasspath(model, moduleDir, module, level));
        Path settings = moduleDir.resolve(".settings");
        Files.createDirectories(settings);
        write(settings.resolve("org.eclipse.jdt.core.prefs"), corePrefs(level));
        return 3;
    }

    private static String dotProject(String name) {
        StringBuilder sb = xmlHeader();
        sb.append("<projectDescription>\n");
        sb.append("  <name>").append(esc(name)).append("</name>\n");
        sb.append("  <comment></comment>\n");
        sb.append("  <projects></projects>\n");
        sb.append("  <buildSpec>\n");
        sb.append("    <buildCommand>\n");
        sb.append("      <name>org.eclipse.jdt.core.javabuilder</name>\n");
        sb.append("      <arguments></arguments>\n");
        sb.append("    </buildCommand>\n");
        sb.append("  </buildSpec>\n");
        sb.append("  <natures>\n");
        sb.append("    <nature>org.eclipse.jdt.core.javanature</nature>\n");
        sb.append("  </natures>\n");
        sb.append("</projectDescription>\n");
        return sb.toString();
    }

    private static String dotClasspath(IdeModel model, Path moduleDir, IdeModule module, int level) {
        String outMain = rel(moduleDir, module.jdtClassesDir());
        String outTest = rel(moduleDir, module.jdtTestClassesDir());

        StringBuilder sb = xmlHeader();
        sb.append("<classpath>\n");

        // Source / resource folders, inferred by directory existence exactly as the IntelliJ
        // generator does (traditional Maven layout wins over the simple layout).
        boolean hasTraditional = Files.isDirectory(moduleDir.resolve("src/main/java"))
                || Files.isDirectory(moduleDir.resolve("src/main/kotlin"))
                || Files.isDirectory(moduleDir.resolve("src/test/java"))
                || Files.isDirectory(moduleDir.resolve("src/test/kotlin"));
        if (hasTraditional) {
            srcEntry(sb, moduleDir, "src/main/java", false, outMain);
            srcEntry(sb, moduleDir, "src/main/kotlin", false, outMain);
            srcEntry(sb, moduleDir, "src/main/resources", false, outMain);
            srcEntry(sb, moduleDir, "src/test/java", true, outTest);
            srcEntry(sb, moduleDir, "src/test/kotlin", true, outTest);
            srcEntry(sb, moduleDir, "src/test/resources", true, outTest);
        } else {
            srcEntry(sb, moduleDir, "src", false, outMain);
            srcEntry(sb, moduleDir, "resources", false, outMain);
            srcEntry(sb, moduleDir, "test", true, outTest);
            srcEntry(sb, moduleDir, "test-resources", true, outTest);
        }

        // Annotation-processor output roots (created so JDT's classpath stays valid), emitted when
        // this module declares processors or the dir already exists — mirrors the IntelliJ generator.
        List<Path> procs = model.processorJars().getOrDefault(moduleDir, List.of());
        Path gen = module.generatedSourcesDir();
        Path genTest = module.generatedTestSourcesDir();
        if (!procs.isEmpty() || Files.isDirectory(gen)) {
            try {
                Files.createDirectories(gen);
                Files.createDirectories(genTest);
            } catch (IOException ignored) {
                // best-effort; JDT tolerates a missing dir with a warning
            }
            srcEntryRaw(sb, rel(moduleDir, gen), false, outMain);
            srcEntryRaw(sb, rel(moduleDir, genTest), true, outTest);
        }

        // Live cross-module source dependencies.
        for (ModuleRef mr : model.siblingRefs().getOrDefault(moduleDir, List.of())) {
            sb.append("  <classpathentry combineaccessrules=\"false\" kind=\"src\" path=\"/")
                    .append(esc(mr.name()))
                    .append("\"");
            if ("TEST".equals(mr.scope())) sb.append(" ").append(testAttr());
            sb.append("/>\n");
        }

        // JRE container bound to the module's execution environment.
        sb.append("  <classpathentry kind=\"con\" path=\"")
                .append(JRE_CONTAINER)
                .append("/")
                .append(STANDARD_VM_TYPE)
                .append("/")
                .append(eeName(level))
                .append("\"/>\n");

        // External libraries (absolute paths — the repo lives outside the workspace).
        for (LibEntry entry : model.libEntries().getOrDefault(moduleDir, List.of())) {
            LibDef def = model.allLibs().get(entry.libName());
            if (def == null || def.jarPath() == null) continue;
            String jar = abs(def.jarPath());
            boolean test = isTestOnly(entry.scopes());
            sb.append("  <classpathentry kind=\"lib\" path=\"").append(esc(jar)).append("\"");
            if (def.sourcesPath() != null) {
                sb.append(" sourcepath=\"").append(esc(abs(def.sourcesPath()))).append("\"");
            }
            if (test) {
                sb.append(">\n    <attributes>\n      <attribute name=\"test\" value=\"true\"/>\n")
                        .append("    </attributes>\n  </classpathentry>\n");
            } else {
                sb.append("/>\n");
            }
        }

        // Default output.
        sb.append("  <classpathentry kind=\"output\" path=\"").append(esc(outMain)).append("\"/>\n");
        sb.append("</classpath>\n");
        return sb.toString();
    }

    private static String corePrefs(int level) {
        String v = eeLevelString(level);
        return "eclipse.preferences.version=1\n"
                + "org.eclipse.jdt.core.compiler.codegen.targetPlatform=" + v + "\n"
                + "org.eclipse.jdt.core.compiler.compliance=" + v + "\n"
                + "org.eclipse.jdt.core.compiler.release=enabled\n"
                + "org.eclipse.jdt.core.compiler.source=" + v + "\n";
    }

    private static void srcEntry(StringBuilder sb, Path moduleDir, String relative, boolean test, String output) {
        if (!Files.isDirectory(moduleDir.resolve(relative))) return;
        srcEntryRaw(sb, relative, test, output);
    }

    private static void srcEntryRaw(StringBuilder sb, String path, boolean test, String output) {
        sb.append("  <classpathentry kind=\"src\" path=\"").append(esc(path)).append("\"");
        sb.append(" output=\"").append(esc(output)).append("\"");
        if (test) sb.append(" ").append(testAttr());
        sb.append("/>\n");
    }

    private static String testAttr() {
        return "test=\"true\"";
    }

    // =========================================================================
    // .vscode/*.json
    // =========================================================================

    private static String settingsJson(IdeModel model) {
        // One runtime per distinct language level, keyed by execution-environment name.
        Map<Integer, SdkRef> byLevel = new LinkedHashMap<>();
        for (SdkRef r : model.sdkRefs().values()) byLevel.putIfAbsent(r.languageLevel(), r);
        byLevel.putIfAbsent(model.defaultSdk().languageLevel(), model.defaultSdk());
        int defaultLevel = model.defaultSdk().languageLevel();

        StringBuilder rt = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, SdkRef> e : byLevel.entrySet()) {
            if (!first) rt.append(",\n");
            first = false;
            rt.append("    {\n");
            rt.append("      \"name\": \"").append(eeName(e.getKey())).append("\",\n");
            rt.append("      \"path\": \"").append(jsonEsc(abs(e.getValue().javaHome()))).append("\"");
            if (e.getKey() == defaultLevel) rt.append(",\n      \"default\": true");
            rt.append("\n    }");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"editor.semanticHighlighting.enabled\": true,\n");
        // The JDK that RUNS the language server must be 21+; prefer the project default, else the
        // highest available >= 21. Omit the key (fall back to JAVA_HOME / bundled JRE) if none.
        Path lsHome = languageServerHome(byLevel, defaultLevel);
        if (lsHome != null) {
            sb.append("  \"java.jdt.ls.java.home\": \"").append(jsonEsc(abs(lsHome))).append("\",\n");
        }
        sb.append("  \"java.configuration.runtimes\": [\n").append(rt).append("\n  ],\n");
        sb.append("  \"java.import.maven.enabled\": false,\n");
        sb.append("  \"java.import.gradle.enabled\": false,\n");
        sb.append("  \"java.server.launchMode\": \"Standard\",\n");
        sb.append("  \"java.autobuild.enabled\": true,\n");
        sb.append("  \"java.completion.guessMethodArguments\": \"insertBestGuessedArguments\",\n");
        sb.append("  \"java.inlayHints.parameterNames.enabled\": \"all\",\n");
        sb.append("  \"java.compile.nullAnalysis.mode\": \"automatic\",\n");
        sb.append("  \"java.jdt.ls.vmargs\": \"-XX:+UseParallelGC -XX:GCTimeRatio=4 "
                + "-XX:AdaptiveSizePolicyWeight=90 -Dsun.zip.disableMemoryMapping=true -Xmx2G -Xms100m\"\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static Path languageServerHome(Map<Integer, SdkRef> byLevel, int defaultLevel) {
        SdkRef def = byLevel.get(defaultLevel);
        if (def != null && defaultLevel >= 21) return def.javaHome();
        SdkRef best = null;
        for (Map.Entry<Integer, SdkRef> e : byLevel.entrySet()) {
            if (e.getKey() >= 21 && (best == null || e.getKey() > best.languageLevel())) best = e.getValue();
        }
        return best != null ? best.javaHome() : null;
    }

    private static String launchJson(IdeModel model) {
        List<String> configs = new ArrayList<>();
        Map<Path, IdeModule> targets =
                model.modules().isEmpty() ? model.allModules() : model.modules();
        for (IdeModule module : targets.values()) {
            String main = module.mainClass();
            if (main == null) continue;
            String name = module.name();
            configs.add("    {\n"
                    + "      \"type\": \"java\",\n"
                    + "      \"name\": \"" + jsonEsc(name) + "\",\n"
                    + "      \"request\": \"launch\",\n"
                    + "      \"mainClass\": \"" + jsonEsc(main) + "\",\n"
                    + "      \"projectName\": \"" + jsonEsc(name) + "\"\n"
                    + "    }");
        }
        if (configs.isEmpty()) return null;
        return "{\n  \"version\": \"0.2.0\",\n  \"configurations\": [\n"
                + String.join(",\n", configs) + "\n  ]\n}\n";
    }

    private static String extensionsJson() {
        return "{\n  \"recommendations\": [\n"
                + "    \"redhat.java\",\n"
                + "    \"vscjava.vscode-java-debug\"\n"
                + "  ]\n}\n";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void assertUniqueNames(IdeModel model) {
        Set<String> seen = new LinkedHashSet<>();
        for (IdeModule module : model.allModules().values()) {
            String name = module.name();
            if (!seen.add(name)) {
                throw new IdeSupport.IdeException(
                        2, "duplicate module name '" + name + "' — Eclipse project names must be unique");
            }
        }
    }

    /** True when TEST is the only classpath-relevant scope (map to Eclipse's {@code test} attribute). */
    private static boolean isTestOnly(List<Scope> scopes) {
        if (!scopes.contains(Scope.TEST)) return false;
        for (Scope s : scopes) {
            if (s == Scope.MAIN || s == Scope.EXPORT || s == Scope.PROVIDED || s == Scope.RUNTIME) return false;
        }
        return true;
    }

    /** Eclipse execution-environment name for a Java level (e.g. {@code JavaSE-25}, {@code JavaSE-1.8}). */
    private static String eeName(int level) {
        return "JavaSE-" + eeLevelString(level);
    }

    private static String eeLevelString(int level) {
        int l = Math.min(level, MAX_KNOWN_EE);
        return l <= 8 ? "1." + l : String.valueOf(l);
    }

    /** Module-relative, forward-slash path. */
    private static String rel(Path moduleDir, Path target) {
        return moduleDir.relativize(target).toString().replace('\\', '/');
    }

    /** Absolute, forward-slash path. */
    private static String abs(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static StringBuilder xmlHeader() {
        return new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
