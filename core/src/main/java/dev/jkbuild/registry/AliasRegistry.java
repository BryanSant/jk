// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.registry;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Curated mapping of short names to {@code group:artifact} pairs.
 *
 * <p>Lets users write {@code jackson-databind = "2.18.2"} in their
 * {@code jk.toml} instead of repeating the Maven coordinate, and lets
 * {@code jk add jackson-databind} resolve the group automatically.
 *
 * <p>The bundled registry ships as a classpath resource at
 * {@code dev/jkbuild/registry/aliases.toml}. {@link #bundled()} returns a
 * lazy-loaded singleton. Tests can build synthetic registries via
 * {@link #of(Map)}.
 *
 * <p>The bundled registry is intentionally version-free — it's a
 * name-to-coordinate index, not a curated catalog. Version pinning is the
 * project's responsibility.
 */
public final class AliasRegistry {

    private static final String BUNDLED_RESOURCE = "/dev/jkbuild/registry/aliases.toml";

    private static volatile AliasRegistry bundled;

    private final Map<String, Module> aliases;

    private AliasRegistry(Map<String, Module> aliases) {
        this.aliases = Map.copyOf(aliases);
    }

    /**
     * The bundled registry. Loaded once on first call; subsequent calls
     * are O(1). Throws {@link UncheckedIOException} if the resource is
     * missing or malformed — failures here are programmer errors, never
     * something the user can recover from at runtime.
     */
    public static AliasRegistry bundled() {
        AliasRegistry local = bundled;
        if (local != null) return local;
        synchronized (AliasRegistry.class) {
            if (bundled != null) return bundled;
            bundled = loadBundled();
            return bundled;
        }
    }

    /** Test seam: build a registry from an in-memory map. */
    public static AliasRegistry of(Map<String, Module> aliases) {
        return new AliasRegistry(aliases);
    }

    /** Test seam: parse from a TOML string instead of the bundled resource. */
    public static AliasRegistry parse(String toml) {
        return new AliasRegistry(parseTable(toml));
    }

    /**
     * Look up a short name. Returns the {@code (group, artifact)} pair
     * when the alias is curated, empty otherwise. Lookups are
     * case-sensitive — alias spellings should match exactly.
     */
    public Optional<Module> lookup(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(aliases.get(name));
    }

    /** All known short names. Sorted lexicographically for stable iteration. */
    public Set<String> names() {
        return aliases.keySet();
    }

    public int size() {
        return aliases.size();
    }

    /**
     * The non-version half of a Maven coordinate: the {@code group} and
     * {@code artifact} pair that a short name resolves to.
     */
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

    private static AliasRegistry loadBundled() {
        try (InputStream in = AliasRegistry.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing classpath resource: " + BUNDLED_RESOURCE);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new AliasRegistry(parseTable(text));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to load bundled alias registry from " + BUNDLED_RESOURCE, e);
        }
    }

    private static Map<String, Module> parseTable(String toml) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new IllegalStateException(
                    "alias registry has invalid TOML: " + result.errors().getFirst().getMessage());
        }
        TomlTable table = result.getTable("aliases");
        if (table == null) {
            throw new IllegalStateException("alias registry missing required [aliases] table");
        }
        Map<String, Module> out = new LinkedHashMap<>();
        for (String name : table.keySet()) {
            Object raw = table.get(name);
            if (!(raw instanceof String coord)) {
                throw new IllegalStateException("aliases." + name
                        + " must be a string of the form \"group:artifact\"");
            }
            int sep = coord.indexOf(':');
            if (sep <= 0 || sep == coord.length() - 1) {
                throw new IllegalStateException("aliases." + name
                        + " must be \"group:artifact\" — got: " + coord);
            }
            if (coord.indexOf(':', sep + 1) >= 0) {
                throw new IllegalStateException("aliases." + name
                        + " carries a version — strip it; the registry is name→coord only: "
                        + coord);
            }
            out.put(name, new Module(coord.substring(0, sep), coord.substring(sep + 1)));
        }
        return out;
    }
}
