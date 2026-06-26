// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The result of parsing an argument vector against a {@link Command}: the option values, boolean
 * flag states, and positional arguments, keyed by each option's {@link Opt#canonicalName()}.
 * Commands read their inputs from here instead of from picocli-populated fields.
 *
 * <p>Built by the arg parser via {@link #builder()}.
 */
public final class Invocation {

    private final Map<String, List<String>> values; // value options: canonical → values
    private final Map<String, Boolean> flags; // boolean flags: canonical → state
    private final List<String> positionals;

    private Invocation(Map<String, List<String>> values, Map<String, Boolean> flags, List<String> positionals) {
        this.values = values;
        this.flags = flags;
        this.positionals = List.copyOf(positionals);
    }

    /** True when a flag was present (in either form) or a value option was given. */
    public boolean has(String option) {
        return flags.containsKey(option) || values.containsKey(option);
    }

    /** A boolean flag's state: present-true, {@code --no-} present-false, or empty when absent. */
    public Optional<Boolean> flag(String option) {
        return Optional.ofNullable(flags.get(option));
    }

    /** Convenience: a flag's state defaulting to false when absent. */
    public boolean isSet(String option) {
        return flags.getOrDefault(option, false);
    }

    /** The first value given for a value option, if any. */
    public Optional<String> value(String option) {
        List<String> v = values.get(option);
        return (v == null || v.isEmpty()) ? Optional.empty() : Optional.of(v.get(0));
    }

    /** All values given for a (possibly repeatable / split) value option. */
    public List<String> values(String option) {
        return values.getOrDefault(option, List.of());
    }

    /** Positional arguments, in order. */
    public List<String> positionals() {
        return positionals;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable accumulator the parser fills as it walks the argument vector. */
    public static final class Builder {
        private final Map<String, List<String>> values = new LinkedHashMap<>();
        private final Map<String, Boolean> flags = new LinkedHashMap<>();
        private final List<String> positionals = new ArrayList<>();

        public Builder flag(String canonical, boolean state) {
            flags.put(canonical, state);
            return this;
        }

        public Builder addValue(String canonical, String value) {
            values.computeIfAbsent(canonical, k -> new ArrayList<>()).add(value);
            return this;
        }

        /** Replace any prior value(s) — last-wins semantics for single-valued options. */
        public Builder putValue(String canonical, String value) {
            List<String> v = new ArrayList<>();
            v.add(value);
            values.put(canonical, v);
            return this;
        }

        public Builder addPositional(String value) {
            positionals.add(value);
            return this;
        }

        public Invocation build() {
            return new Invocation(values, flags, positionals);
        }
    }
}
