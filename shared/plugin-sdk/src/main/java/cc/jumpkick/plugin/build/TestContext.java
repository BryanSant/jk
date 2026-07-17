// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * The {@link TestExtension} contribution surface: steps ordered before {@link Phase#TEST} that wire
 * test-time inputs a framework reads off the classpath (Android's Robolectric test config).
 */
public interface TestContext extends StepContribution {}
