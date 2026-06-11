// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Writes {@code <jdk>} entries into the JetBrains IDEs' global SDK tables
 * ({@code <config>/<Product><Version>/options/jdk.table.xml}). The write
 * counterpart to the read-only {@link IntellijJdkTable}.
 *
 * <p>IntelliJ pins a project SDK by name; the name only resolves if a matching
 * {@code <jdk>} exists in this global table. {@code jk idea} therefore registers
 * a {@code jk-<vendor>-<level>} SDK pointing at the stable
 * {@link StableJdkPointer} path so the generated {@code .idea/} resolves with no
 * "missing SDK", and survives point-release upgrades (the path is stable).
 *
 * <p>Everything here is <strong>best-effort</strong>: a missing IDE, an
 * unreadable table, or a malformed file is skipped, never fatal. Upserts are
 * idempotent — an entry with the same {@code <name>} is replaced in place, all
 * other SDKs preserved.
 *
 * <p><b>Caveat (documented for callers to surface):</b> a running IDE keeps
 * {@code jdk.table.xml} in memory and rewrites it on exit, so an edit made while
 * the IDE is open can be clobbered. Run {@code jk idea} with the IDE closed, or
 * reopen the project afterwards. This is inherent to IntelliJ's config model.
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
     * Registrar over explicit vendor roots (each a directory whose children are
     * {@code <Product><Version>} config dirs). Used by {@code jk idea}'s
     * {@code --ide-config-dir} override and by tests.
     */
    public static IntellijSdkRegistrar of(List<Path> vendorRoots) {
        return new IntellijSdkRegistrar(vendorRoots);
    }

    /** Registrar backed by the host's real IDE config directories. */
    public static IntellijSdkRegistrar shared() {
        return new IntellijSdkRegistrar(IntellijJdkTable.defaultVendorRoots(
                System::getenv,
                System.getProperty("os.name", ""),
                System.getProperty("user.home", "")));
    }

    /**
     * Upsert {@code sdks} into every Java-capable IDE table found. Returns the
     * table files actually written (for caller logging). Never throws.
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

    /** {@code options/jdk.table.xml} under every installed IntelliJ IDEA / Android Studio config dir. */
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

    /** Only IDEs that actually use a JavaSDK table: IntelliJ IDEA (both editions) and Android Studio. */
    private static boolean isJavaIde(String product) {
        String p = product.toLowerCase(Locale.ROOT);
        return p.startsWith("intellijidea") || p.startsWith("ideaic") || p.startsWith("androidstudio");
    }

    private void upsert(Path table, Collection<SdkEntry> sdks) throws Exception {
        DocumentBuilder db = newDocumentBuilder();
        Document doc;
        if (Files.isRegularFile(table)) {
            try (InputStream in = Files.newInputStream(table)) {
                doc = db.parse(in);
            }
        } else {
            doc = db.newDocument();
            doc.appendChild(doc.createElement("application"));
        }

        Element application = doc.getDocumentElement();
        if (application == null) {
            application = doc.createElement("application");
            doc.appendChild(application);
        }
        Element component = findComponent(application, "ProjectJdkTable");
        if (component == null) {
            component = doc.createElement("component");
            component.setAttribute("name", "ProjectJdkTable");
            application.appendChild(component);
        }

        for (SdkEntry sdk : sdks) {
            Element existing = findJdkByName(component, sdk.name());
            if (existing != null) component.removeChild(existing);
            component.appendChild(buildJdk(doc, sdk));
        }

        Files.createDirectories(table.getParent());
        try (OutputStream out = Files.newOutputStream(table)) {
            writeDocument(doc, out);
        }
    }

    private static Element buildJdk(Document doc, SdkEntry sdk) {
        String home = sdk.javaHome().toAbsolutePath().normalize().toString().replace('\\', '/');

        Element jdk = doc.createElement("jdk");
        jdk.setAttribute("version", "2");
        jdk.appendChild(valued(doc, "name", sdk.name()));
        jdk.appendChild(valued(doc, "type", "JavaSDK"));
        if (sdk.version() != null && !sdk.version().isBlank()) {
            jdk.appendChild(valued(doc, "version", "java version \"" + sdk.version() + "\""));
        }
        jdk.appendChild(valued(doc, "homePath", home));

        Element roots = doc.createElement("roots");
        Element annotations = doc.createElement("annotationsPath");
        annotations.appendChild(rootEl(doc, "composite", null));
        roots.appendChild(annotations);

        Element classPath = doc.createElement("classPath");
        Element composite = rootEl(doc, "composite", null);
        // Modular JDK (9+): a single jrt root over the (stable) home.
        composite.appendChild(rootEl(doc, "simple", "jrt://" + home + "!/"));
        classPath.appendChild(composite);
        roots.appendChild(classPath);
        jdk.appendChild(roots);

        jdk.appendChild(doc.createElement("additional"));
        return jdk;
    }

    private static Element valued(Document doc, String tag, String value) {
        Element e = doc.createElement(tag);
        e.setAttribute("value", value);
        return e;
    }

    private static Element rootEl(Document doc, String type, String url) {
        Element e = doc.createElement("root");
        if (url != null) e.setAttribute("url", url);
        e.setAttribute("type", type);
        return e;
    }

    private static Element findComponent(Element application, String name) {
        for (Element c : children(application, "component")) {
            if (name.equals(c.getAttribute("name"))) return c;
        }
        return null;
    }

    private static Element findJdkByName(Element component, String name) {
        for (Element jdk : children(component, "jdk")) {
            for (Element n : children(jdk, "name")) {
                if (name.equals(n.getAttribute("value"))) return jdk;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // Harden against XXE — these files are trusted, but there's no reason to
        // resolve external entities (mirrors IntellijJdkTable's reader).
        setFeature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(f, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(f, "http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static void setFeature(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // Feature unsupported by this parser — fine, the others still apply.
        }
    }

    private static void writeDocument(Document doc, OutputStream out) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.transform(new DOMSource(doc), new StreamResult(out));
    }
}
