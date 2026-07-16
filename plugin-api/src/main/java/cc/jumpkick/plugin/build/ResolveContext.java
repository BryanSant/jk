// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * The {@link ResolveExtension} contribution surface: steps in the RESOLVE window, before any
 * compilation — dependency/metadata preparation a later phase reads.
 */
public interface ResolveContext extends StepContribution {}
