package com.voxticket.identity;

import com.voxticket.persistence.repository.CustomerRepository;
import org.springframework.stereotype.Service;

/**
 * Spec §8/§42. Resolves the low-assurance "caller ID is a convenience
 * signal" identity. This never grants OTP_VERIFIED - that only happens
 * through a VerificationService once Phase 9 exists.
 */
@Service
public class IdentityService {

    private final CustomerRepository customerRepository;

    public IdentityService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public CustomerIdentity resolveByPhone(String phoneE164) {
        return customerRepository.findByPhone(phoneE164)
                .map(customer -> new CustomerIdentity(customer.getId(), IdentityAssurance.PHONE_MATCHED, phoneE164))
                .orElseGet(CustomerIdentity::anonymous);
    }
}