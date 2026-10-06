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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pass 2C (Task 22): every deterministic VerificationService branch carries
 * a stable internal code alongside its compatibility message. Mock-based so
 * it runs without Docker.
 */
class VerificationServiceCodeTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String PHONE = "+923001234567";

    private VerificationChallengeRepository repository;
    private CustomerRepository customerRepository;
    private OtpDeliveryService otpDeliveryService;
    private ConversationSession session;
    private Customer customer;
    private VerificationChallenge lastSaved;

    @BeforeEach
    void setUp() {
        repository = mock(VerificationChallengeRepository.class);
        customerRepository = mock(CustomerRepository.class);
        otpDeliveryService = mock(OtpDeliveryService.class);
        customer = new Customer("Test", "User", "user@example.pk", PHONE);

        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(otpDeliveryService.channel()).thenReturn(OtpDeliveryChannel.DEV);
        when(repository.findFirstBySessionIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(repository.countBySessionIdAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(0L);
        when(repository.countByCustomerIdAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(0L);
        when(repository.save(any(VerificationChallenge.class))).thenAnswer(inv -> {
            lastSaved = inv.getArgument(0);
            return lastSaved;
        });

        session = ConversationSession.newSession("sess-code", Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(CUSTOMER_ID, IdentityAssurance.PHONE_MATCHED, PHONE));
    }

    private VerificationService service(long resendCooldownSeconds, int maxAttempts,
                                        long sessionChallengeCount, long customerChallengeCount,
                                        int maxChallengesPerSession, int maxChallengesPerCustomerPerHour) {
        when(repository.countBySessionIdAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(sessionChallengeCount);
        when(repository.countByCustomerIdAndCreatedAtAfter(any(), any(Instant.class))).thenReturn(customerChallengeCount);
        return new VerificationService(repository, customerRepository, otpDeliveryService,
                5, maxAttempts, resendCooldownSeconds, maxChallengesPerCustomerPerHour, maxChallengesPerSession);
    }

    private VerificationService service() {
        return service(0, 3, 0, 0, 5, 5);
    }

    @Test
    void challengeIssuedCarriesStableCodeAndMaskedDestination() {
        VerificationOutcome outcome = service().issueChallenge(session, VerificationPurpose.CANCELLATION, UUID.randomUUID(), "ORD-1");

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.issueCode()).isEqualTo(VerificationIssueCode.CHALLENGE_ISSUED);
        assertThat(outcome.metadata().get("maskedDestination")).endsWith("4567");
        assertThat(outcome.metadata().get("maskedDestination")).doesNotContain("92300");
    }

    @Test
    void devOtpStaysPlaintextInMetadataButNeverMatchesTheStoredHash() {
        VerificationOutcome outcome = service().issueChallenge(session, VerificationPurpose.CANCELLATION, UUID.randomUUID(), "ORD-1");
        VerificationChallenge stored = issuedChallenge(outcome);

        assertThat(outcome.metadata().get("devOtp")).matches("\\d{6}");
        assertThat(stored.getOtpHash()).doesNotContain(outcome.metadata().get("devOtp"));
    }

    @Test
    void resendCooldownCarriesStableCode() {
        VerificationService cooldownService = service(3600, 3, 0, 0, 5, 5);
        UUID procedureId = UUID.randomUUID();
        VerificationChallenge pending = challenge(procedureId, "ORD-1", Instant.now().plusSeconds(300));
        when(repository.findFirstBySessionIdOrderByCreatedAtDesc(any())).thenReturn(Optional.of(pending));

        VerificationOutcome outcome = cooldownService.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-1");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.issueCode()).isEqualTo(VerificationIssueCode.RESEND_COOLDOWN);
    }

    @Test
    void sessionRateLimitCarriesStableCode() {
        VerificationOutcome outcome = service(0, 3, 5, 0, 5, 5)
                .issueChallenge(session, VerificationPurpose.CANCELLATION, UUID.randomUUID(), "ORD-1");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.issueCode()).isEqualTo(VerificationIssueCode.SESSION_RATE_LIMITED);
    }

    @Test
    void customerRateLimitCarriesStableCode() {
        VerificationOutcome outcome = service(0, 3, 0, 5, 5, 5)
                .issueChallenge(session, VerificationPurpose.CANCELLATION, UUID.randomUUID(), "ORD-1");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.issueCode()).isEqualTo(VerificationIssueCode.CUSTOMER_RATE_LIMITED);
    }

    @Test
    void verifyWithNoPendingChallengeCarriesNoPendingCode() {
        VerificationResult result = service().verify(session, "123456", UUID.randomUUID(), "ORD-1");

        assertThat(result.verified()).isFalse();
        assertThat(result.code()).isEqualTo(VerificationResultCode.NO_PENDING);
    }

    @Test
    void verifyConsumedChallengeCarriesNoLongerValidCode() {
        UUID procedureId = UUID.randomUUID();
        VerificationChallenge pending = challenge(procedureId, "ORD-1", Instant.now().plusSeconds(300));
        pending.markConsumedWithoutVerification();
        stubPending(pending);

        VerificationResult result = service().verify(session, "123456", procedureId, "ORD-1");

        assertThat(result.verified()).isFalse();
        assertThat(result.code()).isEqualTo(VerificationResultCode.NO_LONGER_VALID);
    }

    @Test
    void verifyBindingMismatchCarriesBindingMismatchCode() {
        UUID procedureId = UUID.randomUUID();
        stubPending(challenge(procedureId, "ORD-1", Instant.now().plusSeconds(300)));

        VerificationResult result = service().verify(session, "123456", UUID.randomUUID(), "ORD-1");

        assertThat(result.verified()).isFalse();
        assertThat(result.code()).isEqualTo(VerificationResultCode.BINDING_MISMATCH);
    }

    @Test
    void verifyExpiredChallengeCarriesExpiredCode() {
        UUID procedureId = UUID.randomUUID();
        stubPending(challenge(procedureId, "ORD-1", Instant.now().minusSeconds(300)));

        VerificationResult result = service().verify(session, "123456", procedureId, "ORD-1");

        assertThat(result.verified()).isFalse();
        assertThat(result.code()).isEqualTo(VerificationResultCode.EXPIRED);
    }

    @Test
    void verifyWrongCodeCarriesWrongCode() {
        UUID procedureId = UUID.randomUUID();
        stubPending(challenge(procedureId, "ORD-1", Instant.now().plusSeconds(300)));

        VerificationResult result = service().verify(session, "123456", procedureId, "ORD-1");

        assertThat(result.verified()).isFalse();
        assertThat(result.code()).isEqualTo(VerificationResultCode.WRONG_CODE);
    }

    @Test
    void verifyMaxAttemptsExceededCarriesMaxAttemptsExceededCode() {
        UUID procedureId = UUID.randomUUID();
        stubPending(challenge(procedureId, "ORD-1", Instant.now().plusSeconds(300)));
        VerificationService attemptsService = service();

        VerificationResult third = null;
        for (int i = 0; i < 3; i++) {
            third = attemptsService.verify(session, "123456", procedureId, "ORD-1");
        }

        assertThat(third.verified()).isFalse();
        assertThat(third.code()).isEqualTo(VerificationResultCode.MAX_ATTEMPTS_EXCEEDED);
    }

    @Test
    void verifyCorrectCodeCarriesVerifiedCode() {
        UUID procedureId = UUID.randomUUID();
        String salt = "salt";
        String plainOtp = "482916";
        stubPending(challengeWithKnownOtp(procedureId, "ORD-1", plainOtp, salt));

        VerificationResult result = service().verify(session, plainOtp, procedureId, "ORD-1");

        assertThat(result.verified()).isTrue();
        assertThat(result.code()).isEqualTo(VerificationResultCode.VERIFIED);
    }

    private void stubPending(VerificationChallenge pending) {
        session.setPendingVerificationChallengeId(UUID.randomUUID());
        when(repository.findById(any())).thenReturn(Optional.of(pending));
    }

    private VerificationChallenge challenge(UUID procedureId, String orderNumber, Instant expiresAt) {
        String salt = "test-salt";
        return new VerificationChallenge(customer, "sess-code", VerificationPurpose.CANCELLATION,
                OtpDeliveryChannel.DEV, "********4567", hashOf("482916", salt), salt, procedureId, orderNumber, expiresAt);
    }

    private VerificationChallenge challengeWithKnownOtp(UUID procedureId, String orderNumber, String plainOtp, String salt) {
        return new VerificationChallenge(customer, "sess-code", VerificationPurpose.CANCELLATION,
                OtpDeliveryChannel.DEV, "********4567", hashOf(plainOtp, salt), salt, procedureId, orderNumber,
                Instant.now().plusSeconds(300));
    }

    private VerificationChallenge issuedChallenge(VerificationOutcome outcome) {
        // The service saved the real challenge through the mocked repository.
        return lastSaved;
    }

    private String hashOf(String otp, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(otp.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
