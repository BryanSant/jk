// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

/**
 * A plugin capability: own the terminal {@link Phase#IMAGE} goal — assemble an image (an OCI image
 * via Jib, a native image) from the finished module. Terminal-goal execution is wired in the
 * extension-remodel plan's Stream 6. Implemented alongside {@link build.jumpkick.plugin.Plugin}.
 */
@FunctionalInterface
public interface ImageExtension {

    /** Assemble the image against {@code ctx}. */
    void image(ImageContext ctx) throws Exception;
}
