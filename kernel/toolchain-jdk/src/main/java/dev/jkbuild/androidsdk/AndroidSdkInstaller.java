// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.androidsdk;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ensures Android SDK components exist under the {@link AndroidSdk} root (android-plan.md §3.2):
 * already-installed (or Studio-provided, via the symlinked root) components are returned as-is;
 * anything else downloads from Google's {@code repository2} feed — sha1-verified, license-gated.
 *
 * <p>The license gate is the sdkmanager contract: a component only downloads when its license's
 * text hash is recorded under {@code <root>/licenses/}. An unaccepted license fails with the
 * remediation naming {@code jk android licenses --yes} — acceptance is an explicit user action,
 * never implicit in a build.
 */
public final class AndroidSdkInstaller {

    /** Test seam: overrides the feed URL (a {@code file:} fixture in tests). */
    public static final String FEED_URL_PROPERTY = "jk.android.feedUrl";

    private final AndroidSdk sdk;
    private final HttpClient http;
    private AndroidRepoFeed feed; // fetched once per installer instance

    public AndroidSdkInstaller(AndroidSdk sdk) {
        this.sdk = sdk;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * The installed directory of {@code componentPath} ({@code platforms;android-28},
     * {@code platform-tools}, …), downloading it if absent. Never re-downloads an existing
     * component (Studio's copies count — the root may be a symlink into a Studio SDK).
     */
    public Path ensure(String componentPath) throws IOException, InterruptedException {
        Path dir = sdk.componentDir(componentPath);
        if (sdk.installed(componentPath)) return dir;

        AndroidRepoFeed.Component component = feed().find(componentPath);
        if (component == null) {
            throw new IOException("unknown Android SDK component: " + componentPath
                    + " — not in Google's repository feed");
        }
        AndroidRepoFeed.Archive archive = component.archiveFor(AndroidRepoFeed.hostOs());
        if (archive == null) {
            throw new IOException("Android SDK component " + componentPath + " has no archive for this OS");
        }

        String licenseId = component.licenseId();
        String licenseText = licenseId.isEmpty() ? null : feed().licenseText(licenseId);
        if (licenseText != null && !sdk.licenseAccepted(licenseId, AndroidRepoFeed.licenseHash(licenseText))) {
            throw new IOException("Android SDK component " + componentPath + " requires accepting the '"
                    + licenseId + "' license — run `jk android licenses --yes` first");
        }

        Path archiveFile = download(archive);
        try {
            extractZip(archiveFile, dir);
        } finally {
            Files.deleteIfExists(archiveFile);
        }
        return dir;
    }

    /** The parsed feed (fetched once; {@code file:} fixture override for tests). */
    public AndroidRepoFeed feed() throws IOException, InterruptedException {
        if (feed == null) {
            String url = System.getProperty(FEED_URL_PROPERTY, AndroidRepoFeed.FEED_URL);
            if (url.startsWith("file:")) {
                feed = AndroidRepoFeed.parse(Files.readAllBytes(Path.of(URI.create(url))));
            } else {
                HttpResponse<byte[]> response = http.send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("Android SDK feed " + url + " returned " + response.statusCode());
                }
                feed = AndroidRepoFeed.parse(response.body());
            }
        }
        return feed;
    }

    public AndroidSdk sdk() {
        return sdk;
    }

    /** Download the archive next to the SDK root, verifying its sha1 as it streams. */
    private Path download(AndroidRepoFeed.Archive archive) throws IOException, InterruptedException {
        Path downloads = Files.createDirectories(sdk.root().resolve(".downloads"));
        Path target = Files.createTempFile(downloads, "jk-sdk-", ".zip");
        URI uri = URI.create(AndroidRepoFeed.REPOSITORY_BASE + archive.url());
        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            Files.deleteIfExists(target);
            throw new IOException("Android SDK download " + uri + " returned " + response.statusCode());
        }
        MessageDigest sha1 = sha1();
        try (DigestInputStream in = new DigestInputStream(response.body(), sha1)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String actual = hex(sha1.digest());
        if (!actual.equalsIgnoreCase(archive.sha1())) {
            Files.deleteIfExists(target);
            throw new IOException("Android SDK download " + uri + " checksum mismatch: expected "
                    + archive.sha1() + ", got " + actual);
        }
        return target;
    }

    /**
     * Extract a component zip into {@code dir}. SDK zips carry one top-level directory
     * ({@code android-9/}, {@code platform-tools/}); its contents become the component dir —
     * matching sdkmanager's layout exactly.
     */
    private static void extractZip(Path zip, Path dir) throws IOException {
        Path staging = Files.createTempDirectory(dir.getParent() == null ? zip.getParent() : mkdirs(dir.getParent()),
                ".extract-");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                Path out = staging.resolve(entry.getName()).normalize();
                if (!out.startsWith(staging)) {
                    throw new IOException("zip entry escapes the extraction dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    // Unix mode rides the "extra" high bits in java.util.zip only via ZipFile;
                    // mark the obvious executables by location instead (bin-less SDK zips are
                    // data-only; platform-tools' adb/fastboot live at the top level).
                    String name = out.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.contains(".") || name.endsWith(".sh")) {
                        out.toFile().setExecutable(true);
                    }
                }
            }
        }
        Path root = singleTopLevel(staging);
        Files.createDirectories(dir.getParent());
        try {
            Files.move(root, dir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(root, dir);
        }
        deleteRecursively(staging);
    }

    private static Path mkdirs(Path dir) throws IOException {
        return Files.createDirectories(dir);
    }

    private static Path singleTopLevel(Path staging) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(staging)) {
            Path only = null;
            for (Path child : children) {
                if (only != null) return staging; // multiple roots — the staging dir IS the component
                only = child;
            }
            return only != null && Files.isDirectory(only) ? only : staging;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }
}
