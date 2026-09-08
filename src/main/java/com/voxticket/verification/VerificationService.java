package com.voxticket.verification;

import com.voxticket.conversation.ConversationSession;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.VerificationChallenge;
import com.voxticket.persistence.entity.enums.OtpDeliveryChannel;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.VerificationChallengeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §9. Never logs or stores a plaintext OTP - only a salted hash.
 * Never sends the code anywhere the model could see it.
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final VerificationChallengeRepository repository;
    private final CustomerRepository customerRepository;
    private final OtpDeliveryService otpDeliveryService;
    private final Duration expiry;
    private final int maxAttempts;
    private final Duration resendCooldown;
    private final int maxChallengesPerCustomerPerHour;
    private final int maxChallengesPerSession;

    public VerificationService(
            VerificationChallengeRepository repository,
            CustomerRepository customerRepository,
            OtpDeliveryService otpDeliveryService,
            @Value("${voxticket.otp.expiry-minutes:5}") long expiryMinutes,
            @Value("${voxticket.otp.max-attempts:3}") int maxAttempts,
            @Value("${voxticket.otp.resend-cooldown-seconds:60}") long resendCooldownSeconds,
            @Value("${voxticket.otp.max-challenges-per-customer-per-hour:5}") int maxChallengesPerCustomerPerHour,
            @Value("${voxticket.otp.max-challenges-per-session:5}") int maxChallengesPerSession) {
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.otpDeliveryService = otpDeliveryService;
        this.expiry = Duration.ofMinutes(expiryMinutes);
        this.maxAttempts = maxAttempts;
        this.resendCooldown = Duration.ofSeconds(resendCooldownSeconds);
        this.maxChallengesPerCustomerPerHour = maxChallengesPerCustomerPerHour;
        this.maxChallengesPerSession = maxChallengesPerSession;
    }

    @Transactional
    public VerificationOutcome issueChallenge(ConversationSession session, VerificationPurpose purpose) {
        UUID customerId = session.getCustomerIdentity().requireCustomerId();
        Customer customer = customerRepository.findById(customerId).orElseThrow();

        Optional<VerificationChallenge> latest = repository.findFirstBySessionIdOrderByCreatedAtDesc(session.getSessionId());
        if (latest.isPresent() && !latest.get().isConsumed()
                && Duration.between(latest.get().getCreatedAt(), Instant.now()).compareTo(resendCooldown) < 0) {
            return VerificationOutcome.error("A code was already sent - please wait a moment before requesting another.");
        }

        long sessionCount = repository.countBySessionIdAndCreatedAtAfter(session.getSessionId(), Instant.now().minus(Duration.ofDays(1)));
        if (sessionCount >= maxChallengesPerSession) {
            log.warn("event=otp_rate_limited sessionId={} scope=session count={}", session.getSessionId(), sessionCount);
            return VerificationOutcome.error("Too many verification attempts for this conversation - please try again later or ask for a human agent.");
        }

        long customerCount = repository.countByCustomerIdAndCreatedAtAfter(customerId, Instant.now().minus(Duration.ofHours(1)));
        if (customerCount >= maxChallengesPerCustomerPerHour) {
            log.warn("event=otp_rate_limited customerId={} scope=customer count={}", customerId, customerCount);
            return VerificationOutcome.error("Too many verification attempts recently - please try again later or ask for a human agent.");
        }

        String plainOtp = generateOtp();
        String salt = generateSalt();
        String hash = hash(plainOtp, salt);
        OtpDeliveryChannel channel = otpDeliveryService.channel();
        String maskedDestination = mask(customer, channel);

        VerificationChallenge challenge = repository.save(new VerificationChallenge(
                customer, session.getSessionId(), purpose, channel, maskedDestination, hash, salt, Instant.now().plus(expiry)));

        otpDeliveryService.deliver(customer, plainOtp, purpose);
        session.setPendingVerificationChallengeId(challenge.getId());
        log.info("event=otp_issued customerId={} sessionId={} channel={} purpose={}", customerId, session.getSessionId(), channel, purpose);

        Map<String, String> metadata = channel == OtpDeliveryChannel.DEV ? Map.of("devOtp", plainOtp) : Map.of();
        return VerificationOutcome.challengeIssued(
                "I've sent a 6-digit verification code to " + maskedDestination + ". Could you read that back to me once you receive it?", metadata);
    }

    @Transactional
    public VerificationResult verify(ConversationSession session, String submittedCode) {
        UUID challengeId = session.getPendingVerificationChallengeId().orElse(null);
        if (challengeId == null) {
            return VerificationResult.error("There's no verification code pending right now.");
        }
        VerificationChallenge challenge = repository.findById(challengeId).orElse(null);
        if (challenge == null || challenge.isConsumed()) {
            session.clearPendingVerification();
            return VerificationResult.error("That verification code is no longer valid - let's request a new one.");
        }
        if (challenge.isExpired()) {
            challenge.markConsumedWithoutVerification();
            session.clearPendingVerification();
            log.info("event=otp_expired sessionId={}", session.getSessionId());
            return VerificationResult.error("That code has expired - let's request a new one.");
        }
        if (!hash(submittedCode, challenge.getOtpSalt()).equals(challenge.getOtpHash())) {
            challenge.recordFailedAttempt();
            if (challenge.getAttempts() >= maxAttempts) {
                challenge.markConsumedWithoutVerification();
                session.clearPendingVerification();
                log.warn("event=otp_verification_failed sessionId={} outcome=max_attempts_exceeded", session.getSessionId());
                return VerificationResult.error("That code didn't match too many times - let's request a new one.");
            }
            log.warn("event=otp_verification_failed sessionId={} outcome=wrong_code attempts={}", session.getSessionId(), challenge.getAttempts());
            return VerificationResult.error("That code didn't match - please double check and try again.");
        }

        challenge.markVerified();
        session.clearPendingVerification();
        log.info("event=otp_verified sessionId={} customerId={}", session.getSessionId(), challenge.getCustomer().getId());
        return VerificationResult.success();
    }

    private String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private String generateSalt() {
        byte[] saltBytes = new byte[16];
        SECURE_RANDOM.nextBytes(saltBytes);
        return HexFormat.of().formatHex(saltBytes);
    }

    private String hash(String otp, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(otp.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String mask(Customer customer, OtpDeliveryChannel channel) {
        return channel == OtpDeliveryChannel.EMAIL ? maskEmail(customer.getEmail()) : maskPhone(customer.getPhone());
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "your registered number";
        }
        int visible = 4;
        return "*".repeat(phone.length() - visible) + phone.substring(phone.length() - visible);
    }

    private String maskEmail(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 1 ? "your registered email" : email.charAt(0) + "***" + email.substring(at);
    }
}