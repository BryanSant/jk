// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.registry;

import dev.jkbuild.util.JkDirs;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

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

/**
 * Curated mapping of short names to {@code group:artifact} pairs.
 *
 * <p>The registry is layered. Each layer shadows the ones below it on a
 * per-name basis, so a project can locally override a single entry without
 * losing the rest of the curated set. Highest precedence first:
 *
 * <ol>
 *   <li><b>Project</b> — the {@code [aliases]} table in the project's
 *       {@code jk.toml}. Passed in by the parser.</li>
 *   <li><b>User</b> — {@code ~/.jk/aliases.toml} (per-user overrides).</li>
 *   <li><b>Downloaded</b> — {@code ~/.jk/registry/aliases.toml}, refreshed
 *       by {@code jk registry update} from
 *       {@code github.com/BryanSant/jk-registry}.</li>
 *   <li><b>Bundled</b> — classpath resource shipped with the jk binary
 *       ({@code dev/jkbuild/registry/aliases.toml}). Acts as the floor so
 *       lookups still work offline before any update has run.</li>
 * </ol>
 *
 * <p>The non-project layers are read-only from jk's perspective. The
 * bundled layer is the only one guaranteed to exist; the others light up
 * when their files appear on disk.
 */
public final class AliasRegistry {

    private static final String BUNDLED_RESOURCE = "/dev/jkbuild/registry/aliases.toml";

    /** Filename used for both the user-global file and the downloaded copy. */
    public static final String FILE_NAME = "aliases.toml";

    private static volatile AliasRegistry bundled;

    private final List<Layer> layers;

    private AliasRegistry(List<Layer> layers) {
        this.layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
    }

    /**
     * The bundled-only registry. Loaded once on first call; subsequent
     * calls are O(1). Use this when other layers shouldn't apply
     * (renderer-side defaulting, where local overrides would create
     * file-vs-tool consistency surprises).
     */
    public static AliasRegistry bundled() {
        AliasRegistry local = bundled;
        if (local != null) return local;
        synchronized (AliasRegistry.class) {
            if (bundled != null) return bundled;
            bundled = new AliasRegistry(List.of(loadBundledLayer()));
            return bundled;
        }
    }

    /**
     * The full read-only chain: user overrides → downloaded → bundled.
     * Project overrides aren't applied here — the parser layers them on
     * top via {@link #withProjectOverrides}.
     */
    public static AliasRegistry layered() {
        List<Layer> chain = new ArrayList<>();
        loadFileLayer(JkDirs.home().resolve(FILE_NAME), "user").ifPresent(chain::add);
        loadFileLayer(JkDirs.home().resolve("registry").resolve(FILE_NAME), "downloaded")
                .ifPresent(chain::add);
        chain.add(loadBundledLayer());
        return new AliasRegistry(chain);
    }

    /** Test seam: build a registry from a single in-memory map. */
    public static AliasRegistry of(Map<String, Module> aliases) {
        return new AliasRegistry(List.of(new Layer("test", Map.copyOf(aliases))));
    }

    /** Test seam: parse a single layer from a TOML string. */
    public static AliasRegistry parse(String toml) {
        return new AliasRegistry(List.of(new Layer("inline", parseTable(toml, "inline"))));
    }

    /**
     * Returns a new view with {@code projectAliases} as the top-priority
     * layer. The original registry is unchanged. Used by the parser to
     * make {@code [aliases]} from the current {@code jk.toml} the
     * authoritative source for that file.
     */
    public AliasRegistry withProjectOverrides(Map<String, Module> projectAliases) {
        if (projectAliases == null || projectAliases.isEmpty()) return this;
        List<Layer> chain = new ArrayList<>(layers.size() + 1);
        chain.add(new Layer("project", Map.copyOf(projectAliases)));
        chain.addAll(layers);
        return new AliasRegistry(chain);
    }

