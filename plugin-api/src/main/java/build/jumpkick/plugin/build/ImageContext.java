// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@link ImageExtension} surface: assembles an image from the finished module (an OCI image via
 * Jib, a native image). Consumes the main artifact + runtime closure ({@link TerminalContext}); the
 * image settings (base, registry, tag, platform, ports, env, labels, mode) ride the generic {@link
 * #config()} table.
 */
public interface ImageContext extends TerminalContext {

    /** The module's compiled classes dir, when the image layers classes directly (no jar), else empty. */
    Optional<Path> classesDir();
}
