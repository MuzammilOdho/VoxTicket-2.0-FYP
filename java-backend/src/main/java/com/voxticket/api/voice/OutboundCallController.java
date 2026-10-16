package com.voxticket.api.voice;

import com.voxticket.api.voice.OutboundDtos.OutboundCallResponse;
import com.voxticket.api.voice.OutboundDtos.OutboundConsentRequest;
import com.voxticket.api.voice.OutboundDtos.OutboundConsentResponse;
import com.voxticket.api.voice.OutboundDtos.OutboundStatusUpdateRequest;
import com.voxticket.api.voice.OutboundDtos.OutboundTriggerRequest;
import com.voxticket.persistence.entity.OutboundCall;
import com.voxticket.persistence.entity.OutboundConsent;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P4.1: the outbound-call trigger API.
 *
 * <p>Intentionally thin, like {@link VoiceTurnController}: validation and
 * consent live in {@link OutboundCallService}. Authorization is the SAME
 * as the other voice endpoints (P1.5): {@code AdminSecurityConfig.permitAll}
 * covers {@code /api/v1/voice/outbound} and {@code /api/v1/voice/outbound/**}
 * exactly like {@code /api/v1/chat}. No new auth system is introduced here.
 *
 * <p>Honesty note: Java only queues calls. The Python worker polls
 * {@code GET /outbound/pending} and performs the actual dial-out, reporting
 * progress through {@code PATCH /outbound/{callId}/status}; this API never
 * fabricates dial progress.
 */
@RestController
@RequestMapping("/api/v1/voice")
// P1.5 posture, extended to the outbound endpoints: registered in every
// profile, same public-API auth as the other voice endpoints.
public class OutboundCallController {

    private final OutboundCallService outboundCallService;

    public OutboundCallController(OutboundCallService outboundCallService) {
        this.outboundCallService = outboundCallService;
    }

    /**
     * Queue one outbound call. 403 {@code OUTBOUND_CONSENT_REQUIRED} when
     * the customer has not consented; 404 {@code OUTBOUND_CUSTOMER_NOT_FOUND}
     * for an unknown phone; 400 {@code INVALID_OUTBOUND_REQUEST} on bad
     * input.
     */
    @PostMapping("/outbound")
    public OutboundCallResponse trigger(@Valid @RequestBody OutboundTriggerRequest request) {
        OutboundCall call = outboundCallService.triggerOutbound(
                request.customerPhone(), request.reason(), request.idempotencyKey());
        return toResponse(call);
    }

    /**
     * Set (or revoke) a customer's outbound-call consent. Idempotent.
     */
    @PostMapping("/outbound/consent")
    public OutboundConsentResponse setConsent(@Valid @RequestBody OutboundConsentRequest request) {
        OutboundConsent consent = outboundCallService.setConsent(request.customerId(), request.consented());
        return new OutboundConsentResponse(consent.getCustomerId(), consent.isConsented());
    }

    /**
     * Read the status of a previously triggered call
     * ({@code QUEUED} until the worker reports progress).
     */
    @GetMapping("/outbound/{callId}")
    public ResponseEntity<OutboundCallResponse> status(@PathVariable UUID callId) {
        try {
            return ResponseEntity.ok(toResponse(outboundCallService.callStatus(callId)));
        } catch (InvalidOutboundRequestException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * P4.1: the worker's poll queue - {@code QUEUED} calls, oldest first.
     * The worker dials these and reports progress through the status
     * endpoint. Same public-API auth posture as the other outbound
     * endpoints (permitAll via {@code AdminSecurityConfig}).
     *
     * @param limit batch size, clamped to [1, 50] by the service
     */
    @GetMapping("/outbound/pending")
    public List<OutboundCallResponse> pending(@RequestParam(defaultValue = "10") int limit) {
        return outboundCallService.pendingCalls(limit).stream()
                .map(OutboundCallController::toResponse)
                .toList();
    }

    /**
     * P4.1: worker-reported dial progress. Only forward transitions are
     * legal ({@code QUEUED->DIALING}, {@code DIALING->COMPLETED},
     * {@code DIALING->FAILED}, {@code QUEUED->FAILED}); anything else is
     * a 400 {@code INVALID_OUTBOUND_REQUEST}, and unknown call ids are
     * a 404. Terminal states never change - a stale worker can never
     * resurrect a finished call.
     */
    @PatchMapping("/outbound/{callId}/status")
    public ResponseEntity<OutboundCallResponse> updateStatus(
            @PathVariable UUID callId,
            @Valid @RequestBody OutboundStatusUpdateRequest request) {
        try {
            return ResponseEntity.ok(toResponse(
                    outboundCallService.updateStatus(callId, request.status())));
        } catch (InvalidOutboundRequestException e) {
            if (e.getMessage() != null && e.getMessage().contains("unknown callId")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();
        }
    }

    private static OutboundCallResponse toResponse(OutboundCall call) {
        return new OutboundCallResponse(
                call.getCallId(),
                call.getCustomerPhone(),
                call.getReason(),
                call.getStatus().name());
    }
}
