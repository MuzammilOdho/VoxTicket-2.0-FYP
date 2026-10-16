package com.voxticket.eval;

import java.util.function.Supplier;

/**
 * P3.3: runs eval scenarios. Each scenario gets a fresh session (via the
 * driver factory); each step records the user text, performs the drive
 * actions, snapshots the {@link ObservedOutcome}, and runs the assertions.
 * A failing assertion throws AssertionError with the scenario and step
 * identified, so a red test names the exact regression.
 */
public final class ScenarioRunner {

    private final Supplier<ScenarioDriver> driverFactory;

    public ScenarioRunner(Supplier<ScenarioDriver> driverFactory) {
        this.driverFactory = driverFactory;
    }

    public void run(EvalScenario scenario) {
        ScenarioDriver driver = driverFactory.get();
        int stepIndex = 0;
        for (EvalStep step : scenario.steps()) {
            stepIndex++;
            driver.userSays(step.userText());
            step.drive().accept(driver);
            ObservedOutcome observed = driver.observe();
            try {
                step.expect().accept(observed);
            } catch (AssertionError e) {
                throw new AssertionError(
                        "eval scenario '" + scenario.name() + "' step " + stepIndex
                                + " (user: \"" + step.userText() + "\") failed: " + e.getMessage(), e);
            }
        }
    }
}
