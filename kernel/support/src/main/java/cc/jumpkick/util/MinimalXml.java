// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A deliberately minimal XML reader/writer for the handful of small, machine-written config files
 * jk touches on the client (Maven {@code settings.xml}, IntelliJ {@code jdk.table.xml}) — the XML
 * sibling of {@code MinimalTar}'s "implement the sliver we need" philosophy. Using JAXP for these three call sites dragged the entire {@code java.xml} module
 * (Xerces + Xalan, ~1.7 MB of machine code plus its image-heap metadata) into the size-budgeted
 * {@code jk} native image; this class replaces it with a few hundred lines.
 *
 * <p>Supported: elements, attributes (single- or double-quoted), text with the five predefined
 * entities and decimal/hex character references, CDATA, comments (preserved as nodes so files
 * round-trip), an optional XML declaration, and UTF-8. NOT supported, by design: DOCTYPE (rejected
 * outright — the XXE hardening the JAXP call sites needed feature flags for is structural here),
 * processing instructions beyond the prolog, namespace semantics (names are treated verbatim),
 * and mixed content with significant whitespace (whitespace-only text is dropped; the writer
 * re-indents).
 *
 * <p>Malformed input throws {@link IllegalArgumentException}; every caller treats these files as
 * best-effort foreign input and degrades on any exception.
 */
public final class MinimalXml {

    private MinimalXml() {}

    /** A node in the tree: an {@link Element}, a {@link Text} run, or a {@link Comment}. */
    public sealed interface Node permits Element, Text, Comment {}

    /** A text run (entity references already decoded). */
    public record Text(String value) implements Node {}

    /** A comment ({@code <!-- ... -->}), preserved so machine-written files round-trip. */
    public record Comment(String value) implements Node {}

    /** An element: a name, ordered attributes, and ordered children. */
    public static final class Element implements Node {
        private final String name;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<Node> children = new ArrayList<>();

        private Element(String name) {
            this.name = name;
        }

        public static Element of(String name) {
            return new Element(name);
        }

        public String name() {
            return name;
        }

        /** Attribute value, or {@code null} when absent. */
        public String attr(String attrName) {
            return attributes.get(attrName);
        }

        public Element setAttr(String attrName, String value) {
            attributes.put(attrName, value);
            return this;
        }

        /** The attribute map, in document order (mutable view — the writer serializes it as-is). */
        public Map<String, String> attributes() {
            return attributes;
        }

        /** All children in document order (mutable — append/remove operate on this list). */
        public List<Node> children() {
            return children;
        }

        public Element append(Node child) {
            children.add(child);
            return this;
        }

        public boolean remove(Node child) {
            return children.remove(child);
        }

        /** Direct child elements, in order. */
        public List<Element> elements() {
            List<Element> out = new ArrayList<>();
            for (Node n : children) if (n instanceof Element e) out.add(e);
            return out;
        }

        /** Direct child elements with the given name, in order. */
        public List<Element> elements(String childName) {
            List<Element> out = new ArrayList<>();
            for (Node n : children) {
                if (n instanceof Element e && e.name.equals(childName)) out.add(e);
            }
            return out;
        }

        /** First direct child element with the given name. */
        public Optional<Element> element(String childName) {
            for (Node n : children) {
                if (n instanceof Element e && e.name.equals(childName)) return Optional.of(e);
            }
            return Optional.empty();
        }

        /** Every element named {@code descendantName} anywhere below this one, depth-first. */
        public List<Element> descendants(String descendantName) {
            List<Element> out = new ArrayList<>();
            collectDescendants(this, descendantName, out);
            return out;
        }

        private static void collectDescendants(Element parent, String name, List<Element> out) {
            for (Node n : parent.children) {
                if (n instanceof Element e) {
                    if (e.name.equals(name)) out.add(e);
                    collectDescendants(e, name, out);
                }
            }
        }

        /** Concatenated direct text content, stripped; empty string when there is none. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Node n : children) if (n instanceof Text t) sb.append(t.value());
            return sb.toString().strip();
        }
    }

    // ---- parsing ------------------------------------------------------------------------------

    /** Parse a complete document, returning its root element. */
    public static Element parse(String xml) {
        Parser p = new Parser(xml);
        Element root = p.document();
        if (root == null) throw new IllegalArgumentException("no root element");
        return root;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            // Strip a UTF-8 BOM if the caller read raw bytes into a string.
            this.s = !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
        }

        Element document() {
            Element root = null;
            while (i < s.length()) {
                skipWhitespace();
                if (i >= s.length()) break;
                if (lookingAt("<?")) {
                    skipUntil("?>");
                } else if (lookingAt("<!--")) {
                    comment(); // prolog/trailing comments are dropped; in-element ones survive
                } else if (lookingAt("<!")) {
                    // DOCTYPE (or any other markup declaration): structurally rejected — this
                    // parser has no entity resolution to harden.
                    throw new IllegalArgumentException("markup declarations are not supported: "
                            + s.substring(i, Math.min(i + 20, s.length())));
                } else if (lookingAt("<")) {
                    if (root != null) throw new IllegalArgumentException("content after root element");
                    root = element();
                } else {
                    throw new IllegalArgumentException("unexpected content at offset " + i);
                }
            }
            return root;
        }

        private Element element() {
            expect('<');
            Element e = Element.of(name());
            while (true) {
                skipWhitespace();
                if (consume("/>")) return e;
                if (consume(">")) break;
                String attrName = name();
                skipWhitespace();
                expect('=');
                skipWhitespace();
                e.setAttr(attrName, quotedValue());
            }
            content(e);
            return e;
        }

