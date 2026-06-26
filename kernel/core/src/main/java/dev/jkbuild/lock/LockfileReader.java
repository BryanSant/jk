// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.lock;

import dev.jkbuild.model.Scope;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses {@code jk.lock} (TOML) into a {@link Lockfile}.
 *
 * <p>Strict: rejects unknown top-level keys, missing required keys, or a
 * lockfile-version we don't know how to read. Forward compatibility is
 * deliberately a non-feature (PRD §9.1 schema-versioning policy).
 */
public final class LockfileReader {

    private LockfileReader() {}

    /**
     * Memoises {@link #read(Path)} results for the life of the process, keyed by
     * file identity (absolute path + size + mtime). jk is single-shot and the
     * lockfile does not change during a build: the engine reads {@code jk.lock}
     * from several places (parse-build scope, parse-build execute, sync-deps scope,
     * predictSync in EffortWeights, sibling-lockfile loops) that all resolve to the
     * same bytes on disk. Caching collapses those repeated TOML parses to a single
     * read per distinct file per process. Keying on size + mtime means an
     * auto-lock update (which rewrites the file) gets a fresh parse.
     */
    private static final ConcurrentHashMap<CacheKey, Lockfile> READ_CACHE =
            new ConcurrentHashMap<>();

    private record CacheKey(Path path, long size, FileTime modified) {}

    /** Test seam: drop the per-process read memo so freshly-written files re-parse. */
    public static void clearCache() {
        READ_CACHE.clear();
    }

    public static Lockfile read(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        CacheKey key = new CacheKey(
                file.toAbsolutePath().normalize(), attrs.size(), attrs.lastModifiedTime());
        Lockfile cached = READ_CACHE.get(key);
        if (cached != null) return cached;
        TomlParseResult result = Toml.parse(file);
        Lockfile lockfile = fromResult(result, file.toString());
        READ_CACHE.put(key, lockfile);
        return lockfile;
    }

    public static Lockfile parse(String content) {
        TomlParseResult result = Toml.parse(content);
        return fromResult(result, "<string>");
    }

    private static Lockfile fromResult(TomlParseResult result, String origin) {
        if (result.hasErrors()) {
            throw new IllegalArgumentException(
                    "jk.lock parse error in " + origin + ": " + result.errors().getFirst().getMessage());
        }
        Long lockVersionLong = result.getLong("version");
        if (lockVersionLong == null) {
            throw new IllegalArgumentException("jk.lock is missing required key `version`");
        }
        int lockVersion = lockVersionLong.intValue();
        if (lockVersion < Lockfile.MIN_SUPPORTED_VERSION || lockVersion > Lockfile.CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "jk.lock schema version " + lockVersion + " is not supported (this jk reads v"
                            + Lockfile.MIN_SUPPORTED_VERSION + "-v" + Lockfile.CURRENT_VERSION + ")");
        }
        String generatedBy = requireString(result, "generated-by");
        String resolutionAlgorithm = requireString(result, "resolution-algorithm");
        String jdk = result.getString("jdk"); // optional
        String kotlin = result.getString("kotlin"); // optional, resolved Kotlin compiler version

        List<Lockfile.Artifact> artifacts = new ArrayList<>();
        TomlArray artifactArray = result.getArray("artifact");
        if (artifactArray != null) {
            for (int i = 0; i < artifactArray.size(); i++) {
                artifacts.add(toArtifact(artifactArray.getTable(i)));
            }
        }

        List<Lockfile.PluginEntry> plugins = new ArrayList<>();
        TomlArray pluginArray = result.getArray("plugin");
        if (pluginArray != null) {
            for (int i = 0; i < pluginArray.size(); i++) {
                TomlTable t    = pluginArray.getTable(i);
                String coord   = requireString(t, "coordinate");
                String ver     = requireString(t, "version");
                String chk     = requireString(t, "checksum");
                plugins.add(new Lockfile.PluginEntry(coord, ver, chk));
            }
        }

        return new Lockfile(lockVersion, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, plugins);
    }

    private static Lockfile.Artifact toArtifact(TomlTable table) {
        String name = requireString(table, "name");
        String version = requireString(table, "version");
        String source = requireString(table, "source");
        String checksum = table.getString("checksum");
        String path = table.getString("path");
        String pinnedBy = table.getString("pinned-by"); // optional

        List<Scope> scopes = new ArrayList<>();
        TomlArray scopesArray = table.getArray("scopes");
        if (scopesArray != null) {
            for (int i = 0; i < scopesArray.size(); i++) {
                String name2 = scopesArray.getString(i);
                scopes.add(Scope.valueOf(name2.toUpperCase()));
            }
        }
        if (scopes.isEmpty()) {
            // v4 compatibility: untagged packages default to MAIN.
            scopes.add(Scope.MAIN);
        }

        List<String> deps = new ArrayList<>();
        TomlArray depsArray = table.getArray("deps");
        if (depsArray != null) {
            for (int i = 0; i < depsArray.size(); i++) {
                deps.add(depsArray.getString(i));
            }
        }

        Lockfile.Artifact.GitInfo git = null;
        String gitUrl = table.getString("git");
        if (gitUrl != null) {
            git = new Lockfile.Artifact.GitInfo(gitUrl, requireString(table, "rev"),
                    table.getString("ref"));
        }
        String sourcesChecksum = table.getString("sources"); // optional
        return new Lockfile.Artifact(name, version, source, checksum, path, scopes, deps, pinnedBy,
                git, sourcesChecksum);
    }

    private static String requireString(TomlParseResult result, String key) {
        String value = result.getString(key);
        if (value == null) {
            throw new IllegalArgumentException("jk.lock is missing required key `" + key + "`");
        }
        return value;
    }

    private static String requireString(TomlTable table, String key) {
        String value = table.getString(key);
        if (value == null) {
            throw new IllegalArgumentException("[[artifact]] is missing required key `" + key + "`");
        }
        return Objects.requireNonNull(value);
    }
}
