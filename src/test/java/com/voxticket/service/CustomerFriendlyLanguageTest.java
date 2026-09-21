package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import org.junit.jupiter.api.Test;

class CustomerFriendlyLanguageTest {

    @Test
    void everyOrderStatusHasAFriendlyDescriptionWithNoRawEnumLeaking() {
        for (OrderStatus status : OrderStatus.values()) {
            String description = CustomerFriendlyLanguage.describe(status);
            assertThat(description).isNotBlank();
            assertThat(description).isNotEqualTo(status.name());
        }
    }

    @Test
    void everyPaymentStatusHasAFriendlyDescription() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(CustomerFriendlyLanguage.describe(status)).isNotBlank().isNotEqualTo(status.name());
        }
    }

    @Test
    void everyShipmentStatusHasAFriendlyDescription() {
        for (ShipmentStatus status : ShipmentStatus.values()) {
            assertThat(CustomerFriendlyLanguage.describe(status)).isNotBlank().isNotEqualTo(status.name());
        }
    }
    @Test
    void returnRequestedStatusIsUnambiguousAboutWhoseTurnItIs() {
        String description = CustomerFriendlyLanguage.describe(ReturnStatus.REQUESTED);
        assertThat(description).contains("awaiting approval");
    }
}