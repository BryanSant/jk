// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.ObjectStoreConfig;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tomlj.TomlTable;

/**
 * The one parser for a {@code [repositories.<name>]} table — its inline {@code ${ENV}} interpolation,
 * bearer/basic {@link RepoCredential}, and object-store ({@link ObjectStoreConfig}) fields. Shared by
 * the project ({@code JkBuildParser}) and global ({@code GlobalConfig}) config layers, which differ
 * only in their <em>missing-variable policy</em>: the project layer fails loudly on an unset {@code
 * ${VAR}} (a typo shouldn't silently auth anonymously), the global layer is lenient. Callers pass
 * that policy as {@code resolveVar}; the field-reading logic lives here once.
 */
public final class RepositoryToml {

    private RepositoryToml() {}

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * Expand {@code ${VAR}} references in {@code raw}, resolving each var name via {@code resolveVar}
     * (which returns the replacement, or may throw for a strict "unset var" policy). {@code null} in →
     * {@code null} out.
     */
    public static String interpolate(String raw, UnaryOperator<String> resolveVar) {
        if (raw == null) return null;
        Matcher m = ENV_REF.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(resolveVar.apply(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Bearer ({@code token}) or basic ({@code username}/{@code password}) credential on the table, or
     * empty. {@code interp} applies the caller's {@code ${ENV}} interpolation to each value.
     */
    public static Optional<RepoCredential> credential(TomlTable t, UnaryOperator<String> interp) {
        String token = interp.apply(t.getString("token"));
        String username = interp.apply(t.getString("username"));
        String password = interp.apply(t.getString("password"));
        if (token != null && !token.isBlank()) {
            return Optional.of(new RepoCredential.Bearer(token));
        }
        if (username != null && !username.isBlank()) {
            return Optional.of(new RepoCredential.Basic(username, password == null ? "" : password));
        }
        return Optional.empty();
    }

    /**
     * Object-store config ({@code region}/{@code endpoint}/{@code access-key}/{@code secret-key}/
     * {@code session-token}) for s3://gs:// backends, or empty when none set. {@code interp} applies
     * the caller's {@code ${ENV}} interpolation.
     */
    public static Optional<ObjectStoreConfig> objectStore(TomlTable t, UnaryOperator<String> interp) {
        ObjectStoreConfig cfg = new ObjectStoreConfig(
                blankToNull(interp.apply(t.getString("region"))),
                blankToNull(interp.apply(t.getString("endpoint"))),
                blankToNull(interp.apply(t.getString("access-key"))),
                blankToNull(interp.apply(t.getString("secret-key"))),
                blankToNull(interp.apply(t.getString("session-token"))));
        return cfg.isEmpty() ? Optional.empty() : Optional.of(cfg);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
