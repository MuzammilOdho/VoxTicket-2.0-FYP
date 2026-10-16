package com.voxticket.conversation;

import com.voxticket.procedure.ProcedureType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2.1: validates and applies {@code updateDialogueState} proposals.
 *
 * <p>The safety boundary does not move: the LLM only <em>proposes</em>
 * dialogue state through the constrained tool schema; this class decides
 * what actually lands in the {@link DialogueState}:
 * <ul>
 *   <li>slot names must be in the {@link DialogueSlotNames} vocabulary -
 *       unknown slots are rejected and logged;</li>
 *   <li>values must match the slot's format rule (order-number regex,
 *       known return-reason enum, ...);</li>
 *   <li>confidence is clamped to [0,1]; LLM-proposed {@code INFERRED} is
 *       capped at 0.7 so an inference can never silently drive a mutation;</li>
 *   <li>provenance: the model may never claim {@code TOOL_READ} (downgraded
 *       to {@code INFERRED}); a {@code USER_SAID} claim needs a matching
 *       span in the heard transcript, else it is downgraded to
 *       {@code INFERRED}; an existing {@code TOOL_READ} value always wins
 *       over a proposal;</li>
 *   <li>interruption classifications are re-validated by
 *       {@link InterruptionClassifier} - disagreements fall back to
 *       {@code NEW_INTENT}.</li>
 * </ul>
 *
 * <p>Every applied or rejected proposal returns a customer-safe message the
 * model can act on, including clarification copy (P2.4) when the resulting
 * slot is too shaky to act on.
 */
public final class DialogueStateUpdater {

    private static final Logger log = LoggerFactory.getLogger(DialogueStateUpdater.class);

    private static final double DEFAULT_USER_SAID_CONFIDENCE = 0.85;
    private static final double DEFAULT_INFERRED_CONFIDENCE = 0.6;

    /**
     * Queues a procedure request on the pending stack. Implemented by
     * {@code ProcedureCoordinator}, which owns order resolution - the
     * updater never resolves business references itself.
     */
    @FunctionalInterface
    public interface PendingRequestQueuer {
        /** Queues the request; returns the customer-safe result message. */
        String queue(ConversationSession session, ProcedureType type, String orderNumber);
    }

    private DialogueStateUpdater() {
    }

    public static DialogueStateUpdateResult apply(ConversationSession session,
                                                 DialogueStateProposal proposal,
                                                 PendingRequestQueuer queuer) {
        DialogueState state = session.getDialogueState();
        String heard = heardTranscript(session);
        return switch (proposal.action()) {
            case SET_SLOT -> applySetSlot(session, state, proposal, heard, false);
            case CONFIRM_SLOT -> applyConfirmSlot(session, state, proposal);
            case MARK_CORRECTION -> applySetSlot(session, state, proposal, heard, true);
            case PUSH_REQUEST -> applyPushRequest(session, state, proposal, queuer);
            case POP_REQUEST -> applyPopRequest(session, state);
            case CLASSIFY_INTERRUPTION -> applyClassification(session, state, proposal, heard);
        };
    }

    // ---- slots ----

