// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link PluginProtocol} spec (engine→plugin) as JSONL lines — one writer for every
 * plugin, replacing the per-plugin KEY-value/tab spec builders. Fluent; call {@link #lines} and
 * write them to the spec file the plugin is forked with.
 */
public final class SpecWriter {

    private final List<String> lines = new ArrayList<>();

    public SpecWriter op(String op, String name, String pluginId) {
        StringBuilder b = new StringBuilder("{\"t\":")
                .append(Jsonl.quote(PluginProtocol.OP))
                .append(",\"op\":")
                .append(Jsonl.quote(op));
        if (name != null) b.append(",\"name\":").append(Jsonl.quote(name));
        b.append(",\"plugin\":").append(Jsonl.quote(pluginId)).append('}');
        lines.add(b.toString());
        return this;
    }

    /** Serialize a whole validated config table (string/bool/int/list values). */
    public SpecWriter config(PluginConfig config) {
        for (Map.Entry<String, Object> e : config.values().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) configString(e.getKey(), s);
            else if (v instanceof Boolean b) configBool(e.getKey(), b);
            else if (v instanceof Long l) configInt(e.getKey(), l);
            else if (v instanceof Integer i) configInt(e.getKey(), i.longValue());
            else if (v instanceof List<?> list) {
                List<String> strs = new ArrayList<>();
                for (Object o : list) strs.add(String.valueOf(o));
                configList(e.getKey(), strs);
            }
        }
        return this;
    }

    public SpecWriter configString(String key, String value) {
        if (value == null) return this;
        lines.add("{\"t\":\"config\",\"key\":" + Jsonl.quote(key) + ",\"kind\":\"string\",\"value\":"
                + Jsonl.quote(value) + "}");
        return this;
    }

    public SpecWriter configBool(String key, boolean value) {
        lines.add("{\"t\":\"config\",\"key\":" + Jsonl.quote(key) + ",\"kind\":\"bool\",\"value\":" + value + "}");
        return this;
    }

    public SpecWriter configInt(String key, long value) {
        lines.add("{\"t\":\"config\",\"key\":" + Jsonl.quote(key) + ",\"kind\":\"int\",\"value\":" + value + "}");
        return this;
    }

    public SpecWriter configList(String key, List<String> values) {
        lines.add("{\"t\":\"config\",\"key\":" + Jsonl.quote(key) + ",\"kind\":\"list\",\"values\":" + array(values)
                + "}");
        return this;
    }

    public SpecWriter project(ProjectFacts p) {
        StringBuilder b = new StringBuilder("{\"t\":\"project\",\"group\":")
                .append(Jsonl.quote(p.group()))
                .append(",\"name\":")
                .append(Jsonl.quote(p.name()))
                .append(",\"version\":")
                .append(Jsonl.quote(p.version()))
                .append(",\"javaRelease\":")
                .append(p.javaRelease())
                .append(",\"nativeDeclared\":")
                .append(p.nativeDeclared())
                .append(",\"kotlin\":")
                .append(p.kotlin());
        if (p.mainClass() != null && !p.mainClass().isBlank()) {
            b.append(",\"mainClass\":").append(Jsonl.quote(p.mainClass()));
        }
        b.append('}');
        lines.add(b.toString());
        for (Map.Entry<String, String> e : p.manifest().entrySet()) {
            lines.add("{\"t\":\"manifest-attr\",\"key\":" + Jsonl.quote(e.getKey()) + ",\"value\":"
                    + Jsonl.quote(e.getValue()) + "}");
        }
        return this;
    }

    public SpecWriter layout(Map<String, Path> dirs) {
        StringBuilder b = new StringBuilder("{\"t\":\"layout\"");
        for (Map.Entry<String, Path> e : dirs.entrySet()) {
            if (e.getValue() != null) {
                b.append(',')
                        .append(Jsonl.quote(e.getKey()))
                        .append(':')
                        .append(Jsonl.quote(e.getValue().toAbsolutePath().toString()));
            }
        }
        b.append('}');
        lines.add(b.toString());
        return this;
    }

    public SpecWriter javaHome(Path javaHome) {
        lines.add("{\"t\":\"java-home\",\"path\":"
                + Jsonl.quote(javaHome.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter artifact(Path path) {
        lines.add("{\"t\":\"artifact\",\"path\":"
                + Jsonl.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter cp(Path path, String role) {
        lines.add("{\"t\":\"cp\",\"path\":" + Jsonl.quote(path.toAbsolutePath().toString()) + ",\"role\":"
                + Jsonl.quote(role) + "}");
        return this;
    }

    public SpecWriter entry(String fileName, Path jar, boolean snapshot, Path container) {
        StringBuilder b = new StringBuilder("{\"t\":\"entry\",\"file\":").append(Jsonl.quote(fileName));
        if (jar != null)
            b.append(",\"path\":").append(Jsonl.quote(jar.toAbsolutePath().toString()));
        b.append(",\"snapshot\":").append(snapshot);
        if (container != null)
            b.append(",\"container\":")
                    .append(Jsonl.quote(container.toAbsolutePath().toString()));
        b.append('}');
        lines.add(b.toString());
        return this;
    }

    public SpecWriter source(Path path) {
        lines.add("{\"t\":\"source\",\"path\":"
                + Jsonl.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter arg(String value) {
        lines.add("{\"t\":\"arg\",\"value\":" + Jsonl.quote(value) + "}");
        return this;
    }

    public SpecWriter compilerPlugin(String id, Path jar, List<String> options) {
        lines.add("{\"t\":\"compiler-plugin\",\"id\":" + Jsonl.quote(id) + ",\"path\":"
                + Jsonl.quote(jar.toAbsolutePath().toString()) + ",\"options\":" + array(options) + "}");
        return this;
    }

    public SpecWriter stepOutput(String name, Path dir) {
        lines.add("{\"t\":\"step-output\",\"name\":" + Jsonl.quote(name) + ",\"dir\":"
                + Jsonl.quote(dir.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter extra(String name, Path path) {
        lines.add("{\"t\":\"extra\",\"name\":" + Jsonl.quote(name) + ",\"path\":"
                + Jsonl.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    /** A resolved secret — package/publish specs only; never echoed by plugins. */
    public SpecWriter secret(String key, String value) {
        lines.add("{\"t\":\"secret\",\"key\":" + Jsonl.quote(key) + ",\"value\":" + Jsonl.quote(value) + "}");
        return this;
    }

    public SpecWriter commandArgs(List<String> args) {
        lines.add("{\"t\":\"command-args\",\"values\":" + array(args) + "}");
        return this;
    }

    public List<String> lines() {
        return lines;
    }

    private static String array(List<String> values) {
        return Jsonl.array(values);
    }
}
