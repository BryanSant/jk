// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;

/**
 * Packages a Spring Boot executable jar (spring-boot plan §3.3) — the layout Boot's {@code
 * JarLauncher} and {@code -Djarmode=tools} expect:
 *
 * <pre>
 * META-INF/MANIFEST.MF            Main-Class: JarLauncher, Start-Class: app main
 * org/springframework/boot/loader/**   exploded from spring-boot-loader.jar
 * BOOT-INF/classes/**             the application's classes + resources
 * BOOT-INF/lib/*.jar              runtime deps, STORED (uncompressed) — the nested-jar
 *                                 loader random-accesses entries and requires it
 * BOOT-INF/classpath.idx          nested-jar order (launcher classpath)
 * BOOT-INF/layers.idx             dependencies / spring-boot-loader /
 *                                 snapshot-dependencies / application
 * </pre>
 *
 * <p>Reproducibility mirrors {@link JarPackager}: sorted entries within each section, fixed
 * timestamps, a manifest containing only what we set.
 */
public final class BootJarPackager {

    /** Boot's launcher entry point (the loader-classes package moved to {@code .launch} in 3.2). */
    public static final String JAR_LAUNCHER = "org.springframework.boot.loader.launch.JarLauncher";

    private static final String CLASSES_PREFIX = "BOOT-INF/classes/";
    private static final String LIB_PREFIX = "BOOT-INF/lib/";
    private static final String CLASSPATH_IDX = "BOOT-INF/classpath.idx";
    private static final String LAYERS_IDX = "BOOT-INF/layers.idx";
    private static final String LOADER_PREFIX = "org/";

    /** Classpath locations Boot's own readers use ({@code BuildProperties}, the sbom actuator). */
    static final String BUILD_INFO_ENTRY = CLASSES_PREFIX + "META-INF/build-info.properties";

    static final String SBOM_ENTRY = CLASSES_PREFIX + "META-INF/sbom/application.cdx.json";

    public Path packageBootJar(BootJarRequest request) throws IOException {
        Files.createDirectories(request.outputJar().getParent());
        Manifest manifest = buildManifest(request);
        long epoch = request.timestampEpochSeconds();
        Set<String> dirsWritten = new HashSet<>();

        try (OutputStream out = Files.newOutputStream(request.outputJar());
                JarOutputStream jos = new JarOutputStream(out)) {
            writeManifest(jos, manifest, epoch);
            dirsWritten.add("META-INF/");

            // 1. Loader classes exploded at the root — java -jar must find
            //    Main-Class before anything else is resolvable.
            explodeLoader(jos, request.loaderJar(), epoch, dirsWritten);

            // 2. Application classes + resources under BOOT-INF/classes/, then any AOT
            //    output roots (generated classes + GraalVM hint resources) — app files win
            //    on collision, AOT roots in the given order after that.
            Set<String> classEntries = new HashSet<>();
            List<Path> roots = new ArrayList<>();
            roots.add(request.classesDir());
            roots.addAll(request.aotDirs());
            for (Path root : roots) {
                List<Path> files = collectFiles(root);
                files.sort(Comparator.comparing(p -> normalize(root, p)));
                for (Path file : files) {
                    String name = normalize(root, file);
                    if (name.equals("META-INF/MANIFEST.MF")) continue;
                    if (isBuildStamp(name)) continue;
                    if (!classEntries.add(name)) continue;
                    writeParentDirs(jos, CLASSES_PREFIX + name, epoch, dirsWritten);
                    writeEntryStreaming(jos, CLASSES_PREFIX + name, Files.newInputStream(file), epoch);
                }
            }

            // 3. Boot-read metadata under BOOT-INF/classes/META-INF (classpath-visible:
            //    BuildProperties and the sbom actuator resolve these as resources).
            if (!request.buildInfo().isEmpty()) {
                writeParentDirs(jos, BUILD_INFO_ENTRY, epoch, dirsWritten);
                writeEntry(jos, BUILD_INFO_ENTRY, buildInfoProperties(request.buildInfo()), epoch);
            }
            if (request.sbom() != null) {
                writeParentDirs(jos, SBOM_ENTRY, epoch, dirsWritten);
                writeEntry(jos, SBOM_ENTRY, request.sbom(), epoch);
            }

            // 4. Nested dependency jars — STORED with a precomputed CRC.
            writeDir(jos, LIB_PREFIX, epoch, dirsWritten);
            for (Lib lib : request.libs()) {
                writeStored(jos, LIB_PREFIX + lib.fileName(), lib.jar(), epoch);
            }

            // 5. The two index files the manifest points at.
            writeEntry(jos, CLASSPATH_IDX, classpathIndex(request.libs()), epoch);
            writeEntry(jos, LAYERS_IDX, layersIndex(request.libs()), epoch);
        }
        return request.outputJar();
    }

