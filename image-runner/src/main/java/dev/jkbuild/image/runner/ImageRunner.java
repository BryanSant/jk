// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.image.runner;

import dev.jkbuild.image.ImageBuilder;
import dev.jkbuild.image.ImageConfig;
import dev.jkbuild.plugin.protocol.Ndjson;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the {@code jk-image-runner} worker subprocess.
 *
 * <p>Receives a single argument: path to a line-oriented spec file:
 * <pre>
 * MAIN_JAR  /abs/path/to/app-1.0.jar
 * ARTIFACT  app
 * VERSION   1.0.0
 * MAIN_CLASS com.example.App
 * BASE      gcr.io/distroless/java21-debian12:nonroot
 * USER      nonroot
 * TARBALL   /abs/path/to/out.oci.tar   # omit for push mode
 * REGISTRY  registry.example.com       # omit for tarball mode
 * TAG       1.0.0
 * PLATFORM  linux/amd64
 * DEP_JAR   /abs/path/to/dep.jar       # repeated
 * PORT      8080                       # repeated
 * ENV       KEY=value                  # repeated
 * LABEL     key=value                  # repeated
 * </pre>
 *
 * <p>Streams {@value #PREFIX}-prefixed NDJSON to stdout:
 * <pre>
 * ##JKIM:{"t":"progress","msg":"building layers"}
 * ##JKIM:{"t":"result","ok":true,"ref":"registry/app:1.0"}
 * ##JKIM:{"t":"result","ok":false,"error":"..."}
 * </pre>
 *
 * <p>Exit 0 on success, 1 on build/push error, 2 on bad arguments.
 */
public final class ImageRunner {

    static final String PREFIX = "##JKIM:";

    private ImageRunner() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1) {
            err.println("jk-image-runner: expected spec file path");
            return 2;
        }
        Path specFile = Path.of(args[0]);
        if (!Files.isRegularFile(specFile)) {
            err.println("jk-image-runner: spec file not found: " + specFile);
            return 2;
        }

        Path mainJar = null, tarball = null;
        String artifact = null, version = null, mainClass = null;
        String base = null, user = null, registry = null, tag = null;
        List<Path> depJars = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        Map<String, String> env = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        List<String> platforms = new ArrayList<>();

        try {
            for (String line : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sp = line.indexOf(' ');
                if (sp < 0) continue;
                String key = line.substring(0, sp);
                String val = line.substring(sp + 1).strip();
                switch (key) {
                    case "MAIN_JAR"   -> mainJar    = Path.of(val);
                    case "ARTIFACT"   -> artifact   = val;
                    case "VERSION"    -> version    = val;
                    case "MAIN_CLASS" -> mainClass  = val;
                    case "BASE"       -> base       = val;
                    case "USER"       -> user       = val;
                    case "TARBALL"    -> tarball    = Path.of(val);
                    case "REGISTRY"   -> registry   = val;
                    case "TAG"        -> tag        = val;
                    case "DEP_JAR"    -> depJars.add(Path.of(val));
                    case "PLATFORM"   -> platforms.add(val);
                    case "PORT"       -> {
                        try { ports.add(Integer.parseInt(val)); } catch (NumberFormatException ignored) {}
                    }
                    case "ENV"   -> { int eq = val.indexOf('='); if (eq > 0) env.put(val.substring(0, eq), val.substring(eq + 1)); }
                    case "LABEL" -> { int eq = val.indexOf('='); if (eq > 0) labels.put(val.substring(0, eq), val.substring(eq + 1)); }
                }
            }
        } catch (IOException e) {
            err.println("jk-image-runner: could not read spec: " + e.getMessage());
            return 2;
        }

        if (mainJar == null || artifact == null || version == null || mainClass == null) {
            err.println("jk-image-runner: spec missing MAIN_JAR, ARTIFACT, VERSION, or MAIN_CLASS");
            return 2;
        }

        ImageConfig config = new ImageConfig(base, user, ports, env, labels,
                registry, tag, platforms.isEmpty() ? null : platforms, mainClass);
        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config, artifact, version, mainClass, mainJar, depJars);

        try {
            if (tarball != null) {
                emit(out, "{\"t\":\"progress\",\"msg\":\"building OCI tarball\"}");
                Files.createDirectories(tarball.getParent());
                ImageBuilder.writeToTarball(plan, tarball);
                emit(out, "{\"t\":\"result\",\"ok\":true,\"tarball\":" + Ndjson.quote(tarball.toString()) + "}");
            } else {
                String ref = config.targetReference(artifact, version);
                emit(out, "{\"t\":\"progress\",\"msg\":\"pushing " + ref + "\"}");
                ImageBuilder.pushToRegistry(plan);
                emit(out, "{\"t\":\"result\",\"ok\":true,\"ref\":" + Ndjson.quote(ref) + "}");
            }
            return 0;
        } catch (Exception e) {
            emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static void emit(PrintStream out, String json) {
        out.println(PREFIX + json);
        out.flush();
    }
}
