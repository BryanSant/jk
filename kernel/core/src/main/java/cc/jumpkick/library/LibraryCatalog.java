// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.library;

import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.tomlj.TomlTable;

/**
 * Curated mapping of short names to {@code group:artifact} pairs.
 *
 * <p>The catalog is layered. Each layer shadows the ones below it on a per-name basis, so a project
 * can locally override a single entry without losing the rest of the curated set. Highest
 * precedence first:
 *
 * <ol>
 *   <li><b>Project</b> — the {@code [libraries]} table in the project's {@code jk.toml}. Passed in
 *       by the parser.
 *   <li><b>Local</b> — {@code ~/.jk/libs.toml} (per-user overrides, hand-edited).
 *   <li><b>Global</b> — {@code ~/.jk/cache/libs.global.toml}, refreshed by {@code jk library
 *       update} and revalidated by {@code jk lock} from {@code
 *       github.com/jkbuild/jk-library-registry}.
 *   <li><b>Bundled</b> — classpath resource shipped with the jk binary ({@code
 *       cc/jumpkick/library/libraries.toml}). Acts as the floor so lookups still work offline
 *       before any update has run.
 * </ol>
 *
 * <p>The non-project layers are read-only from jk's perspective. The bundled layer is the only one
 * guaranteed to exist; the others light up when their files appear on disk.
 */
public final class LibraryCatalog {

    private static final String BUNDLED_RESOURCE = "/cc/jumpkick/library/libraries.toml";

    private static volatile LibraryCatalog bundled;

    private final List<Layer> layers;

    private LibraryCatalog(List<Layer> layers) {
        this.layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
    }

    /** Per-user manual override layer: {@code ~/.jk/libs.toml}. */
    public static Path userFile() {
        return JkDirs.home().resolve("libs.toml");
    }

    /**
     * The downloaded layer, refreshed by {@code jk library update} and revalidated by {@code jk
     * lock}: {@code ~/.jk/cache/libs.global.toml} — under {@code cache/} because it is exactly
     * that: a regenerable mirror of the upstream registry.
     */
    public static Path downloadedFile() {
        return JkDirs.cache().resolve("libs.global.toml");
    }

    /**
     * Hidden sidecar next to {@code cacheFile} storing the validating {@code ETag} used for
     * conditional-GET revalidation. Kept alongside the cache file it validates so overriding one (as
     * tests do for {@link #downloadedFile()}) naturally relocates the other.
     */
    public static Path etagFileFor(Path cacheFile) {
        return cacheFile.resolveSibling("." + cacheFile.getFileName() + ".etag");
    }

    /** The validating {@code ETag} sidecar for {@link #downloadedFile()}. */
    public static Path etagFile() {
        return etagFileFor(downloadedFile());
    }

    /**
     * The bundled-only catalog. Loaded once on first call; subsequent calls are O(1). Use this when
     * other layers shouldn't apply (renderer-side defaulting, where local overrides would create
     * file-vs-tool consistency surprises).
     */
    public static LibraryCatalog bundled() {
        LibraryCatalog local = bundled;
        if (local != null) return local;
        synchronized (LibraryCatalog.class) {
            if (bundled != null) return bundled;
            bundled = new LibraryCatalog(List.of(loadBundledLayer()));
            return bundled;
        }
    }

    /**
     * The full read-only chain: local overrides → global → bundled. Project overrides aren't applied
     * here — the parser layers them on top via {@link #withProjectOverrides}.
     */
    public static LibraryCatalog layered() {
        return layered(w -> {});
    }

    /**
     * As {@link #layered()}, but reports each skipped-malformed-layer warning to {@code warn} instead
     * of a stream — only the CLI view layer owns {@code System.err}, so callers there pass {@code
     * System.err::println}.
     */
    public static LibraryCatalog layered(java.util.function.Consumer<String> warn) {
        List<Layer> chain = new ArrayList<>();
        loadFileLayer(userFile(), "local", warn).ifPresent(chain::add);
        loadFileLayer(downloadedFile(), "global", warn).ifPresent(chain::add);
        chain.add(loadBundledLayer());
        return new LibraryCatalog(chain);
    }

    /** Test seam: build a catalog from a single in-memory map. */
    public static LibraryCatalog of(Map<String, Module> libraries) {
        return new LibraryCatalog(List.of(new Layer("test", Map.copyOf(libraries))));
    }

