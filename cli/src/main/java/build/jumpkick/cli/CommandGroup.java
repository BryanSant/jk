// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli;

import java.util.List;

/**
 * A named grouping of subcommand names for the top-level / short help screens. Moved out of {@code
 * Jk} so {@link UsageGroups} and {@link DescribesUsage} can share the type.
 *
 * @param heading the group heading (e.g. {@code "Build commands:"})
 * @param names the canonical subcommand names in this group, in display order
 */
public record CommandGroup(String heading, List<String> names) {}
