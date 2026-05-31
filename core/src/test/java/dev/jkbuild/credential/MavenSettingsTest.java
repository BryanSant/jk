// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.credential;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenSettingsTest {

    private static Path write(Path dir, String xml) throws Exception {
        Path p = dir.resolve("settings.xml");
        Files.writeString(p, xml);
        return p;
    }

    @Test
    void reads_servers_by_id(@TempDir Path dir) throws Exception {
        Path xml = write(dir, """
                <settings>
                  <servers>
                    <server>
                      <id>corp-nexus</id>
                      <username>deployer</username>
                      <password>s3cr3t</password>
                    </server>
                    <server>
                      <id>ghp</id>
                      <username>octocat</username>
                      <password>ghp_token</password>
                    </server>
                  </servers>
                </settings>
                """);

        MavenSettings settings = MavenSettings.loadFrom(xml);
        assertThat(settings.server("corp-nexus")).hasValueSatisfying(s -> {
            assertThat(s.username()).isEqualTo("deployer");
            assertThat(s.password()).isEqualTo("s3cr3t");
        });
        assertThat(settings.server("ghp")).hasValueSatisfying(
                s -> assertThat(s.username()).isEqualTo("octocat"));
        assertThat(settings.server("unknown")).isEmpty();
    }

    @Test
    void missing_file_is_empty(@TempDir Path dir) {
        assertThat(MavenSettings.loadFrom(dir.resolve("nope.xml")).isEmpty()).isTrue();
    }

    @Test
    void malformed_xml_degrades_to_empty(@TempDir Path dir) throws Exception {
        Path xml = write(dir, "<settings><servers><server><id>x");
        assertThat(MavenSettings.loadFrom(xml).isEmpty()).isTrue();
    }

    @Test
    void server_without_credentials_is_skipped(@TempDir Path dir) throws Exception {
        Path xml = write(dir, """
                <settings>
                  <servers>
                    <server>
                      <id>ssh-only</id>
                      <privateKey>/home/me/.ssh/id_rsa</privateKey>
                    </server>
                  </servers>
                </settings>
                """);
        assertThat(MavenSettings.loadFrom(xml).server("ssh-only")).isEmpty();
    }

    @Test
    void does_not_resolve_external_entities(@TempDir Path dir) throws Exception {
        // XXE attempt: a DOCTYPE with an external entity. Hardened parser must
        // refuse the DOCTYPE outright (→ empty), never read /etc/hostname.
        Path xml = write(dir, """
                <?xml version="1.0"?>
                <!DOCTYPE settings [ <!ENTITY xxe SYSTEM "file:///etc/hostname"> ]>
                <settings><servers><server>
                  <id>evil</id><username>&xxe;</username><password>p</password>
                </server></servers></settings>
                """);
        assertThat(MavenSettings.loadFrom(xml).isEmpty()).isTrue();
    }
}