    /** Test seam: parse a single layer from a TOML string. */
    public static LibraryCatalog parse(String toml) {
        return new LibraryCatalog(List.of(new Layer("inline", parseTable(toml, "inline"))));
    }

    /**
     * Returns a new view with {@code projectLibraries} as the top-priority layer. The original
     * catalog is unchanged. Used by the parser to make {@code [libraries]} from the current {@code
     * jk.toml} the authoritative source for that file.
     */
    public LibraryCatalog withProjectOverrides(Map<String, Module> projectLibraries) {
        if (projectLibraries == null || projectLibraries.isEmpty()) return this;
        List<Layer> chain = new ArrayList<>(layers.size() + 1);
        chain.add(new Layer("project", Map.copyOf(projectLibraries)));
        chain.addAll(layers);
        return new LibraryCatalog(chain);
    }

    /**
     * Look up a short name. Walks layers in order; the first hit wins. Returns empty when no layer
     * carries the name.
     */
    public Optional<Module> lookup(String name) {
        if (name == null) return Optional.empty();
        for (Layer layer : layers) {
            Module hit = layer.libraries.get(name);
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    /** All names across every layer, sorted lexicographically. */
    public Set<String> names() {
        Set<String> all = new TreeSet<>();
        for (Layer layer : layers) all.addAll(layer.libraries.keySet());
        return all;
    }

    /**
     * For diagnostics: report which layer a name resolves through. Empty when no layer carries it.
     */
    public Optional<Source> source(String name) {
        if (name == null) return Optional.empty();
        for (Layer layer : layers) {
            Module hit = layer.libraries.get(name);
            if (hit != null) return Optional.of(new Source(layer.name, hit));
        }
        return Optional.empty();
    }

    /** All layers, in lookup order (highest precedence first). */
    public List<String> layerNames() {
        List<String> out = new ArrayList<>(layers.size());
        for (Layer layer : layers) out.add(layer.name);
        return List.copyOf(out);
    }

    public int size() {
        return names().size();
    }

    /**
     * Best-effort name suggestions for an unknown library. Splits the candidate on {@code -} and
     * returns up to {@code maxResults} catalog names that contain every non-empty part as a substring
     * (case-insensitive). Useful for "did you mean" diagnostics — typing {@code jackson-databind}
     * surfaces {@code jackson2-databind} and {@code jackson3-databind} since both contain "jackson"
     * and "databind".
     *
     * <p>Walks the layered chain in lookup-priority order, so a project-level override shadows a
     * same-named bundled entry in the suggestion list too.
     */
    public List<String> suggestionsFor(String unknownName, int maxResults) {
        if (unknownName == null || unknownName.isBlank() || maxResults <= 0) {
            return List.of();
        }
        String lowerInput = unknownName.toLowerCase(java.util.Locale.ROOT);
        List<String> parts = new ArrayList<>();
        for (String p : lowerInput.split("-")) {
            if (!p.isEmpty()) parts.add(p);
        }
        if (parts.isEmpty()) return List.of();
        List<String> hits = new ArrayList<>();
        for (String name : names()) {
            if (name.equalsIgnoreCase(unknownName)) continue; // not a "suggestion"
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            boolean allMatch = true;
            for (String p : parts) {
                if (!lower.contains(p)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                hits.add(name);
                if (hits.size() >= maxResults) break;
            }
        }
        return List.copyOf(hits);
    }

    /** The non-version half of a Maven coordinate. */
    public record Module(String group, String artifact) {
        public Module {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            if (group.isBlank()) throw new IllegalArgumentException("group must not be blank");
            if (artifact.isBlank()) throw new IllegalArgumentException("artifact must not be blank");
        }

        public String moduleKey() {
            return group + ":" + artifact;
        }
    }

    /** Where a lookup resolved — used by {@code jk library list}. */
    public record Source(String layer, Module module) {}

    private record Layer(String name, Map<String, Module> libraries) {}

    private static Layer loadBundledLayer() {
        try (InputStream in = LibraryCatalog.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing classpath resource: " + BUNDLED_RESOURCE);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Layer("bundled", parseTable(text, BUNDLED_RESOURCE));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load bundled library catalog from " + BUNDLED_RESOURCE, e);
        }
    }

    private static Optional<Layer> loadFileLayer(
            Path file, String layerName, java.util.function.Consumer<String> warn) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(new Layer(layerName, parseTable(text, file.toString())));
        } catch (IOException | IllegalStateException e) {
            // Fail soft: a malformed user/downloaded layer should warn, not
            // break every jk invocation. Hand the message to the caller's sink
            // (the CLI routes it to stderr) and skip the layer.
            warn.accept("warning: ignoring library catalog layer at " + file + " — " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse a {@code [libraries]} table from catalog TOML source. A line scanner, not tomlj: the
     * catalog files (bundled resource, {@code ~/.jk/libs.toml}, the downloaded layer) are a
     * jk-owned flat format — {@code name = "group:artifact"} — and this parse runs client-side
     * (list/search/suggestions, tool targets, scaffold), where the thin client ships no TOML
     * parser. Validation is per-entry and as strict as the old parse: a malformed entry throws
     * with the same messages.
     */
    static Map<String, Module> parseTable(String toml, String displayPath) {
        Map<String, Module> out = new LinkedHashMap<>();
        boolean seenTable = false;
        boolean inTable = false;
        for (String raw : toml.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                int close = line.indexOf(']');
                String section = close > 1
                        ? line.substring(line.startsWith("[[") ? 2 : 1, close).replace("]", "").strip()
                        : "";
                inTable = section.equals("libraries");
                seenTable |= inTable;
                continue;
            }
            if (!inTable) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException(displayPath + " has invalid TOML: unexpected line `" + line + "`");
            }
            String name = unquoteKey(line.substring(0, eq).strip());
            String rest = line.substring(eq + 1).strip();
            if (rest.length() < 2 || rest.charAt(0) != '"') {
                throw new IllegalStateException(
                        displayPath + ".libraries." + name + " must be a string of the form \"group:artifact\"");
            }
            int end = rest.indexOf('"', 1);
            if (end < 0) {
                throw new IllegalStateException(displayPath + " has invalid TOML: unterminated string for " + name);
            }
            String coord = rest.substring(1, end);
            int sep = coord.indexOf(':');
            if (sep <= 0 || sep == coord.length() - 1) {
                throw new IllegalStateException(
                        displayPath + ".libraries." + name + " must be \"group:artifact\" — got: " + coord);
            }
            if (coord.indexOf(':', sep + 1) >= 0) {
                throw new IllegalStateException(displayPath
                        + ".libraries."
                        + name
                        + " carries a version — strip it; the catalog is name→coord only: "
                        + coord);
            }
            out.put(name, new Module(coord.substring(0, sep), coord.substring(sep + 1)));
        }
        if (!seenTable) {
            throw new IllegalStateException(displayPath + " is missing the required [libraries] table");
        }
        return out;
    }

    /** Strip optional quotes from a TOML key ({@code "a.b" = …}). */
    private static String unquoteKey(String key) {
        if (key.length() >= 2 && (key.charAt(0) == '"' || key.charAt(0) == '\'')) {
            char q = key.charAt(0);
            if (key.charAt(key.length() - 1) == q) return key.substring(1, key.length() - 1);
        }
        return key;
    }

    /**
     * Parse an already-located {@code [libraries]} sub-table. Used by the jk.toml parser, which has
     * already navigated to the table.
     */
    public static Map<String, Module> parseLibrariesTable(TomlTable table, String displayPath) {
        Map<String, Module> out = new LinkedHashMap<>();
        for (String name : table.keySet()) {
            Object raw = table.get(name);
            if (!(raw instanceof String coord)) {
                throw new IllegalStateException(
                        displayPath + ".libraries." + name + " must be a string of the form \"group:artifact\"");
            }
            int sep = coord.indexOf(':');
            if (sep <= 0 || sep == coord.length() - 1) {
                throw new IllegalStateException(
                        displayPath + ".libraries." + name + " must be \"group:artifact\" — got: " + coord);
            }
            if (coord.indexOf(':', sep + 1) >= 0) {
                throw new IllegalStateException(displayPath
                        + ".libraries."
                        + name
                        + " carries a version — strip it; the catalog is name→coord only: "
                        + coord);
            }
            out.put(name, new Module(coord.substring(0, sep), coord.substring(sep + 1)));
        }
        return out;
    }
}
