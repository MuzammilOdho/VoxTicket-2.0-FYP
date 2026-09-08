package com.voxticket.verification;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.enums.OtpDeliveryChannel;
import com.voxticket.persistence.entity.enums.VerificationPurpose;

/** Spec §9. Delivery channel is an implementation detail behind this - VerificationService's logic never depends on which one is active. */
public interface OtpDeliveryService {
    OtpDeliveryChannel channel();
    void deliver(Customer customer, String plainOtp, VerificationPurpose purpose);
}