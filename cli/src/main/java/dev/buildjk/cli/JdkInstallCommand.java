// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.http.Http;
import dev.buildjk.jdk.DiscoClient;
import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.jdk.JdkInstaller;
import dev.buildjk.jdk.JdkPackage;
import dev.buildjk.jdk.JdkRegistry;
import dev.buildjk.jdk.Platform;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk install <spec>} — query Disco for the requested JDK, download
 * it, verify sha256, extract into {@code ~/.jk/jdks/}.
 *
 * <p>v0.4 first iteration accepts spec forms: {@code <version>} (default
 * vendor = Temurin) and {@code <version>-<vendor>} (SDKMAN-style suffix).
 * Range / "lts" / "latest" tokens land later.
 */
@Command(name = "install", description = "Download and install JDK versions")
public final class JdkInstallCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<spec>",
            description = "Version or version-vendor (e.g. 21, 21.0.5-tem, 21-graalce).")
    String spec;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Option(names = "--disco-url", hidden = true,
            description = "Override the foojay Disco API base URL (for tests).")
    URI discoUrl;

    @Override
    public Integer call() throws Exception {
        VendorSpec parsed = VendorSpec.parse(spec);

        DiscoClient.SearchQuery query = DiscoClient.SearchQuery.builder()
                .distribution(parsed.distribution())
                .version(parsed.version())
                .architecture(Platform.currentArchitecture())
                .operatingSystem(Platform.currentOperatingSystem())
                .archiveType(Platform.currentArchiveType())
                .libCType(Platform.currentLibCType())
                .build();

        DiscoClient client = discoUrl != null
                ? new DiscoClient(new Http(), discoUrl)
                : new DiscoClient();
        List<JdkPackage> packages = client.search(query);
        if (packages.isEmpty()) {
            System.err.println("jk jdk install: no Disco package matched "
                    + parsed.distribution() + " " + parsed.version()
                    + " on " + Platform.currentArchitecture() + "/" + Platform.currentOperatingSystem());
            return 1;
        }
        JdkPackage pkg = packages.getFirst();
        System.out.println("Installing " + pkg.distribution() + " " + pkg.version()
                + " (" + pkg.architecture() + "/" + pkg.operatingSystem() + ")...");

        Path jdksRoot = jdksDir != null
                ? jdksDir
                : Path.of(System.getProperty("user.home"), ".jk", "jdks");
        JdkRegistry registry = new JdkRegistry(jdksRoot);
        InstalledJdk installed = new JdkInstaller(new Http(), registry).install(pkg);

        System.out.println("Installed " + installed.identifier() + " at " + installed.home());
        return 0;
    }

    /**
     * Parses {@code <version>}, {@code <version>-<vendor>} into a
     * (version, foojay distribution) pair.
     */
    record VendorSpec(String version, String distribution) {

        static VendorSpec parse(String spec) {
            // Handle "graalvm-21" / "graalvm-jdk-21" prefixes.
            String lower = spec.toLowerCase();
            if (lower.startsWith("graalvm-jdk-")) {
                return new VendorSpec(spec.substring("graalvm-jdk-".length()), "graalvm_oracle");
            }
            if (lower.startsWith("graalvm-")) {
                return new VendorSpec(spec.substring("graalvm-".length()), "graalvm_ce");
            }
            int dash = spec.lastIndexOf('-');
            if (dash > 0 && dash < spec.length() - 1) {
                String version = spec.substring(0, dash);
                String vendor = spec.substring(dash + 1).toLowerCase();
                return new VendorSpec(version, vendorToDistribution(vendor));
            }
            return new VendorSpec(spec, "temurin");
        }

        static String vendorToDistribution(String vendor) {
            return switch (vendor) {
                case "tem", "temurin" -> "temurin";
                case "graalce", "graal-ce" -> "graalvm_ce";
                case "graal", "graalvm" -> "graalvm_oracle";
                case "librca", "liberica" -> "liberica";
                case "zulu", "zlu" -> "zulu";
                case "amzn", "corretto" -> "corretto";
                case "sapmchn", "sapmachine" -> "sapmachine";
                case "sem", "semeru" -> "semeru";
                case "ms", "microsoft" -> "microsoft";
                case "open", "oracle" -> "oracle_openjdk";
                default -> vendor;
            };
        }
    }
}
