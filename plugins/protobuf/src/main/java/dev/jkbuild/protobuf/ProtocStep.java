// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.protobuf;

import dev.jkbuild.plugin.build.StepExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code protoc} step: fork the fetched protoc binary over every {@code .proto} under the
 * configured source dir ({@code [protobuf] src}, default {@code proto/}), generating Java into
 * the {@code gen} output (contributed to the compiler's source set). {@code lite = true} emits
 * the lite-runtime variant (the Android/datastore posture — pairs with protobuf-javalite).
 *
 * <p>protoc publishes to Maven as a bare native binary (no jar wrapper), so the fetched file
 * arrives without the executable bit — it is staged into scratch and chmod +x'd before the fork.
 */
final class ProtocStep {

    private ProtocStep() {}

    static void run(StepExec exec) throws Exception {
        String src = exec.config().stringOpt("src").orElse("proto");
        Path protoDir = exec.moduleDir().resolve(src);
        Path gen = exec.outputDir("gen");
        List<Path> protos = protoFiles(protoDir);
        if (protos.isEmpty()) {
            return; // an empty/missing proto dir is a no-op, not an error — gen stays empty
        }
        boolean lite = exec.config().bool("lite", false);
        Path protoc = executable(exec);
        exec.label("protoc (" + protos.size() + (protos.size() == 1 ? " file)" : " files)"));
        StepExec.ToolRun run = exec.tool(protoc)
                .arg("--java_out=" + (lite ? "lite:" : "") + gen.toAbsolutePath())
                .arg("-I")
                .arg(protoDir.toAbsolutePath().toString())
                .cwd(exec.moduleDir());
        if (exec.config().bool("kotlin", false)) {
            // The Kotlin DSL wraps the Java codegen (both land in gen; the engine's suffix
            // unions route .java to javac and .kt to kotlinc).
            run.arg("--kotlin_out=" + (lite ? "lite:" : "") + gen.toAbsolutePath());
        }
        for (Path proto : protos) {
            run.arg(proto.toAbsolutePath().toString());
        }
        StepExec.ToolRun.Result result = run.run();
        if (result.exit() != 0) {
            throw new IllegalStateException("protoc failed (exit " + result.exit() + "):\n" + result.output());
        }
    }

    private static List<Path> protoFiles(Path protoDir) throws IOException {
        if (!Files.isDirectory(protoDir)) return List.of();
        try (Stream<Path> walk = Files.walk(protoDir)) {
            return walk.filter(f -> f.getFileName().toString().endsWith(".proto"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
    }

    /** Stage the fetched binary into scratch with the executable bit set (cache files are read-only). */
    private static Path executable(StepExec exec) throws IOException {
        Path fetched = exec.requireExtra("protoc");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path staged = Files.createDirectories(exec.scratch().resolve("tools"))
                .resolve(windows ? "protoc.exe" : "protoc");
        Files.copy(fetched, staged, StandardCopyOption.REPLACE_EXISTING);
        if (!staged.toFile().setExecutable(true) && !windows) {
            throw new IOException("cannot mark protoc executable: " + staged);
        }
        return staged;
    }
}
