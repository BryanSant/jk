// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import static cc.jumpkick.repo.DomXml.childElement;
import static cc.jumpkick.repo.DomXml.childElements;
import static cc.jumpkick.repo.DomXml.childText;

/**
 * Parsed contents of a Maven repository's {@code maven-metadata.xml}. Only the bits the resolver
 * currently needs: the version list.
 */
public record MavenMetadata(String groupId, String artifactId, List<String> versions, String latest, String release) {

    public MavenMetadata {
        Objects.requireNonNull(artifactId, "artifactId");
        versions = List.copyOf(versions);
    }

    public static MavenMetadata parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(xml)));
            return fromDocument(doc);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("failed to parse maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    private static MavenMetadata fromDocument(Document doc) {
        Element metadata = doc.getDocumentElement();
        if (metadata == null || !"metadata".equals(metadata.getNodeName())) {
            throw new IllegalArgumentException(
                    "expected <metadata> root, got: " + (metadata == null ? "<none>" : metadata.getNodeName()));
        }
        String groupId = childText(metadata, "groupId");
        String artifactId = childText(metadata, "artifactId");
        if (artifactId == null) {
            throw new IllegalArgumentException("maven-metadata.xml missing <artifactId>");
        }
        Element versioning = childElement(metadata, "versioning");
        String latest = versioning == null ? null : childText(versioning, "latest");
        String release = versioning == null ? null : childText(versioning, "release");
        List<String> versions = new ArrayList<>();
        if (versioning != null) {
            Element versionsElement = childElement(versioning, "versions");
            if (versionsElement != null) {
                for (Element v : childElements(versionsElement, "version")) {
                    String text = v.getTextContent().trim();
                    if (!text.isEmpty()) versions.add(text);
                }
            }
        }
        return new MavenMetadata(groupId, artifactId, versions, latest, release);
    }

    // --- DOM helpers (duplicated lightly from PomParser to keep modules independent) ---



}
