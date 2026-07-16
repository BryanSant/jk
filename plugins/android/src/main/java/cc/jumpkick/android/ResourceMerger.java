// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * AGP-style dependency resource merging (android-plan A5e finding 9): the AAR closure's
 * {@code res/} trees fold into ONE merged tree before a single aapt2 compile+link. Two androidx
 * libraries routinely define the same value resource (compose-ui and compose-foundation both
 * ship {@code string/autofill} translations), and aapt2 hard-errors on conflicting values across
 * separate link inputs — merging first, at resource granularity, is how AGP resolves it.
 *
 * <p>Precedence: dependencies in classpath order, <b>earlier wins</b> (the closer dependency
 * overrides, matching AGP's dependency-order overlay); the module's own resources never enter
 * this tree — they stay the link's {@code -R} overlay, so the app always beats a library.
 *
 * <ul>
 *   <li><b>File resources</b> (layout/, drawable/, raw/, …): first writer of a
 *       {@code <config-dir>/<file>} pair keeps it.
 *   <li><b>Value resources</b> ({@code values*} dirs): each file's {@code <resources>} children
 *       merge per config dir, keyed by {@code (tag, type-attr, name)} — first definition wins,
 *       whole elements are atomic (a {@code declare-styleable} replaces as a unit). Merged
 *       entries serialize into one {@code values.xml} per config; {@code xmlns:*} declarations
 *       from every contributing root re-declare on the merged root (parsing is
 *       namespace-unaware, so prefixed attributes ride verbatim).
 * </ul>
 */
final class ResourceMerger {

    private ResourceMerger() {}

    /**
     * Merge every AAR's {@code res/} into {@code out}. Returns {@code out}, or null when no
     * dependency carries resources (link then has no dep input at all).
     */
    static Path mergeDepRes(List<AndroidDeps.Aar> aars, Path out) throws Exception {
        // config dir → resource key → element (first wins); plus the xmlns decls seen.
        Map<String, Map<String, Element>> valuesByConfig = new LinkedHashMap<>();
        Map<String, String> xmlns = new TreeMap<>();
        boolean any = false;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();

        for (AndroidDeps.Aar aar : aars) {
            if (!aar.hasRes()) continue;
            any = true;
            try (var configs = Files.list(aar.res())) {
                for (Path configDir : (Iterable<Path>) configs.sorted()::iterator) {
                    if (!Files.isDirectory(configDir)) continue;
                    String config = configDir.getFileName().toString();
                    if (config.equals("values") || config.startsWith("values-")) {
                        mergeValuesDir(db, configDir, valuesByConfig.computeIfAbsent(config, k -> new LinkedHashMap<>()), xmlns);
                    } else {
                        copyFirstWins(configDir, out.resolve(config));
                    }
                }
            }
        }
        if (!any) return null;

        for (var config : valuesByConfig.entrySet()) {
            writeMergedValues(db, config.getValue(), xmlns, out.resolve(config.getKey()).resolve("values.xml"));
        }
        Files.createDirectories(out); // an all-values closure still needs the tree root to exist
        return out;
    }

    private static void copyFirstWins(Path configDir, Path outDir) throws IOException {
        try (var files = Files.list(configDir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!Files.isRegularFile(file)) continue;
                Path target = outDir.resolve(file.getFileName().toString());
                if (Files.exists(target)) continue; // earlier dependency won
                Files.createDirectories(outDir);
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void mergeValuesDir(
            DocumentBuilder db, Path valuesDir, Map<String, Element> merged, Map<String, String> xmlns)
            throws Exception {
        try (var files = Files.list(valuesDir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!Files.isRegularFile(file) || !file.toString().endsWith(".xml")) continue;
                Document doc = db.parse(file.toFile());
                Element root = doc.getDocumentElement();
                if (!"resources".equals(root.getTagName())) continue;
                var attrs = root.getAttributes();
                for (int a = 0; a < attrs.getLength(); a++) {
                    Node attr = attrs.item(a);
                    if (attr.getNodeName().startsWith("xmlns:")) {
                        xmlns.putIfAbsent(attr.getNodeName(), attr.getNodeValue());
                    }
                }
                NodeList children = root.getChildNodes();
                for (int c = 0; c < children.getLength(); c++) {
                    if (!(children.item(c) instanceof Element el)) continue;
                    merged.putIfAbsent(keyOf(el), el);
                }
            }
        }
    }

    /** {@code tag/type-attr/name} — {@code <item type="id" name="x">} keys apart from strings. */
    private static String keyOf(Element el) {
        return el.getTagName() + '/' + el.getAttribute("type") + '/' + el.getAttribute("name");
    }

    private static void writeMergedValues(
            DocumentBuilder db, Map<String, Element> entries, Map<String, String> xmlns, Path out)
            throws Exception {
        Document doc = db.newDocument();
        Element root = doc.createElement("resources");
        for (var decl : xmlns.entrySet()) {
            root.setAttribute(decl.getKey(), decl.getValue());
        }
        doc.appendChild(root);
        for (Element el : entries.values()) {
            root.appendChild(doc.importNode(el, true));
        }
        Files.createDirectories(out.getParent());
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter sw = new StringWriter();
        tf.transform(new DOMSource(doc), new StreamResult(sw));
        Files.writeString(out, sw.toString());
    }
}
