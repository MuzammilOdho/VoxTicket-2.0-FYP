package com.voxticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.repository.OrderItemRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnedOrderItemResolverTest {

    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final OwnedOrderItemResolver resolver = new OwnedOrderItemResolver(orderItemRepository);
    private final VerifiedOrderRef ref = new VerifiedOrderRef(UUID.randomUUID(), "ORD-TEST", UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, Instant.now());

    private OrderItem item(String name, String sku) {
        return new OrderItem(name, sku, 1, BigDecimal.TEN, true, false);
    }

    @Test
    void aSingleItemOrderAutoResolvesRegardlessOfWhatWasPassed() {
        OrderItem onlyItem = item("Running Shoes", "SKU-1");
        when(orderItemRepository.findByOrderId(ref.orderId())).thenReturn(List.of(onlyItem));

        assertThat(resolver.resolve(ref, null)).isSameAs(onlyItem);
        assertThat(resolver.resolve(ref, "")).isSameAs(onlyItem);
        assertThat(resolver.resolve(ref, "something unrelated")).isSameAs(onlyItem);
    }

    @Test
    void exactSkuStillMatchesDirectly() {
        OrderItem shoes = item("Running Shoes", "SKU-1");
        OrderItem shirt = item("Cotton T-Shirt", "SKU-2");
        when(orderItemRepository.findByOrderId(ref.orderId())).thenReturn(List.of(shoes, shirt));

        assertThat(resolver.resolve(ref, "SKU-2")).isSameAs(shirt);
    }

    @Test
    void aUniqueProductNameSubstringResolvesTheMatchingItem() {
        OrderItem shoes = item("Running Shoes", "SKU-1");
        OrderItem shirt = item("Cotton T-Shirt", "SKU-2");
        when(orderItemRepository.findByOrderId(ref.orderId())).thenReturn(List.of(shoes, shirt));

        assertThat(resolver.resolve(ref, "shoes")).isSameAs(shoes);
        assertThat(resolver.resolve(ref, "the t-shirt")).isSameAs(shirt);
    }

    @Test
    void anAmbiguousDescriptionAcrossMultipleItemsThrowsWithAllCandidates() {
        OrderItem redShirt = item("Red Cotton Shirt", "SKU-1");
        OrderItem blueShirt = item("Blue Cotton Shirt", "SKU-2");
        when(orderItemRepository.findByOrderId(ref.orderId())).thenReturn(List.of(redShirt, blueShirt));

        assertThatThrownBy(() -> resolver.resolve(ref, "shirt"))
                .isInstanceOf(AmbiguousItemException.class)
                .satisfies(e -> assertThat(((AmbiguousItemException) e).getCandidates()).containsExactlyInAnyOrder(redShirt, blueShirt));
    }

    @Test
    void aBlankDescriptionWithMultipleItemsIsAmbiguousRatherThanGuessing() {
        OrderItem shoes = item("Running Shoes", "SKU-1");
        OrderItem shirt = item("Cotton T-Shirt", "SKU-2");
        when(orderItemRepository.findByOrderId(ref.orderId())).thenReturn(List.of(shoes, shirt));

        assertThatThrownBy(() -> resolver.resolve(ref, null)).isInstanceOf(AmbiguousItemException.class);
    }

    @Test
    void noMatchAtAllThrowsNotFound() {
        OrderItem shoes = item("Running Shoes", "SKU-1");
        when(orderItemRepository.findByOrderId(ref.orderId())).thenReturn(List.of(shoes));
        // Force multi-item path by adding a second item so single-item auto-resolve doesn't apply.
        OrderItem shirt = item("Cotton T-Shirt", "SKU-2");
        when(orderItemRepository.findByOrderId(ref.orderId())).thenReturn(List.of(shoes, shirt));

        assertThatThrownBy(() -> resolver.resolve(ref, "nonexistent product")).isInstanceOf(ResourceNotFoundForAccountException.class);
    }

    @Test
    void pluralProductDescriptionResolvesAgainstASingularProductName() {
        OrderItem bedsheetSet = item("Cotton Bedsheet Set", "SKU-1");
        OrderItem shoes = item("Running Shoes", "SKU-2");
        when(orderItemRepository.findByOrderId(ref.orderId())).thenReturn(List.of(bedsheetSet, shoes));

        assertThat(resolver.resolve(ref, "bedsheets")).isSameAs(bedsheetSet);
    }
}