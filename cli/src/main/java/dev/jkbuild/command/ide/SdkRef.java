// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command.ide;

import java.nio.file.Path;

/**
 * A module's resolved JDK handle.
 *
 * @param stableName the {@code <vendor>-<level>} pointer name (e.g. {@code temurin-25})
 * @param sdkName the IntelliJ SDK name (e.g. {@code jk-temurin-25})
 * @param languageLevel the source language level for this module ({@code project.java}/{@code jdk})
 * @param javaHome the stable {@code JAVA_HOME} path (via {@code StableJdkPointer}) — survives point
 *     releases; used for VS Code's {@code java.configuration.runtimes} / {@code java.jdt.ls.java.home}
 * @param version the resolved JDK version string (e.g. {@code 25.0.3}), or the bare level when unknown
 */
public record SdkRef(String stableName, String sdkName, int languageLevel, Path javaHome, String version) {}
