package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.OtpDeliveryChannel;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Spec §9 + Core Improvement #4. Bound to the exact procedure/order it authorizes - never stores a plaintext OTP, only a salted hash. */
@Entity
@Table(name = "verification_challenges")
public class VerificationChallenge extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @NotNull
    @Size(max = 100)
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    private VerificationPurpose purpose;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_channel", nullable = false, length = 10)
    private OtpDeliveryChannel deliveryChannel;

    @NotNull
    @Size(max = 255)
    @Column(name = "masked_destination", nullable = false, length = 255)
    private String maskedDestination;

    @NotNull
    @Column(name = "otp_hash", nullable = false, length = 128)
    private String otpHash;

    @NotNull
    @Column(name = "otp_salt", nullable = false, length = 64)
    private String otpSalt;

    @NotNull
    @Column(name = "procedure_id", nullable = false)
    private UUID procedureId;

    @NotNull
    @Size(max = 30)
    @Column(name = "order_number", nullable = false, length = 30)
    private String orderNumber;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    protected VerificationChallenge() {
        // JPA
    }

    public VerificationChallenge(
            Customer customer, String sessionId, VerificationPurpose purpose, OtpDeliveryChannel deliveryChannel,
            String maskedDestination, String otpHash, String otpSalt, UUID procedureId, String orderNumber, Instant expiresAt) {
        this.customer = customer;
        this.sessionId = sessionId;
        this.purpose = purpose;
        this.deliveryChannel = deliveryChannel;
        this.maskedDestination = maskedDestination;
        this.otpHash = otpHash;
        this.otpSalt = otpSalt;
        this.procedureId = procedureId;
        this.orderNumber = orderNumber;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public void recordFailedAttempt() {
        this.attempts++;
    }

    public void markVerified() {
        this.verified = true;
        this.consumed = true;
    }

    public void markConsumedWithoutVerification() {
        this.consumed = true;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getSessionId() {
        return sessionId;
    }

    public VerificationPurpose getPurpose() {
        return purpose;
    }

    public OtpDeliveryChannel getDeliveryChannel() {
        return deliveryChannel;
    }

    public String getMaskedDestination() {
        return maskedDestination;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public String getOtpSalt() {
        return otpSalt;
    }

    public UUID getProcedureId() {
        return procedureId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public boolean isVerified() {
        return verified;
    }
}