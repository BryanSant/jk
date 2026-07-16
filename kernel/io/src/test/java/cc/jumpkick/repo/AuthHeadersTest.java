// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import org.junit.jupiter.api.Test;

class AuthHeadersTest {

    @Test
    void anonymous_has_no_headers() {
        assertThat(AuthHeaders.of(RepoCredential.ANONYMOUS)).isEmpty();
    }

    @Test
    void basic_is_base64_user_colon_pass() {
        // base64("u:p") == "dTpw"
        assertThat(AuthHeaders.of(new RepoCredential.Basic("u", "p"))).containsEntry("Authorization", "Basic dTpw");
    }

    @Test
    void bearer_is_verbatim_token() {
        assertThat(AuthHeaders.of(new RepoCredential.Bearer("tok-123")))
                .containsEntry("Authorization", "Bearer tok-123");
    }
}
