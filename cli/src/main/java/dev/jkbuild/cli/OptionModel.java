// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

/**
 * A named option as shown in a help screen.
 *
 * @param namePart    the joined option names (e.g. {@code "-q, --quiet"}), with
 *                    hidden aliases already filtered out
 * @param labelPart   the value label (e.g. {@code "<WHEN>"}) or {@code ""} for a
 *                    boolean flag
 * @param description the option's description lines (may be empty)
 */
public record OptionModel(String namePart, String labelPart, String[] description) {}
