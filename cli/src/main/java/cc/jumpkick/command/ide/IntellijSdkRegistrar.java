// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.jdk.IntellijJdkTable;
import cc.jumpkick.util.MinimalXml;
import cc.jumpkick.util.MinimalXml.Element;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Writes {@code <jdk>} entries into the JetBrains IDEs' global SDK tables ({@code
 * <config>/<Product><Version>/options/jdk.table.xml}). The write counterpart to the read-only
 * {@link IntellijJdkTable}.
 *
 * <p>IntelliJ pins a project SDK by name; the name only resolves if a matching {@code <jdk>} exists
 * in this global table. {@code jk idea} therefore registers a {@code jk-<vendor>-<level>} SDK
 * pointing at the stable {@link StableJdkPointer} path so the generated {@code .idea/} resolves
 * with no "missing SDK", and survives point-release upgrades (the path is stable).
 *
 * <p>Everything here is <strong>best-effort</strong>: a missing IDE, an unreadable table, or a
 * malformed file is skipped, never fatal. Upserts are idempotent — an entry with the same {@code
 * <name>} is replaced in place, all other SDKs preserved.
 *
 * <p><b>Caveat (documented for callers to surface):</b> a running IDE keeps {@code jdk.table.xml}
 * in memory and rewrites it on exit, so an edit made while the IDE is open can be clobbered. Run
 * {@code jk idea} with the IDE closed, or reopen the project afterwards. This is inherent to
 * IntelliJ's config model.
 */
public final class IntellijSdkRegistrar {

