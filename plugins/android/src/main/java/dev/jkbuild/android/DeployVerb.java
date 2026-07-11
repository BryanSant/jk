// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.VerbExec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The {@code deploy} verb — {@code jk run}'s device story (android-plan §3.4): {@code adb install
 * -r} the built APK onto the selected device, then {@code am start} its launcher activity. adb
 * arrives as a provisioned SDK component ({@code platform-tools}) exactly like the build steps'
 * tools; {@code --adb <path>} overrides it (also the test seam — a scripted fake).
 *
 * <p>Device selection is adb's own ({@code ANDROID_SERIAL} / the single connected device);
 * {@code -s <serial>} pass-through is Phase 2 polish.
 */
final class DeployVerb {

    private DeployVerb() {}

    static int run(VerbExec exec) throws Exception {
        Path apk = exec.mainArtifact().orElse(null);
        if (apk == null || !apk.toString().endsWith(".apk")) {
            exec.out("jk run: no APK built yet — run `jk build` first");
            return 1;
        }
        Path adb = adbPath(exec);
        String namespace = exec.config().string("namespace");
        String activity = launcherActivity(exec.moduleDir().resolve("AndroidManifest.xml"), namespace);

        exec.label("adb install");
        exec.out("Installing " + apk.getFileName() + " …");
        int install = adb(exec, adb, "install", "-r", apk.toAbsolutePath().toString());
        if (install != 0) return install;

        exec.label("am start");
        exec.out("Launching " + namespace + "/" + activity + " …");
        return adb(exec, adb, "shell", "am", "start", "-n", namespace + "/" + activity);
    }

    /** {@code --adb <path>} override, else the provisioned platform-tools binary. */
    private static Path adbPath(VerbExec exec) {
        List<String> args = exec.args();
        for (int i = 0; i < args.size() - 1; i++) {
            if ("--adb".equals(args.get(i))) return Path.of(args.get(i + 1));
        }
        return exec.requireExtra("adb");
    }

    /** Fork adb, streaming its output through the verb channel; returns its exit code. */
    private static int adb(VerbExec exec, Path adb, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(adb.toAbsolutePath().toString());
        command.addAll(List.of(args));
        Process process =
                new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) exec.out("  " + line);
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            exec.out("adb " + args[0] + " failed (exit " + exit + ") — is a device connected? (`adb devices`)");
        }
        return exit;
    }

    /**
     * The MAIN/LAUNCHER activity from the module's manifest, as {@code am start} wants it —
     * relative names ({@code .MainActivity}) resolve against the app id.
     */
    static String launcherActivity(Path manifest, String namespace) throws Exception {
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("no AndroidManifest.xml at " + manifest);
        }
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var doc = dbf.newDocumentBuilder().parse(manifest.toFile());
        NodeList activities = doc.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            NodeList actions = activity.getElementsByTagName("action");
            boolean main = false;
            for (int j = 0; j < actions.getLength(); j++) {
                if ("android.intent.action.MAIN"
                        .equals(((Element) actions.item(j)).getAttribute("android:name"))) {
                    main = true;
                }
            }
            if (!main) continue;
            String name = activity.getAttribute("android:name");
            if (name.startsWith(".")) return namespace + name;
            return name;
        }
        throw new IllegalStateException("no MAIN/LAUNCHER activity in " + manifest
                + " — declare one to make the app launchable");
    }
}
