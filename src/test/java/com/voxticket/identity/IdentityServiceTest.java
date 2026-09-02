package com.voxticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.repository.CustomerRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityServiceTest {

    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final IdentityService identityService = new IdentityService(customerRepository);

    @Test
    void knownPhoneResolvesToPhoneMatched() {
        UUID customerId = UUID.randomUUID();
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn(customerId);
        when(customerRepository.findByPhone("+923001234567")).thenReturn(Optional.of(customer));

        CustomerIdentity identity = identityService.resolveByPhone("+923001234567");

        assertThat(identity.assuranceLevel()).isEqualTo(IdentityAssurance.PHONE_MATCHED);
        assertThat(identity.customerId()).isEqualTo(customerId);
        assertThat(identity.phone()).isEqualTo("+923001234567");
    }

    @Test
    void unknownPhoneResolvesToAnonymous() {
        when(customerRepository.findByPhone("+923000000000")).thenReturn(Optional.empty());

        CustomerIdentity identity = identityService.resolveByPhone("+923000000000");

        assertThat(identity.assuranceLevel()).isEqualTo(IdentityAssurance.ANONYMOUS);
        assertThat(identity.customerId()).isNull();
    }

    @Test
    void anonymousIdentityDoesNotSatisfyPhoneMatchedRequirement() {
        assertThat(CustomerIdentity.anonymous().isAtLeast(IdentityAssurance.PHONE_MATCHED)).isFalse();
    }

    @Test
    void requireCustomerIdFailsFastForAnonymousIdentity() {
        CustomerIdentity anonymous = CustomerIdentity.anonymous();
        assertThrows(IllegalStateException.class, anonymous::requireCustomerId);
    }
}