        /** Children until (and including) this element's end tag. */
        private void content(Element parent) {
            StringBuilder text = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("unclosed <" + parent.name() + ">");
                if (lookingAt("</")) {
                    flushText(parent, text);
                    i += 2;
                    String closing = name();
                    if (!closing.equals(parent.name())) {
                        throw new IllegalArgumentException("mismatched </" + closing + ">, expected </"
                                + parent.name() + ">");
                    }
                    skipWhitespace();
                    expect('>');
                    return;
                } else if (lookingAt("<!--")) {
                    flushText(parent, text);
                    parent.append(comment());
                } else if (lookingAt("<![CDATA[")) {
                    i += "<![CDATA[".length();
                    int end = s.indexOf("]]>", i);
                    if (end < 0) throw new IllegalArgumentException("unterminated CDATA");
                    text.append(s, i, end);
                    i = end + 3;
                } else if (lookingAt("<!")) {
                    throw new IllegalArgumentException("markup declarations are not supported");
                } else if (lookingAt("<")) {
                    flushText(parent, text);
                    parent.append(element());
                } else {
                    int lt = s.indexOf('<', i);
                    if (lt < 0) throw new IllegalArgumentException("unclosed <" + parent.name() + ">");
                    text.append(decodeEntities(s.substring(i, lt)));
                    i = lt;
                }
            }
        }

        /** Whitespace-only runs are formatting, not data — the writer re-indents. */
        private static void flushText(Element parent, StringBuilder text) {
            if (!text.isEmpty()) {
                if (!text.toString().isBlank()) parent.append(new Text(text.toString()));
                text.setLength(0);
            }
        }

        private Comment comment() {
            i += "<!--".length();
            int end = s.indexOf("-->", i);
            if (end < 0) throw new IllegalArgumentException("unterminated comment");
            Comment c = new Comment(s.substring(i, end));
            i = end + 3;
            return c;
        }

        private String name() {
            int start = i;
            while (i < s.length() && isNameChar(s.charAt(i))) i++;
            if (i == start) throw new IllegalArgumentException("expected a name at offset " + i);
            return s.substring(start, i);
        }

        private static boolean isNameChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':';
        }

        private String quotedValue() {
            if (i >= s.length()) throw new IllegalArgumentException("expected a quoted value");
            char quote = s.charAt(i);
            if (quote != '"' && quote != '\'') throw new IllegalArgumentException("expected a quote at offset " + i);
            i++;
            int end = s.indexOf(quote, i);
            if (end < 0) throw new IllegalArgumentException("unterminated attribute value");
            String v = decodeEntities(s.substring(i, end));
            i = end + 1;
            return v;
        }

        private static String decodeEntities(String raw) {
            int amp = raw.indexOf('&');
            if (amp < 0) return raw;
            StringBuilder out = new StringBuilder(raw.length());
            int pos = 0;
            while (amp >= 0) {
                out.append(raw, pos, amp);
                int semi = raw.indexOf(';', amp);
                if (semi < 0) throw new IllegalArgumentException("unterminated entity reference");
                String entity = raw.substring(amp + 1, semi);
                out.append(switch (entity) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    default -> {
                        if (entity.startsWith("#x") || entity.startsWith("#X")) {
                            yield Character.toString(Integer.parseInt(entity.substring(2), 16));
                        }
                        if (entity.startsWith("#")) {
                            yield Character.toString(Integer.parseInt(entity.substring(1)));
                        }
                        throw new IllegalArgumentException("unsupported entity &" + entity + ";");
                    }
                });
                pos = semi + 1;
                amp = raw.indexOf('&', pos);
            }
            out.append(raw, pos, raw.length());
            return out.toString();
        }

        private boolean lookingAt(String prefix) {
            return s.startsWith(prefix, i);
        }

        private boolean consume(String token) {
            if (s.startsWith(token, i)) {
                i += token.length();
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at offset " + i);
            }
            i++;
        }

        private void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private void skipUntil(String token) {
            int end = s.indexOf(token, i);
            if (end < 0) throw new IllegalArgumentException("unterminated " + token);
            i = end + token.length();
        }
    }

    // ---- writing ------------------------------------------------------------------------------

    /**
     * Serialize with an XML declaration and two-space indentation — the shape IntelliJ writes its
     * own config files in. Elements whose children are text-only are inlined ({@code
     * <tag>text</tag>}); childless elements self-close with IntelliJ's {@code <tag />} spelling.
     */
    public static String write(Element root) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writeNode(sb, root, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeNode(StringBuilder sb, Node node, int depth) {
        switch (node) {
            case Text t -> sb.append(escapeText(t.value()));
            case Comment c -> sb.append("<!--").append(c.value()).append("-->");
            case Element e -> {
                sb.append('<').append(e.name());
                e.attributes().forEach((k, v) ->
                        sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"'));
                List<Node> kids = e.children();
                if (kids.isEmpty()) {
                    sb.append(" />");
                } else if (kids.stream().allMatch(n -> n instanceof Text)) {
                    sb.append('>');
                    for (Node kid : kids) writeNode(sb, kid, depth + 1);
                    sb.append("</").append(e.name()).append('>');
                } else {
                    sb.append('>');
                    for (Node kid : kids) {
                        sb.append('\n').append("  ".repeat(depth + 1));
                        writeNode(sb, kid, depth + 1);
                    }
                    sb.append('\n').append("  ".repeat(depth));
                    sb.append("</").append(e.name()).append('>');
                }
            }
        }
    }

    private static String escapeText(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String v) {
        return escapeText(v).replace("\"", "&quot;");
    }
}
