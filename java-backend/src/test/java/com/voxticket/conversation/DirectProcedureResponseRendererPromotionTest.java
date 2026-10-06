package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.procedure.ProcedureOutcome;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pass 2D-B cleanup (Tasks 1-2): every {@link ProcedureOutcome} code that
 * {@code ProcedureCoordinator.promoteDeferredIntent} can legitimately return
 * has deterministic customer-facing handling in
 * {@link DirectProcedureResponseRenderer} - never the generic
 * "Something went wrong" fallback.
 */
class DirectProcedureResponseRendererPromotionTest {

    private final DirectProcedureResponseRenderer renderer = new DirectProcedureResponseRenderer();

    private static ProcedureOutcome outcome(String code, Map<String, String> metadata) {
        // Deliberately misleading English prose: the renderer must ignore it.
        return new ProcedureOutcome(false, code, "This message is a lie about what happened.", metadata);
    }

    // ---- A. promoted claim CONFIRMATION_REQUIRED ----

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedClaimConfirmationAsksForExplicitConfirmation(ConversationLanguage language) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("orderReference", "ORD-10002");
        metadata.put("itemName", "Blue Widget");
        metadata.put("claimReason", "DAMAGED");
        metadata.put("problemDescription", "box was crushed");

        String rendered = renderer.render(language, outcome("CONFIRMATION_REQUIRED", metadata));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).doesNotContain("This message is a lie");
        assertThat(rendered).contains("ORD-10002");
        assertThat(rendered).contains("Blue Widget");
        assertThat(rendered).isNotBlank();
    }

    @Test
    void promotedClaimConfirmationNeverPromisesRefundOrReplacement() {
        String rendered = renderer.render(ConversationLanguage.ENGLISH, outcome("CONFIRMATION_REQUIRED",
                Map.of("orderReference", "ORD-10002", "itemName", "Blue Widget", "claimReason", "DAMAGED")));

        assertThat(rendered.toLowerCase()).doesNotContain("refund");
        assertThat(rendered.toLowerCase()).doesNotContain("replacement");
        assertThat(rendered).contains("yes");
    }

    @Test
    void promotedClaimConfirmationTextDiffersAcrossLanguages() {
        Map<String, String> metadata = Map.of("orderReference", "ORD-10002", "itemName", "Blue Widget");
        String english = renderer.render(ConversationLanguage.ENGLISH, outcome("CONFIRMATION_REQUIRED", metadata));
        String urdu = renderer.render(ConversationLanguage.URDU, outcome("CONFIRMATION_REQUIRED", metadata));

        assertThat(english).isNotEqualTo(urdu);
        assertThat(urdu).isNotBlank();
    }

    // ---- B. promoted cancellation VERIFICATION_REQUIRED ----

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedCancellationOtpUsesDeterministicLocalizedRenderer(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("VERIFICATION_REQUIRED",
                Map.of("orderReference", "ORD-10001", "maskedDestination", "********4567")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("********4567");
    }

    // ---- C. promoted NOT_ELIGIBLE ----

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedCancellationDenialRendersDeterministically(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("NOT_ELIGIBLE",
                Map.of("orderReference", "ORD-10001", "denialReason", "ORDER_FULFILLED",
                        "paymentConsequence", "NO_REFUND_REQUIRED")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("ORD-10001");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedReturnDenialRendersDeterministically(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("NOT_ELIGIBLE",
                Map.of("orderReference", "ORD-10001", "itemName", "Blue Widget",
                        "denialReason", "RETURN_WINDOW_EXPIRED", "maxReturnableQuantity", "0")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("Blue Widget");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedClaimDenialOnCancelledOrderRendersDeterministically(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("NOT_ELIGIBLE",
                Map.of("orderReference", "ORD-10001", "itemName", "Blue Widget")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("ORD-10001");
    }

    @Test
    void allKnownDenialReasonsRenderWithoutFallback() {
        String[] reasons = {"ALREADY_CANCELLED", "ORDER_ALREADY_COMPLETED", "ORDER_FULFILLED",
                "PAYMENT_STATE_INCOMPATIBLE", "ITEM_NOT_DELIVERED", "RETURN_WINDOW_EXPIRED",
                "ITEM_FINAL_SALE", "ITEM_NOT_RETURNABLE", "NO_REMAINING_RETURNABLE_QUANTITY"};
        for (String reason : reasons) {
            String rendered = renderer.render(ConversationLanguage.ENGLISH, outcome("NOT_ELIGIBLE",
                    Map.of("orderReference", "ORD-1", "itemName", "Widget", "denialReason", reason)));
            assertThat(rendered)
                    .as("denial reason %s", reason)
                    .doesNotContain("Something went wrong")
                    .isNotBlank();
        }
    }

    // ---- D. deferred item no longer resolvable ----

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedItemRequiredRendersDeterministically(ConversationLanguage language) {
        String rendered = renderer.render(language,
                outcome("ITEM_REQUIRED", Map.of("orderReference", "ORD-10001")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("ORD-10001");
    }

    @Test
    void ambiguousItemCandidatesAreListedFromMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("orderReference", "ORD-10001");
        metadata.put("candidateItem.1", "Blue Widget");
        metadata.put("candidateItem.2", "Blue Widget Pro");

        String rendered = renderer.render(ConversationLanguage.ENGLISH, outcome("ITEM_REQUIRED", metadata));

        assertThat(rendered).contains("Blue Widget").contains("Blue Widget Pro");
        assertThat(rendered).doesNotContain("Something went wrong");
    }

    // ---- E. clarification outcomes ----

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedReasonRequiredAsksForReason(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("REASON_REQUIRED",
                Map.of("orderReference", "ORD-10001", "itemName", "Blue Widget")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("Blue Widget");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedProblemRequiredAsksForProblem(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("PROBLEM_REQUIRED",
                Map.of("orderReference", "ORD-10001", "itemName", "Blue Widget")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("Blue Widget");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedQuantityRequiredAsksForQuantity(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("QUANTITY_REQUIRED",
                Map.of("orderReference", "ORD-10001", "itemName", "Blue Widget", "maxReturnableQuantity", "3")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("Blue Widget");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotedNotFoundForAccountRendersDeterministically(ConversationLanguage language) {
        String rendered = renderer.render(language,
                outcome("NOT_FOUND_FOR_ACCOUNT", Map.of("orderReference", "ORD-99999")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("ORD-99999");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotionFailedNoticeIsSafeAndSpecific(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("PROMOTION_FAILED", Map.of()));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void promotionFailedNoticeNeverPromisesAutomaticProcessing(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("PROMOTION_FAILED", Map.of()));
        String lower = rendered.toLowerCase();

        // The notice must communicate only: the queued request could not be
        // started, it remains saved, and the customer may retry/continue
        // later. It must never promise VoxTicket will process it automatically.
        assertThat(lower).doesNotContain("automatically");
        assertThat(lower).doesNotContain("right after the current step");
        assertThat(lower).doesNotContain("foran baad");
        assertThat(lower).doesNotContain("i'll get to it");
        assertThat(rendered).isNotBlank();
    }

    @Test
    void rendererNeverExposesInternalIdentifiersForPromotionOutcomes() {
        Map<String, String> metadata = Map.of(
                "orderReference", "ORD-10002",
                "itemName", "Blue Widget",
                "claimReason", "DAMAGED",
                "problemDescription", "box was crushed");
        String rendered = renderer.render(ConversationLanguage.ENGLISH,
                outcome("CONFIRMATION_REQUIRED", metadata));

        assertThat(rendered).doesNotContain("SKU-1");
        assertThat(rendered).doesNotContain("challenge");
        assertThat(rendered).doesNotContain("otp");
    }
}
