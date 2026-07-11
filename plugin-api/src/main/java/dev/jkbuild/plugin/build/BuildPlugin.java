// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

/**
 * The build-plugin code layer (build-plugins plan §3.1): the hard 10% that declarative manifest
 * contributions can't express. Runs only in the plugin's forked worker JVM — the engine never
 * classloads plugin code; it learns the registered steps/packagers over the describe protocol and
 * calls back per execution with resolved inputs.
 */
public interface BuildPlugin {

    /** Register steps, packagers, and shapes against the typed hooks. */
    void register(BuildPluginContext ctx);
}
