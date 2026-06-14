// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test-skip freshness key: it must stay stable when nothing changed, and
 * bust when the module's own main code, a test source, a dependency's content,
 * the lock, or the toolchain identity changed.
 */
class TestStampTest {

    private static Path write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void stable_when_nothing_changes(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        write(mainClasses.resolve("Foo.class"), "AAAA");
        Path lock = write(dir.resolve("jk.lock"), "version = 1");
        Path sibJar = write(dir.resolve("dep/target/dep.jar"), "DEPBYTES");

        String k1 = TestStamp.computeKey(List.of(testSrc), mainClasses, lock,
                List.of(sibJar), List.of("jk:1.0", "runner:abc"));
        String k2 = TestStamp.computeKey(List.of(testSrc), mainClasses, lock,
                List.of(sibJar), List.of("jk:1.0", "runner:abc"));
        assertThat(k1).isNotNull().isEqualTo(k2);
    }

    @Test
    void own_main_change_busts_the_key(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path mainClass = write(mainClasses.resolve("Foo.class"), "AAAA");
        Path lock = write(dir.resolve("jk.lock"), "v=1");

        String before = TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(), List.of());
        Files.writeString(mainClass, "BBBB");   // main code changed; test source untouched
        String after = TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(), List.of());

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void dependency_content_change_busts_but_identical_rebuild_does_not(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path lock = write(dir.resolve("jk.lock"), "v=1");
        Path sibJar = write(dir.resolve("dep/target/dep.jar"), "DEP-V1");

        String base = TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(sibJar), List.of());

        // jk re-jars every build: identical bytes, new file mtime → must NOT bust.
        Files.writeString(sibJar, "DEP-V1");
        Files.setLastModifiedTime(sibJar, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(sibJar), List.of()))
                .as("byte-identical sibling rebuild keeps the key")
                .isEqualTo(base);

        // A real content change in the dependency → must bust (retest the dependent).
        Files.writeString(sibJar, "DEP-V2");
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(sibJar), List.of()))
                .as("dependency content change busts the dependent's key")
                .isNotEqualTo(base);
    }

    @Test
    void test_source_lock_and_extras_changes_bust_the_key(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path lock = write(dir.resolve("jk.lock"), "v=1");

        String base = TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(), List.of("jk:1.0"));

        Files.writeString(testSrc, "class FooTest { void t() {} }");
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(), List.of("jk:1.0")))
                .isNotEqualTo(base);

        Files.writeString(testSrc, "class FooTest {}");           // restore
        Files.writeString(lock, "v=2");                            // dep set changed
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(), List.of("jk:1.0")))
                .isNotEqualTo(base);

        Files.writeString(lock, "v=1");                            // restore
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(), List.of("jk:2.0")))
                .as("a toolchain/runner identity change retests")
                .isNotEqualTo(base);
    }

    @Test
    void isFresh_round_trips_and_detects_change(@TempDir Path dir) throws IOException {
        Path testClasses = Files.createDirectories(dir.resolve("classes/test"));
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        write(mainClasses.resolve("Foo.class"), "AAAA");
        Path lock = write(dir.resolve("jk.lock"), "v=1");

        String key = TestStamp.computeKey(List.of(testSrc), mainClasses, lock, List.of(), List.of());
        assertThat(TestStamp.isFresh(testClasses, key)).isFalse();   // no stamp yet → retest
        TestStamp.write(testClasses, key);
        assertThat(TestStamp.isFresh(testClasses, key)).isTrue();    // unchanged → skip
        assertThat(TestStamp.isFresh(testClasses, key + "x")).isFalse(); // changed → retest
        assertThat(TestStamp.isFresh(testClasses, null)).isFalse();  // null key → retest
    }
}
