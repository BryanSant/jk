// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

/**
 * The {@link BuildExtension} contribution surface: steps in the COMPILE window — before-compile
 * source generation (protobuf's {@code protoc}) or after-compile class production (Spring's AOT).
 * Set the window with {@link #after}/{@link #before}; the default is {@link Phase#COMPILE} →
 * {@link Phase#PACKAGE}.
 */
public interface BuildContext extends StepContribution {}
