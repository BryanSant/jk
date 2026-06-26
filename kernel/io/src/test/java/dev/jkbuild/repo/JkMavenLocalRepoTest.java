// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkMavenLocalRepoTest {

    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");

    private static String rel(Coordinate c, String ext) {
        return ext.equals("pom") ? MavenLayout.pomPath(c) : MavenLayout.artifactPath(c);
    }

    @Test
    void materialize_hard_links_the_cas_blob_under_m2_layout(@TempDir Path cache) throws IOException {
        Cas cas = new Cas(cache);
        Path blob = cas.put("jar-bytes".getBytes(StandardCharsets.UTF_8));
        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);

        repo.materialize(rel(WIDGET, "jar"), blob);

        Path mirrored = cache.resolve("repo/com/example/widget/1.0/widget-1.0.jar");
        assertThat(mirrored).exists();
        assertThat(Files.readAllBytes(mirrored)).isEqualTo("jar-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(repo.locate(rel(WIDGET, "jar"))).isPresent();
    }

    @Test
    void materialize_is_idempotent(@TempDir Path cache) throws IOException {
        Cas cas = new Cas(cache);
        Path blob = cas.put("bytes".getBytes(StandardCharsets.UTF_8));
        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);

        repo.materialize(rel(WIDGET, "jar"), blob);
        repo.materialize(rel(WIDGET, "jar"), blob); // no throw, no change

        assertThat(repo.versions("com.example", "widget")).containsExactly("1.0");
    }

    @Test
    void locate_of_unknown_path_is_empty(@TempDir Path cache) {
        assertThat(new JkMavenLocalRepo(cache).locate(rel(WIDGET, "jar"))).isEmpty();
    }

    @Test
    void versions_lists_directories_holding_artifacts(@TempDir Path cache) throws IOException {
        Cas cas = new Cas(cache);
        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);
        Path blob = cas.put("x".getBytes(StandardCharsets.UTF_8));
        repo.materialize(rel(Coordinate.of("com.example", "widget", "1.0"), "pom"), blob);
        repo.materialize(rel(Coordinate.of("com.example", "widget", "2.0"), "jar"), blob);
        repo.materialize(rel(Coordinate.of("com.example", "widget", "1.5"), "jar"), blob);

        assertThat(repo.versions("com.example", "widget")).containsExactlyInAnyOrder("1.0", "1.5", "2.0");
    }

    @Test
    void versions_of_unknown_artifact_is_empty(@TempDir Path cache) {
        assertThat(new JkMavenLocalRepo(cache).versions("no.such", "thing")).isEmpty();
    }

    @Test
    void modules_lists_every_cached_coordinate_with_versions(@TempDir Path cache) throws IOException {
        Cas cas = new Cas(cache);
        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);
        Path blob = cas.put("x".getBytes(StandardCharsets.UTF_8));
        repo.materialize(rel(Coordinate.of("com.example", "widget", "1.0"), "pom"), blob);
        repo.materialize(rel(Coordinate.of("com.example", "widget", "2.0"), "jar"), blob);
        repo.materialize(rel(Coordinate.of("org.acme.tools", "gadget", "3.1"), "pom"), blob);

        List<JkMavenLocalRepo.Module> modules = repo.modules();
        assertThat(modules)
                .extracting(JkMavenLocalRepo.Module::moduleKey)
                .containsExactlyInAnyOrder("com.example:widget", "org.acme.tools:gadget");
        JkMavenLocalRepo.Module widget = modules.stream()
                .filter(m -> m.moduleKey().equals("com.example:widget"))
                .findFirst()
                .orElseThrow();
        assertThat(widget.group()).isEqualTo("com.example");
        assertThat(widget.artifact()).isEqualTo("widget");
        assertThat(widget.versions()).containsExactlyInAnyOrder("1.0", "2.0");
    }

    @Test
    void modules_of_empty_repo_is_empty(@TempDir Path cache) {
        assertThat(new JkMavenLocalRepo(cache).modules()).isEmpty();
    }

    @Test
    void remove_shas_unlinks_matching_files_and_prunes_dirs(@TempDir Path cache) throws IOException {
        Cas cas = new Cas(cache);
        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        Path blob = cas.put(bytes);
        String hex = Hashing.sha256Hex(bytes);
        repo.materialize(rel(WIDGET, "jar"), blob);
        assertThat(repo.locate(rel(WIDGET, "jar"))).isPresent();

        int removed = repo.removeShas(Set.of(hex), false);

        assertThat(removed).isEqualTo(1);
        assertThat(repo.locate(rel(WIDGET, "jar"))).isEmpty();
        // Empty version/artifact/group dirs are pruned away.
        assertThat(cache.resolve("repo/com")).doesNotExist();
    }

    @Test
    void remove_shas_dry_run_keeps_files(@TempDir Path cache) throws IOException {
        Cas cas = new Cas(cache);
        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        Path blob = cas.put(bytes);
        repo.materialize(rel(WIDGET, "jar"), blob);

        int removed = repo.removeShas(Set.of(Hashing.sha256Hex(bytes)), true);

        assertThat(removed).isEqualTo(1);
        assertThat(repo.locate(rel(WIDGET, "jar"))).isPresent();
    }

    @Test
    void none_is_a_no_op(@TempDir Path cache) {
        JkMavenLocalRepo.NONE.materialize(rel(WIDGET, "jar"), cache.resolve("nope"));
        assertThat(JkMavenLocalRepo.NONE.locate(rel(WIDGET, "jar"))).isEmpty();
        assertThat(JkMavenLocalRepo.NONE.versions("com.example", "widget")).isEmpty();
        assertThat(JkMavenLocalRepo.NONE.modules()).isEmpty();
        assertThat(JkMavenLocalRepo.NONE.removeShas(Set.of("abc"), false)).isZero();
    }
}
