// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkSelector;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jline.terminal.Terminal;

/**
 * 3-step TUI for {@code jk jdk install} when no {@code <spec>} was given. Asks for major version
 * (25 / 21 / 17), vendor (drawn from the catalog, defaulting to Temurin), and whether to mark the
 * installed JDK as the system-wide default. Resolves to a concrete {@link JdkCatalog.Entry} which
 * the caller hands to {@code JdkInstaller}.
 */
final class JdkInstallWizard {

    /**
     * Majors the wizard exposes — derived from the (already supported- filtered) catalog rather than
     * hard-coded, so when JDK 27 ships we don't have to update a constant.
     */
    static List<Integer> supportedMajorsFrom(JdkCatalog catalog, String os, String arch) {
        var sorted = new java.util.TreeSet<Integer>(Comparator.reverseOrder());
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os)) continue;
            if (!e.arch().equals(arch)) continue;
            sorted.add(e.majorVersion());
        }
        return List.copyOf(sorted);
    }

    /** Separator used inside vendor-step choice IDs to encode "vendor|product". */
    private static final String VENDOR_PRODUCT_SEP = "|";

    /**
     * Curated default set surfaced in the wizard. {@code --show-all} bypasses this and exposes every
     * (vendor, product) from the feed. Order here is preserved in the rendered list.
     */
    private static final List<String> CURATED_VENDOR_IDS =
            List.of("Eclipse|Temurin", "Oracle|GraalVM", "Amazon|Corretto", "BellSoft|Liberica JDK");

    private JdkInstallWizard() {}

    public record Result(JdkCatalog.Entry entry, boolean makeDefault) {}

    /**
     * One row in the vendor-step list. {@code id} is the wizard answer key (encoded
     * "vendor|product"); {@code label} is the friendly display ("Eclipse Temurin"); {@code
     * installFolderByMajor} maps each supported major to the {@code installFolderName} of the latest
     * entry for that (vendor, product, major) — used as the dark-gray hint and updated reactively
     * when the user changes their version selection.
     */
    record VendorOption(
            String id, String label, String vendor, String product, Map<Integer, String> installFolderByMajor) {

        VendorOption {
            installFolderByMajor = Map.copyOf(installFolderByMajor);
        }

        String hintFor(int major) {
            return installFolderByMajor.getOrDefault(major, "");
        }
    }

    public static Optional<Result> run(JdkCatalog catalog, String os, String arch, boolean showAll, Terminal terminal) {
        List<VendorOption> vendors = vendorsFor(catalog, os, arch, showAll);
        if (vendors.isEmpty()) {
            return Optional.empty();
        }
        List<Integer> majors = supportedMajorsFrom(catalog, os, arch);
        if (majors.isEmpty()) {
            return Optional.empty();
        }
        String vendorDefault = pickVendorDefault(vendors);

        Wizard wizard = buildWizard(majors, vendors, vendorDefault);
        Optional<Answers> ans = wizard.run(terminal);
        if (ans.isEmpty()) return Optional.empty();
        Answers a = ans.get();

        int major = Integer.parseInt(a.get("version"));
        String vendorId = a.get("vendor");
        boolean makeDefault = "yes".equals(a.get("default"));

        Optional<JdkCatalog.Entry> entry = pickLatest(catalog, os, arch, vendorId, major);
        return entry.map(e -> new Result(e, makeDefault));
    }

    static Wizard buildWizard(List<Integer> majors, List<VendorOption> vendors, String vendorDefault) {
        WizardStep.RadioStep.Builder versionStep = WizardStep.RadioStep.horizontal("version", "Select a JDK Version");
        for (Integer m : majors) {
            String s = m.toString();
            versionStep.choice(s, s);
        }
        // Default to the latest LTS we have on hand; if there's no LTS in
        // the catalog at all (unlikely), fall back to the highest major.
        int defaultMajor = majors.stream()
                .filter(dev.jkbuild.jdk.JdkLts::isLtsMajor)
                .max(Integer::compareTo)
                .orElse(majors.getFirst());
        versionStep.defaultChoice(String.valueOf(defaultMajor));

        WizardStep.RadioStep.Builder vendorStep = WizardStep.RadioStep.vertical("vendor", "Select a JDK Vendor");
        for (VendorOption v : vendors) {
            vendorStep.choice(v.id(), v.label(), answers -> hintForAnswers(v, answers));
        }
        vendorStep.defaultChoice(vendorDefault);

        WizardStep.RadioStep makeDefaultStep = WizardStep.RadioStep.horizontal("default", "Make this the default JDK?")
                .choice("yes", "Yes")
                .choice("no", "No")
                .defaultChoice("no")
                .build();

        return Wizard.builder()
                .title("Jk - Install a Java Development Kit")
                .step(versionStep.build())
                .step(vendorStep.build())
                .step(makeDefaultStep)
                .build();
    }

    /**
     * Resolve the dark-gray hint for a vendor option against the current answers — i.e., the {@code
     * installFolderName} for the major the user has selected on the version step. Falls back to the
     * option's highest supported major when the version answer is missing (e.g., when the vendor step
     * is rendered without a prior version step).
     */
    private static String hintForAnswers(VendorOption v, Answers answers) {
        var versionStr = answers.get("version");
        if (versionStr == null || versionStr.isEmpty()) {
            // No version picked yet — show the highest-major install_folder
            // so the option still carries a concrete example.
            return v.installFolderByMajor().entrySet().stream()
                    .max(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .orElse("");
        }
        try {
            return v.hintFor(Integer.parseInt(versionStr));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /**
     * Distinct (vendor, product) pairs from the catalog with at least one supported (os, arch, LTS,
     * non-preview) entry. Each option carries a map from supported major → {@code installFolderName}
     * of the latest entry for that major, so the wizard can update the dark-gray hint reactively as
     * the user picks a version.
     *
     * <p>When {@code showAll} is false, the result is restricted to the curated set ({@link
     * #CURATED_VENDOR_IDS}) and preserves that order; when true, every (vendor, product) is included,
     * Temurin pinned first.
     */
    static List<VendorOption> vendorsFor(JdkCatalog catalog, String os, String arch, boolean showAll) {
        // For each (vendor, product), track the latest entry per major.
        var perKey = new LinkedHashMap<String, Map<Integer, JdkCatalog.Entry>>();
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os)) continue;
            if (!e.arch().equals(arch)) continue;
            // Catalogs from JdkCatalogClient are already supported-filtered;
            // this defensive check covers programmatically-constructed
            // catalogs (tests, fixtures) so an unsupported major from those
            // can't leak through to the wizard.
            if (!dev.jkbuild.jdk.SupportedJdk.isSupported(e.majorVersion())) continue;
            String key = e.vendor() + VENDOR_PRODUCT_SEP + e.product();
            var byMajor = perKey.computeIfAbsent(key, k -> new TreeMap<>());
            var prior = byMajor.get(e.majorVersion());
            if (prior == null
                    || JdkSelector.versionKey(e.version()).compareTo(JdkSelector.versionKey(prior.version())) > 0) {
                byMajor.put(e.majorVersion(), e);
            }
        }
        if (!showAll) {
            return CURATED_VENDOR_IDS.stream()
                    .map(perKey::get)
                    .filter(Objects::nonNull)
                    .map(JdkInstallWizard::toOption)
                    .toList();
        }
        return perKey.values().stream()
                .map(JdkInstallWizard::toOption)
                .sorted(vendorOrder())
                .toList();
    }

    private static VendorOption toOption(Map<Integer, JdkCatalog.Entry> byMajor) {
        // Any entry works for the vendor/product/label — they're identical
        // across the map by construction.
        var any = byMajor.values().iterator().next();
        var hints = new LinkedHashMap<Integer, String>();
        for (var en : byMajor.entrySet()) {
            hints.put(en.getKey(), en.getValue().installFolderName());
        }
        return new VendorOption(
                any.vendor() + VENDOR_PRODUCT_SEP + any.product(),
                any.vendor() + " " + any.product(),
                any.vendor(),
                any.product(),
                hints);
    }

    static String pickVendorDefault(List<VendorOption> vendors) {
        // First Temurin-bearing option (case-insensitive); fall back to first.
        return vendors.stream()
                .filter(v -> v.label().toLowerCase().contains("temurin"))
                .findFirst()
                .map(VendorOption::id)
                .orElse(vendors.getFirst().id());
    }

    /** Highest non-preview entry for the (vendor+product, major, os, arch) tuple. */
    static Optional<JdkCatalog.Entry> pickLatest(
            JdkCatalog catalog, String os, String arch, String vendorId, int major) {
        int sep = vendorId.indexOf(VENDOR_PRODUCT_SEP);
        if (sep < 0) return Optional.empty();
        String vendor = vendorId.substring(0, sep);
        String product = vendorId.substring(sep + 1);
        return catalog.entries().stream()
                .filter(e -> !e.preview())
                .filter(e -> e.os().equals(os))
                .filter(e -> e.arch().equals(arch))
                .filter(e -> e.vendor().equals(vendor))
                .filter(e -> e.product().equals(product))
                .filter(e -> e.majorVersion() == major)
                .max(Comparator.comparing(e -> JdkSelector.versionKey(e.version())));
    }

    /** Temurin pinned first, others alphabetical by label — keeps the default at the visual top. */
    private static Comparator<VendorOption> vendorOrder() {
        return (a, b) -> {
            boolean aT = a.label().toLowerCase().contains("temurin");
            boolean bT = b.label().toLowerCase().contains("temurin");
            if (aT && !bT) return -1;
            if (bT && !aT) return 1;
            return a.label().compareTo(b.label());
        };
    }
}
