// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Ndjson;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine's read-only "which declared dependencies are behind" report ({@link
 * EngineProtocol#OUTDATED_REQUEST}) for {@code jk outdated}. {@code error} non-null means the report
 * could not be produced (no jk.toml, parse failure); its message is ready to print and {@code rows}
 * is empty. {@code workspace} is true when the report spans a workspace (more than one module) — the
 * renderer adds a Module column in that case.
 *
 * <p>Each {@link Row} is one direct declared dependency:
 *
 * <ul>
 *   <li>{@code moduleLabel} — the owning workspace module's {@code group:artifact} (empty in a
 *       single-project report).
 *   <li>{@code coordinate} — the dependency's {@code group:artifact} (the join key / fallback label).
 *   <li>{@code display} — the short catalog name for the coordinate, or empty when none maps; the
 *       renderer shows it italic in place of the coordinate.
 *   <li>{@code scope} — the scope it was declared under (main/test/…).
 *   <li>{@code current} — the version pinned in {@code jk.lock}; for git, the tracked tag / short
 *       rev / {@code tip} for a moving branch. Empty when unlocked.
 *   <li>{@code compatible} — newest stable version the declared selector still allows (git: mirrors
 *       {@code current}, an immutable pin).
 *   <li>{@code latest} — newest stable version overall (git: highest stable SemVer tag).
 *   <li>{@code tip} — newest non-stable frontier ahead of {@code latest} (git: newest prerelease
 *       tag, else the literal {@code tip} for the moving HEAD). Empty when nothing non-stable is
 *       ahead. Only rendered under {@code --show-tip}.
 * </ul>
 *
 * <p>Wire form: {@code workspace} is a bool scalar; rows ride a single {@code rows} string array,
 * each element a {@code |}-joined 8-tuple. The fields are Maven coordinates, concrete versions / tag
 * names, and lowercase scope names — none contains a {@code |} (git ref names are sanitized), so the
 * join is unambiguous.
 */
public record OutdatedReport(String error, boolean workspace, List<Row> rows) {

    /** One direct declared dependency's version picture. */
    public record Row(
            String moduleLabel,
            String coordinate,
            String display,
            String scope,
            String current,
            String compatible,
            String latest,
            String tip) {}

    public static OutdatedReport error(String message) {
        return new OutdatedReport(message, false, List.of());
    }

    public static OutdatedReport of(boolean workspace, List<Row> rows) {
        return new OutdatedReport(null, workspace, List.copyOf(rows));
    }

    public String encode() {
        List<String> encoded = new ArrayList<>(rows.size());
        for (Row r : rows) {
            encoded.add(String.join(
                    "|",
                    r.moduleLabel(),
                    r.coordinate(),
                    r.display(),
                    r.scope(),
                    r.current(),
                    r.compatible(),
                    r.latest(),
                    r.tip()));
        }
        return "{\"t\":\"" + EngineProtocol.OUTDATED_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Ndjson.quote(error))
                + ",\"workspace\":" + workspace
                + ",\"rows\":" + EngineProtocol.quoteArray(encoded)
                + "}";
    }

    public static OutdatedReport decode(String line) {
        String error = Ndjson.str(line, "error");
        boolean workspace = Ndjson.bool(line, "workspace", false);
        List<Row> rows = new ArrayList<>();
        for (String enc : Ndjson.strArray(line, "rows")) {
            String[] f = enc.split("\\|", -1);
            rows.add(new Row(at(f, 0), at(f, 1), at(f, 2), at(f, 3), at(f, 4), at(f, 5), at(f, 6), at(f, 7)));
        }
        return new OutdatedReport(error, workspace, rows);
    }

    private static String at(String[] a, int i) {
        return i < a.length ? a[i] : "";
    }
}
