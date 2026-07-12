// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.VerbExec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code avd} verb — managed devices (android-plan §3.6): create/list/boot Android Virtual
 * Devices under the jk-managed SDK root ({@code <sdk>/avd}, {@code ANDROID_AVD_HOME}), the same
 * provisioning DNA as JDKs. The AVD definition is avdmanager's on-disk format written directly
 * ({@code <name>.ini} + {@code <name>/config.ini}) — no avdmanager binary needed.
 *
 * <ul>
 *   <li>{@code jk avd create <name> --system-image <package>} — writes the AVD definition against
 *       an installed system image ({@code jk android sdk} + licenses provision it; the image
 *       package is e.g. {@code system-images;android-34;aosp_atd;x86_64}).
 *   <li>{@code jk avd list} — the managed definitions.
 *   <li>{@code jk avd boot <name> [--emulator <path>]} — fork a headless emulator
 *       ({@code -no-window -no-audio -no-boot-anim}); requires KVM and the {@code emulator}
 *       SDK component.
 * </ul>
 */
final class AvdVerb {

    private AvdVerb() {}

    static int run(VerbExec exec) throws Exception {
        List<String> args = exec.args();
        Path root = exec.requireExtra("sdk-root");
        Path avdHome = root.resolve("avd");
        String sub = args.isEmpty() ? "list" : args.get(0);
        return switch (sub) {
            case "list" -> list(exec, avdHome);
            case "create" -> create(exec, root, avdHome, args);
            case "boot" -> boot(exec, root, avdHome, args);
            default -> {
                exec.out("usage: jk avd [list | create <name> --system-image <pkg> | boot <name>]");
                yield 64;
            }
        };
    }

    private static int list(VerbExec exec, Path avdHome) throws Exception {
        if (!Files.isDirectory(avdHome)) {
            exec.out("no managed AVDs — create one with `jk avd create <name> --system-image <pkg>`");
            return 0;
        }
        try (var entries = Files.list(avdHome)) {
            var inis = entries.filter(p -> p.getFileName().toString().endsWith(".ini"))
                    .sorted()
                    .toList();
            if (inis.isEmpty()) {
                exec.out("no managed AVDs — create one with `jk avd create <name> --system-image <pkg>`");
                return 0;
            }
            for (Path ini : inis) {
                String name = ini.getFileName().toString();
                exec.out("  " + name.substring(0, name.length() - ".ini".length()));
            }
        }
        return 0;
    }

    private static int create(VerbExec exec, Path root, Path avdHome, List<String> args) throws Exception {
        if (args.size() < 2) {
            exec.out("usage: jk avd create <name> --system-image <pkg>");
            return 64;
        }
        String name = args.get(1);
        String image = flag(args, "--system-image");
        if (image == null) {
            exec.out("jk avd create: --system-image <pkg> is required "
                    + "(e.g. system-images;android-34;aosp_atd;x86_64)");
            return 64;
        }
        // The image must be installed (jk android sdk provisions; licenses gate as always).
        Path imageDir = root.resolve(image.replace(';', '/'));
        if (!Files.isDirectory(imageDir)) {
            exec.out("jk avd create: system image " + image + " is not installed — "
                    + "run `jk android sdk " + image + "` first (licenses apply)");
            return 1;
        }
        String[] parts = image.split(";");
        if (parts.length < 4) {
            exec.out("jk avd create: malformed system-image package: " + image);
            return 64;
        }
        String target = parts[1]; // android-34
        String tag = parts[2]; // aosp_atd / default / google_apis
        String abi = parts[3]; // x86_64

        Path avdDir = Files.createDirectories(avdHome.resolve(name + ".avd"));
        StringBuilder config = new StringBuilder();
        config.append("AvdId=").append(name).append('\n');
        config.append("avd.ini.displayname=").append(name).append('\n');
        config.append("abi.type=").append(abi).append('\n');
        config.append("hw.cpu.arch=").append("x86_64".equals(abi) ? "x86_64" : abi).append('\n');
        config.append("image.sysdir.1=").append(image.replace(';', '/')).append('/').append('\n');
        config.append("tag.id=").append(tag).append('\n');
        config.append("target=").append(target).append('\n');
        config.append("hw.ramSize=2048\n");
        config.append("hw.lcd.width=1080\n");
        config.append("hw.lcd.height=1920\n");
        config.append("hw.lcd.density=420\n");
        config.append("disk.dataPartition.size=2G\n");
        Files.writeString(avdDir.resolve("config.ini"), config.toString(), StandardCharsets.UTF_8);

        StringBuilder pointer = new StringBuilder();
        pointer.append("avd.ini.encoding=UTF-8\n");
        pointer.append("path=").append(avdDir.toAbsolutePath()).append('\n');
        pointer.append("target=").append(target).append('\n');
        Files.writeString(avdHome.resolve(name + ".ini"), pointer.toString(), StandardCharsets.UTF_8);
        exec.out("created AVD " + name + " (" + image + ") under " + avdHome);
        return 0;
    }

    private static int boot(VerbExec exec, Path root, Path avdHome, List<String> args) throws Exception {
        if (args.size() < 2) {
            exec.out("usage: jk avd boot <name> [--emulator <path>]");
            return 64;
        }
        String name = args.get(1);
        if (!Files.isRegularFile(avdHome.resolve(name + ".ini"))) {
            exec.out("jk avd boot: no AVD named " + name + " — `jk avd list`");
            return 1;
        }
        if (!Files.exists(Path.of("/dev/kvm"))) {
            exec.out("jk avd boot: /dev/kvm is unavailable — hardware acceleration is required "
                    + "for a usable emulator");
            return 1;
        }
        String override = flag(args, "--emulator");
        Path emulator = override != null ? Path.of(override) : root.resolve("emulator/emulator");
        if (!Files.isRegularFile(emulator)) {
            exec.out("jk avd boot: the emulator component is not installed — "
                    + "run `jk android sdk emulator` first");
            return 1;
        }
        exec.label("emulator " + name);
        List<String> command = new ArrayList<>(List.of(
                emulator.toAbsolutePath().toString(),
                "-avd",
                name,
                "-no-window",
                "-no-audio",
                "-no-boot-anim"));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("ANDROID_AVD_HOME", avdHome.toAbsolutePath().toString());
        pb.environment().put("ANDROID_SDK_ROOT", root.toAbsolutePath().toString());
        Process process = pb.start();
        // Stream until the boot line (or EOF) — the emulator keeps running detached after.
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) exec.out("  " + line);
                if (line.contains("boot completed") || line.contains("Successfully loaded snapshot")) {
                    exec.out("emulator is up (leave it running; `adb devices` sees it)");
                    return 0;
                }
            }
        }
        return process.isAlive() ? 0 : process.waitFor();
    }

    private static String flag(List<String> args, String name) {
        for (int i = 0; i < args.size() - 1; i++) {
            if (name.equals(args.get(i))) return args.get(i + 1);
        }
        return null;
    }
}
