// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.image;

import build.jumpkick.image.ImageBuilder;
import build.jumpkick.image.ImageConfig;
import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginConfig;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.build.ImageContext;
import build.jumpkick.plugin.build.ImageExtension;
import build.jumpkick.plugin.build.ImageResult;
import build.jumpkick.plugin.build.PackageIo;
import build.jumpkick.plugin.build.ProjectFacts;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code jk-image-builder} worker: the terminal {@link ImageExtension} goal for OCI images. Its
 * worker entry ({@link #run}) reads the engine's spec, assembles an {@link ImageContext} from the
 * finished module (main jar + runtime closure + {@code [image]} settings), and hands it to {@link
 * #image}, which drives {@link ImageBuilder} (Jib) to a tarball, the local daemon, or a registry.
 *
 * <p>The spec is line-oriented ({@code MAIN_JAR /abs/app.jar}, {@code BASE …}, {@code TARBALL …},
 * {@code DEP_JAR …}, …); the reply is {@value #PREFIX}-prefixed NDJSON, terminating in
 * {@code {"t":"result","ok":true,"ref":"…"}} (or {@code "tarball"}), {@code {"t":"result",
 * "ok":false,"error":"…"}} on failure. Exit 0 success, 1 build/push error, 2 bad arguments.
 */
public final class ImagePlugin implements Plugin, ImageExtension {

    private static final String PREFIX = "##JKIM:";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-image-builder", PREFIX);
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-image-runner: expected spec file path");
            return 2;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-image-runner: spec file not found: " + specFile);
            return 2;
        }

        Path mainJar = null, tarball = null, classesDir = null;
        String artifact = null, version = null, mainClass = null;
        String base = null, user = null, registry = null, tag = null;
        String mode = null, dockerExecutable = null;
        List<Path> depJars = new ArrayList<>();
        List<Path> snapshotJars = new ArrayList<>();
        List<String> ports = new ArrayList<>();
        List<String> env = new ArrayList<>();
        List<String> labels = new ArrayList<>();
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
                    case "MAIN_JAR" -> mainJar = Path.of(val);
                    case "ARTIFACT" -> artifact = val;
                    case "VERSION" -> version = val;
                    case "MAIN_CLASS" -> mainClass = val;
                    case "BASE" -> base = val;
                    case "USER" -> user = val;
                    case "TARBALL" -> tarball = Path.of(val);
                    case "REGISTRY" -> registry = val;
                    case "TAG" -> tag = val;
                    case "MODE" -> mode = val;
                    case "DOCKER_EXECUTABLE" -> dockerExecutable = val;
                    case "DEP_JAR" -> depJars.add(Path.of(val));
                    case "SNAPSHOT_DEP_JAR" -> snapshotJars.add(Path.of(val));
                    case "CLASSES_DIR" -> classesDir = Path.of(val);
                    case "PLATFORM" -> platforms.add(val);
                    case "PORT" -> ports.add(val);
                    case "ENV" -> env.add(val);
                    case "LABEL" -> labels.add(val);
                    default -> {
                        // forward compatibility: ignore unknown keys
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("jk-image-runner: could not read spec: " + e.getMessage());
            return 2;
        }

        if (mainJar == null || artifact == null || version == null || mainClass == null) {
            System.err.println("jk-image-runner: spec missing MAIN_JAR, ARTIFACT, VERSION, or MAIN_CLASS");
            return 2;
        }

        // Assemble the generic terminal context: image settings ride config(), the built jar rides
        // mainArtifact(), the dependency closure rides runtimeEntries() (snapshot flag per entry).
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("artifact", artifact);
        cfg.put("version", version);
        cfg.put("mainClass", mainClass);
        if (base != null) cfg.put("base", base);
        if (user != null) cfg.put("user", user);
        if (registry != null) cfg.put("registry", registry);
        if (tag != null) cfg.put("tag", tag);
        if (mode != null) cfg.put("mode", mode);
        if (dockerExecutable != null) cfg.put("dockerExecutable", dockerExecutable);
        if (tarball != null) cfg.put("tarball", tarball.toString());
        if (!ports.isEmpty()) cfg.put("ports", List.copyOf(ports));
        if (!env.isEmpty()) cfg.put("env", List.copyOf(env));
        if (!labels.isEmpty()) cfg.put("labels", List.copyOf(labels));
        if (!platforms.isEmpty()) cfg.put("platforms", List.copyOf(platforms));

        List<PackageIo.RuntimeEntry> entries = new ArrayList<>();
        for (Path dep : depJars) entries.add(new PackageIo.RuntimeEntry(dep.getFileName().toString(), dep, false));
        for (Path dep : snapshotJars) entries.add(new PackageIo.RuntimeEntry(dep.getFileName().toString(), dep, true));

        ImageContext ctx = new SpecImageContext(
                new PluginConfig("jk-image-builder", cfg),
                new ProjectFacts("", artifact, version, 0, mainClass, false, false, Map.of()),
                Optional.of(mainJar),
                entries,
                Optional.ofNullable(classesDir),
                out);

        try {
            ImageResult result = image(ctx);
            if (result.tarball() != null) {
                out.emit("{\"t\":\"result\",\"ok\":true,\"tarball\":" + Ndjson.quote(result.tarball().toString()) + "}");
            } else if (result.daemon()) {
                out.emit("{\"t\":\"result\",\"ok\":true,\"ref\":" + Ndjson.quote(result.reference()) + ",\"daemon\":true}");
            } else {
                out.emit("{\"t\":\"result\",\"ok\":true,\"ref\":" + Ndjson.quote(result.reference()) + "}");
            }
            return 0;
        } catch (Exception e) {
            out.emit("{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    @Override
    public ImageResult image(ImageContext ctx) throws Exception {
        PluginConfig c = ctx.config();
        String artifact = c.string("artifact");
        String version = c.string("version");
        String mainClass = c.string("mainClass");
        List<Integer> ports = new ArrayList<>();
        for (String p : c.stringList("ports")) {
            try {
                ports.add(Integer.parseInt(p));
            } catch (NumberFormatException ignored) {
                // skip malformed port
            }
        }
        Map<String, String> env = splitPairs(c.stringList("env"));
        Map<String, String> labels = splitPairs(c.stringList("labels"));
        List<String> platforms = c.stringList("platforms");
        String base = c.stringOpt("base").orElse(null);
        String registry = c.stringOpt("registry").orElse(null);
        String tag = c.stringOpt("tag").orElse(null);
        String dockerExecutable = c.stringOpt("dockerExecutable").orElse(null);

        Path mainJar = ctx.mainArtifact()
                .orElseThrow(() -> new IOException("image goal needs a built main artifact"));
        List<Path> depJars = new ArrayList<>();
        List<Path> snapshotJars = new ArrayList<>();
        for (PackageIo.RuntimeEntry e : ctx.runtimeEntries()) {
            (e.snapshot() ? snapshotJars : depJars).add(e.jar());
        }
        Path classesDir = ctx.classesDir().orElse(null);

        ImageConfig config = new ImageConfig(
                base, c.stringOpt("user").orElse(null), ports, env, labels, registry, tag,
                platforms.isEmpty() ? null : platforms, mainClass, dockerExecutable, null);
        ImageBuilder.Plan plan =
                new ImageBuilder.Plan(config, artifact, version, mainClass, mainJar, depJars, snapshotJars, classesDir);

        Optional<String> tarball = c.stringOpt("tarball");
        if (tarball.isPresent()) {
            Path tarballPath = Path.of(tarball.get());
            ctx.label("building OCI tarball");
            if (tarballPath.getParent() != null) Files.createDirectories(tarballPath.getParent());
            ImageBuilder.writeToTarball(plan, tarballPath);
            return ImageResult.tarball(tarballPath);
        }
        String ref = config.targetReference(artifact, version);
        if ("daemon".equals(c.stringOpt("mode").orElse(null))) {
            String exe = dockerExecutable != null ? dockerExecutable : "docker";
            ctx.label("loading " + ref + " into " + exe);
            ImageBuilder.loadToLocalDaemon(plan, dockerExecutable != null ? Path.of(dockerExecutable) : null);
            return ImageResult.loaded(ref);
        }
        ctx.label("pushing " + ref);
        ImageBuilder.pushToRegistry(plan);
        return ImageResult.pushed(ref);
    }

    private static Map<String, String> splitPairs(List<String> pairs) {
        Map<String, String> out = new HashMap<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    /** The generic terminal context assembled from the worker spec. */
    private record SpecImageContext(
            PluginConfig config,
            ProjectFacts project,
            Optional<Path> mainArtifact,
            List<PackageIo.RuntimeEntry> runtimeEntries,
            Optional<Path> classesDir,
            ProtocolWriter out)
            implements ImageContext {

        @Override
        public Path moduleDir() {
            return null; // the image goal consumes the built artifact, not the project tree
        }

        @Override
        public Path javaHome() {
            return null; // Jib runs in-process; no JDK fork
        }

        @Override
        public void label(String text) {
            out.emit("{\"t\":\"progress\",\"msg\":" + Ndjson.quote(text) + "}");
        }
    }
}