    /**
     * {@code build-info.properties} the way Boot's {@code BuildProperties} reads it: {@code build.}
     * prefixed keys, sorted for reproducibility. {@code build.time} is deliberately absent unless
     * the caller supplies one — a wall-clock stamp would churn an otherwise-identical jar.
     */
    private static byte[] buildInfoProperties(Map<String, String> info) {
        StringBuilder sb = new StringBuilder();
        info.keySet().stream().sorted().forEach(k -> sb.append("build.")
                .append(k)
                .append('=')
                .append(info.get(k))
                .append('\n'));
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** {@code classpath.idx}: one {@code - "BOOT-INF/lib/…jar"} line per nested jar, in order. */
    private static byte[] classpathIndex(List<Lib> libs) {
        StringBuilder sb = new StringBuilder();
        for (Lib lib : libs) {
            sb.append("- \"").append(LIB_PREFIX).append(lib.fileName()).append("\"\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * {@code layers.idx} in Boot's default layer order (least- to most-frequently changing):
     * dependencies, spring-boot-loader, snapshot-dependencies, application. Empty layers still
     * appear — {@code jarmode tools list-layers} prints every declared layer.
     */
    private static byte[] layersIndex(List<Lib> libs) {
        StringBuilder sb = new StringBuilder();
        sb.append("- \"dependencies\":\n");
        for (Lib lib : libs) {
            if (!lib.snapshot()) sb.append("  - \"").append(LIB_PREFIX).append(lib.fileName()).append("\"\n");
        }
        sb.append("- \"spring-boot-loader\":\n");
        sb.append("  - \"").append(LOADER_PREFIX).append("\"\n");
        sb.append("- \"snapshot-dependencies\":\n");
        for (Lib lib : libs) {
            if (lib.snapshot()) sb.append("  - \"").append(LIB_PREFIX).append(lib.fileName()).append("\"\n");
        }
        sb.append("- \"application\":\n");
        sb.append("  - \"BOOT-INF/classes/\"\n");
        sb.append("  - \"").append(CLASSPATH_IDX).append("\"\n");
        sb.append("  - \"").append(LAYERS_IDX).append("\"\n");
        sb.append("  - \"META-INF/\"\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Copy every class from the loader jar to the boot jar's root. Loader jars ship their own
     * signature-free META-INF which we drop (our manifest already points at the launcher).
     */
    private static void explodeLoader(JarOutputStream jos, Path loaderJar, long epoch, Set<String> dirsWritten)
            throws IOException {
        try (JarFile jf = new JarFile(loaderJar.toFile())) {
            List<JarEntry> entries = new ArrayList<>();
            jf.stream().filter(e -> !e.isDirectory()).forEach(entries::add);
            entries.sort(Comparator.comparing(JarEntry::getName));
            for (JarEntry e : entries) {
                String name = e.getName();
                if (name.startsWith("META-INF/")) continue;
                writeParentDirs(jos, name, epoch, dirsWritten);
                writeEntryStreaming(jos, name, jf.getInputStream(e), epoch);
            }
        }
    }

    /**
     * Write {@code file} as a STORED (uncompressed) entry. Boot's nested-jar loader maps entries
     * of BOOT-INF/lib/*.jar directly; a DEFLATED nested jar cannot be random-accessed and fails at
     * launch. STORED requires size + CRC-32 up front, so the file is streamed twice —
     * constant-memory either way.
     */
    private static void writeStored(JarOutputStream jos, String name, Path file, long epoch) throws IOException {
        long size = Files.size(file);
        CRC32 crc = new CRC32();
        try (InputStream in = new CheckedInputStream(Files.newInputStream(file), crc)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        JarEntry entry = new JarEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
        entry.setTimeLocal(LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.UTC));
        jos.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(jos);
        }
        jos.closeEntry();
    }

    /** Emit any missing directory entries leading up to {@code entryName} (deterministic layout). */
    private static void writeParentDirs(JarOutputStream jos, String entryName, long epoch, Set<String> written)
            throws IOException {
        int slash = -1;
        while ((slash = entryName.indexOf('/', slash + 1)) >= 0) {
            writeDir(jos, entryName.substring(0, slash + 1), epoch, written);
        }
    }

    private static void writeDir(JarOutputStream jos, String dirName, long epoch, Set<String> written)
            throws IOException {
        if (!written.add(dirName)) return;
        JarEntry entry = new JarEntry(dirName);
        entry.setTimeLocal(LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.UTC));
        jos.putNextEntry(entry);
        jos.closeEntry();
    }

    private static void writeEntry(JarOutputStream jos, String name, byte[] data, long epochSeconds)
            throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTimeLocal(LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC));
        jos.putNextEntry(entry);
        jos.write(data);
        jos.closeEntry();
    }

    private static void writeEntryStreaming(JarOutputStream jos, String name, InputStream in, long epochSeconds)
            throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTimeLocal(LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC));
        jos.putNextEntry(entry);
        try (in) {
            in.transferTo(jos);
        }
        jos.closeEntry();
    }

