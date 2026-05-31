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
            Function<Answers, String> initialValueFn,
            Predicate<Answers> shouldRun,
            Function<String, ValidationResult> validator)
            implements WizardStep {

        public static Builder of(String key, String prompt) {
            return new Builder(key, prompt);
        }

        /**
         * Seed value the wizard injects into the input buffer when the step
         * opens, derived from the answers collected so far. Returns
         * {@code ""} when no function was configured, so callers can treat
         * the result uniformly.
         */
        public String initialValueFor(Answers answers) {
            return initialValueFn != null ? initialValueFn.apply(answers) : "";
        }

        public static final class Builder {
            private final String key;
            private final String prompt;
            private String placeholder = "";
            private String defaultValue = "";
            private Function<Answers, String> initialValueFn;
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

            /**
             * Derive the initial input-buffer contents from earlier wizard
             * answers (e.g., pre-populate "artifactId" with the project
             * name). Applied only when the wizard has no prior answer for
             * this step's key.
             */
            public Builder initialValueFn(Function<Answers, String> fn) {
                this.initialValueFn = fn;
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
                return new InputStep(key, prompt, placeholder, defaultValue, initialValueFn, shouldRun, validator);
            }
        }
    }

    record RadioStep(
            String key,
            String prompt,
            List<Choice> choices,
            Function<Answers, List<Choice>> choicesFn,
            String defaultChoice,
            Orientation orientation,
            String customPlaceholder,
            Predicate<Answers> shouldRun)
            implements WizardStep {

        public RadioStep {
            choices = List.copyOf(choices);
            if (customPlaceholder == null) customPlaceholder = "";
        }

        /**
         * Whether this step appends a free-form "enter your own" row after the
         * fixed choices. Only honored for {@link Orientation#VERTICAL} lists.
         * When selected, the typed text becomes the answer verbatim (in place
         * of a choice id).
         */
        public boolean hasCustomOption() {
            return !customPlaceholder.isEmpty();
        }

        /**
         * Returns the rendered choice list for the current answers. When a
         * {@code choicesFn} was supplied it wins; otherwise the static
         * {@code choices} list is returned. Used by the wizard runtime so
         * a step can re-derive its options from prior answers (e.g., "pick
         * a survivor" after a multi-select earlier in the same wizard).
         */
        public List<Choice> choicesFor(Answers answers) {
            return choicesFn != null ? List.copyOf(choicesFn.apply(answers)) : choices;
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
            private Function<Answers, List<Choice>> choicesFn;
            private String defaultChoice = "";
            private String customPlaceholder = "";
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

            public Builder choice(String id, String label, String hint) {
                this.choices.add(new Choice(id, label, hint));
                return this;
            }

            public Builder choice(String id, String label, Function<Answers, String> hintFn) {
                this.choices.add(new Choice(id, label, hintFn));
                return this;
            }

            /**
             * Append a free-form text row after the fixed choices (vertical
             * lists only). {@code placeholder} is shown as dim example text
             * the user can type over, like an input step. Selecting this row
             * stores the typed text as the answer instead of a choice id.
             */
            public Builder customOption(String placeholder) {
                this.customPlaceholder = placeholder == null ? "" : placeholder;
                return this;
            }

            /**
             * Supply a function that derives the choice list from the
             * wizard's current answers. Wins over any static {@code .choice()}
             * calls. Useful when later-step options depend on earlier-step
             * picks (e.g., "pick a survivor" after a multi-select).
             */
            public Builder choicesFn(Function<Answers, List<Choice>> fn) {
                this.choicesFn = fn;
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
                return new RadioStep(key, prompt, choices, choicesFn, resolved, orientation,
                        customPlaceholder, shouldRun);
            }
        }
    }

    record MultiSelectStep(
            String key,
            String prompt,
            List<Choice> choices,
            Set<String> defaults,
            Orientation orientation,
            String customPlaceholder,
            Predicate<Answers> shouldRun)
            implements WizardStep {

        public MultiSelectStep {
            choices = List.copyOf(choices);
            defaults = Set.copyOf(defaults);
            if (customPlaceholder == null) customPlaceholder = "";
        }

        /**
         * Whether this step appends a free-form "enter your own" checkbox row
         * after the fixed choices (vertical lists only). The row counts as
         * checked while it holds text; that text is appended to the selected
         * list verbatim on commit.
         */
        public boolean hasCustomOption() {
            return !customPlaceholder.isEmpty();
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
            private String customPlaceholder = "";
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

            public Builder choice(String id, String label, String hint) {
                this.choices.add(new Choice(id, label, hint));
                return this;
            }

            public Builder choice(String id, String label, Function<Answers, String> hintFn) {
                this.choices.add(new Choice(id, label, hintFn));
                return this;
            }

            /** Add a pre-built {@link Choice} — used for rich-label rows. */
            public Builder choice(Choice c) {
                this.choices.add(c);
                return this;
            }

            public Builder defaults(Set<String> defaults) {
                this.defaults.clear();
                this.defaults.addAll(defaults);
                return this;
            }

            /**
             * Append a free-form text checkbox row after the fixed choices
             * (vertical lists only). {@code placeholder} is shown as dim
             * example text the user can type over. When non-blank on commit,
             * the typed text is appended to the selected list verbatim.
             */
            public Builder customOption(String placeholder) {
                this.customPlaceholder = placeholder == null ? "" : placeholder;
                return this;
            }

            public Builder when(Predicate<Answers> shouldRun) {
                this.shouldRun = shouldRun;
                return this;
            }

            public MultiSelectStep build() {
                return new MultiSelectStep(key, prompt, choices, defaults, orientation,
                        customPlaceholder, shouldRun);
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
