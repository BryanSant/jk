// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;

/**
 * The engine's IDE-agnostic workspace model ({@link EngineProtocol#IDE_MODEL_REQUEST}) — the thin
 * client's replacement for the client-side {@code IdeSupport.build} model math, which needed the
 * parsed root, every workspace module, and their lockfiles. The client-side generators (IntelliJ /
 * VS Code file emitters — TTY + disk writers) consume this instead of {@code JkBuild}.
 *
 * <p>Per-module data rides as parallel lists indexed by {@code moduleDirs}; cross-module edges and
 * per-module lists ride as {@code moduleIndex|…} strings (the {@code WhyReport} convention). Jar
 * and JDK paths are absolute. {@code error} non-null means the model could not be computed; its
 * message is ready to print.
 */
public record IdeWireModel(
        String error,
        String wsRoot,
        String rootName,
        boolean workspace,
        List<String> moduleDirs,
        List<String> names,
        List<String> javaReleases,
        List<String> mainClasses,
        List<String> classesDirs,
        List<String> testClassesDirs,
        List<String> jdtClassesDirs,
        List<String> jdtTestClassesDirs,
        List<String> genSrcDirs,
        List<String> genTestSrcDirs,
        List<String> libNames,
        List<String> libFiles,
        List<String> libJars,
        List<String> libSources,
        List<String> siblingRefs,
        List<String> libEntries,
        List<String> processorJars,
        List<String> sdkStableNames,
        List<String> sdkNames,
        List<String> sdkLevels,
        List<String> sdkHomes,
        List<String> sdkVersions,
        String defSdkStableName,
        String defSdkName,
        int defSdkLevel,
        String defSdkHome,
        String defSdkVersion,
        List<String> sdkEntries) {

    public static IdeWireModel error(String message) {
        return new IdeWireModel(
                message, "", "", false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", 0, "", "", List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.IDE_MODEL_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"wsRoot\":" + Jsonl.quote(wsRoot)
                + ",\"rootName\":" + Jsonl.quote(rootName)
                + ",\"workspace\":" + workspace
                + ",\"moduleDirs\":" + EngineProtocol.quoteArray(moduleDirs)
                + ",\"names\":" + EngineProtocol.quoteArray(names)
                + ",\"javaReleases\":" + EngineProtocol.quoteArray(javaReleases)
                + ",\"mainClasses\":" + EngineProtocol.quoteArray(mainClasses)
                + ",\"classesDirs\":" + EngineProtocol.quoteArray(classesDirs)
                + ",\"testClassesDirs\":" + EngineProtocol.quoteArray(testClassesDirs)
                + ",\"jdtClassesDirs\":" + EngineProtocol.quoteArray(jdtClassesDirs)
                + ",\"jdtTestClassesDirs\":" + EngineProtocol.quoteArray(jdtTestClassesDirs)
                + ",\"genSrcDirs\":" + EngineProtocol.quoteArray(genSrcDirs)
                + ",\"genTestSrcDirs\":" + EngineProtocol.quoteArray(genTestSrcDirs)
                + ",\"libNames\":" + EngineProtocol.quoteArray(libNames)
                + ",\"libFiles\":" + EngineProtocol.quoteArray(libFiles)
                + ",\"libJars\":" + EngineProtocol.quoteArray(libJars)
                + ",\"libSources\":" + EngineProtocol.quoteArray(libSources)
                + ",\"siblingRefs\":" + EngineProtocol.quoteArray(siblingRefs)
                + ",\"libEntries\":" + EngineProtocol.quoteArray(libEntries)
                + ",\"processorJars\":" + EngineProtocol.quoteArray(processorJars)
                + ",\"sdkStableNames\":" + EngineProtocol.quoteArray(sdkStableNames)
                + ",\"sdkNames\":" + EngineProtocol.quoteArray(sdkNames)
                + ",\"sdkLevels\":" + EngineProtocol.quoteArray(sdkLevels)
                + ",\"sdkHomes\":" + EngineProtocol.quoteArray(sdkHomes)
                + ",\"sdkVersions\":" + EngineProtocol.quoteArray(sdkVersions)
                + ",\"defSdkStableName\":" + Jsonl.quote(defSdkStableName)
                + ",\"defSdkName\":" + Jsonl.quote(defSdkName)
                + ",\"defSdkLevel\":" + defSdkLevel
                + ",\"defSdkHome\":" + Jsonl.quote(defSdkHome)
                + ",\"defSdkVersion\":" + Jsonl.quote(defSdkVersion)
                + ",\"sdkEntries\":" + EngineProtocol.quoteArray(sdkEntries)
                + "}";
    }

    public static IdeWireModel decode(String line) {
        return new IdeWireModel(
                Jsonl.str(line, "error"),
                orEmpty(Jsonl.str(line, "wsRoot")),
                orEmpty(Jsonl.str(line, "rootName")),
                Jsonl.bool(line, "workspace", false),
                Jsonl.strArray(line, "moduleDirs"),
                Jsonl.strArray(line, "names"),
                Jsonl.strArray(line, "javaReleases"),
                Jsonl.strArray(line, "mainClasses"),
                Jsonl.strArray(line, "classesDirs"),
                Jsonl.strArray(line, "testClassesDirs"),
                Jsonl.strArray(line, "jdtClassesDirs"),
                Jsonl.strArray(line, "jdtTestClassesDirs"),
                Jsonl.strArray(line, "genSrcDirs"),
                Jsonl.strArray(line, "genTestSrcDirs"),
                Jsonl.strArray(line, "libNames"),
                Jsonl.strArray(line, "libFiles"),
                Jsonl.strArray(line, "libJars"),
                Jsonl.strArray(line, "libSources"),
                Jsonl.strArray(line, "siblingRefs"),
                Jsonl.strArray(line, "libEntries"),
                Jsonl.strArray(line, "processorJars"),
                Jsonl.strArray(line, "sdkStableNames"),
                Jsonl.strArray(line, "sdkNames"),
                Jsonl.strArray(line, "sdkLevels"),
                Jsonl.strArray(line, "sdkHomes"),
                Jsonl.strArray(line, "sdkVersions"),
                orEmpty(Jsonl.str(line, "defSdkStableName")),
                orEmpty(Jsonl.str(line, "defSdkName")),
                Jsonl.intValue(line, "defSdkLevel", 0),
                orEmpty(Jsonl.str(line, "defSdkHome")),
                orEmpty(Jsonl.str(line, "defSdkVersion")),
                Jsonl.strArray(line, "sdkEntries"));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
