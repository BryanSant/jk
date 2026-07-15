// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.tui;

import java.util.List;
import java.util.Map;

/**
 * Immutable typed view over a wizard's accumulated answers. Strings come from input/radio steps;
 * lists come from multi-select steps. Construction copies the map defensively so callers cannot
 * mutate state after the wizard returns.
 */
public final class Answers {

    private final Map<String, Object> values;

    private Answers(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    public static Answers of(Map<String, Object> values) {
        return new Answers(values);
    }

    public String get(String key) {
        var v = values.get(key);
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(String key) {
        var v = values.get(key);
        if (v instanceof List<?> list) {
            return List.copyOf((List<String>) list);
        }
        return List.of();
    }

    /**
     * True iff the key is present AND its value is meaningful: non-blank for strings, non-empty for
     * lists. This is the predicate used by step {@code when()} conditions.
     */
    public boolean has(String key) {
        var v = values.get(key);
        return switch (v) {
            case null -> false;
            case String s -> !s.isBlank();
            case List<?> list -> !list.isEmpty();
            default -> true;
        };
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
