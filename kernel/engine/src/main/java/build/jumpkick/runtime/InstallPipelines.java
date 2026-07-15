// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.StepNames;

import build.jumpkick.config.JkBuildParser;
import build.jumpkick.git.GitFetcher;
import build.jumpkick.layout.BuildLayout;
import build.jumpkick.model.Coordinate;
import build.jumpkick.model.GitRefSpec;
import build.jumpkick.model.GitSource;
import build.jumpkick.model.JkBuild;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineKey;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepKind;
import build.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The shared {@code jk install} pipelines — hoisted out of the CLI so the resident engine can host the
 * command's heavy halves (Wave 3 of {@code docs/architecture/slim-client.md}) while the command's
 * test-only in-process path builds the exact same pipelines:
 *
 * <ul>
 *   <li>{@link #projectInstallPipeline} — the full {@link BuildPipelines} (plus declared tails and, for
 *       a native application, the {@link BuildPipelines#nativeStep} tail with a client-resolved
 *       GraalVM) followed by the {@code cache-install} step: jar + generated pom into {@code
 *       repos/local/} (or additionally mirrored to {@code ~/.m2} when {@code m2install = true})
 *       with index sidecars.
 *   <li>{@link #gitFetchPipeline} — materialize a git checkout (clone via the engine-forked git-client
 *       plugin), publishing {@link #CHECKOUT}/{@link #FETCHED_SHA} for the follow-up project
 *       install.
 * </ul>
 *
 * <p>The "make install" half — launcher/binary into {@code ~/.jk/bin} + {@code ~/.jk/lib} — is
 * deliberately <em>not</em> here: it writes user-home shim files the client owns, so it runs
 * client-side after the hosted pipeline succeeds (see {@code InstallCommand}).
 */
public final class InstallPipelines {

    private InstallPipelines() {}

    // Cross-step keys.
    public static final PipelineKey<Coordinate> PRIMARY = PipelineKey.of("primary-coord", Coordinate.class);
    public static final PipelineKey<Path> CHECKOUT = PipelineKey.of("checkout-dir", Path.class);
    public static final PipelineKey<String> FETCHED_SHA = PipelineKey.of("fetched-sha", String.class);

    /**
     * Build the project-install pipeline for {@code projectDir}: core pipeline + declared tails +
     * (native application only) the native-image tail with {@code graalHome} + the {@code
     * cache-install} step. {@code m2Dir} is the local Maven repo root ({@code ~/.m2} or the
     * {@code --m2-dir} override).
     */
    public static Pipeline projectInstallPipeline(
            Path projectDir, Path cache, Path m2Dir, boolean skipTests, boolean verbose, Path graalHome)
            throws IOException {
        JkBuild proj = JkBuildParser.parse(projectDir.resolve("jk.toml"));
        var pj = proj.project();
        // ALWAYS: native is part of the standard build and install produces a native binary.
        // SUPPORTED: user runs `jk native` explicitly; install deploys the jar.
        boolean isNative = proj.isApplication() && proj.nativeMode() == JkBuild.NativeMode.ALWAYS;

        Path lockFile = projectDir.resolve("jk.lock");
        int estimatedTestCount = TestSupport.estimateTestCount(projectDir.resolve("src/test/java"));
        BuildPipelines.Inputs inputs = new BuildPipelines.Inputs(
                projectDir,
                cache,
                projectDir.resolve("jk.toml"),
                lockFile,
                projectDir,
                1,
                estimatedTestCount,
                null,
                null,
                skipTests,
                verbose,
                false,
                false,
                java.util.Set.of(),
                build.jumpkick.config.SessionContext.current());
        Pipeline.Builder builder = BuildPipelines.coreBuilder(inputs);
        BuildPipelines.appendDeclaredTails(builder, inputs);

        // `jk build` no longer auto-builds native (that's `jk native`), so an installed native
        // application builds its binary here — with the GraalVM the client already resolved.
        if (isNative) {
            builder.addStep(
                    BuildPipelines.nativeStep(projectDir, cache, lockFile, null, graalHome, null, List.of()));
        }

        // cache-install reads the freshly-built jar and must run after every runnable artifact
        // this project produces (so a follow-up client-side make-install finds them all built).
        java.util.List<String> requires = new java.util.ArrayList<>(List.of(StepNames.PACKAGE_JAR));
        if (isNative) requires.add(StepNames.NATIVE_IMAGE);
        if (proj.isApplication() && proj.shadowJar() && !isNative) requires.add(StepNames.PACKAGE_SHADOW);

        Step cacheInstall = Step.builder(StepNames.CACHE_INSTALL)
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(BuildPipelines.PROJECT);
                    BuildLayout layout = ctx.require(BuildPipelines.LAYOUT);
                    var p = project.project();
                    Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
                    ctx.label("install " + coord.group() + ":" + coord.artifact() + ":" + coord.version()
                            + " to cache");
                    try {
                        cacheInstallArtifact(project, layout, cache, m2Dir);
                    } catch (IOException e) {
                        ctx.error(StepNames.CACHE_INSTALL, e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.put(PRIMARY, coord);
                    ctx.progress(1);
                })
                .build();

        return builder.addStep(cacheInstall).build();
    }

    /**
     * Build the git-fetch pipeline for {@code jk install <git-url>}: materialize {@code ref} (tried as
     * a tag first, then a branch) of {@code url} under the cache's git store, requiring the
     * checkout to carry a {@code jk.toml}. {@code refresh} forces a re-fetch. Publishes {@link
     * #CHECKOUT} + {@link #FETCHED_SHA}.
     */
    public static Pipeline gitFetchPipeline(String url, String canonicalUrl, String ref, Path cacheDir, boolean refresh) {
        return gitFetchPipeline(url, canonicalUrl, ref, cacheDir, refresh, /* requireJkToml */ true);
    }

    /**
     * As above, with {@code requireJkToml} switchable: {@code jk tool run <git-url>} accepts
     * JBang-convention checkouts (main.java, single script) too, so it fetches without the
     * jk.toml gate and applies the directory rules client-side (tool-targets-plan §4.6).
     */
    public static Pipeline gitFetchPipeline(
            String url, String canonicalUrl, String ref, Path cacheDir, boolean refresh, boolean requireJkToml) {
        Step fetch = Step.builder(StepNames.FETCH_GIT)
                .kind(StepKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("git fetch " + url + " @ " + ref);
                    GitFetcher fetcher = new GitFetcher(cacheDir.resolve("git"));
                    GitFetcher.Fetched fetched;
                    try {
                        fetched = fetchTagOrBranch(fetcher, url, canonicalUrl, ref, refresh);
                    } catch (IOException e) {
                        ctx.error("fetch", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    Path checkout = fetched.checkoutPath();
                    if (requireJkToml && !Files.exists(checkout.resolve("jk.toml"))) {
                        ctx.error("no-jk-toml", url + " has no jk.toml at " + ref);
                        throw new RuntimeException("no jk.toml in checkout");
                    }
                    ctx.put(CHECKOUT, checkout);
                    ctx.put(FETCHED_SHA, fetched.sha());
                    ctx.progress(1);
                })
                .build();
        return Pipeline.builder("install-git-fetch").addStep(fetch).build();
    }

    /** Try the user's ref as a tag first, then a branch. */
    private static GitFetcher.Fetched fetchTagOrBranch(
            GitFetcher fetcher, String expanded, String canonical, String refStr, boolean refresh) throws IOException {
        IOException tagFailure;
        try {
            GitSource asTag = new GitSource(canonical, expanded, new GitRefSpec.Tag(refStr), null, true, false);
            return fetcher.fetch(asTag, refresh);
        } catch (IOException e) {
            tagFailure = e;
        }
        try {
            GitSource asBranch = new GitSource(canonical, expanded, new GitRefSpec.Branch(refStr), null, true, false);
            return fetcher.fetch(asBranch, refresh);
        } catch (IOException branchFailure) {
            IOException wrapped = new IOException("ref `" + refStr + "` not found as tag or branch in " + expanded);
            wrapped.addSuppressed(tagFailure);
            wrapped.addSuppressed(branchFailure);
            throw wrapped;
        }
    }

    /**
     * Install the built JAR and a generated POM into the artifact store.
     *
     * <ul>
     *   <li><b>m2install = false (default)</b> — {@code repos/local/} is primary. Used as-is for
     *       jk's own plugin modules and any project that hasn't opted into Maven/Gradle interop;
     *       found by {@link build.jumpkick.engine.plugin.PluginJar#locate} without going through {@code
     *       ~/.m2}.</li>
     *   <li><b>m2install = true</b> — {@code repos/local/} is still written (jk's own O(1) index),
     *       and the JAR and POM are additionally mirrored to {@code m2Dir/repository} with full
     *       Maven-compatible {@code .sha1}/{@code .md5} sidecars and a {@code
     *       _remote.repositories} hint, so a system Maven/Gradle can use them without going through
     *       jk.</li>
     * </ul>
     */
    private static void cacheInstallArtifact(JkBuild project, BuildLayout layout, Path cacheDir, Path m2Dir)
            throws IOException {
        var p = project.project();
        Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
        Path jar = layout.mainJar();
        String jarRelPath = build.jumpkick.repo.MavenLayout.artifactPath(coord);
        String pomRelPath = build.jumpkick.repo.MavenLayout.pomPath(coord);
        String pomXml = build.jumpkick.publish.PublishablePom.render(project, null).xml();
        byte[] pomBytes = pomXml.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        if (p.m2install()) {
            // The local Maven repo is primary. m2Dir is caller-resolved (--m2-dir redirects it).
            Path m2Root = m2Dir.resolve("repository");

            // JAR → ~/.m2 with .sha1, .md5, _remote.repositories
            Path m2Jar = m2Root.resolve(jarRelPath);
            build.jumpkick.repo.M2CompatWriter.MavenHashes jarH =
                    build.jumpkick.repo.M2CompatWriter.copyToM2AndHash(jar, m2Jar);
            build.jumpkick.repo.M2CompatWriter.writeMavenSidecars(m2Jar, jarH.sha1(), jarH.md5());
            build.jumpkick.repo.M2CompatWriter.writeRemoteRepositories(
                    m2Jar.getParent(), "local", m2Jar.getFileName().toString());

            // POM → ~/.m2 with .sha1, .md5
            Path m2Pom = m2Root.resolve(pomRelPath);
            build.jumpkick.repo.M2CompatWriter.MavenHashes pomH =
                    build.jumpkick.repo.M2CompatWriter.writeBytesToM2(pomBytes, m2Pom);
            build.jumpkick.repo.M2CompatWriter.writeMavenSidecars(m2Pom, pomH.sha1(), pomH.md5());

            // Index sidecars in repos/local/ (jk's O(1) lookup, pointing to ~/.m2)
            writeLocalIndexSidecar(cacheDir, jarRelPath, Hashing.sha256Hex(jar));
            writeLocalIndexSidecar(cacheDir, pomRelPath, Hashing.sha256Hex(pomBytes));
        } else {
            // repos/local/ is primary (plugin JARs, jk-internal use).
            writeToLocalStore(cacheDir, jarRelPath, jar);
            writeContentToLocalStore(cacheDir, pomRelPath, pomBytes);
        }
    }

    /** Write a sidecar-only entry in {@code repos/local/} pointing to an artifact in {@code ~/.m2}. */
    private static void writeLocalIndexSidecar(Path cacheDir, String relativePath, String sha256) {
        try {
            Path sidecar = cacheDir.resolve("repos/local/" + relativePath + ".sha256");
            Files.createDirectories(sidecar.getParent());
            if (!Files.exists(sidecar)) Files.writeString(sidecar, sha256);
        } catch (IOException ignored) {
        }
    }

    /** See {@link build.jumpkick.repo.RepoArtifactStore#writeToLocalStore} — the one shared local-install write. */
    public static void writeToLocalStore(Path cacheDir, String relativePath, Path source) throws IOException {
        build.jumpkick.repo.RepoArtifactStore.writeToLocalStore(cacheDir, relativePath, source);
    }

    /** Write byte content directly into {@code repos/local/} as a full-store entry. */
    private static void writeContentToLocalStore(Path cacheDir, String relativePath, byte[] content)
            throws IOException {
        Path target = cacheDir.resolve("repos/local/" + relativePath);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(tmp, content);
        Files.move(
                tmp,
                target,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(Path.of(target + ".sha256"), Hashing.sha256Hex(content));
    }
}
