package com.voxticket.api.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.OutboundCall;
import com.voxticket.persistence.entity.OutboundConsent;
import com.voxticket.persistence.entity.enums.OutboundCallStatus;
import com.voxticket.persistence.entity.enums.OutboundReason;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OutboundCallRepository;
import com.voxticket.persistence.repository.OutboundConsentRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * P4.1: outbound trigger validation, consent gating, idempotent replay,
 * and consent toggling. Plain unit tests over mocked repositories - no
 * Spring context, no database.
 */
class OutboundCallServiceTest {

    private CustomerRepository customerRepository;
    private OutboundConsentRepository consentRepository;
    private OutboundCallRepository callRepository;
    private OutboundCallService service;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customerRepository = mock(CustomerRepository.class);
        consentRepository = mock(OutboundConsentRepository.class);
        callRepository = mock(OutboundCallRepository.class);
        service = new OutboundCallService(customerRepository, consentRepository, callRepository);

        customer = new Customer("Test", "User", "test@example.com", "+923001234567");
        when(customerRepository.findByPhone("+923001234567")).thenReturn(Optional.of(customer));
    }

    @Test
    void badE164IsRejected() {
        assertThatThrownBy(() -> service.triggerOutbound("not-a-phone", OutboundReason.FOLLOW_UP, null))
                .isInstanceOf(InvalidOutboundRequestException.class);
        assertThatThrownBy(() -> service.triggerOutbound(null, OutboundReason.FOLLOW_UP, null))
                .isInstanceOf(InvalidOutboundRequestException.class);
        verify(callRepository, never()).save(any());
    }

    @Test
    void unknownPhoneIs404() {
        when(customerRepository.findByPhone("+999000000000")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.triggerOutbound("+999000000000", OutboundReason.FOLLOW_UP, null))
                .isInstanceOf(OutboundCustomerNotFoundException.class);
    }

    @Test
    void noConsentRowMeans403WithStableCode() {
        when(consentRepository.findByCustomerId(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.triggerOutbound("+923001234567", OutboundReason.FOLLOW_UP, null))
                .isInstanceOf(OutboundConsentRequiredException.class)
                .hasMessageContaining("OUTBOUND_CONSENT_REQUIRED");
        // The exception itself carries the 403 mapping.
        assertThat(OutboundConsentRequiredException.class.getAnnotation(ResponseStatus.class).value())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void revokedConsentMeans403() {
        when(consentRepository.findByCustomerId(any()))
                .thenReturn(Optional.of(new OutboundConsent(customer.getId(), false, Instant.now())));
        assertThatThrownBy(() -> service.triggerOutbound("+923001234567", OutboundReason.FOLLOW_UP, null))
                .isInstanceOf(OutboundConsentRequiredException.class);
    }

    @Test
    void happyPathQueuesCall() {
        when(consentRepository.findByCustomerId(any()))
                .thenReturn(Optional.of(new OutboundConsent(customer.getId(), true, Instant.now())));
        when(callRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        OutboundCall call = service.triggerOutbound("+923001234567", OutboundReason.APPOINTMENT_REMINDER, "key-1");

        assertThat(call.getCallId()).isNotNull();
        assertThat(call.getStatus()).isEqualTo(OutboundCallStatus.QUEUED);
        assertThat(call.getReason()).isEqualTo(OutboundReason.APPOINTMENT_REMINDER);
        assertThat(call.getCustomerPhone()).isEqualTo("+923001234567");
        verify(callRepository).save(any(OutboundCall.class));
    }

    @Test
    void idempotencyKeyReplaysInsteadOfDuplicating() {
        when(consentRepository.findByCustomerId(any()))
                .thenReturn(Optional.of(new OutboundConsent(customer.getId(), true, Instant.now())));
        OutboundCall existing = new OutboundCall(
                UUID.randomUUID(), "+923001234567", OutboundReason.FOLLOW_UP,
                OutboundCallStatus.QUEUED, "key-9", Instant.now());
        when(callRepository.findByIdempotencyKey("key-9")).thenReturn(Optional.of(existing));

        OutboundCall call = service.triggerOutbound("+923001234567", OutboundReason.FOLLOW_UP, "key-9");

        assertThat(call.getCallId()).isEqualTo(existing.getCallId());
        verify(callRepository, never()).save(any());
    }

    @Test
    void consentToggleCreatesThenUpdates() {
        UUID customerId = UUID.randomUUID();
        when(consentRepository.findByCustomerId(customerId)).thenReturn(Optional.empty());
        when(consentRepository.save(any(OutboundConsent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        OutboundConsent created = service.setConsent(customerId, true);
        assertThat(created.isConsented()).isTrue();

        when(consentRepository.findByCustomerId(customerId)).thenReturn(Optional.of(created));
        OutboundConsent revoked = service.setConsent(customerId, false);
        assertThat(revoked.isConsented()).isFalse();
    }

    @Test
    void unknownCallIdStatusIs400() {
        when(callRepository.findByCallId(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.callStatus(UUID.randomUUID()))
                .isInstanceOf(InvalidOutboundRequestException.class);
    }

    // ---- P4.1: worker poll queue ----

    @Test
    void pendingCallsAreOldestFirstAndLimited() {
        OutboundCall first = queuedCall(Instant.now().minusSeconds(30));
        OutboundCall second = queuedCall(Instant.now().minusSeconds(20));
        OutboundCall third = queuedCall(Instant.now().minusSeconds(10));
        // Repository contract is oldest-first; the service must not reorder.
        when(callRepository.findByStatusOrderByCreatedAtAsc(OutboundCallStatus.QUEUED))
                .thenReturn(java.util.List.of(first, second, third));

        assertThat(service.pendingCalls(10))
                .containsExactly(first, second, third);
        assertThat(service.pendingCalls(2))
                .containsExactly(first, second);
        // Limit clamps: 0 -> 1, huge -> 50 (no exception, no full-table dump).
        assertThat(service.pendingCalls(0)).containsExactly(first);
        assertThat(service.pendingCalls(10_000)).containsExactly(first, second, third);
    }

    // ---- P4.1: worker-reported status transitions ----

    @Test
    void legalTransitionsAreApplied() {
        assertTransition(OutboundCallStatus.QUEUED, OutboundCallStatus.DIALING);
        assertTransition(OutboundCallStatus.DIALING, OutboundCallStatus.COMPLETED);
        assertTransition(OutboundCallStatus.DIALING, OutboundCallStatus.FAILED);
        assertTransition(OutboundCallStatus.QUEUED, OutboundCallStatus.FAILED);
    }

    @Test
    void illegalTransitionsAreRejected() {
        // Backward moves and skips.
        assertIllegalTransition(OutboundCallStatus.DIALING, OutboundCallStatus.QUEUED);
        assertIllegalTransition(OutboundCallStatus.QUEUED, OutboundCallStatus.COMPLETED);
        assertIllegalTransition(OutboundCallStatus.QUEUED, OutboundCallStatus.QUEUED);
        // Terminal states never change - a stale worker can never resurrect.
        assertIllegalTransition(OutboundCallStatus.COMPLETED, OutboundCallStatus.FAILED);
        assertIllegalTransition(OutboundCallStatus.COMPLETED, OutboundCallStatus.QUEUED);
        assertIllegalTransition(OutboundCallStatus.FAILED, OutboundCallStatus.DIALING);
        assertIllegalTransition(OutboundCallStatus.FAILED, OutboundCallStatus.QUEUED);
    }

    @Test
    void updateStatusRejectsUnknownCallIdAndNulls() {
        when(callRepository.findByCallId(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateStatus(UUID.randomUUID(), OutboundCallStatus.DIALING))
                .isInstanceOf(InvalidOutboundRequestException.class)
                .hasMessageContaining("unknown callId");
        assertThatThrownBy(() -> service.updateStatus(null, OutboundCallStatus.DIALING))
                .isInstanceOf(InvalidOutboundRequestException.class);
        assertThatThrownBy(() -> service.updateStatus(UUID.randomUUID(), null))
                .isInstanceOf(InvalidOutboundRequestException.class);
    }

    private OutboundCall queuedCall(Instant createdAt) {
        return new OutboundCall(
                UUID.randomUUID(), "+923001234567", OutboundReason.FOLLOW_UP,
                OutboundCallStatus.QUEUED, null, createdAt);
    }

    private void assertTransition(OutboundCallStatus from, OutboundCallStatus to) {
        OutboundCall call = queuedCall(Instant.now());
        call.setStatus(from);
        when(callRepository.findByCallId(call.getCallId())).thenReturn(Optional.of(call));
        when(callRepository.save(any(OutboundCall.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        OutboundCall updated = service.updateStatus(call.getCallId(), to);

        assertThat(updated.getStatus()).isEqualTo(to);
    }

    private void assertIllegalTransition(OutboundCallStatus from, OutboundCallStatus to) {
        OutboundCall call = queuedCall(Instant.now());
        call.setStatus(from);
        when(callRepository.findByCallId(call.getCallId())).thenReturn(Optional.of(call));

        assertThatThrownBy(() -> service.updateStatus(call.getCallId(), to))
                .isInstanceOf(InvalidOutboundRequestException.class)
                .hasMessageContaining("illegal status transition");
        verify(callRepository, never()).save(any());
    }
}
