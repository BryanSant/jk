// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads shell hook templates from {@code cli/.../resources/activate/}.
 * Resources are bundled in the jar; native-image builds need them declared in
 * {@code resource-config.json} (see {@code META-INF/native-image} under cli).
 */
final class ShellResources {

    private static final String BASE = "/activate/";

    private ShellResources() {}

    /** Read a template file as UTF-8. Throws if the resource is missing. */
    static String load(String fileName) {
        var path = BASE + fileName;
        try (var in = ShellResources.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing shell resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
