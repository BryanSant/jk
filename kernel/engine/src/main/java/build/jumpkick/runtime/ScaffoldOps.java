// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.engine.protocol.GeneratedFiles;
import build.jumpkick.plugin.manifest.PluginDescriptor;
import build.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Engine-hosted plugin scaffolding (build-plugins plan row 9, P4): {@code jk new --<flag>}
 * renders a plugin's {@code [scaffold]} data — jk.toml fragments appended to the client-rendered
 * base manifest, plus the sample-source templates — and returns the payloads for the client to
 * write. Pure manifest data: no plugin code runs, and the thin client ships none of the content.
 *
 * <p>Params: {@code plugin} (the scaffold flag or plugin id), {@code lang} (java|kotlin),
 * {@code package} (the sample package = project group), {@code simpleLayout} (true|false),
 * {@code sample} (write the sample sources?), {@code baseToml} (the client-rendered jk.toml).
 */
public final class ScaffoldOps {

    private ScaffoldOps() {}

    public static GeneratedFiles scaffold(Path dir, Map<String, String> params) {
        String flag = String.valueOf(params.get("plugin"));
        PluginDescriptor manifest = byScaffoldFlag(flag);
        if (manifest == null) {
            return GeneratedFiles.error("no installed plugin scaffolds --" + flag);
        }
        PluginDescriptor.Scaffold scaffold = manifest.scaffold();
        String lang = params.getOrDefault("lang", "java");
        String pkg = String.valueOf(params.get("package"));
        boolean simple = Boolean.parseBoolean(params.getOrDefault("simpleLayout", "false"));
        boolean sample = Boolean.parseBoolean(params.getOrDefault("sample", "true"));

        List<String> paths = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        // jk.toml = the client-rendered base + this plugin's fragments (order preserved).
        StringBuilder toml = new StringBuilder(String.valueOf(params.getOrDefault("baseToml", "")));
        if (toml.length() > 0 && toml.charAt(toml.length() - 1) != '\n') toml.append('\n');
        for (PluginDescriptor.Append append : scaffold.appends()) {
            if (append.whenLang() != null && !append.whenLang().equals(lang)) continue;
            toml.append(interpolate(PluginTableRegistry.resourceText(manifest, append.template()), pkg));
        }
        paths.add(dir.resolve("jk.toml").toString());
        contents.add(toml.toString());

        if (sample) {
            for (PluginDescriptor.FileTemplate file : scaffold.files()) {
                if (file.whenLang() != null && !file.whenLang().equals(lang)) continue;
                Path target = dir.resolve(interpolatePath(file.path(), lang, pkg, simple));
                if (file.keepExisting() && Files.exists(target)) continue;
                paths.add(target.toString());
                contents.add(interpolate(PluginTableRegistry.resourceText(manifest, file.template()), pkg));
            }
        }
        return new GeneratedFiles(null, paths, contents, List.of());
    }

    private static PluginDescriptor byScaffoldFlag(String flag) {
        for (PluginDescriptor m : PluginTableRegistry.manifests()) {
            if (m.scaffold() == null) continue;
            if (m.scaffold().flag().equals(flag) || m.id().equals(flag)) return m;
        }
        return null;
    }

    /** Template variables: {@code ${package}}. */
    private static String interpolate(String template, String pkg) {
        return template.replace("${package}", pkg);
    }

    /**
     * Path variables: {@code ${main-root}} / {@code ${test-root}} / {@code ${resources-root}}
     * (jk's own layout rule, resolved here so plugin data never encodes it) and
     * {@code ${package-path}}.
     */
    private static String interpolatePath(String path, String lang, String pkg, boolean simple) {
        return path.replace("${main-root}", simple ? "src" : "src/main/" + lang)
                .replace("${test-root}", simple ? "test" : "src/test/" + lang)
                .replace("${resources-root}", simple ? "src" : "src/main/resources")
                .replace("${package-path}", pkg.replace('.', '/'));
    }
}
