package com.voxticket.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.persistence.repository.OrderRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Guards the repository cleanup: the unscoped {@code findByOrderNumber} was a
 * latent IDOR bypass hazard and has been removed; the customer-scoped
 * ownership lookup used by {@code OwnedOrderResolver} must remain.
 */
class OrderRepositoryScopeTest {

    @Test
    void unscopedOrderNumberLookupDoesNotExist() {
        assertThat(Arrays.stream(OrderRepository.class.getMethods())
                .filter(m -> m.getName().equals("findByOrderNumber"))
                .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class))
                .isEmpty();
    }

    @Test
    void customerScopedOwnershipLookupIsPreserved() throws NoSuchMethodException {
        Method scoped = OrderRepository.class.getMethod("findByOrderNumberAndCustomerId", String.class, UUID.class);

        assertThat(scoped.getReturnType()).isEqualTo(Optional.class);
    }
}
