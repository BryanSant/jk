// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.repo.MavenLayout;
import dev.jkbuild.repo.RepoArtifactStore;
import dev.jkbuild.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibrarySearchCommandTest {

    private ByteArrayOutputStream out;
    private PrintStream originalOut;

    @BeforeEach
    void capture() {
        out = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
    }

    @Test
    void search_matches_substring_of_name_in_bundled_catalog(@TempDir Path tempHome) {
        // Isolate JkDirs.home() to an empty dir so only the bundled layer loads —
        // otherwise a developer's downloaded ~/.jk/cache/libs.global.toml shadows the
        // curated rows as [global] and the [bundled] assertion fails. (Under Gradle
        // the java-conventions plugin already points JK_HOME at a throwaway dir; this
        // makes the test self-contained under any runner, including jk build itself.)
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            int exit = Jk.execute("library", "search", "junit", "--show-layer");
            assertThat(exit).isZero();
            String stdout = out.toString(StandardCharsets.UTF_8);
            assertThat(stdout).contains("junit-jupiter");
            assertThat(stdout).contains("junit-platform-launcher");
            // The layer tag is opt-in via --show-layer; with it, bundled rows are tagged.
            assertThat(stdout).contains("[bundled]");
        } finally {
            System.setProperty("user.home", prevHome);
        }
    }

    @Test
    void search_matches_substring_of_group() {
        // "springframework" appears in groups (org.springframework.boot) but
        // not in names — exercises the group-field branch of the matcher.
        int exit = Jk.execute("library", "search", "springframework");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("spring-boot-starter");
        assertThat(stdout).contains("org.springframework.boot");
    }

    @Test
    void search_AND_semantics_with_multiple_terms() {
        // Both "spring" and "starter" must appear somewhere.
        int exit = Jk.execute("library", "search", "spring", "starter");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("spring-boot-starter");
        // But NOT something that has spring but no starter — there is no
        // such bundled entry, so this is implicit.
    }

    @Test
    void search_is_case_insensitive() {
        int exit = Jk.execute("library", "search", "PICOCLI");
        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("picocli");
    }

    @Test
    void search_with_no_matches_returns_nonzero_and_clear_message() {
        int exit = Jk.execute("library", "search", "definitely-not-in-registry-xyz");
        assertThat(exit).isOne();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("No matches");
    }

    @Test
    void offline_restricts_to_cached_coords_and_annotates_versions(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        // Cache one of the two "junit" curated coords.
        Coordinate coord = Coordinate.of("org.junit.jupiter", "junit-jupiter", "6.1.0");
        byte[] bytes = "junit-jar".getBytes(StandardCharsets.UTF_8);
        Path blob = new Cas(cache).put(bytes);
        RepoArtifactStore.forRepoName(cache, "central")
                .materialize(MavenLayout.artifactPath(coord), blob, Hashing.sha256Hex(bytes));

        int exit = Jk.execute("library", "search", "junit", "--offline", "--cache-dir", cache.toString());
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        // The cached coord is shown, annotated with its local version...
        assertThat(stdout).contains("junit-jupiter").contains("(cached: 6.1.0)");
        // ...the uncached sibling is filtered out under --offline.
        assertThat(stdout).doesNotContain("junit-platform-launcher");
    }

    @Test
    void offline_with_nothing_cached_reports_no_local_matches(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        int exit = Jk.execute("library", "search", "junit", "--offline", "--cache-dir", cache.toString());
        assertThat(exit).isOne();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("No matches (cached locally)");
    }

    @Test
    void search_limit_truncates_with_explanatory_footer() {
        int exit = Jk.execute("library", "search", "kotlin", "--limit", "1");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("more");
    }
}
