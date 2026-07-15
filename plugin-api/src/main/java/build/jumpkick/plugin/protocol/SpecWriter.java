// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.protocol;

import build.jumpkick.plugin.PluginConfig;
import build.jumpkick.plugin.build.ProjectFacts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link PluginProtocol} spec (engine→plugin) as NDJSON lines — one writer for every
 * plugin, replacing the per-plugin KEY-value/tab spec builders. Fluent; call {@link #lines} and
 * write them to the spec file the plugin is forked with.
 */
public final class SpecWriter {

    private final List<String> lines = new ArrayList<>();

    public SpecWriter op(String op, String name, String pluginId) {
        StringBuilder b = new StringBuilder("{\"t\":").append(Ndjson.quote(PluginProtocol.OP))
                .append(",\"op\":").append(Ndjson.quote(op));
        if (name != null) b.append(",\"name\":").append(Ndjson.quote(name));
        b.append(",\"plugin\":").append(Ndjson.quote(pluginId)).append('}');
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
        lines.add("{\"t\":\"config\",\"key\":" + Ndjson.quote(key) + ",\"kind\":\"string\",\"value\":"
                + Ndjson.quote(value) + "}");
        return this;
    }

    public SpecWriter configBool(String key, boolean value) {
        lines.add("{\"t\":\"config\",\"key\":" + Ndjson.quote(key) + ",\"kind\":\"bool\",\"value\":" + value + "}");
        return this;
    }

    public SpecWriter configInt(String key, long value) {
        lines.add("{\"t\":\"config\",\"key\":" + Ndjson.quote(key) + ",\"kind\":\"int\",\"value\":" + value + "}");
        return this;
    }

    public SpecWriter configList(String key, List<String> values) {
        lines.add("{\"t\":\"config\",\"key\":" + Ndjson.quote(key) + ",\"kind\":\"list\",\"values\":"
                + array(values) + "}");
        return this;
    }

    public SpecWriter project(ProjectFacts p) {
        StringBuilder b = new StringBuilder("{\"t\":\"project\",\"group\":").append(Ndjson.quote(p.group()))
                .append(",\"name\":").append(Ndjson.quote(p.name()))
                .append(",\"version\":").append(Ndjson.quote(p.version()))
                .append(",\"javaRelease\":").append(p.javaRelease())
                .append(",\"nativeDeclared\":").append(p.nativeDeclared())
                .append(",\"kotlin\":").append(p.kotlin());
        if (p.mainClass() != null && !p.mainClass().isBlank()) {
            b.append(",\"mainClass\":").append(Ndjson.quote(p.mainClass()));
        }
        b.append('}');
        lines.add(b.toString());
        for (Map.Entry<String, String> e : p.manifest().entrySet()) {
            lines.add("{\"t\":\"manifest-attr\",\"key\":" + Ndjson.quote(e.getKey()) + ",\"value\":"
                    + Ndjson.quote(e.getValue()) + "}");
        }
        return this;
    }

    public SpecWriter layout(Map<String, Path> dirs) {
        StringBuilder b = new StringBuilder("{\"t\":\"layout\"");
        for (Map.Entry<String, Path> e : dirs.entrySet()) {
            if (e.getValue() != null) {
                b.append(',').append(Ndjson.quote(e.getKey())).append(':')
                        .append(Ndjson.quote(e.getValue().toAbsolutePath().toString()));
            }
        }
        b.append('}');
        lines.add(b.toString());
        return this;
    }

    public SpecWriter javaHome(Path javaHome) {
        lines.add("{\"t\":\"java-home\",\"path\":" + Ndjson.quote(javaHome.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter artifact(Path path) {
        lines.add("{\"t\":\"artifact\",\"path\":" + Ndjson.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter cp(Path path, String role) {
        lines.add("{\"t\":\"cp\",\"path\":" + Ndjson.quote(path.toAbsolutePath().toString())
                + ",\"role\":" + Ndjson.quote(role) + "}");
        return this;
    }

    public SpecWriter entry(String fileName, Path jar, boolean snapshot, Path container) {
        StringBuilder b = new StringBuilder("{\"t\":\"entry\",\"file\":").append(Ndjson.quote(fileName));
        if (jar != null) b.append(",\"path\":").append(Ndjson.quote(jar.toAbsolutePath().toString()));
        b.append(",\"snapshot\":").append(snapshot);
        if (container != null) b.append(",\"container\":").append(Ndjson.quote(container.toAbsolutePath().toString()));
        b.append('}');
        lines.add(b.toString());
        return this;
    }

    public SpecWriter source(Path path) {
        lines.add("{\"t\":\"source\",\"path\":" + Ndjson.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter arg(String value) {
        lines.add("{\"t\":\"arg\",\"value\":" + Ndjson.quote(value) + "}");
        return this;
    }

    public SpecWriter compilerPlugin(String id, Path jar, List<String> options) {
        lines.add("{\"t\":\"compiler-plugin\",\"id\":" + Ndjson.quote(id) + ",\"path\":"
                + Ndjson.quote(jar.toAbsolutePath().toString()) + ",\"options\":" + array(options) + "}");
        return this;
    }

    public SpecWriter stepOutput(String name, Path dir) {
        lines.add("{\"t\":\"step-output\",\"name\":" + Ndjson.quote(name) + ",\"dir\":"
                + Ndjson.quote(dir.toAbsolutePath().toString()) + "}");
        return this;
    }

    public SpecWriter extra(String name, Path path) {
        lines.add("{\"t\":\"extra\",\"name\":" + Ndjson.quote(name) + ",\"path\":"
                + Ndjson.quote(path.toAbsolutePath().toString()) + "}");
        return this;
    }

    /** A resolved secret — package/publish specs only; never echoed by plugins. */
    public SpecWriter secret(String key, String value) {
        lines.add("{\"t\":\"secret\",\"key\":" + Ndjson.quote(key) + ",\"value\":" + Ndjson.quote(value) + "}");
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
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(Ndjson.quote(values.get(i)));
        }
        return b.append(']').toString();
    }
}
