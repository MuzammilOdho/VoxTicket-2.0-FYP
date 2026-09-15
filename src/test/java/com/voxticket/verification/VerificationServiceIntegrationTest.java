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
@SpringBootTest(properties = "voxticket.otp.max-attempts=2")
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
    void issuingAChallengePersistsAHashNotThePlaintextCodeAndBindsToTheProcedureAndOrder() {
        UUID procedureId = UUID.randomUUID();
        VerificationOutcome outcome = verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-TEST");

        assertThat(outcome.success()).isTrue();
        VerificationChallenge stored = challengeRepository.findById(session.getPendingVerificationChallengeId().get()).orElseThrow();
        assertThat(stored.getOtpHash()).doesNotContain(outcome.metadata().get("devOtp"));
        assertThat(stored.getProcedureId()).isEqualTo(procedureId);
        assertThat(stored.getOrderNumber()).isEqualTo("ORD-TEST");
    }

    @Test
    void theCorrectCodeVerifiesSuccessfullyWhenProcedureAndOrderMatch() {
        UUID procedureId = UUID.randomUUID();
        VerificationOutcome issued = verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-TEST");

        VerificationResult result = verificationService.verify(session, issued.metadata().get("devOtp"), procedureId, "ORD-TEST");

        assertThat(result.verified()).isTrue();
        assertThat(session.getPendingVerificationChallengeId()).isEmpty();
    }

    @Test
    void aCorrectCodeIsRejectedIfTheProcedureBindingDoesNotMatch() {
        UUID procedureId = UUID.randomUUID();
        VerificationOutcome issued = verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-TEST");

        VerificationResult result = verificationService.verify(session, issued.metadata().get("devOtp"), UUID.randomUUID(), "ORD-TEST");

        assertThat(result.verified()).isFalse();
    }

    @Test
    void aCorrectCodeIsRejectedIfTheOrderBindingDoesNotMatch() {
        UUID procedureId = UUID.randomUUID();
        VerificationOutcome issued = verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-TEST");

        VerificationResult result = verificationService.verify(session, issued.metadata().get("devOtp"), procedureId, "ORD-DIFFERENT");

        assertThat(result.verified()).isFalse();
    }

    @Test
    void aWrongCodeFailsWithoutConsumingTheChallengeImmediately() {
        UUID procedureId = UUID.randomUUID();
        verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-TEST");

        VerificationResult result = verificationService.verify(session, "000000", procedureId, "ORD-TEST");

        assertThat(result.verified()).isFalse();
        assertThat(session.getPendingVerificationChallengeId()).isPresent();
    }

    @Test
    void exceedingMaxAttemptsConsumesTheChallenge() {
        UUID procedureId = UUID.randomUUID();
        verificationService.issueChallenge(session, VerificationPurpose.CANCELLATION, procedureId, "ORD-TEST");

        verificationService.verify(session, "000000", procedureId, "ORD-TEST");
        verificationService.verify(session, "111111", procedureId, "ORD-TEST");

        assertThat(session.getPendingVerificationChallengeId()).isEmpty();
    }

    @Test
    void verifyingWithNoChallengeIssuedFails() {
        VerificationResult result = verificationService.verify(session, "123456", UUID.randomUUID(), "ORD-TEST");

        assertThat(result.verified()).isFalse();
    }
}