    private static DialogueStateUpdateResult applySetSlot(ConversationSession session, DialogueState state,
                                                          DialogueStateProposal proposal, String heard,
                                                          boolean isCorrection) {
        String slotName = proposal.slotName();
        if (!DialogueSlotNames.isKnown(slotName)) {
            log.warn("event=dialogue_state_unknown_slot slot={}", slotName);
            return DialogueStateUpdateResult.rejected(
                    "Unknown slot '" + slotName + "'. Known slots: order_reference, ticket_reference, "
                            + "item_reference, problem_description, return_reason, return_quantity.");
        }
        String value = proposal.slotValue() == null ? "" : proposal.slotValue().trim();
        Optional<String> formatProblem = DialogueSlotNames.formatProblem(slotName, value);
        if (formatProblem.isPresent()) {
            log.warn("event=dialogue_state_bad_value slot={} problem={}", slotName, formatProblem.get());
            return DialogueStateUpdateResult.rejected(
                    "Rejected " + slotName + ": value '" + value + "' is not a valid "
                            + DialogueSlotNames.describe(slotName) + " (" + formatProblem.get() + ").");
        }

        SlotProvenance provenance = parseProvenance(proposal.provenance());
        StringBuilder notes = new StringBuilder();
        if ("TOOL_READ".equalsIgnoreCase(proposal.provenance())) {
            // The model must never claim tool-read provenance - only
            // deterministic code sets it after an authoritative lookup.
            provenance = SlotProvenance.INFERRED;
            notes.append("Provenance TOOL_READ is reserved for system lookups; treated as INFERRED. ");
            log.warn("event=dialogue_state_provenance_downgrade slot={} claimed=TOOL_READ", slotName);
        }
        if (provenance == SlotProvenance.USER_SAID && !hasHeardSpan(heard, slotName, value)) {
            provenance = SlotProvenance.INFERRED;
            notes.append("No matching span in what was heard; treated as INFERRED. ");
            log.info("event=dialogue_state_provenance_downgrade slot={} claimed=USER_SAID", slotName);
        }

        double confidence = clampConfidence(proposal.confidence(), provenance);
        if (proposal.confidence() != null
                && provenance == SlotProvenance.INFERRED
                && proposal.confidence() > ClarificationPolicy.INFERRED_CONFIDENCE_CAP) {
            notes.append("Confidence capped at 0.7 for INFERRED. ");
        }

        Optional<Slot> existing = state.getSlot(slotName);
        if (isCorrection) {
            // A correction is the caller's explicit new statement about the
            // slot - it supersedes whatever was recorded (including a
            // tool-read value for the OLD value). The new value still goes
            // through format, provenance, and policy checks below.
            if (existing.isEmpty()) {
                return DialogueStateUpdateResult.rejected(
                        "Nothing to correct: no " + slotName + " is recorded yet.");
            }
            state.recordCorrection(
                    new CorrectionAntecedent(slotName, existing.get().value(), session.getTurnCount()));
            notes.append("Recorded the correction (was '").append(existing.get().value()).append("'). ");
        } else if (existing.isPresent() && !proposalWins(existing.get().provenance(), provenance)) {
            return DialogueStateUpdateResult.applied("Kept the existing " + slotName + " ("
                    + existing.get().provenance() + " outranks the proposal); nothing changed.");
        }

        Slot slot = new Slot(slotName, value, confidence, provenance, session.getTurnCount());
        state.setSlot(slot);
        int strikes = state.recordSlotConfidence(slotName, confidence);

        StringBuilder message = new StringBuilder();
        if (isCorrection) {
            message.append("Corrected ").append(slotName).append(" to '").append(value).append("'. ");
        } else {
            message.append("Recorded ").append(slotName).append("='").append(value)
                    .append("' (confidence ").append(String.format(Locale.ROOT, "%.2f", confidence))
                    .append(", ").append(provenance).append("). ");
        }
        message.append(notes);

        // P2.4: policy consequences of the resulting slot.
        ClarificationPolicy.Decision decision = ClarificationPolicy.decide(slot);
        if (decision == ClarificationPolicy.Decision.TARGETED_CLARIFICATION) {
            message.append("Ask the caller: \"").append(ClarificationPolicy.renderTargetedClarification(slot)).append("\" ");
            if (DialogueSlotNames.isMutationTarget(slotName)) {
                String owed = slotName + "=" + value;
                boolean alreadyOwed = state.getObligations().stream()
                        .anyMatch(o -> o.type() == ObligationType.CONFIRM_BEFORE_MUTATE && o.detail().equals(owed));
                if (!alreadyOwed) {
                    state.addObligation(ObligationType.CONFIRM_BEFORE_MUTATE, owed, session.getTurnCount());
                }
                message.append("A value-naming confirmation is required before mutating on this. ");
            }
        } else if (decision == ClarificationPolicy.Decision.OPEN_CLARIFICATION) {
            message.append("Too uncertain to act on - ask the caller: \"")
                    .append(ClarificationPolicy.renderOpenClarification(slot)).append("\" ");
            message.append("Do not use this value for any mutation or tool call until confirmed. ");
        }
        if (strikes >= DialogueState.LOW_CONFIDENCE_STRIKE_LIMIT && decision != ClarificationPolicy.Decision.ACT_DIRECTLY) {
            message.append(ClarificationPolicy.renderHandoffOffer(slotName)).append(" ");
        }
        return DialogueStateUpdateResult.applied(message.toString().trim());
    }

