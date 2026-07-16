// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.PomParseException;
import cc.jumpkick.repo.PomParser;
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
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Converts a Maven {@code pom.xml} into a {@link JkBuild} model plus a {@link ImportReport} of
 * constructs that were not perfectly faithful (PRD §24.2 three-tier fidelity).
 *
 * <p>Slice C scope — Tier 1 (lossless) for single-module POMs:
 *
 * <ul>
 *   <li>Project coordinates (groupId/artifactId/version; inherited from parent).
 *   <li>Dependencies across compile / runtime / provided / test scopes. Maven's implicit-caret
 *       {@code 1.2.3} → jk's {@code =1.2.3} exact pin per PRD §7.3.
 *   <li>{@code <dependencyManagement>} BOM imports ({@code scope=import, type=pom}) → {@code
 *       dependencies.platform}.
 *   <li>{@code <repositories>}.
 *   <li>{@code maven-compiler-plugin} release/source/target → {@code project.jdk}.
 * </ul>
 *
 * <p>Anything else (profiles, custom plugins, modules, build extensions, system scope) is logged in
 * the report — Tier 2 / Tier 3 mapping arrives in slice D.
 */
public final class PomImporter {

    public record Result(JkBuild jkBuild, ImportReport report) {}

    /**
     * Outcome of importing a multi-module POM tree: the root's {@link JkBuild} (carrying the
     * workspace block), each module's {@link JkBuild} keyed by the relative module path, and one
     * aggregated {@link ImportReport} spanning the root + every child.
     */
    public record WorkspaceImportResult(JkBuild root, Map<String, JkBuild> modules, ImportReport report) {}

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
        ImportReport.Builder report = ImportReport.builder();

        JkBuild.Project project = mapProject(pom, doc, report, suppressParentMatching);
        Map<Scope, List<Dependency>> byScope = mapDependencies(pom, report);
        List<RepositorySpec> repos = mapRepositories(doc, report);
        warnUnsupportedSections(doc, report, /* isWorkspaceRoot= */ false);

