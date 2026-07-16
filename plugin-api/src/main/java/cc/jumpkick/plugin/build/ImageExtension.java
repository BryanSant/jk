// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * A plugin capability: own the terminal {@link Phase#IMAGE} goal — assemble an image (an OCI image
 * via Jib, a native image) from the finished module. The plugin's worker entry builds an {@link
 * ImageContext} from the finished module (main artifact + runtime closure + {@code [image]} config)
 * and calls {@link #image}, then reports the returned {@link ImageResult}. Implemented alongside
 * {@link cc.jumpkick.plugin.Plugin}.
 */
@FunctionalInterface
public interface ImageExtension {

    /** Assemble the image described by {@code ctx}; return where it landed (reference or tarball). */
    ImageResult image(ImageContext ctx) throws Exception;
}
