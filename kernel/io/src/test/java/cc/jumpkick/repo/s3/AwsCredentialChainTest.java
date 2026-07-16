// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AwsCredentialChainTest {

    private static Function<String, String> env(Map<String, String> m) {
        return m::get;
    }

    @Test
    void env_credentials_win(@TempDir Path awsDir) {
        var chain = new AwsCredentialChain(
                env(Map.of(
                        "AWS_ACCESS_KEY_ID", "AKID",
                        "AWS_SECRET_ACCESS_KEY", "SECRET",
                        "AWS_SESSION_TOKEN", "TOK",
                        "AWS_REGION", "eu-west-1")),
                awsDir);

        AwsCredentials c = chain.resolve(null).orElseThrow();
        assertThat(c.accessKeyId()).isEqualTo("AKID");
        assertThat(c.secretAccessKey()).isEqualTo("SECRET");
        assertThat(c.sessionToken()).isEqualTo("TOK");
        assertThat(c.region()).isEqualTo("eu-west-1");
    }

    @Test
    void region_override_beats_env() {
        var chain = new AwsCredentialChain(
                env(Map.of("AWS_ACCESS_KEY_ID", "AKID", "AWS_SECRET_ACCESS_KEY", "SECRET", "AWS_REGION", "eu-west-1")),
                Path.of("/nonexistent"));
        assertThat(chain.resolve("us-east-2").orElseThrow().region()).isEqualTo("us-east-2");
    }

    @Test
    void reads_shared_files_with_profile(@TempDir Path awsDir) throws Exception {
        Files.writeString(awsDir.resolve("credentials"), """
                [default]
                aws_access_key_id = DEFAULTAK
                aws_secret_access_key = DEFAULTSK

                [work]
                aws_access_key_id = WORKAK
                aws_secret_access_key = WORKSK
                aws_session_token = WORKTOK
                """);
        Files.writeString(awsDir.resolve("config"), """
                [default]
                region = us-east-1

                [profile work]
                region = ap-south-1
                """);

        var work = new AwsCredentialChain(env(Map.of("AWS_PROFILE", "work")), awsDir)
                .resolve(null)
                .orElseThrow();
        assertThat(work.accessKeyId()).isEqualTo("WORKAK");
        assertThat(work.sessionToken()).isEqualTo("WORKTOK");
        assertThat(work.region()).isEqualTo("ap-south-1");

        var def = new AwsCredentialChain(env(Map.of()), awsDir).resolve(null).orElseThrow();
        assertThat(def.accessKeyId()).isEqualTo("DEFAULTAK");
        assertThat(def.region()).isEqualTo("us-east-1");
    }

    @Test
    void empty_when_nothing_configured(@TempDir Path awsDir) {
        assertThat(new AwsCredentialChain(env(Map.of()), awsDir).resolve(null)).isEmpty();
    }
}
