// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Sealed step model. Each variant is an immutable record built via a small
 * mutable builder reachable from a static factory ({@code .of(...)}).
 */
public sealed interface WizardStep
        permits WizardStep.InputStep,
                WizardStep.RadioStep,
                WizardStep.MultiSelectStep,
                WizardStep.OutputStep {

    String key();

    String prompt();

    Predicate<Answers> shouldRun();

    Predicate<Answers> ALWAYS = a -> true;

    Function<String, ValidationResult> NO_VALIDATION = s -> ValidationResult.ok();

    record InputStep(
            String key,
            String prompt,
            String placeholder,
            String defaultValue,
            Predicate<Answers> shouldRun,
            Function<String, ValidationResult> validator)
            implements WizardStep {

        public static Builder of(String key, String prompt) {
            return new Builder(key, prompt);
        }

        public static final class Builder {
            private final String key;
            private final String prompt;
            private String placeholder = "";
            private String defaultValue = "";
            private Predicate<Answers> shouldRun = ALWAYS;
            private Function<String, ValidationResult> validator = NO_VALIDATION;

            private Builder(String key, String prompt) {
                this.key = key;
                this.prompt = prompt;
            }

            public Builder placeholder(String placeholder) {
                this.placeholder = placeholder;
                return this;
            }

            public Builder defaultValue(String defaultValue) {
                this.defaultValue = defaultValue;
                return this;
            }

            public Builder when(Predicate<Answers> shouldRun) {
                this.shouldRun = shouldRun;
                return this;
            }

            public Builder validator(Function<String, ValidationResult> validator) {
                this.validator = validator;
                return this;
            }

            public InputStep build() {
                return new InputStep(key, prompt, placeholder, defaultValue, shouldRun, validator);
            }
        }
    }

    record RadioStep(
            String key,
            String prompt,
            List<Choice> choices,
            String defaultChoice,
            Orientation orientation,
            Predicate<Answers> shouldRun)
            implements WizardStep {

        public RadioStep {
            choices = List.copyOf(choices);
        }

        public static Builder horizontal(String key, String prompt) {
            return new Builder(key, prompt, Orientation.HORIZONTAL);
        }

        public static Builder vertical(String key, String prompt) {
            return new Builder(key, prompt, Orientation.VERTICAL);
        }

        public static Builder of(String key, String prompt) {
            return new Builder(key, prompt, Orientation.HORIZONTAL);
        }

        public static final class Builder {
            private final String key;
            private final String prompt;
            private final Orientation orientation;
            private final List<Choice> choices = new ArrayList<>();
            private String defaultChoice = "";
            private Predicate<Answers> shouldRun = ALWAYS;

            private Builder(String key, String prompt, Orientation orientation) {
                this.key = key;
                this.prompt = prompt;
                this.orientation = orientation;
            }

            public Builder choice(String id, String label) {
                this.choices.add(new Choice(id, label));
                return this;
            }

            public Builder defaultChoice(String id) {
                this.defaultChoice = id;
                return this;
            }

            public Builder when(Predicate<Answers> shouldRun) {
                this.shouldRun = shouldRun;
                return this;
            }

            public RadioStep build() {
                var resolved = defaultChoice.isEmpty() && !choices.isEmpty()
                        ? choices.getFirst().id()
                        : defaultChoice;
                return new RadioStep(key, prompt, choices, resolved, orientation, shouldRun);
            }
        }
    }

    record MultiSelectStep(
            String key,
            String prompt,
            List<Choice> choices,
            Set<String> defaults,
            Orientation orientation,
            Predicate<Answers> shouldRun)
            implements WizardStep {

        public MultiSelectStep {
            choices = List.copyOf(choices);
            defaults = Set.copyOf(defaults);
        }

        public static Builder horizontal(String key, String prompt) {
            return new Builder(key, prompt, Orientation.HORIZONTAL);
        }

        public static Builder vertical(String key, String prompt) {
            return new Builder(key, prompt, Orientation.VERTICAL);
        }

        public static Builder of(String key, String prompt) {
            return new Builder(key, prompt, Orientation.VERTICAL);
        }

        public static final class Builder {
            private final String key;
            private final String prompt;
            private final Orientation orientation;
            private final List<Choice> choices = new ArrayList<>();
            private final Set<String> defaults = new LinkedHashSet<>();
            private Predicate<Answers> shouldRun = ALWAYS;

            private Builder(String key, String prompt, Orientation orientation) {
                this.key = key;
                this.prompt = prompt;
                this.orientation = orientation;
            }

            public Builder choice(String id, String label) {
                this.choices.add(new Choice(id, label));
                return this;
            }

            public Builder defaults(Set<String> defaults) {
                this.defaults.clear();
                this.defaults.addAll(defaults);
                return this;
            }

            public Builder when(Predicate<Answers> shouldRun) {
                this.shouldRun = shouldRun;
                return this;
            }

            public MultiSelectStep build() {
                return new MultiSelectStep(key, prompt, choices, defaults, orientation, shouldRun);
            }
        }
    }

    record OutputStep(String key, String prompt, Function<Answers, List<String>> render, Predicate<Answers> shouldRun)
            implements WizardStep {

        public static Builder of(String key, Function<Answers, List<String>> render) {
            return new Builder(key, render);
        }

        public static final class Builder {
            private final String key;
            private final Function<Answers, List<String>> render;
            private String prompt = "";
            private Predicate<Answers> shouldRun = ALWAYS;

            private Builder(String key, Function<Answers, List<String>> render) {
                this.key = key;
                this.render = render;
            }

            public Builder prompt(String prompt) {
                this.prompt = prompt;
                return this;
            }

            public Builder when(Predicate<Answers> shouldRun) {
                this.shouldRun = shouldRun;
                return this;
            }

            public OutputStep build() {
                return new OutputStep(key, prompt, render, shouldRun);
            }
        }
    }
}
