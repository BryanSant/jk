// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.PluginCommandExec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code instrument} command — on-device instrumented tests (android-plan §3.6): install the app
 * APK (+ a test APK when supplied), run {@code am instrument -r -w} with the AndroidX runner, and
 * parse the raw instrumentation protocol into per-test results.
 *
 * <p>Protocol notes: {@code -r} streams {@code INSTRUMENTATION_STATUS} key/value blocks terminated
 * by {@code INSTRUMENTATION_STATUS_CODE} (1 = start, 0 = pass, -1 = error, -2 = failure, -3/-4 =
 * ignored/assumption-failed), then a final {@code INSTRUMENTATION_CODE}. The v1 runner is
 * AndroidJUnitRunner invoked directly; the Test Orchestrator (per-test instrumentation +
 * clearPackageData) needs its two service APKs installed and is recorded as follow-up in the plan.
 *
 * <p>Args: {@code --test-apk <path>} (the instrumentation APK; its package is {@code
 * <namespace>.test} by convention), {@code --adb <path>} override (also the fake-adb test seam).
 */
final class InstrumentCommand {

    private InstrumentCommand() {}

    static int run(PluginCommandExec exec) throws Exception {
        Path app = exec.mainArtifact().orElse(null);
        if (app == null || !app.toString().endsWith(".apk")) {
            exec.out("jk test --device: no APK built yet — run `jk build` first");
            return 1;
        }
        Path adb = pathArg(exec, "--adb").orElseGet(() -> exec.requireExtra("adb"));
        Path testApk = pathArg(exec, "--test-apk").orElse(null);
        String namespace = exec.config().string("namespace");
        String testPackage = testApk != null ? namespace + ".test" : namespace;

        exec.label("adb install");
        if (adb(exec, adb, false, "install", "-r", app.toAbsolutePath().toString()) != 0) return 1;
        if (testApk != null
                && adb(exec, adb, false, "install", "-r", testApk.toAbsolutePath().toString()) != 0) {
            return 1;
        }

        exec.label("am instrument");
        Results results = new Results(exec);
        int exit = adbLines(
                exec,
                adb,
                results::line,
                "shell",
                "am",
                "instrument",
                "-r",
                "-w",
                testPackage + "/androidx.test.runner.AndroidJUnitRunner");
        results.finish();
        if (exit != 0 || results.failures > 0 || results.errors > 0) {
            exec.out("Instrumented: " + results.summary() + " — FAILED");
            return 1;
        }
        exec.out("Instrumented: " + results.summary());
        return 0;
    }

    /** Streaming parser for the {@code -r} raw instrumentation protocol. */
    static final class Results {
        private final PluginCommandExec exec;
        private final StringBuilder stack = new StringBuilder();
        private String currentClass = "";
        private String currentTest = "";
        int passed;
        int failures;
        int errors;
        int ignored;

        Results(PluginCommandExec exec) {
            this.exec = exec;
        }

        void line(String line) {
            if (line.startsWith("INSTRUMENTATION_STATUS: class=")) {
                currentClass = line.substring("INSTRUMENTATION_STATUS: class=".length()).strip();
            } else if (line.startsWith("INSTRUMENTATION_STATUS: test=")) {
                currentTest = line.substring("INSTRUMENTATION_STATUS: test=".length()).strip();
            } else if (line.startsWith("INSTRUMENTATION_STATUS: stack=")) {
                stack.setLength(0);
                stack.append(line.substring("INSTRUMENTATION_STATUS: stack=".length()));
            } else if (line.startsWith("INSTRUMENTATION_STATUS_CODE: ")) {
                int code = Integer.parseInt(
                        line.substring("INSTRUMENTATION_STATUS_CODE: ".length()).strip());
                switch (code) {
                    case 1 -> { // test start
                    }
                    case 0 -> {
                        passed++;
                        exec.out("  ✓ " + currentClass + "." + currentTest);
                    }
                    case -2 -> {
                        failures++;
                        exec.out("  ✗ " + currentClass + "." + currentTest + " FAILED");
                        if (stack.length() > 0) exec.out("    " + stack);
                    }
                    case -1 -> {
                        errors++;
                        exec.out("  ✗ " + currentClass + "." + currentTest + " ERROR");
                        if (stack.length() > 0) exec.out("    " + stack);
                    }
                    case -3, -4 -> ignored++;
                    default -> {
                        // unknown status — surface nothing, the summary still counts
                    }
                }
            }
        }

        void finish() {
            // nothing buffered — the protocol is line-driven
        }

        String summary() {
            return passed + " passed, " + failures + " failed, " + errors + " errors"
                    + (ignored > 0 ? ", " + ignored + " ignored" : "");
        }
    }

    private static java.util.Optional<Path> pathArg(PluginCommandExec exec, String flag) {
        List<String> args = exec.args();
        for (int i = 0; i < args.size() - 1; i++) {
            if (flag.equals(args.get(i))) return java.util.Optional.of(Path.of(args.get(i + 1)));
        }
        return java.util.Optional.empty();
    }

    private static int adb(PluginCommandExec exec, Path adb, boolean quiet, String... args)
            throws IOException, InterruptedException {
        return adbLines(exec, adb, quiet ? l -> {} : l -> exec.out("  " + l), args);
    }

    private static int adbLines(
            PluginCommandExec exec, Path adb, java.util.function.Consumer<String> sink, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(adb.toAbsolutePath().toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) sink.accept(line);
            }
        }
        return process.waitFor();
    }
}
