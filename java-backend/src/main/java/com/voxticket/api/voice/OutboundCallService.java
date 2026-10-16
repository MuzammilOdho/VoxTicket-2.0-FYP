package com.voxticket.api.voice;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.OutboundCall;
import com.voxticket.persistence.entity.OutboundConsent;
import com.voxticket.persistence.entity.enums.OutboundCallStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OutboundCallRepository;
import com.voxticket.persistence.repository.OutboundConsentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P4.1: outbound-call trigger business logic.
 *
 * <p>The flow is deliberately narrow:
 * <ol>
 *   <li>Validate the E.164 phone (defense in depth behind the DTO's
 *       Bean Validation - the service never trusts its caller).</li>
 *   <li>Resolve the phone to a customer; consent is per customer.</li>
 *   <li>Refuse with {@link OutboundConsentRequiredException} (403) unless
 *       the customer has an explicit consent row with {@code consented}
 *       true. No row - or a revoked row - is "not consented".</li>
 *   <li>Replay an existing queued call when the idempotency key was
 *       already used (safe trigger retries).</li>
 *   <li>Otherwise persist the call {@link OutboundCallStatus#QUEUED} and
 *       return its {@code callId}.</li>
 * </ol>
 *
 * <p>Java stops there. The Python worker polls the {@code QUEUED} queue
 * (see {@link #pendingCalls}) and performs the actual dial-out, reporting
 * progress back through {@link #updateStatus} - Java never fabricates dial
 * progress, and status transitions are guarded so a worker can never
 * resurrect a terminal call.
 */
@Service
public class OutboundCallService {

    private static final Logger log = LoggerFactory.getLogger(OutboundCallService.class);

    /** Same E.164 rule as the inbound voice path. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private final CustomerRepository customerRepository;
    private final OutboundConsentRepository consentRepository;
    private final OutboundCallRepository callRepository;

    public OutboundCallService(CustomerRepository customerRepository,
            OutboundConsentRepository consentRepository,
            OutboundCallRepository callRepository) {
        this.customerRepository = customerRepository;
        this.consentRepository = consentRepository;
        this.callRepository = callRepository;
    }

    /**
     * Triggers one outbound call.
     *
     * @param customerPhone E.164 phone of the customer to call
     * @param reason closed reason enum from the request
     * @param idempotencyKey optional caller key; blank is treated as absent
     * @return the queued (or replayed) call
     * @throws InvalidOutboundRequestException on malformed input (400)
     * @throws OutboundCustomerNotFoundException on unknown phone (404)
     * @throws OutboundConsentRequiredException when not consented (403)
     */
    @Transactional
    public OutboundCall triggerOutbound(String customerPhone,
            com.voxticket.persistence.entity.enums.OutboundReason reason, String idempotencyKey) {
        String phone = validatePhone(customerPhone);
        if (reason == null) {
            throw new InvalidOutboundRequestException("reason is required");
        }
        String key = (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey.trim();

        Customer customer = customerRepository.findByPhone(phone)
                .orElseThrow(() -> new OutboundCustomerNotFoundException(phone));

        boolean consented = consentRepository.findByCustomerId(customer.getId())
                .map(OutboundConsent::isConsented)
                .orElse(false);
        if (!consented) {
            log.info("event=outbound_trigger_refused phone={} reason=no_consent", phone);
            throw new OutboundConsentRequiredException(phone);
        }

        if (key != null) {
            Optional<OutboundCall> replay = callRepository.findByIdempotencyKey(key);
            if (replay.isPresent()) {
                log.info("event=outbound_trigger_replayed callId={}", replay.get().getCallId());
                return replay.get();
            }
        }

        OutboundCall call = new OutboundCall(
                UUID.randomUUID(), phone, reason, OutboundCallStatus.QUEUED, key, Instant.now());
        callRepository.save(call);
        log.info("event=outbound_trigger_queued callId={} reason={}", call.getCallId(), reason);
        return call;
    }

    /**
     * Reads the status of a previously triggered call.
     *
     * @throws InvalidOutboundRequestException (400) when the call id is unknown
     */
    @Transactional(readOnly = true)
    public OutboundCall callStatus(UUID callId) {
        if (callId == null) {
            throw new InvalidOutboundRequestException("callId is required");
        }
        return callRepository.findByCallId(callId)
                .orElseThrow(() -> new InvalidOutboundRequestException("unknown callId"));
    }

    /**
     * P4.1: the worker's poll queue - {@code QUEUED} calls, oldest first.
     *
     * <p>The limit is clamped to [1, 50]: the poller asks for small batches
     * and a runaway client cannot dump the whole table.
     */
    @Transactional(readOnly = true)
    public List<OutboundCall> pendingCalls(int limit) {
        int clamped = Math.max(1, Math.min(50, limit));
        List<OutboundCall> queued =
                callRepository.findByStatusOrderByCreatedAtAsc(OutboundCallStatus.QUEUED);
        return queued.size() <= clamped ? queued : queued.subList(0, clamped);
    }

    /**
     * P4.1: worker-reported dial progress.
     *
     * <p>Only forward transitions are legal: {@code QUEUED->DIALING},
     * {@code DIALING->COMPLETED}, {@code DIALING->FAILED}, and
     * {@code QUEUED->FAILED} (dial never attempted, e.g. the worker was
     * misconfigured). Terminal states ({@code COMPLETED}, {@code FAILED})
     * never change again - a stale or confused worker can never resurrect a
     * finished call.
     *
     * @throws InvalidOutboundRequestException (400) when the call id is
     *         unknown, the target status is null, or the transition is
     *         illegal
     */
    @Transactional
    public OutboundCall updateStatus(UUID callId, OutboundCallStatus status) {
        if (callId == null) {
            throw new InvalidOutboundRequestException("callId is required");
        }
        if (status == null) {
            throw new InvalidOutboundRequestException("status is required");
        }
        OutboundCall call = callRepository.findByCallId(callId)
                .orElseThrow(() -> new InvalidOutboundRequestException("unknown callId"));
        if (!isLegalTransition(call.getStatus(), status)) {
            log.warn("event=outbound_status_transition_rejected callId={} from={} to={}",
                    callId, call.getStatus(), status);
            throw new InvalidOutboundRequestException(
                    "illegal status transition: " + call.getStatus() + " -> " + status);
        }
        call.setStatus(status);
        OutboundCall saved = callRepository.save(call);
        log.info("event=outbound_status_updated callId={} status={}", callId, status);
        return saved;
    }

    /**
     * Sets (or revokes) a customer's outbound-call consent. Creates the row
     * on first use; updates it afterwards.
     */
    @Transactional
    public OutboundConsent setConsent(UUID customerId, boolean consented) {
        if (customerId == null) {
            throw new InvalidOutboundRequestException("customerId is required");
        }
        OutboundConsent consent = consentRepository.findByCustomerId(customerId)
                .orElseGet(() -> new OutboundConsent(customerId, consented, Instant.now()));
        consent.setConsented(consented);
        consent.setUpdatedAt(Instant.now());
        return consentRepository.save(consent);
    }

    /**
     * The P4.1 status transition matrix. The worker moves a call forward
     * exactly once per leg: pick up ({@code QUEUED->DIALING}), then report
     * the outcome ({@code DIALING->COMPLETED} / {@code DIALING->FAILED}).
     * {@code QUEUED->FAILED} covers "dial never attempted" (e.g. the
     * worker was misconfigured). Nothing leaves a terminal state.
     */
    private static boolean isLegalTransition(OutboundCallStatus from, OutboundCallStatus to) {
        return switch (from) {
            case QUEUED -> to == OutboundCallStatus.DIALING || to == OutboundCallStatus.FAILED;
            case DIALING -> to == OutboundCallStatus.COMPLETED || to == OutboundCallStatus.FAILED;
            case COMPLETED, FAILED -> false;
        };
    }

    private static String validatePhone(String customerPhone) {
        if (customerPhone == null || !E164.matcher(customerPhone.trim()).matches()) {
            throw new InvalidOutboundRequestException(
                    "customerPhone must be in E.164 format, e.g. +923001234567");
        }
        return customerPhone.trim();
    }
}
