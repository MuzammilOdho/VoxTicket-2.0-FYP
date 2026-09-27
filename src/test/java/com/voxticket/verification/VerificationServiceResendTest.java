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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Only the newest OTP for a procedure may be valid. A prior unconsumed
 * challenge can exist when the user asks for a resend, or when a procedure
 * was paused (another procedure's challenge replaced the session's single
 * pending-challenge pointer) and later resumed: issuing a new code must
 * invalidate the stale one so two valid codes for the same procedure never
 * exist side by side.
 */
class VerificationServiceResendTest {

    private VerificationChallengeRepository repository;
    private VerificationService service;
    private ConversationSession session;
    private List<VerificationChallenge> issued;

    @BeforeEach
    void setUp() {
        repository = mock(VerificationChallengeRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        OtpDeliveryService otpDeliveryService = mock(OtpDeliveryService.class);
        UUID customerId = UUID.randomUUID();
        Customer customer = new Customer("Test", "User", "user@example.pk", "+923001234567");

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(otpDeliveryService.channel()).thenReturn(OtpDeliveryChannel.DEV);
        when(repository.findFirstBySessionIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(repository.countBySessionIdAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(0L);
        when(repository.countByCustomerIdAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(0L);

        issued = new ArrayList<>();
        when(repository.save(any(VerificationChallenge.class))).thenAnswer(inv -> {
            VerificationChallenge challenge = inv.getArgument(0);
            issued.add(challenge);
            return challenge;
        });
        when(repository.findByProcedureIdAndConsumedFalse(any())).thenAnswer(inv -> {
            UUID procedureId = inv.getArgument(0);
            return issued.stream()
                    .filter(c -> procedureId.equals(c.getProcedureId()) && !c.isConsumed())
                    .toList();
        });

        // resendCooldownSeconds = 0 so the two issues are not cooldown-blocked.
        service = new VerificationService(repository, customerRepository, otpDeliveryService,
                5, 3, 0, 5, 5);

        session = ConversationSession.newSession("sess-1", Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567"));
    }

    @Test
    void reissuingACodeForTheSameProcedureInvalidatesThePreviousOne() {
        UUID procedureId = UUID.randomUUID();

        var first = service.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-1");
        var second = service.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-1");

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(issued).hasSize(2);
        assertThat(issued.get(0).isConsumed()).as("stale challenge must be invalidated").isTrue();
        assertThat(issued.get(1).isConsumed()).as("newest challenge must stay valid").isFalse();
    }

    @Test
    void challengesForDifferentProceduresAreUnaffected() {
        UUID procedureA = UUID.randomUUID();
        UUID procedureB = UUID.randomUUID();

        service.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureA, "ORD-1");
        service.issueChallenge(session, VerificationPurpose.RETURN, procedureB, "ORD-2");

        assertThat(issued).hasSize(2);
        assertThat(issued).allMatch(c -> !c.isConsumed());
    }
}
