package com.voxticket.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.VerificationChallenge;
import com.voxticket.persistence.entity.enums.OtpDeliveryChannel;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.VerificationChallengeRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pass 2D-B: abandoning or replacing the active procedure invalidates the
 * pending OTP. Even the correct code can never authorize the abandoned
 * action or a replacement afterwards. Mock-based so it runs without Docker.
 */
class VerificationChallengeInvalidationTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String PHONE = "+923001234567";
    private static final String ORDER_NUMBER = "ORD-10001";

    private VerificationChallengeRepository repository;
    private CustomerRepository customerRepository;
    private OtpDeliveryService otpDeliveryService;
    private ConversationSession session;
    private VerificationChallenge lastSaved;

    @BeforeEach
    void setUp() {
        repository = mock(VerificationChallengeRepository.class);
        customerRepository = mock(CustomerRepository.class);
        otpDeliveryService = mock(OtpDeliveryService.class);
        var customer = new Customer("Test", "User", "user@example.pk", PHONE);

        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(otpDeliveryService.channel()).thenReturn(OtpDeliveryChannel.DEV);
        when(repository.findFirstBySessionIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(repository.countBySessionIdAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(0L);
        when(repository.countByCustomerIdAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(0L);
        when(repository.save(any(VerificationChallenge.class))).thenAnswer(inv -> {
            lastSaved = inv.getArgument(0);
            return lastSaved;
        });
        when(repository.findById(any())).thenAnswer(inv -> Optional.ofNullable(lastSaved));

        session = ConversationSession.newSession("sess-invalidation", Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(CUSTOMER_ID, IdentityAssurance.PHONE_MATCHED, PHONE));
    }

    private VerificationService service() {
        return new VerificationService(repository, customerRepository, otpDeliveryService,
                5, 3, 0, 5, 5);
    }

    @Test
    void correctOtpFailsAfterInvalidation() {
        VerificationService service = service();
        UUID procedureId = UUID.randomUUID();

        VerificationOutcome issued = service.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, ORDER_NUMBER);
        assertThat(issued.success()).isTrue();
        String correctOtp = issued.metadata().get("devOtp");
        assertThat(correctOtp).matches("\\d{6}");
        // The mocked repository does not assign a JPA id; give the saved
        // challenge one and point the session at it, mirroring production.
        UUID challengeId = UUID.randomUUID();
        try {
            var idField = com.voxticket.persistence.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(lastSaved, challengeId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not assign challenge id", e);
        }
        session.setPendingVerificationChallengeId(challengeId);
        assertThat(lastSaved.isConsumed()).isFalse();

        // The coordinator calls this on abandonActiveProcedure / replacement.
        service.invalidatePendingChallenge(session);

        assertThat(lastSaved.isConsumed()).isTrue();
        assertThat(session.getPendingVerificationChallengeId()).isEmpty();

        // Even the correct OTP is now unusable for the abandoned action...
        VerificationResult forAbandoned = service.verify(session, correctOtp, procedureId, ORDER_NUMBER);
        assertThat(forAbandoned.verified()).isFalse();

        // ...and it cannot authorize a replacement action either.
        VerificationResult forReplacement = service.verify(session, correctOtp, UUID.randomUUID(), ORDER_NUMBER);
        assertThat(forReplacement.verified()).isFalse();
    }

    @Test
    void invalidationWithoutPendingChallengeIsHarmless() {
        VerificationService service = service();

        service.invalidatePendingChallenge(session);

        assertThat(session.getPendingVerificationChallengeId()).isEmpty();
    }
}