    private static void writeManifest(JarOutputStream jos, Manifest manifest, long epochSeconds) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        manifest.write(buf);
        writeEntry(jos, "META-INF/MANIFEST.MF", buf.toByteArray(), epochSeconds);
    }

    private static Manifest buildManifest(BootJarRequest request) {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, JAR_LAUNCHER);
        attrs.put(new Attributes.Name("Start-Class"), request.startClass());
        attrs.put(new Attributes.Name("Spring-Boot-Version"), request.bootVersion());
        attrs.put(new Attributes.Name("Spring-Boot-Classes"), CLASSES_PREFIX);
        attrs.put(new Attributes.Name("Spring-Boot-Lib"), LIB_PREFIX);
        attrs.put(new Attributes.Name("Spring-Boot-Classpath-Index"), CLASSPATH_IDX);
        attrs.put(new Attributes.Name("Spring-Boot-Layers-Index"), LAYERS_IDX);
        if (request.sbom() != null) {
            attrs.put(new Attributes.Name("Sbom-Format"), "CycloneDX");
            attrs.put(new Attributes.Name("Sbom-Location"), SBOM_ENTRY);
        }
        for (Map.Entry<String, String> e : request.attributes().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
            attrs.put(new Attributes.Name(e.getKey()), e.getValue());
        }
        return manifest;
    }

    /** jk's freshness/skip stamps — build-host metadata that must not enter the jar. */
    private static boolean isBuildStamp(String name) {
        return name.endsWith(".jstamp") || name.endsWith(".kstamp") || name.endsWith(".test-stamp");
    }

    private static List<Path> collectFiles(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(result::add);
        }
        return result;
    }

    private static String normalize(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /**
     * One nested jar: its {@code BOOT-INF/lib} file name (original {@code artifact-version.jar},
     * never a CAS hash), the file to copy, and whether it lands in the {@code
     * snapshot-dependencies} layer.
     */
    public record Lib(String fileName, Path jar, boolean snapshot) {

        public Lib {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(jar, "jar");
        }
    }

    /**
     * Inputs for {@link #packageBootJar(BootJarRequest)}.
     *
     * @param buildInfo {@code build-info.properties} keys (without the {@code build.} prefix);
     *     empty map = no entry
     * @param sbom CycloneDX JSON bytes (see {@link CycloneDxSbom}); {@code null} = no SBOM
     * @param aotDirs Spring AOT output roots (generated classes / hint resources) merged into
     *     {@code BOOT-INF/classes} after the app's own files
     */
    public record BootJarRequest(
            Path classesDir,
            List<Lib> libs,
            Path loaderJar,
            Path outputJar,
            String startClass,
            String bootVersion,
            Map<String, String> attributes,
            Map<String, String> buildInfo,
            byte[] sbom,
            List<Path> aotDirs,
            long timestampEpochSeconds) {

        public BootJarRequest {
            Objects.requireNonNull(classesDir, "classesDir");
            Objects.requireNonNull(loaderJar, "loaderJar");
            Objects.requireNonNull(outputJar, "outputJar");
            Objects.requireNonNull(startClass, "startClass");
            Objects.requireNonNull(bootVersion, "bootVersion");
            libs = libs == null ? List.of() : dedupeFileNames(libs);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            buildInfo = buildInfo == null ? Map.of() : Map.copyOf(buildInfo);
            aotDirs = aotDirs == null ? List.of() : List.copyOf(aotDirs);
        }

        /** Back-compat constructor: no build-info, no SBOM, no AOT output. */
        public BootJarRequest(
                Path classesDir,
                List<Lib> libs,
                Path loaderJar,
                Path outputJar,
                String startClass,
                String bootVersion,
                Map<String, String> attributes,
                long timestampEpochSeconds) {
            this(
                    classesDir, libs, loaderJar, outputJar, startClass, bootVersion, attributes, Map.of(), null,
                    List.of(), timestampEpochSeconds);
        }

        /** Back-compat constructor: build-info + SBOM, no AOT output. */
        public BootJarRequest(
                Path classesDir,
                List<Lib> libs,
                Path loaderJar,
                Path outputJar,
                String startClass,
                String bootVersion,
                Map<String, String> attributes,
                Map<String, String> buildInfo,
                byte[] sbom,
                long timestampEpochSeconds) {
            this(
                    classesDir, libs, loaderJar, outputJar, startClass, bootVersion, attributes, buildInfo, sbom,
                    List.of(), timestampEpochSeconds);
        }

        /**
         * Two coordinates can share an {@code artifact-version.jar} name across groups; nested
         * entries must be unique, so later collisions get their group prepended.
         */
        private static List<Lib> dedupeFileNames(List<Lib> libs) {
            Set<String> seen = new LinkedHashSet<>();
            List<Lib> out = new ArrayList<>(libs.size());
            for (Lib lib : libs) {
                Lib effective = lib;
                if (!seen.add(lib.fileName())) {
                    String prefixed = lib.jar().getParent() != null
                            ? lib.jar().getParent().getFileName() + "-" + lib.fileName()
                            : "dup-" + lib.fileName();
                    int n = 2;
                    String candidate = prefixed;
                    while (!seen.add(candidate)) candidate = prefixed + "." + n++;
                    effective = new Lib(candidate, lib.jar(), lib.snapshot());
                }
                out.add(effective);
            }
            return List.copyOf(out);
        }
    }
}
