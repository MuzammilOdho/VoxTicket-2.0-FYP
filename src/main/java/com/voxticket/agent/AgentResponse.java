package com.voxticket.agent;

/**
 * The result of one {@link SupportAgent} turn: the text for the customer
 * plus how the turn ended. The outcome drives observability - an agent
 * failure recovered with a safe fallback is never recorded as a normal
 * turn.
 */
public record AgentResponse(String text, AgentResponse.Outcome outcome) {

    public enum Outcome {
        /** The model returned a usable response. */
        SUCCESS,
        /** The model returned blank; a safe re-prompt fallback was used. */
        BLANK_FALLBACK,
        /** The model call threw; the safe generic fallback was used. */
        MODEL_ERROR;

        /** Label for {@code voxticket.llm.call.duration}. */
        public String toLlmMetricLabel() {
            return switch (this) {
                case SUCCESS -> "success";
                case BLANK_FALLBACK -> "blank_fallback";
                case MODEL_ERROR -> "model_error";
            };
        }

        /**
         * Label for {@code voxticket.turn.duration}. {@code successLabel} is
         * the label the turn would have carried had the agent succeeded
         * ("normal", "verification_unclear", ...); a recovered failure keeps
         * its own label instead of masquerading as a normal turn.
         */
        public String toTurnLabel(String successLabel) {
            return switch (this) {
                case SUCCESS -> successLabel;
                case BLANK_FALLBACK -> "blank_fallback";
                case MODEL_ERROR -> "agent_error_recovered";
            };
        }
    }
}
