// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.credential.RepoCredential;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTransportTest {

    private final FileTransport transport = new FileTransport();

    @Test
    void fetch_reads_an_existing_file(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("repo/g/a/1/a-1.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "bytes");

        Optional<byte[]> body = transport.fetch(jar.toUri(), RepoCredential.ANONYMOUS);
        assertThat(body).isPresent();
        assertThat(new String(body.get(), StandardCharsets.UTF_8)).isEqualTo("bytes");
    }

    @Test
    void fetch_missing_file_is_empty(@TempDir Path dir) throws Exception {
        assertThat(transport.fetch(dir.resolve("nope.jar").toUri(), RepoCredential.ANONYMOUS))
                .isEmpty();
    }

    @Test
    void put_writes_creating_parent_dirs(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("repo/g/a/1/a-1.jar");
        int status = transport.put(
                target.toUri(), new byte[] {1, 2, 3}, "application/java-archive", RepoCredential.ANONYMOUS);
        assertThat(status).isEqualTo(201);
        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3);
    }

    @Test
    void dispatches_for_file_scheme(@TempDir Path dir) {
        assertThat(RepoTransports.forUrl(dir.toUri(), new build.jumpkick.http.Http()))
                .isInstanceOf(FileTransport.class);
    }
}
