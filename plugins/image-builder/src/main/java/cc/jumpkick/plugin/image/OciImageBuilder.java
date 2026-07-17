// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.image.ImageConfig;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.ImageContext;
import cc.jumpkick.plugin.build.ImageExtension;
import cc.jumpkick.plugin.build.ImageResult;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code jk-image-builder} plugin: the terminal {@link ImageExtension} goal for OCI images. Its
 * plugin entry ({@link #run}) reads the engine's spec, assembles an {@link ImageContext} from the
 * finished module (main jar + runtime closure + {@code [image]} settings), and hands it to {@link
 * #image}, which drives {@link ImageBuilder} (Jib) to a tarball, the local daemon, or a registry.
 *
 * <p>The spec is line-oriented ({@code MAIN_JAR /abs/app.jar}, {@code BASE …}, {@code TARBALL …},
 * {@code DEP_JAR …}, …); the reply is {@value #PREFIX}-prefixed JSONL, terminating in
 * {@code {"t":"result","ok":true,"ref":"…"}} (or {@code "tarball"}), {@code {"t":"result",
 * "ok":false,"error":"…"}} on failure. Exit 0 success, 1 build/push error, 2 bad arguments.
 */
public final class OciImageBuilder implements Plugin, ImageExtension {

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
            System.err.println("jk-image-builder: spec file not found: " + specFile);
            return 2;
        }
        PluginSpec spec;
        try {
            spec = PluginSpec.read(specFile);
        } catch (IOException e) {
            System.err.println("jk-image-builder: could not read spec: " + e.getMessage());
            return 2;
        }

        try {
            ImageResult result = image(new SpecImageContext(spec, out));
            Map<String, Object> fields = new LinkedHashMap<>();
            if (result.tarball() != null) {
                fields.put("tarball", result.tarball().toString());
            } else {
                fields.put("ref", result.reference());
                if (result.daemon()) fields.put("daemon", true);
            }
            out.emit(PluginReply.result(fields));
            out.emit(PluginReply.done(0));
            return 0;
        } catch (Exception e) {
            out.emit(PluginReply.error("image", e.getMessage()));
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

        Path mainJar = ctx.mainArtifact().orElseThrow(() -> new IOException("image goal needs a built main artifact"));
        List<Path> depJars = new ArrayList<>();
        List<Path> snapshotJars = new ArrayList<>();
        for (PackageIo.RuntimeEntry e : ctx.runtimeEntries()) {
            (e.snapshot() ? snapshotJars : depJars).add(e.jar());
        }
        Path classesDir = ctx.classesDir().orElse(null);

        ImageConfig config = new ImageConfig(
                base,
                c.stringOpt("user").orElse(null),
                ports,
                env,
                labels,
                registry,
                tag,
                platforms.isEmpty() ? null : platforms,
                mainClass,
                dockerExecutable,
                null);
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

    /** The generic terminal context, backed directly by the plugin spec. */
    private record SpecImageContext(PluginSpec spec, ProtocolWriter out) implements ImageContext {
        @Override
        public PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Path moduleDir() {
            return spec.moduleDir();
        }

        @Override
        public Optional<Path> mainArtifact() {
            return Optional.ofNullable(spec.artifactPath());
        }

        @Override
        public List<PackageIo.RuntimeEntry> runtimeEntries() {
            return spec.entries();
        }

        @Override
        public Path javaHome() {
            return spec.javaHome();
        }

        @Override
        public Optional<Path> classesDir() {
            return Optional.ofNullable(spec.classesDir());
        }

        @Override
        public void label(String text) {
            out.emit(PluginReply.label(text));
        }
    }
}
