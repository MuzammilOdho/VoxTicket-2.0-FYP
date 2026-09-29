package com.voxticket.service.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.persistence.entity.enums.RefundStatus;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Task 8: a failed refund must expose when it failed without inventing why. The domain
 * persists no authoritative failure reason, so the DTO carries {@code failedAt} and no
 * cause field at all - FAILED with a timestamp and no cause means "cause unavailable",
 * never a fabricated provider or error reason.
 */
class RefundContextViewTest {

    @Test
    void failedRefundExposesFailedAt() {
        Instant initiatedAt = Instant.parse("2026-09-28T10:00:00Z");
        Instant failedAt = Instant.parse("2026-09-28T10:05:00Z");

        var view = new RefundContextView("RFN-00001", RefundStatus.FAILED, BigDecimal.valueOf(2500), initiatedAt, null, failedAt);

        assertThat(view.failedAt()).isEqualTo(failedAt);
        assertThat(view.completedAt()).isNull();
    }

    @Test
    void failedRefundWithNoRecordedCauseGainsNoInventedReasonField() {
        var componentNames = Arrays.stream(RefundContextView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).contains("failedAt");
        assertThat(componentNames).doesNotContain(
                "failureReason", "failureCause", "errorReason", "errorMessage", "providerReason");
    }

    @Test
    void nonFailedRefundLeavesFailedAtEmpty() {
        Instant initiatedAt = Instant.parse("2026-09-28T10:00:00Z");

        var view = new RefundContextView("RFN-00002", RefundStatus.SUCCEEDED, BigDecimal.valueOf(2500), initiatedAt, initiatedAt.plusSeconds(60), null);

        assertThat(view.failedAt()).isNull();
        assertThat(view.completedAt()).isNotNull();
    }
}
