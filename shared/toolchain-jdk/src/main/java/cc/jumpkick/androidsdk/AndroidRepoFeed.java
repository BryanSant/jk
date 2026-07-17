// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.androidsdk;

import cc.jumpkick.util.Hashing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Google's {@code repository2} SDK feed, parsed (android-plan.md §3.2). One XML document lists
 * every installable component ({@code platforms;android-<N>}, {@code build-tools;<v>},
 * {@code platform-tools}, …) with per-OS archives, checksums, and the license each component is
 * distributed under. This is the same feed {@code sdkmanager} consumes — jk drives it directly.
 *
 * <p>A component path may appear once per release channel; {@link #find} prefers the stable
 * channel ({@code channel-0}) and falls back to whatever exists (some components, like a fresh
 * {@code platform-tools}, live briefly on the preview channel only).
 */
public final class AndroidRepoFeed {

    /** The canonical feed URL — version-suffixed by Google; bump deliberately. */
    public static final String FEED_URL = "https://dl.google.com/android/repository/repository2-3.xml";

    /** Base URL archives are relative to. */
    public static final String REPOSITORY_BASE = "https://dl.google.com/android/repository/";

    /** One installable archive: the concrete zip for this host OS. */
    public record Archive(String url, String sha1, long size, String hostOs) {}

    /**
     * One remote component: sdkmanager-style {@code path} ({@code platforms;android-28}),
     * dotted {@code revision}, its license id, release channel, and per-OS archives ({@code
     * hostOs} empty = OS-independent).
     */
    public record Component(String path, String revision, String licenseId, String channel, List<Archive> archives) {

        /** The archive for {@code os} ({@code linux}/{@code macosx}/{@code windows}), or the OS-independent one. */
        public Archive archiveFor(String os) {
            for (Archive a : archives) {
                if (a.hostOs().equals(os)) return a;
            }
            for (Archive a : archives) {
                if (a.hostOs().isEmpty()) return a;
            }
            return null;
        }
    }

    private final Map<String, List<Component>> byPath;
    private final Map<String, String> licenseTexts;

    private AndroidRepoFeed(Map<String, List<Component>> byPath, Map<String, String> licenseTexts) {
        this.byPath = byPath;
        this.licenseTexts = licenseTexts;
    }

    /** Parse a feed document. Throws {@link IOException} on malformed XML — callers degrade loudly. */
    public static AndroidRepoFeed parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setExpandEntityReferences(false);
            Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

            Map<String, String> licenses = new LinkedHashMap<>();
            NodeList licenseNodes = doc.getElementsByTagName("license");
            for (int i = 0; i < licenseNodes.getLength(); i++) {
                Element lic = (Element) licenseNodes.item(i);
                licenses.put(lic.getAttribute("id"), lic.getTextContent().strip());
            }

            Map<String, List<Component>> byPath = new LinkedHashMap<>();
            NodeList pkgs = doc.getElementsByTagName("remotePackage");
            for (int i = 0; i < pkgs.getLength(); i++) {
                Element pkg = (Element) pkgs.item(i);
                String path = pkg.getAttribute("path");
                String license = childAttr(pkg, "uses-license", "ref");
                String channel = childAttr(pkg, "channelRef", "ref");
                String revision = revisionOf(pkg);
                List<Archive> archives = new ArrayList<>();
                NodeList archiveNodes = pkg.getElementsByTagName("archive");
                for (int j = 0; j < archiveNodes.getLength(); j++) {
                    Element archive = (Element) archiveNodes.item(j);
                    Element complete = firstChild(archive, "complete");
                    if (complete == null) continue;
                    String url = textOf(complete, "url");
                    String sha1 = textOf(complete, "checksum");
                    long size = parseLong(textOf(complete, "size"));
                    String hostOs = textOf(archive, "host-os");
                    archives.add(new Archive(url, sha1, size, hostOs));
                }
                byPath.computeIfAbsent(path, k -> new ArrayList<>())
                        .add(new Component(path, revision, license, channel, List.copyOf(archives)));
            }
            return new AndroidRepoFeed(byPath, licenses);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot parse the Android SDK repository feed: " + e.getMessage(), e);
        }
    }

    /** The stable-channel component at {@code path} (fallback: any channel), or null when unknown. */
    public Component find(String path) {
        List<Component> candidates = byPath.get(path);
        if (candidates == null || candidates.isEmpty()) return null;
        for (Component c : candidates) {
            if ("channel-0".equals(c.channel())) return c;
        }
        return candidates.get(0);
    }

    /** The license text for {@code id}, or null. */
    public String licenseText(String id) {
        return licenseTexts.get(id);
    }

    /** Every license id → text in the feed, in document order. */
    public Map<String, String> licenses() {
        return Map.copyOf(licenseTexts);
    }

    /**
     * The feed's spelling of the running OS: {@code linux} / {@code macosx} / {@code windows}.
     */
    public static String hostOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return "macosx";
        if (os.contains("win")) return "windows";
        return "linux";
    }

    // ---- small DOM helpers --------------------------------------------------

    private static String childAttr(Element parent, String tag, String attr) {
        Element child = firstChild(parent, tag);
        return child == null ? "" : child.getAttribute(attr);
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String textOf(Element parent, String tag) {
        Element child = firstChild(parent, tag);
        return child == null ? "" : child.getTextContent().strip();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Dotted revision from the {@code <revision>} element ({@code 37.0.1}; major-only → {@code 6}). */
    private static String revisionOf(Element pkg) {
        Element revision = firstChild(pkg, "revision");
        if (revision == null) return "";
        StringBuilder out = new StringBuilder();
        for (String part : new String[] {"major", "minor", "micro"}) {
            String v = textOf(revision, part);
            if (v.isEmpty()) break;
            if (out.length() > 0) out.append('.');
            out.append(v);
        }
        return out.toString();
    }

    /** SHA-1 hex of a license text — the sdkmanager acceptance-hash contract. */
    public static String licenseHash(String text) {
        return Hashing.hashHex("SHA-1", text.strip().getBytes(StandardCharsets.UTF_8));
    }
}
