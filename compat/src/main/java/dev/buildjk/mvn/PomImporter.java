// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.mvn;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.Features;
import dev.buildjk.model.Profiles;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import dev.buildjk.model.Workspace;
import dev.buildjk.repo.Pom;
import dev.buildjk.repo.PomParseException;
import dev.buildjk.repo.PomParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Maven {@code pom.xml} into a {@link BuildJk} model plus a
 * {@link PomImportReport} of constructs that were not perfectly faithful
 * (PRD §24.2 three-tier fidelity).
 *
 * <p>Slice C scope — Tier 1 (lossless) for single-module POMs:
 * <ul>
 *   <li>Project coordinates (groupId/artifactId/version; inherited from parent).</li>
 *   <li>Dependencies across compile / runtime / provided / test scopes. Maven's
 *       implicit-caret {@code 1.2.3} → jk's {@code =1.2.3} exact pin per
 *       PRD §7.3.</li>
 *   <li>{@code <dependencyManagement>} BOM imports ({@code scope=import,
 *       type=pom}) → {@code dependencies.platform}.</li>
 *   <li>{@code <repositories>}.</li>
 *   <li>{@code maven-compiler-plugin} release/source/target → {@code project.jdk}.</li>
 * </ul>
 *
 * <p>Anything else (profiles, custom plugins, modules, build extensions, system
 * scope) is logged in the report — Tier 2 / Tier 3 mapping arrives in slice D.
 */
public final class PomImporter {

    public record Result(BuildJk buildJk, PomImportReport report) {}

    /**
     * Outcome of importing a multi-module POM tree: the root's {@link BuildJk}
     * (carrying the workspace block), each member's {@link BuildJk} keyed by
     * the relative module path, and one aggregated {@link PomImportReport}
     * spanning the root + every child.
     */
    public record WorkspaceImportResult(
            BuildJk root,
            Map<String, BuildJk> members,
            PomImportReport report) {}

    private PomImporter() {}

    public static Result importFrom(Path pomXml) throws IOException {
        return importFromBytes(Files.readAllBytes(pomXml));
    }

    public static Result importFromBytes(byte[] xml) {
        return importFromBytes(xml, null);
    }

    private static Result importFromBytes(byte[] xml, Pom.Parent suppressParentMatching) {
        Pom pom = PomParser.parse(xml);
        Document doc = parseXml(xml);
        PomImportReport.Builder report = PomImportReport.builder();

        BuildJk.Project project = mapProject(pom, doc, report, suppressParentMatching);
        Map<Scope, List<Dependency>> byScope = mapDependencies(pom, report);
        List<RepositorySpec> repos = mapRepositories(doc, report);
        warnUnsupportedSections(doc, report, /*isWorkspaceRoot=*/ false);

        BuildJk.Dependencies dependencies = new BuildJk.Dependencies(byScope);
        BuildJk buildJk = new BuildJk(project, dependencies, repos);
        return new Result(buildJk, report.build());
    }

    /**
     * Import a multi-module Maven build into a workspace-root {@code BuildJk}
     * plus per-member {@code BuildJk}s (PRD §24.2 Tier 2 "multi-module
     * &lt;modules&gt; → workspace"). When the root POM has no
     * {@code <modules>}, falls back to single-POM import.
     */
    public static WorkspaceImportResult importWorkspace(Path rootPom) throws IOException {
        byte[] rootXml = Files.readAllBytes(rootPom);
        Document rootDoc = parseXml(rootXml);
        List<String> modules = readModules(rootDoc);
        if (modules.isEmpty()) {
            Result single = importFromBytes(rootXml);
            return new WorkspaceImportResult(single.buildJk(), Map.of(), single.report());
        }

        PomImportReport.Builder report = PomImportReport.builder();
        Pom rootPomParsed = PomParser.parse(rootXml);
        BuildJk.Project rootProject = mapProject(rootPomParsed, rootDoc, report, null);
        // Root coords serve as the "expected parent" for children.
        Pom.Parent expectedParent = new Pom.Parent(
                rootProject.group(), rootPomParsed.artifactId(), rootProject.version());
        warnUnsupportedSections(rootDoc, report, /*isWorkspaceRoot=*/ true);

        Workspace workspace = new Workspace(modules);
        // The workspace root is a coordination point — no deps of its own.
        BuildJk rootBuildJk = new BuildJk(
                rootProject, BuildJk.Dependencies.empty(),
                List.of(), Profiles.empty(), Features.empty(), workspace);

        Map<String, BuildJk> members = new LinkedHashMap<>();
        Path projectDir = rootPom.toAbsolutePath().getParent();
        for (String module : modules) {
            Path childPom = projectDir.resolve(module).resolve("pom.xml");
            if (!Files.exists(childPom)) {
                report.error("workspace module `" + module + "` has no pom.xml at " + childPom);
                continue;
            }
            byte[] childXml = Files.readAllBytes(childPom);
            Result childResult = importFromBytes(childXml, expectedParent);
            members.put(module, childResult.buildJk());
            for (PomImportReport.Issue issue : childResult.report().issues()) {
                String prefixed = "[" + module + "] " + issue.message();
                if (issue.severity() == PomImportReport.Severity.ERROR) {
                    report.error(prefixed);
                } else {
                    report.warning(prefixed);
                }
            }
        }
        return new WorkspaceImportResult(rootBuildJk, members, report.build());
    }

