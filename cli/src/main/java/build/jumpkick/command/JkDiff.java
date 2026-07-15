// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records what the previous {@code jk hook-env} call did so the next one can undo it. Stored in the
 * {@code __JK_DIFF} env var as a compact, base64-encoded text payload.
 *
 * <p>Each entry holds {@code KEY -> previousValue}. {@code previousValue} is the value the env var
 * held <em>before</em> jk overrode it — empty when the key was unset originally (distinguished via
 * the {@link #UNSET_SENTINEL}).
 *
 * <p>On the next hook-env call:
 *
 * <ul>
 *   <li>If the new target also overrides the key: emit {@code export} with the new value (the
 *       {@code previousValue} stays unchanged in the diff so a later deactivation still restores
 *       correctly).
 *   <li>If the new target no longer overrides the key: restore the previous value, or {@code unset}
 *       the key when the sentinel was recorded.
 * </ul>
 *
 * <p>Format: one line per entry, {@code KEY\0value\n}, then base64-encoded. Null byte is illegal in
 * env var keys, so it's a safe separator.
 */
public final class JkDiff {

    /** Sentinel stored when the previous env had no entry for a key. */
    static final String UNSET_SENTINEL = "__jk_unset__";

    private final Map<String, String> previous;

    public JkDiff(Map<String, String> previous) {
        this.previous = new LinkedHashMap<>(previous);
    }

    public static JkDiff empty() {
        return new JkDiff(Map.of());
    }

    /**
     * Parse a base64-encoded diff payload. Empty or malformed input yields an empty diff (we never
     * want a bad serialization to wedge the shell).
     */
    public static JkDiff parse(String encoded) {
        if (encoded == null || encoded.isBlank()) return empty();
        try {
            var decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            var map = new LinkedHashMap<String, String>();
            for (var line : decoded.split("\n")) {
                if (line.isEmpty()) continue;
                int sep = line.indexOf('\0');
                if (sep < 0) continue;
                map.put(line.substring(0, sep), line.substring(sep + 1));
            }
            return new JkDiff(map);
        } catch (IllegalArgumentException e) {
            return empty();
        }
    }

    /** Serialize as a base64-encoded string suitable for an env var value. */
    public String encode() {
        if (previous.isEmpty()) return "";
        var sb = new StringBuilder();
        previous.forEach((k, v) -> sb.append(k).append('\0').append(v).append('\n'));
        return Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Keys currently tracked. */
    public java.util.Set<String> keys() {
        return previous.keySet();
    }

    /**
     * Previous value for {@code key}; {@code null} if the diff has no entry, or the {@link
     * #UNSET_SENTINEL} when the key was unset in the original environment.
     */
    public String previousValue(String key) {
        return previous.get(key);
    }

    /** True iff the recorded "previous" value means the key was unset. */
    public boolean wasUnset(String key) {
        return UNSET_SENTINEL.equals(previous.get(key));
    }

    /**
     * Build the diff that will be stored after applying {@code target}: the union of
     * previously-tracked keys and the keys {@code target} overrides, each carrying the value the env
     * held <em>before</em> {@code jk} touched it.
     */
    public JkDiff next(JkEnv.Target target, EnvSnapshot current) {
        var combined = new LinkedHashMap<String, String>();
        for (var key : target.vars().keySet()) {
            // Reuse the prior diff's "before" value when we still own the key —
            // otherwise reach into the live env and capture (or sentinel) it.
            if (previous.containsKey(key)) {
                combined.put(key, previous.get(key));
            } else {
                var cur = current.get(key);
                combined.put(key, cur == null ? UNSET_SENTINEL : cur);
            }
        }
        return new JkDiff(combined);
    }

    /** Restore-source the live environment, used to seed {@link #next}. */
    @FunctionalInterface
    public interface EnvSnapshot {
        String get(String key);

        static EnvSnapshot fromSystem() {
            return System::getenv;
        }
    }
}
