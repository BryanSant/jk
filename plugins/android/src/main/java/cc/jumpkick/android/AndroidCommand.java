// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.PluginCommandExec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code android} command — the provisioning surface (android-plan §3.4):
 *
 * <ul>
 *   <li>{@code jk android licenses [--yes]} — show the SDK licenses (from Google's repository2
 *       feed) and, with {@code --yes}, record acceptance under the SDK root's {@code licenses/}
 *       in the sdkmanager on-disk format (SHA-1 text hashes) — the gate the engine's installer
 *       enforces before any component downloads.
 *   <li>{@code jk android sdk} — the components this project needs and whether each is installed.
 * </ul>
 *
 * <p>The SDK root arrives as the {@code sdk-root} tool artifact (the {@code root}
 * pseudo-component) — the command never learns where jk keeps it. The feed is fetched directly (it
 * is a public ~400KB XML; {@code JK_ANDROID_FEED_URL} overrides for tests/mirrors).
 */
final class AndroidCommand {

    private static final String FEED_URL = "https://dl.google.com/android/repository/repository2-3.xml";
    private static final Pattern LICENSE =
            Pattern.compile("<license id=\"([^\"]+)\"[^>]*>(.*?)</license>", Pattern.DOTALL);

    private AndroidCommand() {}

    static int run(PluginCommandExec exec) throws Exception {
        String sub = exec.args().isEmpty() ? "" : exec.args().get(0);
        return switch (sub) {
            case "licenses" -> licenses(exec, exec.args().contains("--yes"));
            case "sdk" -> sdkStatus(exec);
            default -> {
                exec.out("usage: jk android <licenses [--yes] | sdk>");
                yield sub.isEmpty() ? 0 : 64;
            }
        };
    }

    private static int licenses(PluginCommandExec exec, boolean yes) throws Exception {
        Path root = exec.requireExtra("sdk-root");
        Map<String, String> licenses = fetchLicenses();
        if (licenses.isEmpty()) {
            exec.out("no licenses found in the SDK repository feed");
            return 1;
        }
        Path dir = root.resolve("licenses");
        if (!yes) {
            exec.out("The Android SDK components are distributed under these licenses:");
            for (var e : licenses.entrySet()) {
                boolean accepted = accepted(dir.resolve(e.getKey()), hash(e.getValue()));
                exec.out("  " + e.getKey() + (accepted ? "  (accepted)" : ""));
            }
            exec.out("");
            exec.out("Review them at https://developer.android.com/studio/terms — then run");
            exec.out("`jk android licenses --yes` to accept (recorded in " + dir + ",");
            exec.out("the same format sdkmanager and Android Studio use).");
            return 0;
        }
        Files.createDirectories(dir);
        for (var e : licenses.entrySet()) {
            record(dir.resolve(e.getKey()), hash(e.getValue()));
            exec.out("accepted " + e.getKey());
        }
        return 0;
    }

    private static int sdkStatus(PluginCommandExec exec) {
        Path root = exec.requireExtra("sdk-root");
        long compileSdk = exec.config().intValue("compile-sdk", 0);
        exec.out("SDK root: " + root + (Files.isSymbolicLink(root) ? " (reusing an existing SDK)" : ""));
        Map<String, Path> components = new LinkedHashMap<>();
        components.put("platforms;android-" + compileSdk, root.resolve("platforms/android-" + compileSdk));
        components.put("platform-tools", root.resolve("platform-tools"));
        for (var e : components.entrySet()) {
            exec.out("  " + e.getKey() + ": " + (Files.isDirectory(e.getValue()) ? "installed" : "missing"));
        }
        return 0;
    }

    /** License id → text from the feed — a tolerant regex read of the two license elements. */
    private static Map<String, String> fetchLicenses() throws IOException, InterruptedException {
        String url = System.getenv().getOrDefault("JK_ANDROID_FEED_URL", FEED_URL);
        String xml;
        if (url.startsWith("file:")) {
            xml = Files.readString(Path.of(URI.create(url)), StandardCharsets.UTF_8);
        } else {
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("SDK feed " + url + " returned " + response.statusCode());
            }
            xml = response.body();
        }
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = LICENSE.matcher(xml);
        while (m.find()) {
            out.put(m.group(1), m.group(2).strip());
        }
        return out;
    }

    private static boolean accepted(Path file, String hash) {
        try {
            return Files.isRegularFile(file)
                    && Files.readAllLines(file).stream()
                            .anyMatch(line -> line.strip().equalsIgnoreCase(hash));
        } catch (IOException e) {
            return false;
        }
    }

    private static void record(Path file, String hash) throws IOException {
        if (accepted(file, hash)) return;
        String existing = Files.isRegularFile(file) ? Files.readString(file).stripTrailing() + "\n" : "";
        Files.writeString(file, existing + hash + "\n");
    }

    private static String hash(String text) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(text.strip().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
