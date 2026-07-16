// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Ndjson;
import java.util.List;

/**
 * The engine's one-shot parsed-project summary ({@link EngineProtocol#PROJECT_INFO_REQUEST}) —
 * the thin client's replacement for every client-side {@code JkBuildParser.parse} peek
 * (docs/thin-client-plan.md §2.1). Flat scalars + string lists only, per the wire discipline.
 *
 * <p>{@code error} non-null means the project could not be summarized (no jk.toml, parse
 * failure); its message is ready to print and every other field is defaulted.
 */
public record ProjectInfo(
        String error,
        String group,
        String name,
        String version,
        String jdk,
        int javaRelease,
        boolean kotlin,
        String kotlinVersion,
        boolean layoutSimple,
        boolean workspaceRoot,
        String workspaceRootDir,
        List<String> moduleDirs,
        boolean application,
        String mainClass,
        boolean shadowJar,
        String nativeMode,
        String graal,
        boolean springBoot,
        String springBootVersion,
        String formatStyle,
        String formatJava,
        String formatKotlin,
        boolean formatOptimizeImports,
        boolean hasLock,
        String lockJdk,
        String mainJarPath,
        String shadowJarPath,
        String nativeBinPath,
        String nativeLibPath,
        List<String> pathDeps,
        String sourcesJarPath,
        String javadocJarPath,
        List<String> envRefs) {

    /** The {@code group:name} display coordinate. */
    public String coord() {
        return group + ":" + name;
    }

    public static ProjectInfo error(String message) {
        return new ProjectInfo(
                message, "", "", "", "", 0, false, "", true, false, "", List.of(), false, "", false, "DISABLED",
                "", false, "", "", "", "", false, false, "", "", "", "", "", List.of(), "", "", List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.PROJECT_INFO_ACK + "\""
                + ",\"error\":" + quoteOrNull(error)
                + ",\"group\":" + Ndjson.quote(group)
                + ",\"name\":" + Ndjson.quote(name)
                + ",\"version\":" + Ndjson.quote(version)
                + ",\"jdk\":" + Ndjson.quote(jdk)
                + ",\"javaRelease\":" + javaRelease
                + ",\"kotlin\":" + kotlin
                + ",\"kotlinVersion\":" + Ndjson.quote(kotlinVersion)
                + ",\"layoutSimple\":" + layoutSimple
                + ",\"workspaceRoot\":" + workspaceRoot
                + ",\"workspaceRootDir\":" + Ndjson.quote(workspaceRootDir)
                + ",\"moduleDirs\":" + EngineProtocol.quoteArray(moduleDirs)
                + ",\"application\":" + application
                + ",\"mainClass\":" + Ndjson.quote(mainClass)
                + ",\"shadowJar\":" + shadowJar
                + ",\"nativeMode\":" + Ndjson.quote(nativeMode)
                + ",\"graal\":" + Ndjson.quote(graal)
                + ",\"springBoot\":" + springBoot
                + ",\"springBootVersion\":" + Ndjson.quote(springBootVersion)
                + ",\"formatStyle\":" + Ndjson.quote(formatStyle)
                + ",\"formatJava\":" + Ndjson.quote(formatJava)
                + ",\"formatKotlin\":" + Ndjson.quote(formatKotlin)
                + ",\"formatOptimizeImports\":" + formatOptimizeImports
                + ",\"hasLock\":" + hasLock
                + ",\"lockJdk\":" + Ndjson.quote(lockJdk)
                + ",\"mainJarPath\":" + Ndjson.quote(mainJarPath)
                + ",\"shadowJarPath\":" + Ndjson.quote(shadowJarPath)
                + ",\"nativeBinPath\":" + Ndjson.quote(nativeBinPath)
                + ",\"nativeLibPath\":" + Ndjson.quote(nativeLibPath)
                + ",\"pathDeps\":" + EngineProtocol.quoteArray(pathDeps)
                + ",\"sourcesJarPath\":" + Ndjson.quote(sourcesJarPath)
                + ",\"javadocJarPath\":" + Ndjson.quote(javadocJarPath)
                + ",\"envRefs\":" + EngineProtocol.quoteArray(envRefs)
                + "}";
    }

    public static ProjectInfo decode(String line) {
        String error = Ndjson.str(line, "error");
        return new ProjectInfo(
                error,
                orEmpty(Ndjson.str(line, "group")),
                orEmpty(Ndjson.str(line, "name")),
                orEmpty(Ndjson.str(line, "version")),
                orEmpty(Ndjson.str(line, "jdk")),
                Ndjson.intValue(line, "javaRelease", 0),
                Ndjson.bool(line, "kotlin", false),
                orEmpty(Ndjson.str(line, "kotlinVersion")),
                Ndjson.bool(line, "layoutSimple", true),
                Ndjson.bool(line, "workspaceRoot", false),
                orEmpty(Ndjson.str(line, "workspaceRootDir")),
                Ndjson.strArray(line, "moduleDirs"),
                Ndjson.bool(line, "application", false),
                orEmpty(Ndjson.str(line, "mainClass")),
                Ndjson.bool(line, "shadowJar", false),
                orEmpty(Ndjson.str(line, "nativeMode")),
                orEmpty(Ndjson.str(line, "graal")),
                Ndjson.bool(line, "springBoot", false),
                orEmpty(Ndjson.str(line, "springBootVersion")),
                orEmpty(Ndjson.str(line, "formatStyle")),
                orEmpty(Ndjson.str(line, "formatJava")),
                orEmpty(Ndjson.str(line, "formatKotlin")),
                Ndjson.bool(line, "formatOptimizeImports", false),
                Ndjson.bool(line, "hasLock", false),
                orEmpty(Ndjson.str(line, "lockJdk")),
                orEmpty(Ndjson.str(line, "mainJarPath")),
                orEmpty(Ndjson.str(line, "shadowJarPath")),
                orEmpty(Ndjson.str(line, "nativeBinPath")),
                orEmpty(Ndjson.str(line, "nativeLibPath")),
                Ndjson.strArray(line, "pathDeps"),
                orEmpty(Ndjson.str(line, "sourcesJarPath")),
                orEmpty(Ndjson.str(line, "javadocJarPath")),
                Ndjson.strArray(line, "envRefs"));
    }

    private static String quoteOrNull(String s) {
        return s == null ? "null" : Ndjson.quote(s);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
