package com.voxticket.verification;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.enums.OtpDeliveryChannel;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Spec §9: "Dev/test profile may expose the OTP through a controlled
 * testing mechanism". That mechanism has two parts:
 *
 * 1. VerificationService surfaces the code via ProcedureOutcome/AssistantTurn
 *    metadata in dev/test (never spoken text, never the chat API JSON).
 * 2. This delivery service logs the OTP value itself - but ONLY when the
 *    "dev" profile is active, as a local-development convenience. Under any
 *    other profile (test, prod-like) it logs only non-secret metadata -
 *    customer ID, purpose, channel - so the code never lands in CI logs or
 *    log aggregation outside a developer's machine.
 *
 * Restricted to dev/test by profile AND requires the delivery property to
 * not be explicitly set to something else, so it can never accidentally end
 * up active in a real deployment, and never coexists with
 * EmailOtpDeliveryService (which would otherwise create an ambiguous bean).
 */
@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(prefix = "voxticket.otp", name = "delivery", havingValue = "dev", matchIfMissing = true)
public class DevOtpDeliveryService implements OtpDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DevOtpDeliveryService.class);

    private final Environment environment;

    public DevOtpDeliveryService(Environment environment) {
        this.environment = environment;
    }

    @Override
    public OtpDeliveryChannel channel() {
        return OtpDeliveryChannel.DEV;
    }

    @Override
    public void deliver(Customer customer, String plainOtp, VerificationPurpose purpose) {
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            // Dev profile only: the OTP is visible in local logs for development.
            // Never active in test/prod - those profiles only get non-secret metadata.
            log.info("event=dev_otp_delivery customerId={} purpose={} otp={} channel=DEV", customer.getId(), purpose, plainOtp);
        } else {
            // The OTP value itself is never logged; tests read it from the controlled
            // devOtp metadata in VerificationOutcome instead.
            log.info("event=dev_otp_delivery customerId={} purpose={} channel=DEV", customer.getId(), purpose);
        }
    }
}
