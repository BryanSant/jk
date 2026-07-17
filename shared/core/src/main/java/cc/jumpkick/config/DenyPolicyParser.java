// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.DenyPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Parses the {@code [deny]} block from a {@code jk.toml} into a {@link DenyPolicy} (PRD §23.6). The
 * block is independent of the rest of the file so it has its own entry point — {@code jk deny}
 * doesn't need the full project model.
 */
public final class DenyPolicyParser {

    private DenyPolicyParser() {}

    public static DenyPolicy parse(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    public static DenyPolicy parse(String toml) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(
                    "failed to parse jk.toml: " + result.errors().getFirst().getMessage());
        }
        TomlTable deny = result.getTable("deny");
        if (deny == null) return DenyPolicy.permissive();
        return parseDenyBlock(deny);
    }

    private static DenyPolicy parseDenyBlock(TomlTable deny) {
        List<String> sources = optionalStringList(deny.getTable("sources"), "deny");
        List<String> deniedLicenses = optionalStringList(deny.getTable("licenses"), "deny");
        List<String> allowedLicenses = optionalStringList(deny.getTable("licenses"), "allow");
        String yankedRaw = deny.getString("yanked");
        DenyPolicy.YankedPolicy yanked = parseYanked(yankedRaw == null ? "deny" : yankedRaw);
        return new DenyPolicy(sources, deniedLicenses, allowedLicenses, yanked);
    }

    private static List<String> optionalStringList(TomlTable table, String key) {
        if (table == null) return List.of();
        TomlArray arr = table.getArray(key);
        if (arr == null) return List.of();
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new JkBuildParseException("expected `deny." + key + "` to be a list of strings");
            }
            result.add(s);
        }
        return List.copyOf(result);
    }

    private static DenyPolicy.YankedPolicy parseYanked(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "deny" -> DenyPolicy.YankedPolicy.DENY;
            case "warn" -> DenyPolicy.YankedPolicy.WARN;
            case "allow" -> DenyPolicy.YankedPolicy.ALLOW;
            default ->
                throw new JkBuildParseException("deny.yanked must be `deny`, `warn`, or `allow` (got: " + raw + ")");
        };
    }
}