    /**
     * Look up a short name. Walks layers in order; the first hit wins.
     * Returns empty when no layer carries the name.
     */
    public Optional<Module> lookup(String name) {
        if (name == null) return Optional.empty();
        for (Layer layer : layers) {
            Module hit = layer.aliases.get(name);
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    /** All names across every layer, sorted lexicographically. */
    public Set<String> names() {
        Set<String> all = new TreeSet<>();
        for (Layer layer : layers) all.addAll(layer.aliases.keySet());
        return all;
    }

    /**
     * For diagnostics: report which layer a name resolves through. Empty
     * when no layer carries it.
     */
    public Optional<Source> source(String name) {
        if (name == null) return Optional.empty();
        for (Layer layer : layers) {
            Module hit = layer.aliases.get(name);
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
     * Best-effort name suggestions for an unknown alias. Splits the
     * candidate on {@code -} and returns up to {@code maxResults}
     * registry names that contain every non-empty part as a substring
     * (case-insensitive). Useful for "did you mean" diagnostics — typing
     * {@code jackson-databind} surfaces {@code jackson2-databind} and
     * {@code jackson3-databind} since both contain "jackson" and
     * "databind".
     *
     * <p>Walks the layered chain in lookup-priority order, so a
     * project-level override shadows a same-named bundled entry in the
     * suggestion list too.
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
            if (name.equalsIgnoreCase(unknownName)) continue;   // not a "suggestion"
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            boolean allMatch = true;
            for (String p : parts) {
                if (!lower.contains(p)) { allMatch = false; break; }
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

    /** Where a lookup resolved — used by {@code jk registry list}. */
    public record Source(String layer, Module module) {}

    private record Layer(String name, Map<String, Module> aliases) {}

    private static Layer loadBundledLayer() {
        try (InputStream in = AliasRegistry.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing classpath resource: " + BUNDLED_RESOURCE);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Layer("bundled", parseTable(text, BUNDLED_RESOURCE));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to load bundled alias registry from " + BUNDLED_RESOURCE, e);
        }
    }

    private static Optional<Layer> loadFileLayer(Path file, String layerName) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(new Layer(layerName, parseTable(text, file.toString())));
        } catch (IOException e) {
            // Fail soft: a malformed user/downloaded layer should warn,
            // not break every jk invocation. Surface via stderr and skip.
            System.err.println("warning: ignoring alias registry layer at "
                    + file + " — " + e.getMessage());
            return Optional.empty();
        } catch (IllegalStateException e) {
            System.err.println("warning: ignoring alias registry layer at "
                    + file + " — " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Parse an [aliases] table from TOML source. */
    static Map<String, Module> parseTable(String toml, String displayPath) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new IllegalStateException(displayPath
                    + " has invalid TOML: " + result.errors().getFirst().getMessage());
        }
        TomlTable table = result.getTable("aliases");
        if (table == null) {
            throw new IllegalStateException(displayPath
                    + " is missing the required [aliases] table");
        }
        return parseAliasesTable(table, displayPath);
    }

    /**
     * Parse an already-located {@code [aliases]} sub-table. Used by the
     * jk.toml parser, which has already navigated to the table.
     */
    public static Map<String, Module> parseAliasesTable(TomlTable table, String displayPath) {
        Map<String, Module> out = new LinkedHashMap<>();
        for (String name : table.keySet()) {
            Object raw = table.get(name);
            if (!(raw instanceof String coord)) {
                throw new IllegalStateException(displayPath + ".aliases." + name
                        + " must be a string of the form \"group:artifact\"");
            }
            int sep = coord.indexOf(':');
            if (sep <= 0 || sep == coord.length() - 1) {
                throw new IllegalStateException(displayPath + ".aliases." + name
                        + " must be \"group:artifact\" — got: " + coord);
            }
            if (coord.indexOf(':', sep + 1) >= 0) {
                throw new IllegalStateException(displayPath + ".aliases." + name
                        + " carries a version — strip it; the registry is name→coord only: "
                        + coord);
            }
            out.put(name, new Module(coord.substring(0, sep), coord.substring(sep + 1)));
        }
        return out;
    }
}
