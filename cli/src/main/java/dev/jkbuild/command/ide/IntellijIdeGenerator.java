// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command.ide;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jline.utils.AttributedStyle;

/**
 * Emits IntelliJ IDEA project files ({@code .idea/} + {@code *.iml}) from the shared {@link IdeModel}.
 *
 * <p>Generated files:
 *
 * <ul>
 *   <li>{@code .idea/modules.xml} — module registry
 *   <li>{@code .idea/misc.xml} — project SDK + language level
 *   <li>{@code .idea/compiler.xml} — per-module bytecode target + annotation processing profiles
 *   <li>{@code .idea/libraries/*.xml} — one per external dependency; sources JARs attached when known
 *   <li>{@code .idea/runConfigurations/*.xml} — for modules with a {@code [project] main} class
 *   <li>{@code <module>/<module>.iml} — per module: source roots, deps, scope
 * </ul>
 *
 * <p>Each module maps to a stable {@code jk-<vendor>-<level>} SDK registered into the IDEs' global
 * {@code jdk.table.xml} (best-effort — a running IDE may clobber the edit on exit, so re-run with it
 * closed).
 */
public final class IntellijIdeGenerator implements IdeGenerator {

    @Override
    public IdeTarget target() {
        return IdeTarget.IDEA;
    }

    @Override
    public List<String> generate(IdeModel model) throws IOException {
        Path wsRoot = model.wsRoot();
        JkBuild rootBuild = model.rootBuild();
        Map<Path, JkBuild> modules = model.modules();
        Map<Path, JkBuild> allModules = model.allModules();
        Map<String, LibDef> allLibs = model.allLibs();
        SdkRef defaultSdk = model.defaultSdk();
        Map<Path, SdkRef> sdkRefs = model.sdkRefs();
        Map<Path, List<Path>> processorJars = model.processorJars();

        Path ideaDir = wsRoot.resolve(".idea");
        Files.createDirectories(ideaDir);

        // Register the SDKs into every IntelliJ/Android Studio jdk.table.xml (best-effort; may be
        // clobbered if an IDE is open — see note below).
        Path ideConfigOverride = model.ideConfigDir();
        IntellijSdkRegistrar registrar = ideConfigOverride != null
                ? IntellijSdkRegistrar.of(
                        List.of(ideConfigOverride.resolve("JetBrains"), ideConfigOverride.resolve("Google")))
                : IntellijSdkRegistrar.shared();
        List<Path> touchedTables = registrar.register(model.sdkEntries());

        int files = 0;
        files += writeModulesXml(ideaDir, wsRoot, allModules);
        files += writeMiscXml(ideaDir, defaultSdk);
        files += writeCompilerXml(ideaDir, allModules, processorJars);

        Path libsDir = ideaDir.resolve("libraries");
        Files.createDirectories(libsDir);
        for (LibDef lib : allLibs.values()) {
            write(libsDir.resolve(lib.fileName() + ".xml"), libraryXml(lib));
            files++;
        }

        Path runDir = ideaDir.resolve("runConfigurations");
        for (Map.Entry<Path, JkBuild> me : modules.entrySet()) {
            String main = me.getValue().project().main();
            if (main != null && !main.isBlank()) {
                Files.createDirectories(runDir);
                String modName = IdeSupport.moduleName(me.getValue());
                write(runDir.resolve(IdeSupport.sanitize(modName) + ".xml"), runConfigXml(modName, main));
                files++;
            }
        }
        // Single-project run config
        if (modules.isEmpty()
                && rootBuild.project().main() != null
                && !rootBuild.project().main().isBlank()) {
            Files.createDirectories(runDir);
            String modName = IdeSupport.moduleName(rootBuild);
            write(runDir.resolve(IdeSupport.sanitize(modName) + ".xml"), runConfigXml(modName, rootBuild.project().main()));
            files++;
        }

        // ---- generate *.iml for each module --------------------------------
        for (Map.Entry<Path, JkBuild> me : allModules.entrySet()) {
            Path moduleDir = me.getKey();
            JkBuild module = me.getValue();
            List<ModuleRef> modRefs = model.siblingRefs().getOrDefault(moduleDir, List.of());
            List<LibRef> libRefs = libRefs(model, moduleDir);
            write(
                    moduleDir.resolve(IdeSupport.moduleName(module) + ".iml"),
                    imlXml(
                            moduleDir,
                            module,
                            modRefs,
                            libRefs,
                            sdkRefs.get(moduleDir),
                            defaultSdk,
                            processorJars.getOrDefault(moduleDir, List.of())));
            files++;
        }

        // ---- presentation ---------------------------------------------------
        Theme t = Theme.active();
        String check = Theme.colorize(Glyphs.CHECK, t.success());

        CliOutput.out(GoalWedge.chipLine(
                Glyphs.CHECK,
                "IDEA",
                GlobalConfig.nerdfont(),
                "The " + Theme.colorize(rootBuild.project().name(), t.focused()) + " project is ready"));

        List<String> items = new ArrayList<>();
        if (!touchedTables.isEmpty()) {
            items.add(check + " Registered the " + Theme.colorize(defaultSdk.sdkName(), t.cyan()) + " JDK");
        }
        items.add(check
                + " Generated "
                + files
                + " project file"
                + (files == 1 ? "" : "s")
                + " in "
                + Theme.colorize(".idea", t.path()));
        boolean ansi = t.isAnsi();
        for (int i = 0; i < items.size(); i++) {
            String connector = i == items.size() - 1
                    ? (ansi ? "╰─ " : "`- ")
                    : (ansi ? "├─ " : "+- ");
            CliOutput.out(" " + (ansi ? Theme.colorize(connector, t.darkGray()) : connector) + items.get(i));
        }

        CliOutput.out();
        CliOutput.out(" "
                + Theme.colorize(Glyphs.BANG + " Note", t.warning())
                + ": You may need to "
                + Theme.colorize("restart your IDE", AttributedStyle.DEFAULT.italic())
                + " for changes to take effect");

        return List.of("IntelliJ: " + files + " file" + (files == 1 ? "" : "s"));
    }

