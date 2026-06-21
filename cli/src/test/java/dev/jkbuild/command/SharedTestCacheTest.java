// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared integration-test cache directory is stable, created, and reused. */
class SharedTestCacheTest {

    @Test
    void dir_is_created_and_stable_within_the_jvm() {
        var first = SharedTestCache.dir();
        var second = SharedTestCache.dir();
        assertThat(first).isSameAs(second);               // one shared dir per run
        assertThat(Files.isDirectory(first)).isTrue();    // materialised on access
        assertThat(SharedTestCache.arg()).isEqualTo(first.toString());
    }

    @Test
    void honours_the_gradle_supplied_location() {
        // The Gradle test task points this at <module>/build/test-shared-cache.
        String configured = System.getProperty("jk.test.cache.dir");
        if (configured != null && !configured.isBlank()) {
            assertThat(SharedTestCache.dir().toString()).isEqualTo(configured);
        }
    }
}
