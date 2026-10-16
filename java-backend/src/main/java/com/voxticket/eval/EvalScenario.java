package com.voxticket.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * P3.3: a named scripted conversation for the eval harness.
 *
 * <p>A scenario is a list of {@link EvalStep}s run in order against a fresh
 * session by {@link ScenarioRunner}. Each step records the user text, drives
 * the deterministic core (procedure layer + dialogue state), and asserts the
 * observed outcome. Scenarios are plain JUnit tests - no Docker, no model,
 * no network.
 */
public record EvalScenario(String name, List<EvalStep> steps) {

    public EvalScenario {
        steps = List.copyOf(steps);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final List<EvalStep> steps = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder step(String userText,
                            Consumer<ScenarioDriver> drive,
                            Consumer<ObservedOutcome> expect) {
            steps.add(new EvalStep(userText, drive, expect));
            return this;
        }

        public EvalScenario build() {
            return new EvalScenario(name, steps);
        }
    }
}
