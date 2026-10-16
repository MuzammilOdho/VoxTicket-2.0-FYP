package com.voxticket.admin.dto;

import java.util.List;

/**
 * P4.3: DTOs for the voice analytics aggregate summary.
 *
 * <p>All rates are 0.0-1.0 fractions over the requested window, or 0.0
 * when the denominator is empty (never NaN, never fabricated).
 */
public final class VoiceAnalyticsSummaryDtos {

    private VoiceAnalyticsSummaryDtos() {
    }

    /** One intent and its turn count within the window. */
    public record TopIntentDto(String intent, long count) {
    }

    /**
     * Aggregate voice analytics over the trailing {@code windowDays}.
     *
     * @param windowDays the requested window (echoed)
     * @param callCount voice calls started in the window
     * @param completionRate calls with outcome 'completed' / calls
     * @param bargeInRate turns aborted as superseded / voice turns
     * @param stallRate P1.4 stalled turn metrics / voice turn metrics
     * @param clarificationRate P2.4 clarification events / voice turns
     * @param handoffOfferRate escalation events / calls
     * @param avgTurnCount mean turns per voice session in the window
     * @param topIntents most frequent intents, descending, top 5
     */
    public record VoiceAnalyticsSummaryDto(
            int windowDays,
            long callCount,
            double completionRate,
            double bargeInRate,
            double stallRate,
            double clarificationRate,
            double handoffOfferRate,
            double avgTurnCount,
            List<TopIntentDto> topIntents) {
    }
}
