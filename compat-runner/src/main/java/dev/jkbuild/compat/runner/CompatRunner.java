// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat.runner;

import dev.jkbuild.compat.ImportReport;
import dev.jkbuild.compat.InstalledTool;
import dev.jkbuild.compat.JkBuildRenderer;
import dev.jkbuild.compat.ToolDistribution;
import dev.jkbuild.compat.ToolProvisioning;
import dev.jkbuild.compat.ToolRegistry;
import dev.jkbuild.gradle.GradleImporter;
import dev.jkbuild.gradle.GradleResolver;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.mvn.MavenResolver;
import dev.jkbuild.mvn.PomExporter;
import dev.jkbuild.mvn.PomImporter;
import dev.jkbuild.plugin.protocol.Ndjson;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Entry point for the {@code jk-compat-runner} worker subprocess.
 *
 * <p>Handles four commands — {@code import}, {@code export},
 * {@code provision_mvn}, {@code provision_gradle} — selected by the
 * {@code COMMAND} line in the spec file.
 *
 * <p>Streams {@value #PREFIX}-prefixed NDJSON to stdout:
 * <pre>
 * ##JKCMP:{"t":"wrote","path":"/path/jk.toml"}
 * ##JKCMP:{"t":"note","msg":"3 import notes"}
 * ##JKCMP:{"t":"result","ok":true}
 * ##JKCMP:{"t":"result","ok":false,"error":"..."}
 * ##JKCMP:{"t":"result","ok":true,"bin":"/path/mvn","source":"CACHED"}
 * </pre>
 *
 * <p>Exit 0 on success, 1 on operation error, 2 on bad arguments.
 */
public final class CompatRunner {

    static final String PREFIX = "##JKCMP:";

    private CompatRunner() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1) {
            err.println("jk-compat-runner: expected spec file path");
            return 2;
        }
        Path specFile = Path.of(args[0]);
        if (!Files.isRegularFile(specFile)) {
            err.println("jk-compat-runner: spec file not found: " + specFile);
            return 2;
        }

        // Parse spec.
        String command = null;
        Path source = null, out_ = null, report = null, tmpDir = null, baseDir = null;
        Path projectDir = null, target = null, toolsRoot = null;
        boolean force = false, noDiscover = false;

        try {
            for (String line : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sp = line.indexOf(' ');
                if (sp < 0) continue;
                String key = line.substring(0, sp);
                String val = line.substring(sp + 1).strip();
                switch (key) {
                    case "COMMAND"     -> command    = val;
                    case "SOURCE"      -> source     = Path.of(val);
                    case "OUT"         -> out_       = Path.of(val);
                    case "REPORT"      -> report     = Path.of(val);
                    case "TMP_DIR"     -> tmpDir     = Path.of(val);
                    case "BASE_DIR"    -> baseDir    = Path.of(val);
                    case "PROJECT_DIR" -> projectDir = Path.of(val);
                    case "TARGET"      -> target     = Path.of(val);
                    case "TOOLS_ROOT"  -> toolsRoot  = Path.of(val);
                    case "FORCE"       -> force      = "true".equalsIgnoreCase(val);
                    case "NO_DISCOVER" -> noDiscover = "true".equalsIgnoreCase(val);
                }
            }
        } catch (IOException e) {
            err.println("jk-compat-runner: could not read spec: " + e.getMessage());
            return 2;
        }

        if (command == null) {
            err.println("jk-compat-runner: spec missing COMMAND");
            return 2;
        }

        return switch (command) {
            case "import"          -> runImport(out, err, source, out_, report, tmpDir, baseDir, force);
            case "export"          -> runExport(out, err, projectDir, target, force);
            case "provision_mvn"   -> runProvision(out, err, projectDir, toolsRoot, noDiscover, false);
            case "provision_gradle"-> runProvision(out, err, projectDir, toolsRoot, noDiscover, true);
            default -> { err.println("jk-compat-runner: unknown command: " + command); yield 2; }
        };
    }

    private static int runImport(PrintStream out, PrintStream err,
                                  Path source, Path outPath, Path report,
                                  Path tmpDir, Path baseDir, boolean force) {
        if (source == null || outPath == null) {
            err.println("jk-compat-runner: import requires SOURCE and OUT");
            return 2;
        }
        try {
            String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
            JkBuild root;
            Map<String, JkBuild> members = new LinkedHashMap<>();
            ImportReport importReport;

            if (filename.endsWith("pom.xml")) {
                PomImporter.WorkspaceImportResult result = PomImporter.importWorkspace(source);
                root = result.root();
                members.putAll(result.members());
                importReport = result.report();
            } else if (filename.equals("build.gradle") || filename.equals("build.gradle.kts")) {
                GradleImporter.Result result = GradleImporter.importFrom(source);
                root = result.jkBuild();
                importReport = result.report();
            } else {
                err.println("jk-compat-runner: unrecognised source: " + source.getFileName());
                return 64;
            }

            Files.writeString(outPath, JkBuildRenderer.render(root), StandardCharsets.UTF_8);
            emit(out, "{\"t\":\"wrote\",\"path\":" + Ndjson.quote(outPath.toString()) + "}");

            Path effectiveBaseDir = baseDir != null ? baseDir : source.getParent();
            for (Map.Entry<String, JkBuild> e : members.entrySet()) {
                Path memberJkBuild = effectiveBaseDir.resolve(e.getKey()).resolve("jk.toml");
                if (Files.exists(memberJkBuild) && !force) {
                    emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":\"would overwrite "
                            + memberJkBuild + " — pass --force\"}");
                    return 73;
                }
                Files.writeString(memberJkBuild, JkBuildRenderer.render(e.getValue()),
                        StandardCharsets.UTF_8);
                emit(out, "{\"t\":\"wrote\",\"path\":" + Ndjson.quote(memberJkBuild.toString()) + "}");
            }

            // Write import report.
            Path reportTarget = report;
            if (reportTarget == null && tmpDir != null) {
                var proj = root.project();
                String coord = proj.group() + "-" + proj.name() + "-" + proj.version();
                for (int n = 1; ; n++) {
                    Path candidate = tmpDir.resolve(coord + "-" + n
                            + "-" + source.getFileName() + "-import.md");
                    if (!Files.exists(candidate)) { reportTarget = candidate; break; }
                }
            }
            if (reportTarget != null) {
                Path rDir = reportTarget.getParent();
                if (rDir != null) Files.createDirectories(rDir);
                Files.writeString(reportTarget,
                        importReport.renderMarkdown(source.toString()), StandardCharsets.UTF_8);
                emit(out, "{\"t\":\"wrote\",\"path\":" + Ndjson.quote(reportTarget.toString()) + "}");
            }

            int warnings = importReport.issues().size();
            boolean hasErrors = importReport.hasErrors();
            emit(out, "{\"t\":\"result\",\"ok\":true,\"warnings\":" + warnings
                    + ",\"errors\":" + hasErrors + "}");
            return 0;
        } catch (IOException e) {
            emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static int runExport(PrintStream out, PrintStream err,
                                  Path projectDir, Path target, boolean force) {
        if (projectDir == null || target == null) {
            err.println("jk-compat-runner: export requires PROJECT_DIR and TARGET");
            return 2;
        }
        try {
            dev.jkbuild.config.JkBuildParser jkBuildParser = null; // static access
            JkBuild root = dev.jkbuild.config.JkBuildParser.parse(projectDir.resolve("jk.toml"));
            PomExporter.Result rootResult = PomExporter.export(root);
            Files.writeString(target, rootResult.xml(), StandardCharsets.UTF_8);
            emit(out, "{\"t\":\"wrote\",\"path\":" + Ndjson.quote(target.toString()) + "}");

            int totalWarnings = rootResult.report().issues().size();
            if (root.isWorkspaceRoot()) {
                Map<Path, JkBuild> members =
                        dev.jkbuild.config.WorkspaceLoader.loadMembers(projectDir, root);
                for (Map.Entry<Path, JkBuild> e : members.entrySet()) {
                    Path memberPom = e.getKey().resolve("pom.xml");
                    if (Files.exists(memberPom) && !force) {
                        emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":\"would overwrite "
                                + memberPom + " — pass --force\"}");
                        return 73;
                    }
                    PomExporter.Result memberResult = PomExporter.export(e.getValue());
                    Files.writeString(memberPom, memberResult.xml(), StandardCharsets.UTF_8);
                    emit(out, "{\"t\":\"wrote\",\"path\":" + Ndjson.quote(memberPom.toString()) + "}");
                    totalWarnings += memberResult.report().issues().size();
                }
            }
            emit(out, "{\"t\":\"result\",\"ok\":true,\"warnings\":" + totalWarnings + "}");
            return 0;
        } catch (IOException e) {
            emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static int runProvision(PrintStream out, PrintStream err,
                                     Path projectDir, Path toolsRoot,
                                     boolean noDiscover, boolean isGradle) {
        if (projectDir == null || toolsRoot == null) {
            err.println("jk-compat-runner: provision requires PROJECT_DIR and TOOLS_ROOT");
            return 2;
        }
        try {
            ToolRegistry registry = new ToolRegistry(toolsRoot);
            ToolDistribution dist = isGradle
                    ? new GradleResolver().resolve(projectDir)
                    : new MavenResolver().resolve(projectDir);
            ToolProvisioning.Result result =
                    ToolProvisioning.provision(dist, registry, new Http(), noDiscover);
            InstalledTool tool = result.tool();
            emit(out, "{\"t\":\"result\",\"ok\":true"
                    + ",\"bin\":" + Ndjson.quote(tool.binary().toString())
                    + ",\"home\":" + Ndjson.quote(tool.home().toString())
                    + ",\"version\":" + Ndjson.quote(dist.version())
                    + ",\"source\":" + Ndjson.quote(result.source().name())
                    + "}");
            return 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static void emit(PrintStream out, String json) {
        out.println(PREFIX + json);
        out.flush();
    }
}