    private static DialogueStateUpdateResult applyConfirmSlot(ConversationSession session, DialogueState state,
                                                              DialogueStateProposal proposal) {
        String slotName = proposal.slotName();
        Optional<Slot> existing = state.getSlot(slotName);
        if (existing.isEmpty() || !DialogueSlotNames.isKnown(slotName)) {
            return DialogueStateUpdateResult.rejected(
                    "Nothing to confirm: no " + slotName + " is recorded yet.");
        }
        Slot confirmed = new Slot(slotName, existing.get().value(), 1.0,
                SlotProvenance.USER_SAID, session.getTurnCount());
        state.setSlot(confirmed);
        state.resetSlotStrikes(slotName);
        state.dischargeObligation(ObligationType.CONFIRM_BEFORE_MUTATE, slotName + "=");
        return DialogueStateUpdateResult.applied(
                "Confirmed " + slotName + "='" + confirmed.value() + "' - it is now certain and usable.");
    }

    // ---- pending-request stack ops via the tool ----

    private static DialogueStateUpdateResult applyPushRequest(ConversationSession session, DialogueState state,
                                                             DialogueStateProposal proposal,
                                                             PendingRequestQueuer queuer) {
        if (proposal.procedureType() == null || proposal.procedureType().isBlank()) {
            return DialogueStateUpdateResult.rejected(
                    "PUSH_REQUEST needs procedureType: CANCELLATION, RETURN, or CLAIM.");
        }
        ProcedureType type;
        try {
            type = ProcedureType.valueOf(proposal.procedureType().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return DialogueStateUpdateResult.rejected(
                    "Unknown procedure type '" + proposal.procedureType() + "'. Use CANCELLATION, RETURN, or CLAIM.");
        }
        Optional<String> formatProblem =
                DialogueSlotNames.formatProblem(DialogueSlotNames.ORDER_REFERENCE, proposal.orderNumber());
        if (formatProblem.isPresent()) {
            return DialogueStateUpdateResult.rejected(
                    "Rejected: '" + proposal.orderNumber() + "' is not a valid order number.");
        }
        if (queuer == null) {
            return DialogueStateUpdateResult.rejected("Push is not available in this context.");
        }
        String message = queuer.queue(session, type, proposal.orderNumber().trim());
        return DialogueStateUpdateResult.applied(message);
    }

    private static DialogueStateUpdateResult applyPopRequest(ConversationSession session, DialogueState state) {
        return state.popPendingRequest()
                .map(intent -> {
                    state.dischargeObligation(ObligationType.RESUME_DEFERRED, intent.orderNumber());
                    return DialogueStateUpdateResult.applied(
                            "Withdrew the queued " + describeIntent(intent) + " - nothing was executed.");
                })
                .orElseGet(() -> DialogueStateUpdateResult.rejected("There's no queued request to withdraw."));
    }

    // ---- interruption classification ----

    private static DialogueStateUpdateResult applyClassification(ConversationSession session, DialogueState state,
                                                                DialogueStateProposal proposal, String heard) {
        if (proposal.interruptionClass() == null || proposal.interruptionClass().isBlank()) {
            return DialogueStateUpdateResult.rejected(
                    "CLASSIFY_INTERRUPTION needs interruptionClass: CORRECTION, CONTINUATION, NEW_INTENT, or WITHDRAWAL.");
        }
        InterruptionClass proposed;
        try {
            proposed = InterruptionClass.valueOf(proposal.interruptionClass().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return DialogueStateUpdateResult.rejected(
                    "Unknown interruption class '" + proposal.interruptionClass()
                            + "'. Use CORRECTION, CONTINUATION, NEW_INTENT, or WITHDRAWAL.");
        }
        InterruptionClass validated =
                InterruptionClassifier.validateProposal(proposed, proposal.slotName(), state);
        state.clearLastInterruptionSignal();
        if (validated != proposed) {
            log.info("event=interruption_classification_fallback proposed={} fallback=NEW_INTENT", proposed);
        }
        return switch (validated) {
            case CORRECTION -> {
                if (proposal.slotValue() == null || proposal.slotValue().isBlank()) {
                    yield DialogueStateUpdateResult.rejected(
                            "CORRECTION needs the corrected value (slotValue).");
                }
                // Reuse the slot path so format, provenance, and policy
                // checks apply identically to corrections.
                DialogueStateProposal correction = new DialogueStateProposal(
                        DialogueStateAction.MARK_CORRECTION, proposal.slotName(), proposal.slotValue(),
                        proposal.confidence(), proposal.provenance(), null, null, null);
                yield applySetSlot(session, state, correction, heard, true);
            }
            case CONTINUATION -> {
                if (proposal.slotName() != null && proposal.slotValue() != null
                        && !proposal.slotValue().isBlank()) {
                    DialogueStateProposal merge = DialogueStateProposal.setSlot(
                            proposal.slotName(), proposal.slotValue(), proposal.confidence(), proposal.provenance());
                    DialogueStateUpdateResult merged = applySetSlot(session, state, merge, heard, false);
                    yield DialogueStateUpdateResult.applied(
                            "Merged as a continuation. " + merged.message());
                }
                yield DialogueStateUpdateResult.applied(
                        "Noted as a continuation of the previous request - keep going with what was already established.");
            }
            case WITHDRAWAL -> applyPopRequest(session, state);
            case NEW_INTENT -> {
                if (InterruptionClassifier.isQuestion(lastUserText(session))
                        && (state.hasPendingRequests() || session.getActiveProcedure().isPresent())) {
                    state.addObligation(ObligationType.ANSWER_QUESTION,
                            "answer the caller's fresh question first", session.getTurnCount());
                }
                yield DialogueStateUpdateResult.applied(
                        "Treated as a new intent - start fresh; the previous request stays as it was.");
            }
        };
    }

    // ---- helpers ----

    private static SlotProvenance parseProvenance(String provenance) {
        if (provenance == null || provenance.isBlank()) {
            return SlotProvenance.USER_SAID;
        }
        try {
            return SlotProvenance.valueOf(provenance.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SlotProvenance.INFERRED;
        }
    }

    private static double clampConfidence(Double proposed, SlotProvenance provenance) {
        double confidence = proposed == null
                ? (provenance == SlotProvenance.INFERRED ? DEFAULT_INFERRED_CONFIDENCE : DEFAULT_USER_SAID_CONFIDENCE)
                : proposed;
        if (Double.isNaN(confidence)) {
            confidence = DEFAULT_INFERRED_CONFIDENCE;
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        if (provenance == SlotProvenance.INFERRED) {
            confidence = Math.min(confidence, ClarificationPolicy.INFERRED_CONFIDENCE_CAP);
        }
        return confidence;
    }

    /** Precedence: TOOL_READ > USER_SAID > INFERRED. A proposal wins ties (newer turn). */
    private static boolean proposalWins(SlotProvenance existing, SlotProvenance proposed) {
        return rank(proposed) >= rank(existing);
    }

    private static int rank(SlotProvenance provenance) {
        return switch (provenance) {
            case TOOL_READ -> 3;
            case USER_SAID -> 2;
            case INFERRED -> 1;
        };
    }

    /**
     * The heard transcript: what the caller actually said, from the P1.3
     * repaired history (user messages only - never unsent generations).
     */
    static String heardTranscript(ConversationSession session) {
        return session.getRecentMessages().stream()
                .filter(m -> m.role() == MessageRole.USER)
                .map(ConversationMessage::text)
                .collect(Collectors.joining("\n"));
    }

    private static String lastUserText(ConversationSession session) {
        List<ConversationMessage> messages = session.getRecentMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage m = messages.get(i);
            if (m.role() == MessageRole.USER) {
                return m.text();
            }
        }
        return "";
    }

    /**
     * A USER_SAID claim needs a matching span in the heard transcript -
     * alphanumeric-core containment (so "ord 10001" matches "ORD-10001"),
     * plus a digit-core fallback for reference slots (so "order 10001"
     * matches "ORD-10001").
     */
    private static boolean hasHeardSpan(String heard, String slotName, String value) {
        String heardCore = InterruptionClassifier.alphanumericCore(heard);
        String valueCore = InterruptionClassifier.alphanumericCore(value);
        if (!valueCore.isEmpty() && heardCore.contains(valueCore)) {
            return true;
        }
        if (DialogueSlotNames.ORDER_REFERENCE.equals(slotName)
                || DialogueSlotNames.TICKET_REFERENCE.equals(slotName)) {
            String heardDigits = heardCore.replaceAll("[^0-9]", "");
            String valueDigits = valueCore.replaceAll("[^0-9]", "");
            return !valueDigits.isEmpty() && heardDigits.contains(valueDigits);
        }
        return false;
    }

    private static String describeIntent(com.voxticket.procedure.DeferredProcedureIntent intent) {
        StringBuilder sb = new StringBuilder(intent.type().name().toLowerCase(Locale.ROOT));
        sb.append(" of order ").append(intent.orderNumber());
        if (intent.itemDisplayName() != null) {
            sb.append(" (").append(intent.itemDisplayName()).append(")");
        }
        return sb.toString();
    }
}
