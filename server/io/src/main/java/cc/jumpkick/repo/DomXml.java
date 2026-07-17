// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * DOM child-navigation helpers shared by the XML readers ({@code PomParser}, {@code MavenMetadata},
 * {@code PomImporter}). Namespace-unaware, direct-children only — the shape Maven POM/metadata
 * parsing needs — instead of each reader hand-rolling the same {@code getChildNodes} loops.
 */
public final class DomXml {

    private DomXml() {}

    /** The trimmed text of the first direct child element named {@code tagName}, or {@code null}. */
    public static String childText(Element parent, String tagName) {
        Element child = childElement(parent, tagName);
        return child == null ? null : child.getTextContent().trim();
    }

    /** The first direct child element named {@code tagName}, or {@code null} ({@code null}-safe parent). */
    public static Element childElement(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(tagName)) {
                return (Element) node;
            }
        }
        return null;
    }

    /** All direct child elements named {@code tagName} ({@code null}-safe parent → empty). */
    public static List<Element> childElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(tagName)) {
                result.add((Element) node);
            }
        }
        return result;
    }

    /** All direct child elements, regardless of name ({@code null}-safe parent → empty). */
    public static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }
}
