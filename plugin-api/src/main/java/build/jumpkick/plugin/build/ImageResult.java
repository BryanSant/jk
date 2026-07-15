// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import java.nio.file.Path;

/**
 * The outcome of an {@link ImageExtension#image} run — enough for the worker entry to report it: a
 * pushed/loaded image {@code reference}, or a written {@code tarball} path. Exactly one is set.
 */
public record ImageResult(String reference, Path tarball, boolean daemon) {

    /** An image pushed to a registry. */
    public static ImageResult pushed(String reference) {
        return new ImageResult(reference, null, false);
    }

    /** An image loaded into the local container daemon. */
    public static ImageResult loaded(String reference) {
        return new ImageResult(reference, null, true);
    }

    /** An image written to an OCI tarball. */
    public static ImageResult tarball(Path path) {
        return new ImageResult(null, path, false);
    }
}
