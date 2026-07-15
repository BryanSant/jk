// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.boot;

import build.jumpkick.plugin.PluginConfig;
import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.build.BuildContext;
import build.jumpkick.plugin.build.BuildExtension;
import build.jumpkick.plugin.build.BuildPluginHarness;
import build.jumpkick.plugin.build.In;
import build.jumpkick.plugin.build.PackageContext;
import build.jumpkick.plugin.build.PackageExtension;
import build.jumpkick.plugin.build.PackageIo;
import build.jumpkick.plugin.build.Phase;
import build.jumpkick.plugin.build.StepExec;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The spring-boot build plugin's code layer (build-plugins plan §4 P3) — the blueprint every
 * build plugin copies. Two registrations:
 *
 * <ul>
 *   <li><b>spring-aot</b> step (registered only when AOT is on: explicit {@code aot = true}, or
 *       auto when the project declares {@code [native]}) — forks Boot's {@code
 *       SpringApplicationAotProcessor} against the production classpath, then compiles the
 *       generated sources; contributes the generated classes + GraalVM hint resources to
 *       packaging and the native-image classpath.
 *   <li><b>boot-jar</b> packager — assembles the executable Boot layout (loader at the root, app
 *       under {@code BOOT-INF/classes}, STORED nested libs, index files) via {@link
 *       BootJarPackager}, replacing the main artifact.
 * </ul>
 *
 * <p>Note what is <em>absent</em>: action keys, cache lookups, CAS paths, jk directory layout —
 * the engine fingerprints the declared inputs and skips these bodies entirely on a hit.
 */
public final class SpringBootPlugin implements Plugin, BuildExtension, PackageExtension {

    private static final String AOT_PROCESSOR = "org.springframework.boot.SpringApplicationAotProcessor";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-spring-boot", "##JKSB:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        PluginConfig boot = ctx.config();
        // AOT gate (spring-boot plan §3.4): explicit aot = true, or auto when [native] is
        // declared — registration-time code is exactly where this conditional shape belongs.
        if (boot.bool("aot").orElse(ctx.project().nativeDeclared())) {
            ctx.named("spring-aot")
                    .after(Phase.COMPILE)
                    .before(Phase.PACKAGE)
                    .inputs(In.classes(), In.runtimeClasspath(), In.config())
                    .outputs("classes", "resources", "sources")
                    .contributesClasses("classes")
                    .contributesResources("resources")
                    .run(SpringBootPlugin::runAot);
        }
    }

    @Override
    public void pack(PackageContext ctx) {
        ctx.inputs(In.classes(), In.runtimeEntries(), In.stepOutput("spring-aot"), In.config())
                .produce("boot-jar", SpringBootPlugin::produceBootJar);
    }

    // ---- spring-aot step --------------------------------------------------------------------

    private static void runAot(StepExec exec) throws Exception {
        String startClass = exec.project().mainClass();
        if (startClass == null || startClass.isBlank()) {
            throw new IOException("no application main class — Spring AOT needs the entry point");
        }
        exec.label("Spring AOT processing (" + startClass + ")");
        Path sources = exec.outputDir("sources");
        Path classes = exec.outputDir("classes");
        Path resources = exec.outputDir("resources");

        List<Path> classpath = new ArrayList<>();
        classpath.add(exec.classesDir());
        classpath.addAll(exec.runtimeClasspath());

        StepExec.ToolRun.Result run = exec.java()
                .classpath(classpath)
                .mainClass(AOT_PROCESSOR)
                .arg(startClass)
                .arg(sources.toString())
                .arg(resources.toString())
                .arg(classes.toString())
                .arg(exec.project().group())
                .arg(exec.project().name())
                .args(exec.config().stringList("aot-args"))
                .cwd(exec.moduleDir())
                .run();
        if (run.exit() != 0) {
            throw new IOException("Spring AOT processing failed (exit " + run.exit() + "):\n" + tail(run.output()));
        }

        // Compile the generated sources into the generated-classes dir (alongside the
        // processor-emitted proxy classes), against app classes + the same classpath.
        List<Path> aotSources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sources)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(aotSources::add);
        }
        if (!aotSources.isEmpty()) {
            List<Path> compileCp = new ArrayList<>(classpath);
            compileCp.add(classes);
            StepExec.ToolRun javac = exec.tool("javac")
                    .arg("--release")
                    .arg(Integer.toString(exec.project().javaRelease()))
                    .classpath(compileCp)
                    .arg("-parameters")
                    .arg("-d")
                    .arg(classes.toString());
            for (Path src : aotSources) javac.arg(src.toString());
            StepExec.ToolRun.Result compile = javac.run();
            if (compile.exit() != 0) {
                throw new IOException("compiling Spring AOT generated sources failed:\n" + tail(compile.output()));
            }
        }
    }

    /** The last ~40 lines — context-refresh stacks are long; the cause is at the bottom. */
    private static String tail(String output) {
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - 40);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    // ---- boot-jar packager ------------------------------------------------------------------

    private static void produceBootJar(PackageIo io) throws Exception {
        PluginConfig boot = io.config();
        String bootVersion = boot.string("version");
        String startClass = io.project().mainClass();
        if (startClass == null || startClass.isBlank()) {
            throw new IOException("no application main class — the boot jar needs a Start-Class");
        }
        Path loaderJar = io.extra("spring-boot-loader")
                .orElseThrow(() -> new IOException("spring-boot-loader artifact missing from the packager inputs"));

        List<BootJarPackager.Lib> libs = new ArrayList<>();
        for (PackageIo.RuntimeEntry entry : io.runtimeEntries()) {
            libs.add(new BootJarPackager.Lib(entry.fileName(), entry.jar(), entry.snapshot()));
        }
        if (boot.bool("include-tools", true)) {
            Path tools = io.extra("spring-boot-jarmode-tools")
                    .orElseThrow(
                            () -> new IOException("spring-boot-jarmode-tools artifact missing from the packager"
                                    + " inputs (include-tools is on)"));
            libs.add(new BootJarPackager.Lib("spring-boot-jarmode-tools-" + bootVersion + ".jar", tools, false));
        }

        // Build-info (opt-in): the coordinates BuildProperties surfaces via /actuator/info.
        // No build.time — reproducibility wins; Boot handles its absence.
        Map<String, String> buildInfo = boot.bool("build-info", false)
                ? Map.of(
                        "group", io.project().group(),
                        "artifact", io.project().name(),
                        "name", io.project().name(),
                        "version", io.project().version())
                : Map.of();
        byte[] sbom = null;
        Path sbomFile = io.extra("sbom").orElse(null);
        if (sbomFile != null && Files.isRegularFile(sbomFile)) sbom = Files.readAllBytes(sbomFile);

        // AOT output roots in merge order: generated classes, then hint resources.
        List<Path> aotDirs = new ArrayList<>();
        io.stepOutput("spring-aot").ifPresent(root -> {
            for (String rel : List.of("classes", "resources")) {
                Path dir = root.resolve(rel);
                if (Files.isDirectory(dir)) aotDirs.add(dir);
            }
        });

        io.label("package " + io.artifactPath().getFileName() + " (boot)");
        Map<String, String> attributes = new LinkedHashMap<>(io.project().manifest());
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        io.classesDir(),
                        libs,
                        loaderJar,
                        io.artifactPath(),
                        startClass,
                        bootVersion,
                        attributes,
                        buildInfo,
                        sbom,
                        aotDirs,
                        0L));
    }
}
