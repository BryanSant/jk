// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.credential;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code <server>} credentials from Maven's {@code ~/.m2/settings.xml}
 * so teams already on Maven get repository auth with zero reconfiguration. A
 * repository's jk name is matched against the server {@code <id>} (the same
 * convention Maven uses).
 *
 * <p>Best-effort and read-only: a missing or malformed file yields an empty
 * result rather than an error. Only {@code id}/{@code username}/{@code
 * password} are read; {@code privateKey}/{@code passphrase}/{@code
 * configuration} (SSH and HTTP-header extensions) are out of scope for now.
 * Maven's property/encryption (`{...}`) expansion is also not yet handled.
 */
public final class MavenSettings {

    private final Map<String, Server> byId;

    private MavenSettings(Map<String, Server> byId) {
        this.byId = byId;
    }

    /** A server entry: a username/password keyed by repository id. */
    public record Server(String id, String username, String password) {}

    public static MavenSettings empty() {
        return new MavenSettings(Map.of());
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** Credentials for the given repository id, if {@code settings.xml} defined them. */
    public Optional<Server> server(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Load from the default {@code ~/.m2/settings.xml}; missing/invalid → empty. */
    public static MavenSettings load() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return empty();
        return loadFrom(Path.of(home, ".m2", "settings.xml"));
    }

    /** Load from an explicit path; missing/invalid → empty. */
    public static MavenSettings loadFrom(Path settingsXml) {
        if (settingsXml == null || !Files.isRegularFile(settingsXml)) return empty();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Harden the parser: no DTDs / external entities (XXE-safe).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc;
            try (var in = Files.newInputStream(settingsXml)) {
                doc = builder.parse(in);
            }

            Map<String, Server> byId = new HashMap<>();
            NodeList servers = doc.getElementsByTagName("server");
            for (int i = 0; i < servers.getLength(); i++) {
                if (!(servers.item(i) instanceof Element server)) continue;
                String id = childText(server, "id");
                if (id == null || id.isBlank()) continue;
                String username = childText(server, "username");
                String password = childText(server, "password");
                if (username == null && password == null) continue;   // no usable creds
                byId.put(id.strip(), new Server(id.strip(), username, password));
            }
            return new MavenSettings(byId);
        } catch (Exception e) {
            // Foreign/experimental file — degrade gracefully, like the other parsers.
            return empty();
        }
    }

    /** Text of the first direct child element named {@code tag}, or null. */
    private static String childText(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) {
                String text = n.getTextContent();
                return text == null ? null : text.strip();
            }
        }
        return null;
    }
}