    // =========================================================================
    // Per-module dependency lists (IntelliJ scope vocabulary)
    // =========================================================================

    private static List<LibRef> libRefs(IdeModel model, Path moduleDir) {
        List<LibRef> out = new ArrayList<>();
        for (LibEntry e : model.libEntries().getOrDefault(moduleDir, List.of())) {
            out.add(new LibRef(e.libName(), ideaScope(e.scopes())));
        }
        return out;
    }

    // =========================================================================
    // XML generators
    // =========================================================================

    private static int writeModulesXml(Path ideaDir, Path wsRoot, Map<Path, JkBuild> modules) throws IOException {
        StringBuilder sb = xmlHeader();
        sb.append("<project version=\"4\">\n");
        sb.append("  <component name=\"ProjectModuleManager\">\n");
        sb.append("    <modules>\n");

        for (Map.Entry<Path, JkBuild> me : modules.entrySet()) {
            Path iml = me.getKey().resolve(IdeSupport.moduleName(me.getValue()) + ".iml");
            String rel = "$PROJECT_DIR$/" + wsRoot.relativize(iml).toString().replace('\\', '/');
            sb.append("      <module fileurl=\"file://")
                    .append(rel)
                    .append("\" filepath=\"")
                    .append(rel)
                    .append("\" />\n");
        }
        sb.append("    </modules>\n  </component>\n</project>\n");
        write(ideaDir.resolve("modules.xml"), sb.toString());
        return 1;
    }

    private static int writeMiscXml(Path ideaDir, SdkRef defaultSdk) throws IOException {
        StringBuilder sb = xmlHeader();
        sb.append("<project version=\"4\">\n");
        sb.append("  <component name=\"ProjectRootManager\" version=\"2\"");
        sb.append(" languageLevel=\"JDK_").append(defaultSdk.languageLevel()).append("\"");
        sb.append(" project-jdk-name=\"").append(esc(defaultSdk.sdkName())).append("\"");
        sb.append(" project-jdk-type=\"JavaSDK\">\n");
        // Project-wide compiler output lands under jk's build dir (target/), never IntelliJ's default
        // "out". Each module also overrides this with its own target/classes output (see imlXml), but
        // pointing the project default here too keeps anything not covered by a module out of an
        // "out"/"build" dir.
        sb.append("    <output url=\"file://$PROJECT_DIR$/target\" />\n");
        sb.append("  </component>\n</project>\n");
        write(ideaDir.resolve("misc.xml"), sb.toString());
        return 1;
    }

