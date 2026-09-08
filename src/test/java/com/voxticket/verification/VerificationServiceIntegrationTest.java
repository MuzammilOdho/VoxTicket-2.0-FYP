package com.voxticket.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.VerificationChallenge;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.VerificationChallengeRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "voxticket.otp.max-attempts=2") // small cap so the exhaustion test doesn't need many tries
@Transactional
class VerificationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private VerificationService verificationService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private VerificationChallengeRepository challengeRepository;

    private Customer customer;
    private ConversationSession session;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(new Customer("Test", "User", "otp." + UUID.randomUUID() + "@example.pk", "+9230016" + (System.nanoTime() % 100_000)));
        session = ConversationSession.newSession("otp-session-" + UUID.randomUUID(), Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(customer.getId(), IdentityAssurance.PHONE_MATCHED, customer.getPhone()));
    }

    @Test
    void issuingAChallengePersistsAHashNotThePlaintextCode() {
        VerificationOutcome outcome = verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION);

        assertThat(outcome.success()).isTrue();
        assertThat(session.getPendingVerificationChallengeId()).isPresent();
        VerificationChallenge stored = challengeRepository.findById(session.getPendingVerificationChallengeId().get()).orElseThrow();
        assertThat(stored.getOtpHash()).doesNotContain(outcome.metadata().get("devOtp"));
    }

    @Test
    void theCorrectCodeVerifiesSuccessfully() {
        VerificationOutcome issued = verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION);
        String plainOtp = issued.metadata().get("devOtp");

        VerificationResult result = verificationService.verify(session, plainOtp);

        assertThat(result.verified()).isTrue();
        assertThat(session.getPendingVerificationChallengeId()).isEmpty();
    }

    @Test
    void aWrongCodeFailsWithoutConsumingTheChallengeImmediately() {
        verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION);

        VerificationResult result = verificationService.verify(session, "000000");

        assertThat(result.verified()).isFalse();
        assertThat(session.getPendingVerificationChallengeId()).isPresent(); // still usable for the next attempt
    }

    @Test
    void exceedingMaxAttemptsConsumesTheChallenge() {
        verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION);

        verificationService.verify(session, "000000");
        verificationService.verify(session, "111111");

        assertThat(session.getPendingVerificationChallengeId()).isEmpty();
    }

    @Test
    void verifyingWithNoChallengeIssuedFails() {
        VerificationResult result = verificationService.verify(session, "123456");

        assertThat(result.verified()).isFalse();
    }
}