    /** One SDK to register. {@code javaHome} is absolute and stable (not the patch dir). */
    public record SdkEntry(String name, Path javaHome, String version) {
        public SdkEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(javaHome, "javaHome");
        }
    }

    private final List<Path> vendorRoots;

    IntellijSdkRegistrar(List<Path> vendorRoots) {
        this.vendorRoots = List.copyOf(vendorRoots);
    }

    /**
     * Registrar over explicit vendor roots (each a directory whose children are {@code
     * <Product><Version>} config dirs). Used by {@code jk idea}'s {@code --ide-config-dir} override
     * and by tests.
     */
    public static IntellijSdkRegistrar of(List<Path> vendorRoots) {
        return new IntellijSdkRegistrar(vendorRoots);
    }

    /** Registrar backed by the host's real IDE config directories. */
    public static IntellijSdkRegistrar shared() {
        return new IntellijSdkRegistrar(IntellijJdkTable.defaultVendorRoots(
                System::getenv, System.getProperty("os.name", ""), System.getProperty("user.home", "")));
    }

    /**
     * Upsert {@code sdks} into every Java-capable IDE table found. Returns the table files actually
     * written (for caller logging). Never throws.
     */
    public List<Path> register(Collection<SdkEntry> sdks) {
        List<Path> touched = new ArrayList<>();
        if (sdks == null || sdks.isEmpty()) return touched;
        for (Path table : targetTables()) {
            try {
                upsert(table, sdks);
                touched.add(table);
            } catch (Exception ignored) {
                // Malformed table / permission issue — skip this IDE.
            }
        }
        return touched;
    }

    /**
     * {@code options/jdk.table.xml} under every installed IntelliJ IDEA / Android Studio config dir.
     */
    private List<Path> targetTables() {
        List<Path> out = new ArrayList<>();
        for (Path root : vendorRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> products = Files.list(root)) {
                products.filter(Files::isDirectory)
                        .filter(p -> isJavaIde(p.getFileName().toString()))
                        .map(p -> p.resolve("options").resolve("jdk.table.xml"))
                        .forEach(out::add);
            } catch (IOException ignored) {
                // Unreadable vendor dir — nothing to register here.
            }
        }
        return out;
    }

    /**
     * Only IDEs that actually use a JavaSDK table: IntelliJ IDEA (both editions) and Android Studio.
     */
    private static boolean isJavaIde(String product) {
        String p = product.toLowerCase(Locale.ROOT);
        return p.startsWith("intellijidea") || p.startsWith("ideaic") || p.startsWith("androidstudio");
    }

    private void upsert(Path table, Collection<SdkEntry> sdks) throws Exception {
        Element application;
        if (Files.isRegularFile(table)) {
            application = MinimalXml.parse(Files.readString(table));
        } else {
            application = Element.of("application");
        }

        Element component = findComponent(application, "ProjectJdkTable");
        if (component == null) {
            component = Element.of("component").setAttr("name", "ProjectJdkTable");
            application.append(component);
        }

        for (SdkEntry sdk : sdks) {
            Element existing = findJdkByName(component, sdk.name());
            if (existing != null) component.remove(existing);
            component.append(buildJdk(sdk));
        }

        Files.createDirectories(table.getParent());
        Files.writeString(table, MinimalXml.write(application));
    }

    private static Element buildJdk(SdkEntry sdk) {
        String home = sdk.javaHome().toAbsolutePath().normalize().toString().replace('\\', '/');

        Element jdk = Element.of("jdk").setAttr("version", "2");
        jdk.append(valued("name", sdk.name()));
        jdk.append(valued("type", "JavaSDK"));
        if (sdk.version() != null && !sdk.version().isBlank()) {
            jdk.append(valued("version", "java version \"" + sdk.version() + "\""));
        }
        jdk.append(valued("homePath", home));

        Element roots = Element.of("roots");
        Element annotations = Element.of("annotationsPath");
        annotations.append(rootEl("composite", null));
        roots.append(annotations);

        Element classPath = Element.of("classPath");
        Element composite = rootEl("composite", null);
        // Modular JDK (9+): IntelliJ represents the platform classpath as one
        // jrt root PER MODULE (jrt://<home>!/<module>), exactly as it writes
        // them itself. A single jrt://<home>!/ root over the whole image does
        // not expose any packages — the SDK shows empty and even java.lang.*
        // fails to resolve. Enumerate the modules and emit one root each; only
        // fall back to the bare root when enumeration finds nothing (e.g. a
        // pre-9 JDK or an unreadable home).
        List<String> modules = moduleNames(sdk.javaHome());
        if (modules.isEmpty()) {
            composite.append(rootEl("simple", "jrt://" + home + "!/"));
        } else {
            for (String module : modules) {
                composite.append(rootEl("simple", "jrt://" + home + "!/" + module));
            }
        }
        classPath.append(composite);
        roots.append(classPath);
        jdk.append(roots);

        jdk.append(Element.of("additional"));
        return jdk;
    }

    /**
     * Platform module names for a modular JDK home, sorted — the source for the per-module jrt
     * classpath roots IntelliJ requires. Prefers the {@code MODULES="a b c"} line in the JDK's {@code
     * release} file (present in every JDK 9+ image, including jlink runtimes that ship no {@code
     * jmods/}), then falls back to listing {@code jmods/*.jmod}. Returns empty when the home is
     * non-modular or unreadable, so the caller emits a single bare root.
     */
    private static List<String> moduleNames(Path javaHome) {
        Path release = javaHome.resolve("release");
        try {
            for (String line : Files.readAllLines(release)) {
                if (!line.startsWith("MODULES=")) continue;
                String v = line.substring("MODULES=".length()).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                if (v.isBlank()) break;
                return Stream.of(v.split("\\s+"))
                        .filter(s -> !s.isBlank())
                        .sorted()
                        .toList();
            }
        } catch (IOException ignored) {
            // No release file / unreadable — try jmods next.
        }
        Path jmods = javaHome.resolve("jmods");
        if (Files.isDirectory(jmods)) {
            try (Stream<Path> entries = Files.list(jmods)) {
                return entries.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".jmod"))
                        .map(n -> n.substring(0, n.length() - ".jmod".length()))
                        .sorted()
                        .toList();
            } catch (IOException ignored) {
                // Unreadable jmods dir — give up, fall back to the bare root.
            }
        }
        return List.of();
    }

    private static Element valued(String tag, String value) {
        return Element.of(tag).setAttr("value", value);
    }

    private static Element rootEl(String type, String url) {
        Element e = Element.of("root");
        if (url != null) e.setAttr("url", url);
        e.setAttr("type", type);
        return e;
    }

    private static Element findComponent(Element application, String name) {
        for (Element c : application.elements("component")) {
            if (name.equals(c.attr("name"))) return c;
        }
        return null;
    }

    private static Element findJdkByName(Element component, String name) {
        for (Element jdk : component.elements("jdk")) {
            for (Element n : jdk.elements("name")) {
                if (name.equals(n.attr("value"))) return jdk;
            }
        }
        return null;
    }
}