    private static List<String> readModules(Document doc) {
        Element modules = childElement(doc.getDocumentElement(), "modules");
        if (modules == null) return List.of();
        List<String> result = new ArrayList<>();
        for (Element m : childElements(modules, "module")) {
            String text = m.getTextContent().trim();
            if (!text.isEmpty()) result.add(text);
        }
        return result;
    }

    // --- project ------------------------------------------------------------

    private static BuildJk.Project mapProject(Pom pom, Document doc,
                                              PomImportReport.Builder report,
                                              Pom.Parent suppressParentMatching) {
        String group = pom.groupId();
        String version = pom.version();
        if (pom.parent() != null) {
            if (group == null) group = pom.parent().groupId();
            if (version == null) version = pom.parent().version();
            boolean isWorkspaceParent = suppressParentMatching != null
                    && pom.parent().groupId().equals(suppressParentMatching.groupId())
                    && pom.parent().artifactId().equals(suppressParentMatching.artifactId())
                    && pom.parent().version().equals(suppressParentMatching.version());
            if (!isWorkspaceParent) {
                report.warning("`<parent>` was referenced (" + pom.parent().groupId() + ":"
                        + pom.parent().artifactId() + ":" + pom.parent().version()
                        + ") but jk-import did not flatten its dependencyManagement / properties / build config."
                        + " Run `mvn help:effective-pom` and re-import if any dependency versions are unresolved.");
            }
        }
        if (group == null || group.isBlank()) {
            throw new PomParseException("POM has no <groupId> and no <parent><groupId>");
        }
        if (version == null || version.isBlank()) {
            throw new PomParseException("POM has no <version> and no <parent><version>");
        }
        String jdk = jdkFromCompilerPlugin(doc).orElse("25");
        return new BuildJk.Project(group, pom.artifactId(), version, jdk);
    }

    private static java.util.Optional<String> jdkFromCompilerPlugin(Document doc) {
        Element root = doc.getDocumentElement();
        // First check properties: maven.compiler.release / .source / .target.
        Element properties = childElement(root, "properties");
        if (properties != null) {
            for (String key : new String[] {
                    "maven.compiler.release", "maven.compiler.target", "maven.compiler.source"}) {
                String value = childText(properties, key);
                if (value != null && !value.isBlank()) return java.util.Optional.of(value.trim());
            }
        }
        // Then check the maven-compiler-plugin <configuration>.
        Element build = childElement(root, "build");
        Element plugins = childElement(build, "plugins");
        if (plugins != null) {
            for (Element plugin : childElements(plugins, "plugin")) {
                String artifactId = childText(plugin, "artifactId");
                if (!"maven-compiler-plugin".equals(artifactId)) continue;
                Element config = childElement(plugin, "configuration");
                if (config == null) continue;
                for (String key : new String[] {"release", "target", "source"}) {
                    String value = childText(config, key);
                    if (value != null && !value.isBlank()) return java.util.Optional.of(value.trim());
                }
            }
        }
        return java.util.Optional.empty();
    }

    // --- dependencies -------------------------------------------------------