    private static int writeCompilerXml(Path ideaDir, Map<Path, JkBuild> modules, Map<Path, List<Path>> processorJars)
            throws IOException {
        StringBuilder sb = xmlHeader();
        sb.append("<project version=\"4\">\n");
        sb.append("  <component name=\"CompilerConfiguration\">\n");
        sb.append("    <bytecodeTargetLevel>\n");

        Iterable<Map.Entry<Path, JkBuild>> targets = modules.entrySet();

        for (Map.Entry<Path, JkBuild> me : targets) {
            int release = me.getValue().project().javaRelease();
            if (release > 0) {
                sb.append("      <module name=\"")
                        .append(esc(IdeSupport.moduleName(me.getValue())))
                        .append("\" targetLevel=\"")
                        .append(release)
                        .append("\" />\n");
            }
        }
        sb.append("    </bytecodeTargetLevel>\n");

        boolean anyProcessors = processorJars.values().stream().anyMatch(l -> !l.isEmpty());
        if (anyProcessors) {
            sb.append("    <annotationProcessing>\n");
            for (Map.Entry<Path, JkBuild> me : targets) {
                List<Path> procs = processorJars.getOrDefault(me.getKey(), List.of());
                if (procs.isEmpty()) continue;
                String mod = IdeSupport.moduleName(me.getValue());
                BuildLayout layout = BuildLayout.of(me.getKey(), me.getValue());
                String genRel = me.getKey()
                        .relativize(layout.generatedSourcesDir("annotations"))
                        .toString()
                        .replace('\\', '/');
                String genTestRel = me.getKey()
                        .relativize(layout.generatedSourcesDir("annotations", "test"))
                        .toString()
                        .replace('\\', '/');
                sb.append("      <profile name=\"jk-").append(esc(mod)).append("\" enabled=\"true\">\n");
                sb.append("        <sourceOutputDir name=\"").append(esc(genRel)).append("\" />\n");
                sb.append("        <sourceTestOutputDir name=\"").append(esc(genTestRel)).append("\" />\n");
                sb.append("        <outputRelativeToContentRoot value=\"true\" />\n");
                sb.append("        <module name=\"").append(esc(mod)).append("\" />\n");
                sb.append("        <processorPath useClasspath=\"false\">\n");
                for (Path jar : procs) {
                    sb.append("          <entry name=\"").append(repoJarUrl(jar)).append("\" />\n");
                }
                sb.append("        </processorPath>\n");
                sb.append("      </profile>\n");
            }
            sb.append("    </annotationProcessing>\n");
        }

        sb.append("  </component>\n</project>\n");
        write(ideaDir.resolve("compiler.xml"), sb.toString());
        return 1;
    }

    private static String libraryXml(LibDef lib) {
        StringBuilder sb = xmlHeader();
        sb.append("<component name=\"libraryTable\">\n");
        sb.append("  <library name=\"").append(esc(lib.name())).append("\">\n");
        sb.append("    <CLASSES>\n");
        sb.append("      <root url=\"").append(repoJarUrl(lib.jarPath())).append("\" />\n");
        sb.append("    </CLASSES>\n");
        sb.append("    <JAVADOC />\n");
        sb.append("    <SOURCES>\n");
        if (lib.sourcesPath() != null) {
            sb.append("      <root url=\"").append(repoJarUrl(lib.sourcesPath())).append("\" />\n");
        }
        sb.append("    </SOURCES>\n");
        sb.append("  </library>\n</component>\n");
        return sb.toString();
    }

    private static String imlXml(
            Path moduleDir,
            JkBuild module,
            List<ModuleRef> modRefs,
            List<LibRef> libRefs,
            SdkRef moduleSdk,
            SdkRef defaultSdk,
            List<Path> processorFiles) {
        boolean ownJdk =
                moduleSdk != null && defaultSdk != null && !moduleSdk.sdkName().equals(defaultSdk.sdkName());
        int langLevel = moduleSdk != null ? moduleSdk.languageLevel() : 0;

        StringBuilder sb = xmlHeader();
        sb.append("<module type=\"JAVA_MODULE\" version=\"4\">\n");
        sb.append("  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"false\"");
        if (ownJdk && langLevel > 0) {
            sb.append(" LANGUAGE_LEVEL=\"JDK_").append(langLevel).append("\"");
        }
        sb.append(">\n");

        BuildLayout layout = BuildLayout.of(moduleDir, module);
        sb.append("    <output url=\"file://$MODULE_DIR$/")
                .append(moduleDir.relativize(layout.classesDir()).toString().replace('\\', '/'))
                .append("\" />\n");
        sb.append("    <output-test url=\"file://$MODULE_DIR$/")
                .append(moduleDir.relativize(layout.testClassesDir()).toString().replace('\\', '/'))
                .append("\" />\n");
        sb.append("    <exclude-output />\n");

        sb.append("    <content url=\"file://$MODULE_DIR$\">\n");
        boolean hasTraditional = Files.isDirectory(moduleDir.resolve("src/main/java"))
                || Files.isDirectory(moduleDir.resolve("src/main/kotlin"))
                || Files.isDirectory(moduleDir.resolve("src/test/java"))
                || Files.isDirectory(moduleDir.resolve("src/test/kotlin"));
        if (hasTraditional) {
            addSourceFolder(sb, moduleDir, "src/main/java", false);
            addSourceFolder(sb, moduleDir, "src/main/kotlin", false);
            addResourceFolder(sb, moduleDir, "src/main/resources", false);
            addSourceFolder(sb, moduleDir, "src/test/java", true);
            addSourceFolder(sb, moduleDir, "src/test/kotlin", true);
            addResourceFolder(sb, moduleDir, "src/test/resources", true);
        } else {
            addSourceFolder(sb, moduleDir, "src", false);
            addResourceFolder(sb, moduleDir, "resources", false);
            addSourceFolder(sb, moduleDir, "test", true);
            addResourceFolder(sb, moduleDir, "test-resources", true);
        }

        Path gen = layout.generatedSourcesDir("annotations");
        if (!processorFiles.isEmpty() || Files.isDirectory(gen)) {
            String genRel = moduleDir.relativize(gen).toString().replace('\\', '/');
            sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                    .append(genRel)
                    .append("\" isTestSource=\"false\" generated=\"true\" />\n");
            Path genTest = layout.generatedSourcesDir("annotations", "test");
            String genTestRel = moduleDir.relativize(genTest).toString().replace('\\', '/');
            sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                    .append(genTestRel)
                    .append("\" isTestSource=\"true\" generated=\"true\" />\n");
        }

        sb.append("      <excludeFolder url=\"file://$MODULE_DIR$/target\" />\n");
        sb.append("    </content>\n");

        if (ownJdk) {
            sb.append("    <orderEntry type=\"jdk\" jdkName=\"")
                    .append(esc(moduleSdk.sdkName()))
                    .append("\" jdkType=\"JavaSDK\" />\n");
        } else {
            sb.append("    <orderEntry type=\"inheritedJdk\" />\n");
        }
        sb.append("    <orderEntry type=\"sourceFolder\" forTests=\"false\" />\n");

        for (ModuleRef mr : modRefs) {
            sb.append("    <orderEntry type=\"module\" module-name=\"")
                    .append(esc(mr.name()))
                    .append("\"");
            if ("TEST".equals(mr.scope())) sb.append(" scope=\"TEST\"");
            sb.append(" />\n");
        }

        for (LibRef lr : libRefs) {
            sb.append("    <orderEntry type=\"library\" name=\"")
                    .append(esc(lr.name()))
                    .append("\" level=\"project\"");
            if (!"COMPILE".equals(lr.scope())) sb.append(" scope=\"").append(lr.scope()).append("\"");
            sb.append(" />\n");
        }

        sb.append("  </component>\n</module>\n");
        return sb.toString();
    }

