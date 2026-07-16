// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * SCRATCH (uncommitted): the Now-in-Android {@code :core:*} build attempt (android-plan Step 5
 * north star). Sweeps every module under the clone that carries a hand-written jk.toml, builds
 * each with the real pipeline, and prints a pass/fail inventory — an exploration harness, not an
 * acceptance test; findings feed the plan doc.
 */
@EnabledIfEnvironmentVariable(
        named = "JK_NIA_SCRATCH",
        matches = "1",
        disabledReason = "the marathon NiA sweep — jk-examples/android/nowinandroid/run.sh is the canonical\n                end-to-end; export JK_NIA_SCRATCH=1 to drive it through the engine test harness instead")
class NiaScratchTest {

    private static final Path NIA = Path.of(
            "/tmp/claude-1000/-home-bsant-src-oss-jktest/2ffb4945-d74f-4100-a6ed-fcbecf1a1ffc/scratchpad/nowinandroid");

    @Test
    void sweep_all_jk_modules() throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(cc.jumpkick.androidsdk.AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());
        acceptLicenses();

        // Dependency order comes from the root [workspace] modules list — a plain walk sorts
        // alphabetically (data before database) and breaks a clean rebuild.
        List<Path> modules = new ArrayList<>();
        JkBuild root = JkBuildParser.parse(NIA.resolve("jk.toml"));
        for (String rel : root.workspace().modules()) {
            Path dir = NIA.resolve(rel);
            if (Files.isRegularFile(dir.resolve("jk.toml"))) modules.add(dir);
        }
        System.out.println("NIA-SWEEP: " + modules.size() + " module(s)");
        for (Path module : modules) {
            String name = NIA.relativize(module).toString();
            try {
                String failure = buildOne(module);
                System.out.println(failure == null ? "NIA-PASS: " + name : "NIA-FAIL: " + name + " — " + failure);
                if (failure != null && Files.isDirectory(module.resolve("target"))) {
                    try (var walk = Files.walk(module.resolve("target"), 4)) {
                        walk.filter(Files::isDirectory)
                                .forEach(p -> System.out.println("NIA-TREE: " + module.relativize(p)));
                    }
                }
            } catch (Throwable t) {
                System.out.println("NIA-FAIL: " + name + " — threw " + t);
            }
        }
    }

    /** The release finish: jk build --release of :app → R8 full mode + a signed AAB. */
    @Test
    void app_release_aab() throws Exception {
        Path module = NIA.resolve("app");
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(cc.jumpkick.androidsdk.AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());
        acceptLicenses();

        Path keystore = Path.of(System.getProperty("user.dir"), "build", "nia-release.jks");
        if (!Files.isRegularFile(keystore)) {
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            new ProcessBuilder(
                            keytool.toString(), "-genkeypair", "-keystore", keystore.toString(),
                            "-storepass", "rel-store-pass", "-keypass", "rel-key-pass", "-alias", "upload",
                            "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-dname", "CN=nia")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        }

        JkBuild build = JkBuildParser.parse(module.resolve("jk.toml"));
        var rootManifest = JkBuildParser.parse(NIA.resolve("jk.toml"));
        var modules = WorkspaceLoader.loadModules(NIA, rootManifest);
        build = WorkspaceMerge.applyToModule(rootManifest, build, modules.values());
        Pipeline lock = LockPipelines.lockPipeline(
                module, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        PipelineResult lockResult = lock.run();
        System.out.println("NIA-RELEASE lock: " + lockResult.errors());

        BuildPipelines.Inputs in = new BuildPipelines.Inputs(
                        module,
                        cache,
                        module.resolve("jk.toml"),
                        module.resolve("jk.lock"),
                        module,
                        1,
                        0,
                        null,
                        null,
                        true,
                        false,
                false,
                false,
                java.util.Set.of(),
                SessionContext.current())
                .withVariant(
                        "release|contentType=demo",
                        java.util.Map.of(
                                "RELEASE_KEYSTORE", keystore.toAbsolutePath().toString(),
                                "RELEASE_STORE_PASSWORD", "rel-store-pass",
                                // PKCS12: the key password IS the store password (keytool
                                // ignores -keypass for PKCS12 stores).
                                "RELEASE_KEY_PASSWORD", "rel-store-pass"));
        PipelineResult result = BuildPipelines.coreBuilder(in).build().run();
        System.out.println("NIA-RELEASE diags: " + result.errors());
        System.out.println("NIA-RELEASE success: " + result.success());
        try (var walk = Files.walk(module.resolve("target"))) {
            walk.filter(p -> p.toString().endsWith(".aab") || p.toString().endsWith(".apk"))
                    .forEach(p -> System.out.println("NIA-RELEASE artifact: " + module.relativize(p)));
        }
    }

    /** Null on success, else the first diagnostic. */
    private static String buildOne(Path module) throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        JkBuild build = JkBuildParser.parse(module.resolve("jk.toml"));
        // Workspace context for the lock, exactly LockFlow's module branch: resolve
        // workspace:* placeholders against the sibling list (the build side self-discovers).
        if (!build.isWorkspaceRoot()) {
            var rootOpt = WorkspaceLocator.findRoot(module);
            if (rootOpt.isPresent()) {
                JkBuild rootManifest = JkBuildParser.parse(rootOpt.get().resolve("jk.toml"));
                var modules = WorkspaceLoader.loadModules(rootOpt.get(), rootManifest);
                build = WorkspaceMerge.applyToModule(rootManifest, build, modules.values());
            }
        }
        Pipeline lock = LockPipelines.lockPipeline(
                module, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        PipelineResult lockResult = lock.run();
        if (!lockResult.errors().isEmpty()) return "lock: " + lockResult.errors().getFirst();
        BuildPipelines.Inputs in = new BuildPipelines.Inputs(
                module,
                cache,
                module.resolve("jk.toml"),
                module.resolve("jk.lock"),
                module,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                java.util.Set.of(),
                SessionContext.current());
        // Flavored modules build the demo variant (the flavor NiA's own CI exercises — no
        // backend needed). True workspace variant propagation (the app's selection reaching
        // sibling AAR builds automatically) is a recorded follow-up; the sweep selects
        // per-module.
        if (Files.readString(module.resolve("jk.toml")).contains("[variants.contentType]")
                || module.getFileName().toString().equals("app")) {
            in = in.withVariant("contentType=demo", java.util.Map.of());
        }
        PipelineResult result = BuildPipelines.coreBuilder(in).build().run();
        if (!result.errors().isEmpty()) return "build: ALL-DIAGS " + result.errors();
        if (!result.success()) return "build: failed without diagnostics";
        return null;
    }

    private static void acceptLicenses() throws Exception {
        var sdk = cc.jumpkick.androidsdk.AndroidSdk.resolve();
        var installer = new cc.jumpkick.androidsdk.AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-34")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(
                        license.getKey(), cc.jumpkick.androidsdk.AndroidRepoFeed.licenseHash(license.getValue()));
            }
        }
    }
}
