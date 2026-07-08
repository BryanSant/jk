// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    alias(libs.plugins.graalvm.native)
}

description = "jk command-line entrypoint"

dependencies {
    // The slim client's whole kernel surface (Stage 5): the jk-api model, the build-file/lockfile
    // readers, the thin client I/O slice (http, forge auth, credential files, CAS read/link), the
    // client-resident JDK/toolchain flow, and the engine wire contract. NO :engine, :io, :resolver,
    // or :toolchain — the compiler now enforces that everything heavy reaches the engine over the
    // wire (EngineClient) or through the ServiceLoader-discovered in-process seam (:cli-engine,
    // test/JVM-dist classpaths only).
    implementation(project(":model"))
    implementation(project(":core"))
    implementation(project(":client-io"))
    implementation(project(":toolchain-jdk"))
    implementation(project(":engine-api"))
    // Shared NDJSON reader for the engine/worker wire envelope.
    implementation(project(":plugin-api"))

    // JLine 4 FFM terminal provider for raw-mode TUI (jk init wizard).
    // FFM backend requires JDK 22+; the GraalVM-compiled binary embeds the
    // FFM downcalls natively. Reflection/resource hints live under
    // src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/.
    implementation(libs.jline.terminal.ffm)

}


graalvmNative {
    binaries.named("main") {
        imageName.set("jk")
        mainClass.set("dev.jkbuild.cli.Jk")
        // The plugin's "main" binary is supposed to default to executable,
        // but the 0.10.4 / GraalVM 25 combination defaults to shared library
        // on this host. Force the executable mode explicitly.
        sharedLibrary.set(false)
        // No application plugin here anymore (the JVM dist ships from :cli-engine, which links the
        // engine) — wire the image classpath explicitly: the SLIM classpath is the whole point of
        // this module (Stage 5), and the compiler + this wiring keep it honest.
        classpath(tasks.named("jar"), configurations.runtimeClasspath)

        // Size-first build args. The jk binary's primary UX budget is its download +
        // on-disk size and shell-integration startup latency; per-verb CPU work is
        // shrinking as the CLI delegates the heavy lifting (hashing, compiling,
        // packaging) to the resident engine and its forked workers.
        //
        // -Os       Optimize for size. (History: was -O3 + -march=x86-64-v3, tuned when
        //           the CLI process itself did the CAS/ClasspathFingerprint SHA-256
        //           work — the SIMD -march bought ≈1.5x on no-op builds then. Since the
        //           Stage 5 split that hashing lives in the jk-engine image, which
        //           re-tunes for speed independently — see cli-engine/build.gradle.kts.)
        // --gc=serial
        //           Generational serial GC. Small/fast for short verbs and a ≤256 MiB
        //           engine heap alike, and — unlike epsilon — it actually reclaims, so
        //           verbs that stream data don't accumulate every transient byte until
        //           the process dies.
        // -R:MaxHeapSize=134217728
        //           Hard 128 MiB max heap for the CLI process. jk's own work is tiny;
        //           the cap turns any runaway allocation into a fast, loud OOM instead
        //           of dragging the machine into swap. Heavy work runs in the engine
        //           (spawned with its own -Xms/-Xmx, which override this baked default)
        //           and in forked worker JVMs tuned via JvmOptions.
        // -R:MinHeapSize=25165824
        //           24 MiB initial heap — sized to what a trivial verb actually uses
        //           (`jk --help` measured ~19 MiB RSS), so the smallest commands fit in
        //           the floor without a growth step, while anything bigger still grows
        //           lazily toward the 128 MiB cap.
        buildArgs.add("-Os")
        buildArgs.add("--gc=serial")
        buildArgs.add("-R:MaxHeapSize=134217728")
        buildArgs.add("-R:MinHeapSize=25165824")
        // JLine 4 FFM's signal handler uses Arena.ofShared(), gated behind this
        // flag in GraalVM 25. Without it the wizard crashes on Signal.INT setup.
        buildArgs.add("-H:+SharedArenaSupport")
        // Silence the FFM "restricted method" runtime warning. Without this,
        // every wizard invocation prints a 4-line WARNING block before the UI.
        buildArgs.add("--enable-native-access=ALL-UNNAMED")
        // (No engine code in this image since Stage 5: the engine role — and its setsid(2)
        // downcall — lives in the JVM-hosted engine, shipped as jars by :cli-engine.)
        // Push heavy deps to lazy init. Build-time <clinit> is faster at
        // runtime but blows up .svm_heap with cached objects we may never
        // touch. The crypto/SBOM/git/Jib closures (bouncycastle, sigstore,
        // grpc, cyclonedx, spdx, jgit, com.google) live in forked workers, not
        // on the binary's classpath, so jline is the only contributor left:
        // its FFM Linker/Arena lookups must run at image-runtime regardless.
        buildArgs.add("--initialize-at-run-time=org.jline")
        // tomlj 1.1.1 was generated with ANTLR 4.11.1 but 4.13.2 ends up on the
        // runtime classpath via a transitive dep. ANTLR's static initializer prints
        // a version-mismatch warning on every startup. Initializing at build time
        // bakes the check into the image so it never runs at the user's terminal.
        buildArgs.add("--initialize-at-build-time=org.antlr")
        // jline-native ships a resource-config with a broad "org/jline/nativ/.*"
        // pattern that embeds ALL platform native libs (Windows DLLs, Linux/macOS/
        // FreeBSD .so/.dylib for every arch) as image resources.  jk uses the FFM
        // terminal provider exclusively; the JNI/JNA fallback (JLineNativeLoader,
        // CLibrary, Kernel32, etc.) is reachable via jline-terminal's AbstractPty
        // but never exercised at runtime.  Exclude those cross-platform binaries
        // with -H:ExcludeResources so they are not baked into the image heap.
        buildArgs.add("-H:ExcludeResources=org/jline/nativ/.*")
    }

}

// JLine 4 FFM terminal provider ships native-image hints; we supplement them
// at src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/
// with reflection-config.json and resource-config.json bootstrapped via the
// GraalVM tracing agent against the JVM wizard (see plan §8d).