    private static String runConfigXml(String moduleName, String mainClass) {
        StringBuilder sb = xmlHeader();
        sb.append("<component name=\"ProjectRunConfigurationManager\">\n");
        sb.append("  <configuration default=\"false\" name=\"")
                .append(esc(moduleName))
                .append("\" type=\"Application\" factoryName=\"Application\">\n");
        sb.append("    <option name=\"MAIN_CLASS_NAME\" value=\"").append(esc(mainClass)).append("\" />\n");
        sb.append("    <module name=\"").append(esc(moduleName)).append("\" />\n");
        sb.append("    <method v=\"2\">\n");
        sb.append("      <option name=\"Make\" enabled=\"true\" />\n");
        sb.append("    </method>\n");
        sb.append("  </configuration>\n</component>\n");
        return sb.toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void addSourceFolder(StringBuilder sb, Path moduleDir, String relative, boolean test) {
        Path dir = moduleDir.resolve(relative);
        if (!Files.isDirectory(dir)) return;
        sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                .append(relative)
                .append("\" isTestSource=\"")
                .append(test)
                .append("\" />\n");
    }

    private static void addResourceFolder(StringBuilder sb, Path moduleDir, String relative, boolean test) {
        Path dir = moduleDir.resolve(relative);
        if (!Files.isDirectory(dir)) return;
        sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                .append(relative)
                .append("\" type=\"")
                .append(test ? "java-test-resource" : "java-resource")
                .append("\" />\n");
    }

    /** Convert a repo JAR path to IntelliJ's {@code jar://...!/} URL format ({@code $USER_HOME$}-relative). */
    private static String repoJarUrl(Path jar) {
        String absStr = jar.toAbsolutePath().normalize().toString().replace('\\', '/');
        String home = System.getProperty("user.home", "").replace('\\', '/');
        if (!home.isBlank() && absStr.startsWith(home + "/")) {
            absStr = "$USER_HOME$/" + absStr.substring(home.length() + 1);
        }
        return "jar://" + absStr + "!/";
    }

    /** Map jk scopes to the most permissive IntelliJ scope. */
    private static String ideaScope(List<Scope> scopes) {
        Set<Scope> s = EnumSet.noneOf(Scope.class);
        s.addAll(scopes);
        if (s.contains(Scope.EXPORT) || s.contains(Scope.MAIN)) return "COMPILE";
        if (s.contains(Scope.PROVIDED)) return "PROVIDED";
        if (s.contains(Scope.RUNTIME)) return "RUNTIME";
        if (s.contains(Scope.TEST)) return "TEST";
        return "COMPILE";
    }

    private static StringBuilder xmlHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        return sb;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** A module's reference to an external library, tagged with the IntelliJ order-entry scope. */
    private record LibRef(String name, String scope) {}
}