        JkBuild.Dependencies dependencies = new JkBuild.Dependencies(byScope);
        String mainClass = mainClassFromPom(doc);
        JkBuild.Application application = mainClass != null ? new JkBuild.Application(mainClass, false) : null;
        JkBuild jkBuild = JkBuild.builder(project)
                .dependencies(dependencies)
                .repositories(repos)
                .application(application)
                .build();
        Map<String, String> manifest = manifestFromPom(doc);
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        return new Result(jkBuild, report.build());
    }

    /**
     * Custom jar-manifest attributes from a build plugin's {@code <archive><manifestEntries>}
     * (maven-jar / assembly / shade). {@code Main-Class} is excluded — it routes to {@code
     * [application].main} via {@link #mainClassFromPom}. Unresolved {@code ${...}} property values and
     * blanks are skipped. Insertion order preserved.
     */
    private static Map<String, String> manifestFromPom(Document doc) {
        Map<String, String> attrs = new LinkedHashMap<>();
        NodeList entries = doc.getElementsByTagName("manifestEntries");
        for (int i = 0; i < entries.getLength(); i++) {
            if (!(entries.item(i) instanceof Element entriesEl)) continue;
            for (Element child : childElements(entriesEl)) {
                String name = child.getTagName();
                String value = child.getTextContent();
                if (name == null || name.isBlank() || value == null) continue;
                value = value.trim();
                if (value.isEmpty() || value.startsWith("${")) continue;
                if (name.equalsIgnoreCase("Main-Class")) continue; // routed to [application].main
                attrs.put(name, value);
            }
        }
        return attrs;
    }

    /**
     * Import a multi-module Maven build into a workspace-root {@code JkBuild} plus per-module {@code
     * JkBuild}s (PRD §24.2 Tier 2 "multi-module &lt;modules&gt; → workspace"). When the root POM has
     * no {@code <modules>}, falls back to single-POM import.
     */
    public static WorkspaceImportResult importWorkspace(Path rootPom) throws IOException {
        byte[] rootXml = Files.readAllBytes(rootPom);
        Document rootDoc = parseXml(rootXml);
        List<String> modules = readModules(rootDoc);
        if (modules.isEmpty()) {
            Result single = importFromBytes(rootXml);
            return new WorkspaceImportResult(single.jkBuild(), Map.of(), single.report());
        }

        ImportReport.Builder report = ImportReport.builder();
        Pom rootPomParsed = PomParser.parse(rootXml);
        JkBuild.Project rootProject = mapProject(rootPomParsed, rootDoc, report, null);
        // Root coords serve as the "expected parent" for children.
        Pom.Parent expectedParent =
                new Pom.Parent(rootProject.group(), rootPomParsed.artifactId(), rootProject.version());
        warnUnsupportedSections(rootDoc, report, /* isWorkspaceRoot= */ true);

        Workspace workspace = new Workspace(modules);
        String rootMainClass = mainClassFromPom(rootDoc);
        JkBuild.Application rootApplication =
                rootMainClass != null ? new JkBuild.Application(rootMainClass, false) : null;
        // The workspace root is a coordination point — no deps of its own.
        JkBuild rootJkBuild = JkBuild.builder(rootProject)
                .workspace(workspace)
                .application(rootApplication)
                .build();

        Map<String, JkBuild> moduleBuilds = new LinkedHashMap<>();
        Path projectDir = rootPom.toAbsolutePath().getParent();
        for (String module : modules) {
            Path childPom = projectDir.resolve(module).resolve("pom.xml");
            if (!Files.exists(childPom)) {
                report.error("workspace module `" + module + "` has no pom.xml at " + childPom);
                continue;
            }
            byte[] childXml = Files.readAllBytes(childPom);
            Result childResult = importFromBytes(childXml, expectedParent);
            moduleBuilds.put(module, childResult.jkBuild());
            for (ImportReport.Issue issue : childResult.report().issues()) {
                String prefixed = "[" + module + "] " + issue.message();
                if (issue.severity() == ImportReport.Severity.ERROR) {
                    report.error(prefixed);
                } else {
                    report.warning(prefixed);
                }
            }
        }
        return new WorkspaceImportResult(rootJkBuild, moduleBuilds, report.build());
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

    private static JkBuild.Project mapProject(
            Pom pom, Document doc, ImportReport.Builder report, Pom.Parent suppressParentMatching) {
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
                report.warning("`<parent>` was referenced ("
                        + pom.parent().groupId()
                        + ":"
                        + pom.parent().artifactId()
                        + ":"
                        + pom.parent().version()
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
        int jdk = jdkFromCompilerPlugin(doc).flatMap(PomImporter::parseInt).orElse(25);
        String description = childText(doc.getDocumentElement(), "description");
        if (description != null && description.isBlank()) description = null;
        VersionSelector kotlin = kotlinFromPom(doc, report);
        // A Kotlin project sets `kotlin` and leaves `java` at 0 (mutually exclusive).
        int java = kotlin != null ? 0 : jdk;
        return JkBuild.Project.builder(group, pom.artifactId(), version)
                .jdkMajor(jdk)
                .java(java)
                .kotlin(kotlin)
                .description(description)
                .build();
    }

    /**
     * Detect the Kotlin compiler version from the {@code kotlin-maven-plugin}. The version comes from
     * the plugin's {@code <version>} (resolving a {@code ${kotlin.version}} placeholder against
     * {@code <properties>}), else the {@code kotlin.version} property, else a floating {@link
     * KotlinResolver#DEFAULT_VERSION} (pinned later by {@code jk lock}). Returns {@code null} when
     * the plugin is absent (a Java project).
     */
    private static VersionSelector kotlinFromPom(Document doc, ImportReport.Builder report) {
        Element root = doc.getDocumentElement();
        Element properties = childElement(root, "properties");
        String propVersion = null;
        if (properties != null) {
            for (String key : new String[] {"kotlin.version", "kotlin.compiler.version"}) {
                String v = childText(properties, key);
                if (v != null && !v.isBlank()) {
                    propVersion = v.trim();
                    break;
                }
            }
        }
        Element plugins = childElement(childElement(root, "build"), "plugins");
        boolean present = false;
        String pluginVersion = null;
        if (plugins != null) {
            for (Element plugin : childElements(plugins, "plugin")) {
                if (!"kotlin-maven-plugin".equals(childText(plugin, "artifactId"))) continue;
                present = true;
                String v = childText(plugin, "version");
                if (v != null && !v.isBlank()) pluginVersion = v.trim();
            }
        }
        if (!present) return null; // only the plugin marks a Kotlin project
        String resolved = (pluginVersion != null && !pluginVersion.startsWith("${")) ? pluginVersion : propVersion;
        if (resolved == null || resolved.isBlank()) {
            resolved = KotlinResolver.DEFAULT_VERSION;
            report.warning("kotlin-maven-plugin recognised without a resolvable version; defaulted"
                    + " project.kotlin to "
                    + resolved
                    + " (floating). `jk lock` pins it.");
        }
        return VersionSelector.parseFloating(resolved);
    }

    /**
     * Best-effort application main class: the first non-placeholder {@code <mainClass>} element
     * (jar/assembly/shade/exec plugin configs), or a {@code start-class}/{@code
     * exec.mainClass}/{@code main.class} property.
     */
    private static String mainClassFromPom(Document doc) {
        NodeList nodes = doc.getElementsByTagName("mainClass");
        for (int i = 0; i < nodes.getLength(); i++) {
            String v = nodes.item(i).getTextContent();
            if (v != null && !v.isBlank() && !v.trim().startsWith("${")) return v.trim();
        }
        Element properties = childElement(doc.getDocumentElement(), "properties");
        if (properties != null) {
            for (String key : new String[] {"start-class", "exec.mainClass", "main.class", "mainClass"}) {
                String v = childText(properties, key);
                if (v != null && !v.isBlank() && !v.trim().startsWith("${")) return v.trim();
            }
        }
        return null;
    }

    private static Optional<Integer> parseInt(String s) {
        try {
            return Optional.of(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> jdkFromCompilerPlugin(Document doc) {
        Element root = doc.getDocumentElement();
        // First check properties: maven.compiler.release / .source / .target.
        Element properties = childElement(root, "properties");
        if (properties != null) {
            for (String key :
                    new String[] {"maven.compiler.release", "maven.compiler.target", "maven.compiler.source"}) {
                String value = childText(properties, key);
                if (value != null && !value.isBlank()) return Optional.of(value.trim());
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
                    if (value != null && !value.isBlank()) return Optional.of(value.trim());
                }
            }
        }
        return Optional.empty();
    }

    // --- dependencies -------------------------------------------------------

    private static Map<Scope, List<Dependency>> mapDependencies(Pom pom, ImportReport.Builder report) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        // Standard scopes.
        for (Pom.Dep dep : pom.dependencies()) {
            if ("system".equalsIgnoreCase(dep.scope())) {
                report.error("`<dependency>` with `<scope>system</scope>` is rejected ("
                        + dep.module()
                        + "). Move it to a git dependency or a local repository.");
                continue;
            }
            if (dep.optional()) {
                report.warning("`<dependency><optional>true</optional></dependency>` on "
                        + dep.module()
                        + " — jk has no `<optional>`; emitted as a normal dep."
                        + " Use a feature flag if it should be opt-in.");
            }
            if (dep.classifier() != null && !dep.classifier().isBlank()) {
                report.warning("`<classifier>"
                        + dep.classifier()
                        + "</classifier>` on "
                        + dep.module()
                        + " — classifier support lands in a later slice; the coord was emitted without it.");
            }
            if (!dep.exclusions().isEmpty()) {
                report.warning("`<exclusions>` on "
                        + dep.module()
                        + " — exclusion support lands in a later slice; exclusions were dropped.");
            }
            if (dep.version() == null || dep.version().isBlank()) {
                report.warning("`<dependency>` "
                        + dep.module()
                        + " has no resolved `<version>`; jk wrote `=unresolved`."
                        + " Run `mvn help:effective-pom` and re-import.");
            }
            Scope scope = mapScope(dep.scope());
            byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(toDependency(dep));
        }
        // dependencyManagement: BOM imports → PLATFORM; bare version pins → warning.
        for (Pom.Dep managed : pom.managedDependencies()) {
            if ("import".equalsIgnoreCase(managed.scope()) && "pom".equalsIgnoreCase(managed.type())) {
                byScope.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>()).add(toDependency(managed));
            } else {
                report.warning("`<dependencyManagement>` entry "
                        + managed.module()
                        + " is a version pin,"
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
        // PRD §7.3: jk's bare-version default is Exact, matching Maven semantics.
        VersionSelector selector = VersionSelector.parse(version);
        // Maven coordinates have no notion of a manifest "short name"; default
        // the v0.7 `name` field to the artifactId, matching the manifest's
        // own `artifact`-defaults-to-key rule.
        return Dependency.of(dep.artifactId(), dep.module(), selector);
    }

    // --- repositories -------------------------------------------------------

    private static List<RepositorySpec> mapRepositories(Document doc, ImportReport.Builder report) {
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
            if (name.equals("central")
                    || url.startsWith("https://repo.maven.apache.org/")
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

    private static void warnUnsupportedSections(Document doc, ImportReport.Builder report, boolean isWorkspaceRoot) {
        Element root = doc.getDocumentElement();
        Element profiles = childElement(root, "profiles");
        if (profiles != null) {
            for (Element profile : childElements(profiles, "profile")) {
                analyzeProfile(profile, report);
            }
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
                report.warning("`<plugin>"
                        + artifactId
                        + "</plugin>` was not imported."
                        + " Plugin-aware mappings (Spotless, JaCoCo, Spring Boot, ...) arrive in slice D.");
            }
        }
        Element extensions = childElement(build, "extensions");
        if (extensions != null && !childElements(extensions).isEmpty()) {
            report.error("`<build><extensions>` is not supported. Move build extensions to a custom"
                    + " jk task once tasks land (PRD §17).");
        }
    }

    // --- profile analysis ---------------------------------------------------

    /**
     * Emits per-profile diagnostics describing what was inside a Maven {@code <profile>}. jk's
     * current Profile model carries only javac/JVM args, so faithful mapping of property/dep/plugin
     * profiles is not yet possible — instead we give the user a precise checklist of items to port by
     * hand.
     */
    private static void analyzeProfile(Element profile, ImportReport.Builder report) {
        String id = childText(profile, "id");
        String label = id == null || id.isBlank() ? "<unnamed>" : id;
        StringBuilder summary =
                new StringBuilder("Maven profile `").append(label).append("`: ");
        List<String> parts = new ArrayList<>();

        String activation = describeActivation(childElement(profile, "activation"));
        if (activation != null) parts.add(activation);

        Element deps = childElement(profile, "dependencies");
        if (deps != null) {
            int count = childElements(deps, "dependency").size();
            if (count > 0) {
                parts.add(count
                        + " dependenc"
                        + (count == 1 ? "y" : "ies")
                        + " (convert to a jk feature `"
                        + label
                        + "` if opt-in, or move into the main deps list)");
            }
        }
        Element managed = childElement(childElement(profile, "dependencyManagement"), "dependencies");
        if (managed != null && !childElements(managed, "dependency").isEmpty()) {
            int count = childElements(managed, "dependency").size();
            parts.add(count
                    + " dependencyManagement entr"
                    + (count == 1 ? "y" : "ies")
                    + " (inline versions on the matching `<dependency>` or use a BOM import)");
        }
        Element properties = childElement(profile, "properties");
        if (properties != null) {
            List<Element> propEntries = childElements(properties);
            if (!propEntries.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Element p : propEntries) names.add(p.getNodeName());
                parts.add("properties=["
                        + String.join(",", names)
                        + "]"
                        + " (no jk equivalent — fold maven.compiler.* into project.jdk; drop the rest)");
            }
        }
        Element buildPlugins = childElement(childElement(profile, "build"), "plugins");
        if (buildPlugins != null) {
            List<String> pluginIds = new ArrayList<>();
            for (Element plugin : childElements(buildPlugins, "plugin")) {
                String artifactId = childText(plugin, "artifactId");
                if (artifactId != null && !artifactId.isBlank()) pluginIds.add(artifactId);
            }
            if (!pluginIds.isEmpty()) {
                parts.add("plugins=[" + String.join(",", pluginIds) + "] (plugin mapping is not yet implemented)");
            }
        }
        Element repos = childElement(profile, "repositories");
        if (repos != null && !childElements(repos, "repository").isEmpty()) {
            parts.add("repositories declared (move into the top-level `repositories` block)");
        }

        if (parts.isEmpty()) {
            // Profile with only an activation — name it so the user knows it's gone.
            parts.add("contained no convertible payload; dropped");
        }
        summary.append(String.join("; ", parts)).append('.');
        report.warning(summary.toString());
    }

    private static String describeActivation(Element activation) {
        if (activation == null) return null;
        List<String> kinds = new ArrayList<>();
        if ("true".equalsIgnoreCase(childText(activation, "activeByDefault"))) {
            kinds.add("activeByDefault");
        }
        String jdk = childText(activation, "jdk");
        if (jdk != null && !jdk.isBlank()) kinds.add("jdk=" + jdk);
        Element os = childElement(activation, "os");
        if (os != null) {
            String family = childText(os, "family");
            String name = childText(os, "name");
            kinds.add("os="
                    + (family != null ? family : name != null ? name : "?")
                    + " (use jk target predicates per dep)");
        }
        Element property = childElement(activation, "property");
        if (property != null) {
            String name = childText(property, "name");
            kinds.add("property="
                    + (name != null ? name : "?")
                    + " (no jk equivalent — replace with an explicit jk profile or feature)");
        }
        Element file = childElement(activation, "file");
        if (file != null) {
            kinds.add("file-existence (jk has no equivalent — refactor to a jk profile)");
        }
        return kinds.isEmpty() ? null : "activation=" + String.join("+", kinds);
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