    private static Map<Scope, List<Dependency>> mapDependencies(Pom pom, PomImportReport.Builder report) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        // Standard scopes.
        for (Pom.Dep dep : pom.dependencies()) {
            if ("system".equalsIgnoreCase(dep.scope())) {
                report.error("`<dependency>` with `<scope>system</scope>` is rejected (" + dep.module()
                        + "). Move it to a git dependency or a local repository.");
                continue;
            }
            if (dep.optional()) {
                report.warning("`<dependency><optional>true</optional></dependency>` on "
                        + dep.module() + " — jk has no `<optional>`; emitted as a normal dep."
                        + " Use a feature flag if it should be opt-in.");
            }
            if (dep.classifier() != null && !dep.classifier().isBlank()) {
                report.warning("`<classifier>" + dep.classifier() + "</classifier>` on " + dep.module()
                        + " — classifier support lands in a later slice; the coord was emitted without it.");
            }
            if (!dep.exclusions().isEmpty()) {
                report.warning("`<exclusions>` on " + dep.module()
                        + " — exclusion support lands in a later slice; exclusions were dropped.");
            }
            if (dep.version() == null || dep.version().isBlank()) {
                report.warning("`<dependency>` " + dep.module()
                        + " has no resolved `<version>`; jk wrote `=unresolved`."
                        + " Run `mvn help:effective-pom` and re-import.");
            }
            Scope scope = mapScope(dep.scope());
            byScope.computeIfAbsent(scope, s -> new ArrayList<>())
                    .add(toDependency(dep));
        }
        // dependencyManagement: BOM imports → PLATFORM; bare version pins → warning.
        for (Pom.Dep managed : pom.managedDependencies()) {
            if ("import".equalsIgnoreCase(managed.scope()) && "pom".equalsIgnoreCase(managed.type())) {
                byScope.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>())
                        .add(toDependency(managed));
            } else {
                report.warning("`<dependencyManagement>` entry " + managed.module() + " is a version pin,"
                        + " not a BOM import. jk has no equivalent; the pin was dropped."
                        + " Inline the version on the matching `<dependency>` instead.");
            }
        }
        return byScope;
    }

    private static Scope mapScope(String mavenScope) {
        if (mavenScope == null || mavenScope.isBlank() || "compile".equalsIgnoreCase(mavenScope)) {
            return Scope.MAIN;
        }
        return switch (mavenScope.toLowerCase()) {
            case "runtime" -> Scope.RUNTIME;
            case "provided" -> Scope.PROVIDED;
            case "test" -> Scope.TEST;
            // system handled separately (rejected earlier).
            default -> Scope.MAIN;
        };
    }

    private static Dependency toDependency(Pom.Dep dep) {
        String version = dep.version();
        if (version == null || version.isBlank()) {
            // PomParser will warn via the report; emit a marker so the file
            // still parses round-tripped.
            version = "unresolved";
        }
        // PRD §7.3: imported POMs translated to exact pins to preserve Maven semantics.
        VersionSelector selector = VersionSelector.parse("=" + version);
        return new Dependency(dep.module(), selector);
    }

    // --- repositories -------------------------------------------------------

    private static List<RepositorySpec> mapRepositories(Document doc, PomImportReport.Builder report) {
        Element root = doc.getDocumentElement();
        Element repos = childElement(root, "repositories");
        if (repos == null) return List.of();
        Map<String, RepositorySpec> deduped = new LinkedHashMap<>();
        for (Element repo : childElements(repos, "repository")) {
            String id = childText(repo, "id");
            String url = childText(repo, "url");
            if (url == null || url.isBlank()) {
                report.warning("`<repository>` with no `<url>` was skipped (id=" + id + ").");
                continue;
            }
            String name = (id == null || id.isBlank()) ? "repo" + (deduped.size() + 1) : id;
            // Central is the implicit default — skip duplicate declarations of it.
            if (name.equals("central") || url.startsWith("https://repo.maven.apache.org/")
                    || url.startsWith("https://repo1.maven.org/")) {
                continue;
            }
            try {
                deduped.put(name, new RepositorySpec(name, new URI(url.trim())));
            } catch (URISyntaxException e) {
                report.warning("`<repository><url>" + url + "</url></repository>` is not a valid URI; skipped.");
            }
        }
        return new ArrayList<>(deduped.values());
    }

    // --- unsupported-section warnings ---------------------------------------

    private static void warnUnsupportedSections(Document doc, PomImportReport.Builder report,
                                                boolean isWorkspaceRoot) {
        Element root = doc.getDocumentElement();
        if (childElement(root, "profiles") != null) {
            report.warning("`<profiles>` block present — profile mapping is not yet implemented."
                    + " For now run `mvn help:effective-pom -P<profile>` and re-import per active profile.");
        }
        if (!isWorkspaceRoot && childElement(root, "modules") != null) {
            // The workspace-import path already converted these; warn only for the single-POM path.
            report.warning("`<modules>` block present but this import was run in single-POM mode."
                    + " Re-run as `jk import pom.xml` from the project root to materialise a workspace.");
        }
        Element build = childElement(root, "build");
        Element plugins = childElement(build, "plugins");
        if (plugins != null) {
            for (Element plugin : childElements(plugins, "plugin")) {
                String artifactId = childText(plugin, "artifactId");
                if (artifactId == null || "maven-compiler-plugin".equals(artifactId)) continue;
                report.warning("`<plugin>" + artifactId + "</plugin>` was not imported."
                        + " Plugin-aware mappings (Spotless, JaCoCo, Spring Boot, ...) arrive in slice D.");
            }
        }
        Element extensions = childElement(build, "extensions");
        if (extensions != null && !childElements(extensions).isEmpty()) {
            report.error("`<build><extensions>` is not supported. Move build extensions to a custom"
                    + " jk task once tasks land (PRD §17).");
        }
    }

    // --- XML helpers --------------------------------------------------------

    private static Document parseXml(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new PomParseException("failed to parse POM: " + e.getMessage(), e);
        }
    }

    private static Element childElement(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tagName)) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tagName)) {
                result.add((Element) n);
            }
        }
        return result;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) result.add((Element) n);
        }
        return result;
    }

    private static String childText(Element parent, String tagName) {
        Element child = childElement(parent, tagName);
        return child == null ? null : child.getTextContent().trim();
    }
}
