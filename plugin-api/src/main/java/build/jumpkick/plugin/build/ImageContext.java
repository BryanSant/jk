// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

/**
 * The {@link ImageExtension} surface: assembles an image from the finished module (an OCI image via
 * Jib, a native image). Consumes the main artifact + runtime closure. Execution wired in Stream 6.
 */
public interface ImageContext extends TerminalContext {}
