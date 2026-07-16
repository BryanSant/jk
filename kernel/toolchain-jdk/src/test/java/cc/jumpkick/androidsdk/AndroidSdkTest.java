// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.androidsdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AndroidSdkTest {

    private static byte[] fixture() throws IOException {
        try (var in = AndroidSdkTest.class.getResourceAsStream("repository2-fixture.xml")) {
            return in.readAllBytes();
        }
    }

    @Test
    void feed_parses_components_licenses_and_prefers_stable_channel() throws Exception {
        AndroidRepoFeed feed = AndroidRepoFeed.parse(fixture());

        AndroidRepoFeed.Component platform = feed.find("platforms;android-28");
        assertThat(platform.revision()).isEqualTo("6");
        assertThat(platform.licenseId()).isEqualTo("android-sdk-license");
        assertThat(platform.archiveFor("linux").url()).isEqualTo("platform-28_r06.zip");
        assertThat(platform.archiveFor("linux").sha1()).isEqualTo("9a4e52b1d55bd2e24216b150aafae2503d3efba6");

        // platform-tools appears on channel-2 (37.0.1) AND channel-0 (37.0.0) — stable wins.
        AndroidRepoFeed.Component tools = feed.find("platform-tools");
        assertThat(tools.channel()).isEqualTo("channel-0");
        assertThat(tools.revision()).isEqualTo("37.0.0");
        assertThat(tools.archiveFor("linux").hostOs()).isEqualTo("linux");

        assertThat(feed.licenseText("android-sdk-license")).contains("Android Software Development Kit");
        assertThat(feed.find("build-tools;0.0.0")).isNull();
    }

    @Test
    void root_reuses_discovered_sdk_via_symlink_and_falls_back_to_managed(@TempDir Path tmp) throws Exception {
        // Discovered via env: managed root becomes a symlink to it.
        Path studio = Files.createDirectories(tmp.resolve("studio-sdk"));
        Files.createDirectories(studio.resolve("platforms"));
        Path managed = tmp.resolve("jk").resolve("android-sdk");
        AndroidSdk linked = AndroidSdk.resolve(var -> "ANDROID_HOME".equals(var) ? studio.toString() : null, managed);
        assertThat(Files.isSymbolicLink(managed)).isTrue();
        assertThat(linked.root().toRealPath()).isEqualTo(studio.toRealPath());

        // Nothing discovered: the managed root is created.
        Path managed2 = tmp.resolve("jk2").resolve("android-sdk");
        AndroidSdk owned = AndroidSdk.resolve(var -> null, managed2);
        assertThat(owned.root()).isEqualTo(managed2);
        assertThat(Files.isDirectory(managed2)).isTrue();
    }

    @Test
    void component_layout_and_license_recording_follow_sdkmanager(@TempDir Path tmp) throws Exception {
        AndroidSdk sdk = AndroidSdk.resolve(var -> null, tmp.resolve("android-sdk"));
        assertThat(sdk.componentDir("platforms;android-28"))
                .isEqualTo(sdk.root().resolve("platforms").resolve("android-28"));
        assertThat(sdk.installed("platforms;android-28")).isFalse();

        String hash = AndroidRepoFeed.licenseHash("some license text");
        assertThat(sdk.licenseAccepted("android-sdk-license", hash)).isFalse();
        sdk.recordLicense("android-sdk-license", hash);
        assertThat(sdk.licenseAccepted("android-sdk-license", hash)).isTrue();
        // Idempotent + append-preserving.
        sdk.recordLicense("android-sdk-license", hash);
        String other = AndroidRepoFeed.licenseHash("other text");
        sdk.recordLicense("android-sdk-license", other);
        assertThat(sdk.licenseAccepted("android-sdk-license", hash)).isTrue();
        assertThat(sdk.licenseAccepted("android-sdk-license", other)).isTrue();
    }

    @Test
    void installer_gates_on_license_before_downloading(@TempDir Path tmp) throws Exception {
        Path fixtureFile = tmp.resolve("feed.xml");
        Files.write(fixtureFile, fixture());
        System.setProperty(AndroidSdkInstaller.FEED_URL_PROPERTY, fixtureFile.toUri().toString());
        try {
            AndroidSdk sdk = AndroidSdk.resolve(var -> null, tmp.resolve("android-sdk"));
            AndroidSdkInstaller installer = new AndroidSdkInstaller(sdk);
            assertThatThrownBy(() -> installer.ensure("platforms;android-28"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("jk android licenses --yes");
            assertThatThrownBy(() -> installer.ensure("platforms;android-999"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("not in Google's repository feed");
        } finally {
            System.clearProperty(AndroidSdkInstaller.FEED_URL_PROPERTY);
        }
    }

    @Test
    void installed_component_short_circuits_without_feed_or_network(@TempDir Path tmp) throws Exception {
        AndroidSdk sdk = AndroidSdk.resolve(var -> null, tmp.resolve("android-sdk"));
        Path platform = Files.createDirectories(sdk.componentDir("platforms;android-28"));
        Files.writeString(platform.resolve("android.jar"), "jar");
        // No feed property, no network use: ensure() must return the existing dir untouched.
        Path resolved = new AndroidSdkInstaller(sdk).ensure("platforms;android-28");
        assertThat(resolved).isEqualTo(platform);
    }
}
