// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.lock;

import dev.jkbuild.model.Scope;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses {@code jk.lock} (TOML) into a {@link Lockfile}.
 *
 * <p>Strict: rejects unknown top-level keys, missing required keys, or a
 * lockfile-version we don't know how to read. Forward compatibility is
 * deliberately a non-feature (PRD §9.1 schema-versioning policy).
 */
public final class LockfileReader {

    private LockfileReader() {}

    public static Lockfile read(Path file) throws IOException {
        TomlParseResult result = Toml.parse(file);
        return fromResult(result, file.toString());
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

        List<Lockfile.Package> packages = new ArrayList<>();
        TomlArray pkgArray = result.getArray("package");
        if (pkgArray != null) {
            for (int i = 0; i < pkgArray.size(); i++) {
                TomlTable table = pkgArray.getTable(i);
                packages.add(toPackage(table));
            }
        }
        return new Lockfile(lockVersion, generatedBy, resolutionAlgorithm, jdk, packages);
    }

    private static Lockfile.Package toPackage(TomlTable table) {
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
        return new Lockfile.Package(name, version, source, checksum, path, scopes, deps, pinnedBy);
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
            throw new IllegalArgumentException("[[package]] is missing required key `" + key + "`");
        }
        return Objects.requireNonNull(value);
    }
}
