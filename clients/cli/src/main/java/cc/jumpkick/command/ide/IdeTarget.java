// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

/** The IDEs {@code jk ide} can generate project configuration for. */
public enum IdeTarget {
    /** IntelliJ IDEA — {@code .idea/} + {@code *.iml}. */
    IDEA,
    /** VS Code — {@code .vscode/} + Eclipse project metadata for the redhat.java language server. */
    VSCODE
}
