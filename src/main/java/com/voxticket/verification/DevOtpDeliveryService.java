package com.voxticket.verification;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.enums.OtpDeliveryChannel;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Spec §9: "Dev/test profile may expose the OTP through a controlled
 * testing mechanism" - this log line, plus VerificationService surfacing
 * the code via AssistantTurn.metadata (never spoken text), are that
 * mechanism. Restricted to dev/test by profile AND requires the delivery
 * property to not be explicitly set to something else, so it can never
 * accidentally end up active in a real deployment, and never coexists with
 * EmailOtpDeliveryService (which would otherwise create an ambiguous bean).
 */
@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(prefix = "voxticket.otp", name = "delivery", havingValue = "dev", matchIfMissing = true)
public class DevOtpDeliveryService implements OtpDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DevOtpDeliveryService.class);

    @Override
    public OtpDeliveryChannel channel() {
        return OtpDeliveryChannel.DEV;
    }

    @Override
    public void deliver(Customer customer, String plainOtp, VerificationPurpose purpose) {
        log.info("event=dev_otp_delivery customerId={} purpose={} otp={}", customer.getId(), purpose, plainOtp);
    }
}