// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

/**
 * A positional parameter as shown in a help screen.
 *
 * @param label the display label, already formatted per CLI convention ({@code <name>} when
 *     required, {@code [name]} when optional)
 * @param description the parameter's description lines (may be empty)
 */
public record ParameterModel(String label, String[] description) {}
