// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.jdk.JdkVendor;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A row in the {@code jk new} "Select a JDK" step — either an installed JDK (wraps {@link
 * NewJdkOptions.Option}) or an installable catalog entry (wraps {@link JdkCatalog.Entry}). Used so
 * the wizard can mix already-present JDKs with "we'll install this for you" suggestions (e.g.,
 * latest LTS when none is on disk; latest GraalVM when the user picks the Native build target).
 */
public sealed interface NewJdkCandidate {

    /** Stable id used as the radio-step answer value. */
    String id();

    /**
     * Human-readable label. Format: {@code "JDK <major> - <Vendor> <Product>"} (e.g. {@code "JDK 25 -
     * Eclipse Temurin"}). Unknown vendors fall back to the install id.
     */
    String label();

    /** Vendor used for native-filter logic ({@code GraalVM} variants) and dedup. */
    JdkVendor vendor();

    /**
     * Friendly product label used both in the radio label and in the dedup key, e.g. {@code "Eclipse
     * Temurin"}. Catalog entries carry this directly from the feed; installed candidates derive it
     * from the resolved {@link JdkVendor}.
     */
    String vendorLabel();

    /** Java feature release (e.g. 25). */
    int major();

    /** True when the candidate is already on disk; false when we'd auto-install on select. */
    boolean installed();

    /**
     * Dark-gray suffix rendered after the label by the wizard. Installed rows surface the install
     * identifier (so {@code (temurin-25.0.3)} tells the user where it'll be picked from); installable
     * rows show {@code (will install)}.
     */
    default String hint() {
        return installed() ? "(" + id() + ")" : "(will install)";
    }

    /** Adapter over an installed JDK. */
    record Installed(NewJdkOptions.Option option, JdkVendor resolvedVendor) implements NewJdkCandidate {

        @Override
        public String id() {
            return option.id();
        }

        @Override
        public JdkVendor vendor() {
            return resolvedVendor;
        }

        @Override
        public int major() {
            return option.major();
        }

        @Override
        public boolean installed() {
            return true;
        }

        @Override
        public String vendorLabel() {
            return resolvedVendor == JdkVendor.UNKNOWN
                    ? option.id()
                    : resolvedVendor.vendor() + " " + resolvedVendor.product();
        }

        @Override
        public String label() {
            return "JDK " + major() + " - " + vendorLabel();
        }
    }

    /** Adapter over a catalog entry we'd install on selection. */
    record Installable(JdkCatalog.Entry entry) implements NewJdkCandidate {

        @Override
        public String id() {
            return entry.installFolderName();
        }

        @Override
        public JdkVendor vendor() {
            return vendorByLabel(entry.vendor(), entry.product());
        }

        @Override
        public int major() {
            return entry.majorVersion();
        }

        @Override
        public boolean installed() {
            return false;
        }

        @Override
        public String vendorLabel() {
            return entry.vendor() + " " + entry.product();
        }

        @Override
        public String label() {
            return "JDK " + major() + " - " + vendorLabel();
        }
    }

    /** Convenience: is this candidate a GraalVM build (Oracle or CE)? */
    static boolean isGraalvm(NewJdkCandidate c) {
        return c.vendor() == JdkVendor.ORACLE_GRAALVM || c.vendor() == JdkVendor.GRAALVM_CE;
    }

    /**
     * Build the list of candidates the wizard should consider, deduplicated by {@code (vendor,
     * product, major)} so that an SDKMAN-managed {@code 25.0.3-graal} and the catalog's "Oracle
     * GraalVM 25" don't both appear — they're the same JDK in two wrappers.
     *
     * <p>Priority within a duplicate group: installed candidates beat installable ones; within
     * installed, {@code installed} arrives in the order {@link NewJdkOptions#discover} returns it
     * (jk-managed installs first, then external probes), so {@code ~/.jdks/temurin-25.0.3} wins over
     * {@code ~/.sdkman/.../25.0.3-tem}. Unknown-vendor entries are never collapsed — we can't safely
     * identify them as duplicates of anything.
     *
     * <p>Latest-LTS installable rows for Temurin, Oracle GraalVM, and GraalVM CE are appended when
     * their {@code (vendor, product, major)} slot is still vacant after the installed pass — so a
     * host with nothing on disk still gets all three offered.
     */
    static List<NewJdkCandidate> build(
            List<NewJdkOptions.Option> installed,
            Optional<JdkCatalog> catalog,
            int latestLtsMajor,
            String os,
            String arch) {
        return build(installed, catalog, latestLtsMajor, os, arch, NewJdkCandidate::inferVendor);
    }

