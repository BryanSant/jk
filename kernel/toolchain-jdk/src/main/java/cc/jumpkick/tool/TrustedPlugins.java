// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The build-plugin consent gate (build-plugins plan §3.4 / plugin-refactor Posture A): third-party
 * plugins declared under {@code [plugins]} run code in worker JVMs during ordinary builds, so —
 * unlike {@code jk tool run}, which executes something the user explicitly invoked — the engine
 * refuses to fork a plugin worker until its coordinate is trusted here.
 *
 * <p>{@code ~/.jk/state/trusted-plugins.toml} holds {@code plugins = [ "…" ]} — one entry per
 * line, the same jk-managed line-reader format as {@code trusted-sources.toml} (its own file so
 * the two writers never clobber each other). An entry is an exact {@code group:artifact}
 * coordinate or a group prefix ending in {@code :} ({@code "com.example:"} trusts the whole
 * group). Managed by {@code jk trust plugin} / {@code jk trust remove}; hand edits are fine.
 * First-party plugins shipped inside jk are implicitly trusted and never consult this file.
 */
public final class TrustedPlugins {

    private static final String FILE_NAME = "trusted-plugins.toml";
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private final Path file;
    private final List<String> entries;

    private TrustedPlugins(Path file, List<String> entries) {
        this.file = file;
        this.entries = entries;
    }

    public static TrustedPlugins load(Path stateDir) throws IOException {
        Path file = stateDir.resolve(FILE_NAME);
        List<String> entries = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            boolean inArray = false;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!inArray) {
                    if (!line.startsWith("plugins")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    line = line.substring(eq + 1).strip();
                    if (line.startsWith("[")) line = line.substring(1);
                    inArray = true;
                }
                int close = line.indexOf(']', line.lastIndexOf('"') + 1);
                boolean closes = close >= 0;
                if (closes) line = line.substring(0, close);
                Matcher m = QUOTED.matcher(line);
                while (m.find()) entries.add(m.group(1));
                if (closes) break;
            }
        }
        return new TrustedPlugins(file, entries);
    }

    public List<String> list() {
        return List.copyOf(entries);
    }

    /** True when {@code coordinate} ({@code group:artifact}) matches an entry or group prefix. */
    public boolean isTrusted(String coordinate) {
        String c = normalize(coordinate);
        for (String entry : entries) {
            String e = normalize(entry);
            if (e.endsWith(":") ? c.startsWith(e) : c.equals(e)) return true;
        }
        return false;
    }

    /** Add {@code entry}; returns false when already present. Persists on change. */
    public boolean add(String entry) throws IOException {
        String e = entry.trim();
        if (entries.stream().anyMatch(x -> normalize(x).equals(normalize(e)))) return false;
        entries.add(e);
        save();
        return true;
    }

    /** Remove {@code entry}; returns false when it wasn't present. Persists on change. */
    public boolean remove(String entry) throws IOException {
        boolean removed = entries.removeIf(x -> normalize(x).equals(normalize(entry)));
        if (removed) save();
        return removed;
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Third-party build-plugin coordinates allowed to run worker code during builds.\n");
        sb.append("# Managed by `jk trust plugin|remove`; hand edits are fine. A trailing-colon\n");
        sb.append("# entry (\"com.example:\") trusts every plugin in the group.\n");
        sb.append("plugins = [\n");
        for (String e : entries) {
            sb.append("  \"").append(e.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n");
        }
        sb.append("]\n");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
