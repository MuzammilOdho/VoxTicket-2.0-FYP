package com.voxticket.eval;

import java.util.List;
import java.util.Map;

/**
 * P3.3: everything a scenario step may assert on, snapshotted by the
 * {@link ScenarioDriver} after the step's drive phase.
 *
 * <p>All values are deterministic system state - never model text:
 * procedure transitions, dialogue-state contents, rendered deterministic
 * copy, and the mutations the step executed (counted via the driver's
 * mutation listener, so "no mutation" is assertable).
 *
 * @param activeProcedureType   the live procedure type, or null when none
 * @param activeProcedureStatus the live procedure status, or null when none
 * @param startedProcedures     procedure types started so far, in order
 * @param completedProcedures   procedure types completed so far, in order
 * @param pendingStackDepth     the P2.2 pending-request stack depth
 * @param slots                 dialogue-state slot name to value
 * @param lastOutcomeCode       the last procedure outcome code this step
 * @param mutationsExecuted     mutating service calls this step executed
 * @param renderedReply         deterministic rendered copy, or null
 * @param correctionCount       recorded correction antecedents
 * @param slotStrikes           low-confidence strikes for the asserted slot
 */
public record ObservedOutcome(
        String activeProcedureType,
        String activeProcedureStatus,
        List<String> startedProcedures,
        List<String> completedProcedures,
        int pendingStackDepth,
        Map<String, String> slots,
        String lastOutcomeCode,
        List<String> mutationsExecuted,
        String renderedReply,
        int correctionCount,
        int slotStrikes) {
}
