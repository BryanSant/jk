// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import dev.buildjk.model.DenyPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Parses the {@code deny { ... }} block from a {@code build.jk} into a
 * {@link DenyPolicy} (PRD §23.6). Lives next to {@link BuildJkParser};
 * the block is independent of the rest of the file so it has its own
 * entry point — {@code jk deny} doesn't need the full project model.
 */
public final class DenyPolicyParser {

    private DenyPolicyParser() {}

    public static DenyPolicy parse(Path buildJk) throws IOException {
        return parse(Files.readString(buildJk));
    }

    public static DenyPolicy parse(String hocon) {
        Config config = ConfigFactory.parseString(hocon,
                ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF));
        if (!config.hasPath("deny")) return DenyPolicy.permissive();
        return parseDenyBlock(config.getConfig("deny"));
    }

    private static DenyPolicy parseDenyBlock(Config deny) {
        List<String> sources = optionalStringList(deny, "sources.deny");
        List<String> deniedLicenses = optionalStringList(deny, "licenses.deny");
        List<String> allowedLicenses = optionalStringList(deny, "licenses.allow");
        DenyPolicy.YankedPolicy yanked = parseYanked(
                deny.hasPath("yanked") ? deny.getString("yanked") : "deny");
        return new DenyPolicy(sources, deniedLicenses, allowedLicenses, yanked);
    }

    private static List<String> optionalStringList(Config config, String path) {
        if (!config.hasPath(path)) return List.of();
        return List.copyOf(config.getStringList(path));
    }

    private static DenyPolicy.YankedPolicy parseYanked(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "deny" -> DenyPolicy.YankedPolicy.DENY;
            case "warn" -> DenyPolicy.YankedPolicy.WARN;
            case "allow" -> DenyPolicy.YankedPolicy.ALLOW;
            default -> throw new BuildJkParseException(
                    "deny.yanked must be `deny`, `warn`, or `allow` (got: " + raw + ")");
        };
    }
}
