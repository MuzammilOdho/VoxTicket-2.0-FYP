package com.voxticket.api.voice;

import com.voxticket.persistence.entity.enums.OutboundCallStatus;
import com.voxticket.persistence.entity.enums.OutboundReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * P4.1: request/response DTOs for the outbound-call trigger API.
 *
 * <p>Phone numbers use the same E.164 rule as the inbound voice path
 * ({@link VoiceTurnRequest}): {@code +} followed by 7-15 digits. The
 * reason is a closed enum - P4 has no free-text campaign reasons.
 */
public final class OutboundDtos {

    private OutboundDtos() {
    }

    /**
     * Trigger one outbound call. The {@code idempotencyKey} is optional but
     * recommended: a retried trigger carrying the same key returns the
     * original {@code callId} instead of queueing a duplicate call.
     */
    public record OutboundTriggerRequest(
            @NotBlank(message = "customerPhone is required")
            @Pattern(regexp = "^\\+[1-9]\\d{6,14}$",
                    message = "customerPhone must be in E.164 format, e.g. +923001234567")
            String customerPhone,
            @NotNull(message = "reason is required")
            OutboundReason reason,
            String idempotencyKey) {
    }

    /** Set (or revoke) a customer's outbound-call consent. */
    public record OutboundConsentRequest(
            @NotNull(message = "customerId is required")
            UUID customerId,
            boolean consented) {
    }

    /** The persisted call, as the trigger response and the status response. */
    public record OutboundCallResponse(
            UUID callId,
            String customerPhone,
            OutboundReason reason,
            String status) {
    }

    /**
     * Worker-reported dial progress. Only forward transitions are accepted
     * (see the service's transition matrix); anything else is a 400.
     */
    public record OutboundStatusUpdateRequest(
            @NotNull(message = "status is required")
            OutboundCallStatus status) {
    }

    /** The stored consent flag, as the consent response. */
    public record OutboundConsentResponse(
            UUID customerId,
            boolean consented) {
    }
}
