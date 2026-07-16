// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

/**
 * Where an installed tool came from (docs/tool-targets-plan.md §3) — written into {@code env.json}
 * so {@code jk tool list} can be honest and a future {@code jk tool upgrade} has something to
 * re-resolve.
 *
 * @param kind {@code gav | catalog | file | url | git | jbang-alias}
 * @param spec the target exactly as the user typed it
 * @param resolved what the spec pinned to — a concrete {@code g:a:v}, a raw URL, or a path
 */
public record ToolProvenance(String kind, String spec, String resolved) {}
