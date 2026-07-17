// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.engine.protocol.GeneratedFiles;
import cc.jumpkick.model.command.Exit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared wire + write helpers for the {@code jk export} subcommands. Content generation is
 * engine-hosted (thin client — it needs the parsed root, workspace modules, and merged locked
 * versions); this class fetches the payloads, applies the overwrite guard, writes, and prints.
 */
final class ExportSupport {

    private ExportSupport() {}

    /** Fetch a generator's payloads; prints and returns {@code null} on error. */
    static GeneratedFiles generate(Path dir, String kind, String cmd) {
        return generate(dir, kind, java.util.Map.of(), cmd);
    }

    /** As above with generator parameters (scaffold inputs etc.). */
    static GeneratedFiles generate(Path dir, String kind, java.util.Map<String, String> params, String cmd) {
        try {
            GeneratedFiles files = engineDisabledForTests()
                    ? cc.jumpkick.cli.engine.InProcessEngine.require().generate(dir, kind, params)
                    : cc.jumpkick.cli.engine.EngineClient.generate(
                            cc.jumpkick.engine.EnginePaths.current(), dir, kind, params);
            if (files.error() != null) {
                CliOutput.err(cmd + ": " + files.error());
                return null;
            }
            return files;
        } catch (Exception e) {
            CliOutput.err(cmd + ": " + e.getMessage());
            return null;
        }
    }

    /** Guard every path first (all-or-nothing), then write + report. */
    static int writeAll(GeneratedFiles files, boolean force, String cmd) throws IOException {
        for (String path : files.paths()) {
            if (!canWrite(Path.of(path), force, cmd)) return Exit.CANT_CREATE;
        }
        for (int i = 0; i < files.paths().size(); i++) {
            Path path = Path.of(files.paths().get(i));
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, files.contents().get(i), StandardCharsets.UTF_8);
            wrote(path);
        }
        int warnings = printNotes(files);
        if (warnings > 0) {
            CliOutput.out("  (" + warnings + " fidelity note" + (warnings == 1 ? "" : "s") + ")");
        }
        return 0;
    }

    /**
     * True if it's safe to write {@code target} (doesn't exist, or {@code force}); else prints +
     * false.
     */
    static boolean canWrite(Path target, boolean force, String cmd) {
        if (Files.exists(target) && !force) {
            CliOutput.err(cmd + ": refusing to overwrite " + PathDisplay.styled(target) + " (use --force).");
            return false;
        }
        return true;
    }

    /** Print fidelity notes ({@code severity|message} wire lines) to stderr; return the count. */
    static int printNotes(GeneratedFiles files) {
        int n = 0;
        for (String note : files.notes()) {
            int bar = note.indexOf('|');
            String severity = bar > 0 ? note.substring(0, bar) : "warning";
            String message = bar > 0 ? note.substring(bar + 1) : note;
            String tag = severity.equals("error") ? "error" : "note";
            CliOutput.err(Theme.colorize("  " + tag + ":", Theme.active().darkGray()) + " " + message);
            n++;
        }
        return n;
    }

    static void wrote(Path path) {
        CliOutput.out(Theme.colorize(Glyphs.CHECK, Theme.active().success()) + " Wrote " + PathDisplay.styled(path));
    }

    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "cc.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }
}
