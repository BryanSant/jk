// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.net.URI;
import java.util.Objects;

/**
 * Metadata for a downloadable JDK distribution from the foojay Disco API (PRD §12). Carries the
 * redirect URL the actual archive is at and the sha256 so the installer can verify what it
 * downloaded.
 */
public record JdkPackage(
        String distribution,
        String version,
        String architecture,
        String operatingSystem,
        String archiveType,
        String filename,
        URI downloadUri,
        String sha256,
        long size) {

    public JdkPackage {
        Objects.requireNonNull(distribution, "distribution");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(architecture, "architecture");
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(archiveType, "archiveType");
        Objects.requireNonNull(downloadUri, "downloadUri");
    }

    /**
     * SDKMAN-style identifier: {@code 21.0.5-tem}. Uses the foojay distribution abbreviation as the
     * suffix.
     */
    public String sdkmanIdentifier() {
        return version + "-" + distributionAbbreviation();
    }

    /**
     * The jk-style identifier this package would be installed as under the IntelliJ JDK directory:
     * {@code 21.0.5-tem-x64-linux}. Matches the directory name {@link InstalledJdk#identifier()}
     * carries, so the list / install / uninstall commands can address the same string.
     */
    public String installIdentifier() {
        return sdkmanIdentifier() + "-" + architecture + "-" + operatingSystem;
    }

    private String distributionAbbreviation() {
        return switch (distribution.toLowerCase()) {
            case "temurin" -> "tem";
            case "graalvm_ce", "graalvm-ce" -> "graalce";
            case "graalvm_oracle", "graalvm" -> "graal";
            case "liberica" -> "librca";
            case "zulu" -> "zulu";
            case "corretto" -> "amzn";
            case "oracle_openjdk", "oracle" -> "open";
            case "semeru", "semeru_certified" -> "sem";
            case "sapmachine" -> "sapmchn";
            case "microsoft" -> "ms";
            default -> distribution.toLowerCase().replace("_", "-");
        };
    }
}
