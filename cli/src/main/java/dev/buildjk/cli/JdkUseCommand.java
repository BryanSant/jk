// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk use <spec>} — pin the project to an installed JDK by
 * writing {@code .jk-version}. PRD §12.4: also keeps {@code .sdkmanrc}
 * aligned unless {@code --no-sdkman-compat}.
 */
@Command(name = "use", description = "Pin a project to an installed JDK (writes .jk-version)")
public final class JdkUseCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<spec>",
            description = "Installed JDK identifier or prefix (e.g. 21.0.5-tem).")
    String spec;

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Option(names = "--no-sdkman-compat",
            description = "Don't write .sdkmanrc alongside .jk-version.")
    boolean noSdkmanCompat;

    @Override
    public Integer call() throws IOException {
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();
        Path jdksRoot = jdksDir != null
                ? jdksDir : Path.of(System.getProperty("user.home"), ".jk", "jdks");

        JdkRegistry registry = new JdkRegistry(jdksRoot);
        Optional<InstalledJdk> jdk = registry.find(spec)
                .or(() -> {
                    try { return registry.findByPrefix(spec); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
        if (jdk.isEmpty()) {
            System.err.println("jk jdk use: no installed JDK matches `" + spec
                    + "` (try `jk jdk list`)");
            return 1;
        }

        String identifier = jdk.get().identifier();
        Files.writeString(projectDir.resolve(".jk-version"),
                identifier + "\n", StandardCharsets.UTF_8);
        if (!noSdkmanCompat) {
            // .sdkmanrc uses just the SDKMAN-style version-vendor part, not
            // jk's arch/os-decorated identifier.
            String sdkmanForm = sdkmanFormOf(identifier);
            Files.writeString(projectDir.resolve(".sdkmanrc"),
                    "java=" + sdkmanForm + "\n", StandardCharsets.UTF_8);
        }
        System.out.println("Pinned project to " + identifier);
        return 0;
    }

    /** {@code 21.0.5-tem-x64-linux} -> {@code 21.0.5-tem}. */
    static String sdkmanFormOf(String identifier) {
        String[] parts = identifier.split("-");
        if (parts.length < 4) return identifier;
        // Strip the trailing two segments (arch, os) — keep version + vendor.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 2; i++) {
            if (i > 0) sb.append('-');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
