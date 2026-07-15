// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.protocol;

import build.jumpkick.plugin.protocol.Ndjson;
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
                message, "", "", false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "", "", 0, "", "", List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.IDE_MODEL_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Ndjson.quote(error))
                + ",\"wsRoot\":" + Ndjson.quote(wsRoot)
                + ",\"rootName\":" + Ndjson.quote(rootName)
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
                + ",\"defSdkStableName\":" + Ndjson.quote(defSdkStableName)
                + ",\"defSdkName\":" + Ndjson.quote(defSdkName)
                + ",\"defSdkLevel\":" + defSdkLevel
                + ",\"defSdkHome\":" + Ndjson.quote(defSdkHome)
                + ",\"defSdkVersion\":" + Ndjson.quote(defSdkVersion)
                + ",\"sdkEntries\":" + EngineProtocol.quoteArray(sdkEntries)
                + "}";
    }

    public static IdeWireModel decode(String line) {
        return new IdeWireModel(
                Ndjson.str(line, "error"),
                orEmpty(Ndjson.str(line, "wsRoot")),
                orEmpty(Ndjson.str(line, "rootName")),
                Ndjson.bool(line, "workspace", false),
                Ndjson.strArray(line, "moduleDirs"),
                Ndjson.strArray(line, "names"),
                Ndjson.strArray(line, "javaReleases"),
                Ndjson.strArray(line, "mainClasses"),
                Ndjson.strArray(line, "classesDirs"),
                Ndjson.strArray(line, "testClassesDirs"),
                Ndjson.strArray(line, "jdtClassesDirs"),
                Ndjson.strArray(line, "jdtTestClassesDirs"),
                Ndjson.strArray(line, "genSrcDirs"),
                Ndjson.strArray(line, "genTestSrcDirs"),
                Ndjson.strArray(line, "libNames"),
                Ndjson.strArray(line, "libFiles"),
                Ndjson.strArray(line, "libJars"),
                Ndjson.strArray(line, "libSources"),
                Ndjson.strArray(line, "siblingRefs"),
                Ndjson.strArray(line, "libEntries"),
                Ndjson.strArray(line, "processorJars"),
                Ndjson.strArray(line, "sdkStableNames"),
                Ndjson.strArray(line, "sdkNames"),
                Ndjson.strArray(line, "sdkLevels"),
                Ndjson.strArray(line, "sdkHomes"),
                Ndjson.strArray(line, "sdkVersions"),
                orEmpty(Ndjson.str(line, "defSdkStableName")),
                orEmpty(Ndjson.str(line, "defSdkName")),
                Ndjson.intValue(line, "defSdkLevel", 0),
                orEmpty(Ndjson.str(line, "defSdkHome")),
                orEmpty(Ndjson.str(line, "defSdkVersion")),
                Ndjson.strArray(line, "sdkEntries"));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