    /**
     * Test-friendly overload: caller injects the vendor resolver. Production uses {@link
     * #inferVendor} which reads the install's release file; tests can pass a precomputed mapping so
     * fake-path fixtures don't have to also stage release files.
     */
    static List<NewJdkCandidate> build(
            List<NewJdkOptions.Option> installed,
            Optional<JdkCatalog> catalog,
            int latestLtsMajor,
            String os,
            String arch,
            Function<NewJdkOptions.Option, JdkVendor> vendorResolver) {
        var seen = new java.util.LinkedHashSet<DedupKey>();
        var out = new java.util.ArrayList<NewJdkCandidate>();
        for (var opt : installed) {
            var c = new Installed(opt, vendorResolver.apply(opt));
            if (seen.add(keyOf(c))) out.add(c);
        }
        if (catalog.isPresent()) {
            for (var spec : LATEST_LTS_VENDORS) {
                var entry = latestLtsEntry(catalog.get(), os, arch, spec.vendor(), spec.product(), latestLtsMajor);
                if (entry.isEmpty()) continue;
                var c = new Installable(entry.get());
                if (seen.add(keyOf(c))) out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Equality key for the {@code build()} dedup. Unknown-vendor candidates use their install id as a
     * sentinel so each stays distinct — we don't know enough about them to safely collapse.
     */
    record DedupKey(String vendor, String product, int major) {}

    private static DedupKey keyOf(NewJdkCandidate c) {
        if (c.vendor() == JdkVendor.UNKNOWN) {
            return new DedupKey("__unknown__:" + c.id(), "", c.major());
        }
        return new DedupKey(c.vendor().vendor(), c.vendor().product(), c.major());
    }

    /**
     * Filter for the radio step:
     *
     * <ul>
     *   <li>Native build selected — keep only GraalVM candidates (Oracle + CE) at {@code
     *       latestLtsMajor}. GraalVM is the only thing that can produce a native binary.
     *   <li>Anything else — promote the latest-LTS Eclipse Temurin to the top of the list (so it's
     *       the default selection), then preserve the existing order for everything else. The Temurin
     *       LTS is always present because {@link #build} stamps a catalog row when it's not already
     *       installed.
     * </ul>
     */
    static List<NewJdkCandidate> filter(List<NewJdkCandidate> all, boolean nativeSelected, int latestLtsMajor) {
        if (nativeSelected) {
            return all.stream()
                    .filter(NewJdkCandidate::isGraalvm)
                    .filter(c -> c.major() == latestLtsMajor)
                    .sorted(Comparator.comparing(NewJdkCandidate::installed)
                            .reversed()
                            .thenComparing(NewJdkCandidate::label))
                    .toList();
        }
        var temurinLts = all.stream()
                .filter(c -> c.vendor() == JdkVendor.TEMURIN && c.major() == latestLtsMajor)
                .findFirst();
        if (temurinLts.isEmpty()) return all;
        var reordered = new java.util.ArrayList<NewJdkCandidate>(all.size());
        reordered.add(temurinLts.get());
        for (var c : all) {
            if (c != temurinLts.get()) reordered.add(c);
        }
        return List.copyOf(reordered);
    }

    /** Catalog vendors we offer as installable latest-LTS rows. */
    record VendorSpec(String vendor, String product) {}

    List<VendorSpec> LATEST_LTS_VENDORS = List.of(
            new VendorSpec("Eclipse", "Temurin"),
            new VendorSpec("Oracle", "GraalVM"),
            new VendorSpec("GraalVM Community", "GraalVM CE"));

    private static Optional<JdkCatalog.Entry> latestLtsEntry(
            JdkCatalog catalog, String os, String arch, String vendor, String product, int major) {
        return catalog.entries().stream()
                .filter(e -> !e.preview())
                .filter(e -> e.os().equals(os))
                .filter(e -> e.arch().equals(arch))
                .filter(e -> e.vendor().equals(vendor))
                .filter(e -> e.product().equals(product))
                .filter(e -> e.majorVersion() == major)
                .max(Comparator.comparing(e -> JdkSelector.versionKey(e.version())));
    }

    private static JdkVendor inferVendor(NewJdkOptions.Option option) {
        // The Option carries the home path; use the release file to resolve
        // its vendor (same approach used by the JkEnv hook-env flow).
        try {
            return JdkVendor.fromRelease(option.home());
        } catch (RuntimeException ignored) {
            return JdkVendor.UNKNOWN;
        }
    }

    /**
     * Map a JetBrains-feed {@code (vendor, product)} pair back to a {@link JdkVendor} enum value.
     * {@link JdkVendor} itself only exposes the release-file detector; we need this for the inverse
     * direction when presenting catalog entries to the wizard.
     */
    private static JdkVendor vendorByLabel(String vendor, String product) {
        for (JdkVendor v : JdkVendor.values()) {
            if (v.vendor().equals(vendor) && v.product().equals(product)) return v;
        }
        return JdkVendor.UNKNOWN;
    }
}
