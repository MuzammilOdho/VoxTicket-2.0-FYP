package com.voxticket.eval;

import java.util.function.Consumer;

/**
 * P3.3: one scripted turn of an eval scenario.
 *
 * <p>The {@code drive} lambda performs the turn's system-side actions
 * through the {@link ScenarioDriver} (procedure calls, dialogue-state
 * updates, interruption classification - the deterministic core the real
 * pipeline executes once the LLM has proposed an intent). The
 * {@code expect} lambda then asserts on the {@link ObservedOutcome} the
 * runner snapshots. Scenarios stay honest: they never assert on
 * model-generated text, only on procedure transitions, dialogue state,
 * and rendered deterministic copy.
 *
 * @param userText what the caller said this turn (recorded on the session)
 * @param drive    the deterministic actions this turn performs
 * @param expect   assertions on the observed outcome
 */
public record EvalStep(
        String userText,
        Consumer<ScenarioDriver> drive,
        Consumer<ObservedOutcome> expect) {
}
