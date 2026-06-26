// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Canonical vocabulary for the JDK vendors {@code jk} recognises on disk,
 * with cross-references into the three identifier schemes that
 * matter elsewhere:
 *
 * <ul>
 *   <li><strong>JetBrains feed</strong> — the {@code vendor}, {@code product},
 *       and {@code suggested_sdk_name} prefix used by
 *       {@code download.jetbrains.com/jdk/feed} (which {@code jk jdk install}
 *       and {@code jk jdk list} consume).</li>
 *   <li><strong>SDKMAN</strong> — the candidate-suffix conventions used by
 *       {@code sdk install java <version>-<suffix>}.</li>
 *   <li><strong>foojay Disco API</strong> — the {@code distro} name used by
 *       {@code https://api.foojay.io/disco}.</li>
 * </ul>
 *
 * <p>{@link #fromRelease} reads {@code $JAVA_HOME/release}'s {@code IMPLEMENTOR}
 * (with {@code IMPLEMENTOR_VERSION} disambiguation) and returns the matching
 * vendor. Falls back to {@link #UNKNOWN} when nothing matches.
 */
public enum JdkVendor {

    //                  vendor               product         jbPrefix         sdkman      foojay
    TEMURIN("Eclipse", "Temurin", "temurin", "tem", "temurin"),
    ADOPT_OPENJDK("AdoptOpenJDK", "OpenJDK", null, "adpt", "aoj"),
    ORACLE_OPENJDK("Oracle", "OpenJDK", "openjdk", "open", "oracle_open_jdk"),
    ORACLE_GRAALVM("Oracle", "GraalVM", "graalvm", "graal", "graalvm"),
    GRAALVM_CE("GraalVM Community", "GraalVM CE", "graalvm-ce", "graalce", "graalvm_ce"),
    CORRETTO("Amazon", "Corretto", "corretto", "amzn", "corretto"),
    ZULU("Azul", "Zulu", "zulu", "zulu", "zulu"),
    ZULU_PRIME("Azul", "Zulu Prime", null, "zing", "zulu_prime"),
    LIBERICA("BellSoft", "Liberica", "liberica", "librca", "liberica"),
    SAPMACHINE("SAP", "SapMachine", "sapmachine", "sapmchn", "sap_machine"),
    SEMERU("IBM", "Semeru", "semeru", "sem", "semeru"),
    MICROSOFT("Microsoft", "OpenJDK", "microsoft", "ms", "microsoft"),
    DRAGONWELL("Alibaba", "Dragonwell", "dragonwell", null, "dragonwell"),
    JBR("JetBrains", "Runtime", "jbr", null, "jetbrains"),
    REDHAT("Red Hat", "OpenJDK", null, null, "redhat"),
    MANDREL("Red Hat", "Mandrel", null, null, "mandrel"),
    KONA("Tencent", "Kona", null, null, "kona"),
    BISHENG("Huawei", "Bisheng", null, null, "bisheng"),
    OJDKBUILD("ojdkbuild", "OpenJDK", null, null, "ojdk_build"),
    OPENLOGIC("OpenLogic", "OpenJDK", null, null, "openlogic"),
    DEBIAN("Debian", "OpenJDK", null, null, "debian"),
    UBUNTU("Ubuntu", "OpenJDK", null, null, "ubuntu"),
    HOMEBREW("Homebrew", "OpenJDK", null, null, "homebrew"),
    UNKNOWN("Unknown", "OpenJDK", null, null, null);

    private final String vendor;
    private final String product;
    private final String jbPrefix;
    private final String sdkmanSuffix;
    private final String foojayDistro;

    JdkVendor(String vendor, String product, String jbPrefix, String sdkmanSuffix, String foojayDistro) {
        this.vendor = vendor;
        this.product = product;
        this.jbPrefix = jbPrefix;
        this.sdkmanSuffix = sdkmanSuffix;
        this.foojayDistro = foojayDistro;
    }

    /** JetBrains feed {@code vendor} field (e.g. {@code "Eclipse"}, {@code "Oracle"}). */
    public String vendor() {
        return vendor;
    }

    /** JetBrains feed {@code product} field (e.g. {@code "Temurin"}, {@code "GraalVM"}). */
    public String product() {
        return product;
    }

    /** Vendor + product, joined for display (e.g. {@code "Eclipse Temurin"}). */
    public String displayName() {
        // Avoid awkward duplication when vendor already ends with the product word
        // (e.g. "GraalVM Community" + "GraalVM CE" → "GraalVM Community").
        if (vendor.toLowerCase(Locale.ROOT).startsWith(product.toLowerCase(Locale.ROOT))) {
            return vendor;
        }
        return vendor + " " + product;
    }

    /**
     * JetBrains {@code suggested_sdk_name} prefix, or empty when this vendor
     * isn't in the JetBrains feed. The full identifier for a specific
     * install is {@code jbPrefix + "-" + version} (e.g. {@code "temurin-21.0.5"}).
     */
    public Optional<String> jbPrefix() {
        return Optional.ofNullable(jbPrefix);
    }

    /**
     * SDKMAN candidate suffix, or empty when this vendor isn't on SDKMAN.
     * The full identifier is {@code version + "-" + sdkmanSuffix}
     * (e.g. {@code "21.0.5-tem"}).
     */
    public Optional<String> sdkmanSuffix() {
        return Optional.ofNullable(sdkmanSuffix);
    }

    /** foojay Disco API {@code distro} name, or empty when not represented there. */
    public Optional<String> foojayDistro() {
        return Optional.ofNullable(foojayDistro);
    }

    /** {@code jbPrefix + "-" + version}, e.g. {@code "corretto-26.0.1"}; empty when no prefix. */
    public Optional<String> jbIdentifier(String version) {
        return jbPrefix().map(p -> p + "-" + version);
    }

    /** {@code version + "-" + sdkmanSuffix}, e.g. {@code "26.0.1-amzn"}; empty when no suffix. */
    public Optional<String> sdkmanIdentifier(String version) {
        return sdkmanSuffix().map(s -> version + "-" + s);
    }

    /**
     * Vendor preference for a vendor-unqualified spec (and for breaking ties):
     * Eclipse Temurin, then BellSoft Liberica, Oracle OpenJDK, Amazon Corretto.
     * Any vendor not listed sorts after all listed ones (see {@link #preferenceRank}).
     */
    public static final List<JdkVendor> PREFERENCE = List.of(TEMURIN, LIBERICA, ORACLE_OPENJDK, CORRETTO);

    /** GraalVM-flavour preference for the native / graal chain: Oracle GraalVM, then GraalVM CE. */
    public static final List<JdkVendor> GRAAL_PREFERENCE = List.of(ORACLE_GRAALVM, GRAALVM_CE);

    /** Lower is more preferred. Listed vendors get their index; others sort after, by enum order. */
    public int preferenceRank() {
        int i = PREFERENCE.indexOf(this);
        return i >= 0 ? i : PREFERENCE.size() + ordinal();
    }

    /** Comparator ordering vendors most-preferred first (by {@link #preferenceRank}). */
    public static Comparator<JdkVendor> byPreference() {
        return Comparator.comparingInt(JdkVendor::preferenceRank);
    }

    /**
     * Detect a JDK's vendor by reading {@code home/release}'s {@code IMPLEMENTOR}
     * and {@code IMPLEMENTOR_VERSION} properties. Returns {@link #UNKNOWN} when
     * the file is missing, malformed, or matches no known vendor.
     *
     * <p>Prefer {@link #fromProperties(Properties)} when the caller has already
     * parsed the release file — this method exists so callers that only have
     * a {@link Path} don't have to load the file themselves.
     */
    /**
     * Resolve a vendor from the JetBrains feed's {@code vendor} + {@code product}
     * strings (e.g. {@code "Oracle"} + {@code "GraalVM"} → {@link #ORACLE_GRAALVM}).
     * Returns {@link #UNKNOWN} when no enum constant matches both fields.
     */
    public static JdkVendor fromFeed(String vendor, String product) {
        if (vendor == null || product == null) return UNKNOWN;
        for (JdkVendor v : values()) {
            if (v != UNKNOWN && v.vendor.equalsIgnoreCase(vendor) && v.product.equalsIgnoreCase(product)) {
                return v;
            }
        }
        return UNKNOWN;
    }

    public static JdkVendor fromRelease(Path home) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) return UNKNOWN;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException e) {
            return UNKNOWN;
        }
        return fromProperties(props);
    }

    /**
     * Detect a JDK's vendor from pre-parsed release-file properties.
     *
     * <p>Disambiguation rules (ported from JDKMon's {@code Finder.java:507}):
     * <ul>
     *   <li>{@code Azul Systems, Inc.} + {@code IMPLEMENTOR_VERSION}
     *       starting with {@code "Zing"} or {@code "Prime"} → {@link #ZULU_PRIME}.</li>
     *   <li>{@code Oracle Corporation} + {@code IMPLEMENTOR_VERSION} containing
     *       {@code "GraalVM"} (or a {@code GRAALVM_VERSION} property present) →
     *       {@link #ORACLE_GRAALVM}, with {@code "Community"} downgrading to
     *       {@link #GRAALVM_CE}.</li>
     * </ul>
     */
    public static JdkVendor fromProperties(Properties props) {
        String implementor = stripQuotes(props.getProperty("IMPLEMENTOR", ""));
        String implementorVersion = stripQuotes(props.getProperty("IMPLEMENTOR_VERSION", ""));

        JdkVendor base = byImplementor(implementor);

        // Azul: Zulu vs Zulu Prime
        if (base == ZULU && (implementorVersion.startsWith("Zing") || implementorVersion.startsWith("Prime"))) {
            return ZULU_PRIME;
        }

        // Oracle: OpenJDK vs Oracle GraalVM vs GraalVM Community
        if (base == ORACLE_OPENJDK) {
            String iv = implementorVersion.toLowerCase(Locale.ROOT);
            boolean hasGraalvmVersion = props.getProperty("GRAALVM_VERSION") != null;
            if (iv.contains("graalvm") || hasGraalvmVersion) {
                if (iv.contains("community") || iv.contains("ce")) return GRAALVM_CE;
                return ORACLE_GRAALVM;
            }
        }

        return base;
    }

    private static JdkVendor byImplementor(String implementor) {
        return switch (implementor) {
            case "Eclipse Foundation", "Eclipse Adoptium" -> TEMURIN;
            case "AdoptOpenJDK" -> ADOPT_OPENJDK;
            case "Amazon.com Inc." -> CORRETTO;
            case "Azul Systems, Inc." -> ZULU;
            case "BellSoft" -> LIBERICA;
            case "SAP SE" -> SAPMACHINE;
            case "International Business Machines Corporation", "IBM Corporation" -> SEMERU;
            case "Microsoft" -> MICROSOFT;
            case "Alibaba" -> DRAGONWELL;
            case "JetBrains s.r.o." -> JBR;
            case "Red Hat, Inc." -> REDHAT;
            case "mandrel" -> MANDREL;
            case "Tencent" -> KONA;
            case "Bisheng" -> BISHENG;
            case "ojdkbuild" -> OJDKBUILD;
            case "OpenLogic" -> OPENLOGIC;
            case "Debian" -> DEBIAN;
            case "Ubuntu" -> UBUNTU;
            case "Homebrew" -> HOMEBREW;
            case "Oracle Corporation" -> ORACLE_OPENJDK; // refined below if GraalVM markers present
            default -> UNKNOWN;
        };
    }

    private static String stripQuotes(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }
}
