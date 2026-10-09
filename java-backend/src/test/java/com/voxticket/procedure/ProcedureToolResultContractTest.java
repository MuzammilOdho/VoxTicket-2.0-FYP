package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pass 2B: the model-facing procedure contract.
 *
 * <p>Every procedure tool must expose {@link ProcedureToolResult} - stable
 * codes, a stable next action, and whitelisted structured facts - and never
 * the raw coordinator {@link ProcedureOutcome} with its English
 * {@code message()}. The deterministic runtime contract (coordinator returns
 * {@code ProcedureOutcome}; {@code ConversationRuntime} presents its
 * {@code message()} directly) is guarded by regression tests below.
 */
class ProcedureToolResultContractTest {

    private final JsonMapper objectMapper = new JsonMapper();

    private ProcedureCoordinator coordinator;
    private ProcedureRequestTools tools;

    @BeforeEach
    void setUp() {
        coordinator = mock(ProcedureCoordinator.class);
        tools = new ProcedureRequestTools(coordinator, ConversationSession.newSession("s-" + UUID.randomUUID(), Channel.CHAT));
    }

    private ProcedureOutcome outcome(boolean success, String code, String message, Map<String, String> metadata) {
        return new ProcedureOutcome(success, code, message, metadata);
    }

    // ---- Task 13.1-13.4: tools return ProcedureToolResult, not ProcedureOutcome ----

    @Test
    void requestCancellationReturnsProcedureToolResult() {
        when(coordinator.startCancellation(any(), eq("ORD-10001")))
                .thenReturn(outcome(true, "VERIFICATION_REQUIRED", "A code was sent.",
                        Map.of("orderReference", "ORD-10001", "paymentConsequence", "REFUND_REQUIRED")));

        ProcedureToolResult result = tools.requestCancellation("ORD-10001");

        assertThat(result).isNotNull();
        assertThat(result.procedure()).isEqualTo("CANCELLATION");
        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(result.orderReference()).isEqualTo("ORD-10001");
    }

    @Test
    void requestReturnReturnsProcedureToolResult() {
        when(coordinator.startReturn(any(), eq("ORD-10006"), eq("Running Shoes"), eq(null), isNull()))
                .thenReturn(outcome(false, "REASON_REQUIRED", "Could you tell me why you'd like to return the Running Shoes?",
                        Map.of("orderReference", "ORD-10006", "itemName", "Running Shoes")));

        ProcedureToolResult result = tools.requestReturn("ORD-10006", "Running Shoes", null, null);

        assertThat(result).isNotNull();
        assertThat(result.procedure()).isEqualTo("RETURN");
        assertThat(result.code()).isEqualTo("REASON_REQUIRED");
        assertThat(result.details()).containsEntry("itemName", "Running Shoes");
    }

    @Test
    void requestClaimReturnsProcedureToolResult() {
        when(coordinator.startClaim(any(), eq("ORD-10009"), eq("Coffee Maker"), eq(null)))
                .thenReturn(outcome(false, "PROBLEM_REQUIRED", "Could you tell me what happened with the Coffee Maker?",
                        Map.of("orderReference", "ORD-10009", "itemName", "Coffee Maker")));

        ProcedureToolResult result = tools.requestClaim("ORD-10009", "Coffee Maker", null);

        assertThat(result).isNotNull();
        assertThat(result.procedure()).isEqualTo("CLAIM");
        assertThat(result.code()).isEqualTo("PROBLEM_REQUIRED");
        assertThat(result.nextAction()).isEqualTo(ProcedureNextAction.ASK_PROBLEM_DESCRIPTION);
    }

    @Test
    void requestHumanSupportReturnsProcedureToolResult() {
        when(coordinator.requestHumanSupport(any(), eq("need a person")))
                .thenReturn(outcome(true, "ESCALATED", "I've let our support team know - reference TKT-5001.",
                        Map.of("ticketNumber", "TKT-5001")));

        ProcedureToolResult result = tools.requestHumanSupport("need a person");

        assertThat(result).isNotNull();
        assertThat(result.procedure()).isEqualTo("HUMAN_SUPPORT");
        assertThat(result.code()).isEqualTo("ESCALATED");
        assertThat(result.details()).containsEntry("ticketNumber", "TKT-5001");
    }

    // ---- Task 13.5-13.9: stable next-action mapping ----

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "VERIFICATION_REQUIRED, ASK_VERIFICATION_CODE",
            "CONFIRMATION_REQUIRED, ASK_CONFIRMATION",
            "ITEM_REQUIRED, ASK_ITEM",
            "REASON_REQUIRED, ASK_RETURN_REASON",
            "PROBLEM_REQUIRED, ASK_PROBLEM_DESCRIPTION",
            "ALREADY_PENDING, ASK_VERIFICATION_CODE",
            "QUANTITY_REQUIRED, ASK_QUANTITY",
            "PROCEDURE_DEFERRED, COMPLETE_ACTIVE_PROCEDURE",
            "ALREADY_DEFERRED, COMPLETE_ACTIVE_PROCEDURE",
            "PENDING_REQUEST_LIMIT_REACHED, ASK_PROCEDURE_CHOICE",
            "DEFERRED_REQUEST_PENDING, ASK_PROCEDURE_CHOICE",
            "IDENTITY_NOT_VERIFIED, VERIFY_IDENTITY",
            "VERIFICATION_RATE_LIMITED, RETRY_LATER",
            "NOT_FOUND_FOR_ACCOUNT, NONE",
            "NOT_ELIGIBLE, CHECK_SUPPORT_OPTIONS",
            "ESCALATED, NONE",
            "ALREADY_ESCALATED, NONE",
            "SOME_FUTURE_CODE, NONE"
    })
    void outcomeCodesMapToStableNextActions(String code, String expectedAction) {
        Map<String, String> metadata = new java.util.HashMap<>(Map.of("orderReference", "ORD-10001"));
        // ALREADY_PENDING's next action is driven by the live procedure's
        // stage, carried as pendingStage metadata - mirror the coordinator.
        if ("ALREADY_PENDING".equals(code)) {
            metadata.put("pendingStage", "VERIFICATION_REQUIRED");
        }
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(true, code, "Some coordinator prose.", metadata));

        assertThat(result.nextAction()).isEqualTo(ProcedureNextAction.valueOf(expectedAction));
    }

    @Test
    void mapperNeverParsesCoordinatorMessage() {
        // A misleading message must not influence the model-facing result:
        // only the code and whitelisted metadata count.
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(false, "NOT_ELIGIBLE",
                        "Good news - this item is eligible for return!",
                        Map.of("orderReference", "ORD-10008", "itemName", "Clearance T-Shirt",
                                "denialReason", "ITEM_FINAL_SALE")));

        assertThat(result.code()).isEqualTo("NOT_ELIGIBLE");
        assertThat(result.nextAction()).isEqualTo(ProcedureNextAction.CHECK_SUPPORT_OPTIONS);
        assertThat(result.details()).containsEntry("denialReason", "ITEM_FINAL_SALE");
        assertThat(result.details().values().toString()).doesNotContain("eligible");
    }

    // ---- Task 13.10-13.12: deterministic denial facts preserved ----

    @Test
    void notEligiblePreservesDenialReason() {
        when(coordinator.startReturn(any(), eq("ORD-10008"), eq("Clearance T-Shirt"), eq("changed my mind"), isNull()))
                .thenReturn(outcome(false, "NOT_ELIGIBLE", "This item is not eligible for return (ITEM_FINAL_SALE).",
                        Map.of("orderReference", "ORD-10008", "itemName", "Clearance T-Shirt",
                                "denialReason", "ITEM_FINAL_SALE", "maxReturnableQuantity", "0")));

        ProcedureToolResult result = tools.requestReturn("ORD-10008", "Clearance T-Shirt", "changed my mind", null);

        assertThat(result.success()).isFalse();
        assertThat(result.nextAction()).isEqualTo(ProcedureNextAction.CHECK_SUPPORT_OPTIONS);
        assertThat(result.details())
                .containsEntry("itemName", "Clearance T-Shirt")
                .containsEntry("denialReason", "ITEM_FINAL_SALE")
                .containsEntry("maxReturnableQuantity", 0);
    }

    @Test
    void cancellationVerificationRequiredPreservesPaymentConsequence() {
        when(coordinator.startCancellation(any(), eq("ORD-10001")))
                .thenReturn(outcome(true, "VERIFICATION_REQUIRED", "A verification code was sent.",
                        Map.of("orderReference", "ORD-10001", "paymentConsequence", "VOID_AUTHORIZATION")));

        ProcedureToolResult result = tools.requestCancellation("ORD-10001");

        assertThat(result.nextAction()).isEqualTo(ProcedureNextAction.ASK_VERIFICATION_CODE);
        assertThat(result.details()).containsEntry("paymentConsequence", "VOID_AUTHORIZATION");
    }

    @Test
    void ambiguousItemResultsExposeCandidateNamesWithoutSku() {
        when(coordinator.startReturn(any(), eq("ORD-10002"), eq("shoes"), eq(null), isNull()))
                .thenReturn(outcome(false, "ITEM_REQUIRED", "This order has a few items that could match: Running Shoes, Tennis Shoes. Which one did you mean?",
                        Map.of("orderReference", "ORD-10002",
                                "candidateItem.1", "Running Shoes",
                                "candidateItem.2", "Tennis Shoes")));

        ProcedureToolResult result = tools.requestReturn("ORD-10002", "shoes", null, null);

        assertThat(result.nextAction()).isEqualTo(ProcedureNextAction.ASK_ITEM);
        assertThat(result.details()).containsEntry("candidateItems", java.util.List.of("Running Shoes", "Tennis Shoes"));
    }

    // ---- Task 13.13-13.14: no message text, no internal identifiers ----

    @Test
    void modelFacingJsonContainsNoCoordinatorMessage() throws Exception {
        String message = "Could you tell me why you'd like to return the Running Shoes - for example wrong size, damaged, or you changed your mind?";
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(false, "REASON_REQUIRED", message,
                        Map.of("orderReference", "ORD-10006", "itemName", "Running Shoes")));

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"code\":\"REASON_REQUIRED\"");
        assertThat(json).contains("\"nextAction\":\"ASK_RETURN_REASON\"");
        assertThat(json).contains("\"itemName\":\"Running Shoes\"");
        assertThat(json).doesNotContain("\"message\"");
        assertThat(json).doesNotContain("directMessage");
        assertThat(json).doesNotContain("customerMessage");
        assertThat(json).doesNotContain("Could you tell me why");
    }

    @Test
    void internalIdentifiersNeverReachModelFacingDetails() throws Exception {
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("orderReference", "ORD-10001");
        metadata.put("sku", "SKU-SECRET-1");
        metadata.put("itemId", "9f3c2a11-dead-beef-0000-123456789abc");
        metadata.put("customerId", "11111111-2222-3333-4444-555555555555");
        metadata.put("procedureId", "proc-123");
        metadata.put("challengeId", "ch-456");
        metadata.put("devOtp", "123456");
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(true, "VERIFICATION_REQUIRED", "A code was sent.", metadata));

        String json = objectMapper.writeValueAsString(result);

        assertThat(result.details().keySet()).isEmpty();
        assertThat(json)
                .doesNotContain("SKU-SECRET-1")
                .doesNotContain("procedureId")
                .doesNotContain("challengeId")
                .doesNotContain("devOtp")
                .doesNotContain("9f3c2a11");
    }

    // ---- Task 13.15-13.16: structured context for confirmation/escalation ----

    @Test
    void claimConfirmationResultCarriesStructuredContext() {
        when(coordinator.startClaim(any(), eq("ORD-10009"), eq("Coffee Maker"), eq("it arrived damaged")))
                .thenReturn(outcome(true, "CONFIRMATION_REQUIRED", "Just to confirm - you'd like to file a claim?",
                        Map.of("orderReference", "ORD-10009", "itemName", "Coffee Maker",
                                "claimReason", "DAMAGED", "problemDescription", "it arrived damaged")));

        ProcedureToolResult result = tools.requestClaim("ORD-10009", "Coffee Maker", "it arrived damaged");

        assertThat(result.nextAction()).isEqualTo(ProcedureNextAction.ASK_CONFIRMATION);
        assertThat(result.details())
                .containsEntry("itemName", "Coffee Maker")
                .containsEntry("claimReason", "DAMAGED")
                .containsEntry("problemDescription", "it arrived damaged");
    }

    @Test
    void alreadyEscalatedExposesTicketNumber() {
        when(coordinator.requestHumanSupport(any(), eq("still waiting")))
                .thenReturn(outcome(true, "ALREADY_ESCALATED", "You're already connected to our support team on ticket TKT-5001.",
                        Map.of("ticketNumber", "TKT-5001")));

        ProcedureToolResult result = tools.requestHumanSupport("still waiting");

        assertThat(result.code()).isEqualTo("ALREADY_ESCALATED");
        assertThat(result.nextAction()).isEqualTo(ProcedureNextAction.NONE);
        assertThat(result.details()).containsEntry("ticketNumber", "TKT-5001");
    }

    // ---- Task 13.17-13.18: deterministic runtime contract is untouched ----

    @Test
    void coordinatorRuntimeMethodsStillReturnProcedureOutcome() throws Exception {
        assertThat(ProcedureCoordinator.class
                .getMethod("submitVerificationCode", ConversationSession.class, String.class).getReturnType())
                .isEqualTo(ProcedureOutcome.class);
        assertThat(ProcedureCoordinator.class
                .getMethod("confirmActive", ConversationSession.class).getReturnType())
                .isEqualTo(ProcedureOutcome.class);
        assertThat(ProcedureCoordinator.class
                .getMethod("declineActive", ConversationSession.class).getReturnType())
                .isEqualTo(ProcedureOutcome.class);
        assertThat(ProcedureCoordinator.class
                .getMethod("resendVerificationCode", ConversationSession.class).getReturnType())
                .isEqualTo(ProcedureOutcome.class);
    }

    @Test
    void procedureOutcomeStillCarriesDirectMessageForRuntime() {
        // The runtime/direct path keeps the deterministic English message;
        // it is the tool-mediated path that switched to ProcedureToolResult.
        assertThat(ProcedureOutcome.class.getRecordComponents())
                .extracting("name")
                .containsExactlyInAnyOrder("success", "code", "message", "metadata");

        ProcedureOutcome outcome = ProcedureOutcome.error("VERIFICATION_FAILED", "That code didn't match - please try again.");
        assertThat(outcome.message()).isEqualTo("That code didn't match - please try again.");
    }

    @Test
    void toolMethodsDeclareModelFacingReturnType() throws Exception {
        assertThat(ProcedureRequestTools.class.getMethod("requestCancellation", String.class).getReturnType())
                .isEqualTo(ProcedureToolResult.class);
        assertThat(ProcedureRequestTools.class.getMethod("requestReturn", String.class, String.class, String.class, String.class).getReturnType())
                .isEqualTo(ProcedureToolResult.class);
        assertThat(ProcedureRequestTools.class.getMethod("requestClaim", String.class, String.class, String.class).getReturnType())
                .isEqualTo(ProcedureToolResult.class);
        assertThat(ProcedureRequestTools.class.getMethod("requestHumanSupport", String.class).getReturnType())
                .isEqualTo(ProcedureToolResult.class);
    }

    // ---- Pass 2B cleanup: orderReference only at top level ----

    @Test
    void orderReferencePromotedToTopLevelAndRemovedFromDetails() {
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(false, "REASON_REQUIRED", "Some coordinator prose.",
                        Map.of("orderReference", "ORD-10001", "itemName", "Running Shoes")));

        assertThat(result.orderReference()).isEqualTo("ORD-10001");
        assertThat(result.details()).doesNotContainKey("orderReference");
        assertThat(result.details()).containsEntry("itemName", "Running Shoes");
    }

    @Test
    void serializedJsonContainsOrderReferenceExactlyOnce() throws Exception {
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(false, "REASON_REQUIRED", "Some coordinator prose.",
                        Map.of("orderReference", "ORD-10001", "itemName", "Running Shoes")));

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"orderReference\":\"ORD-10001\"");
        assertThat(json.split("\"orderReference\"", -1)).hasSize(2);
        assertThat(json).doesNotContain("\"details\":{\"orderReference\"");
    }

    @Test
    void toolResultWithoutOrderMetadataStillSerializes() throws Exception {
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult(
                ProcedureToolResultMapper.HUMAN_SUPPORT,
                outcome(true, "ESCALATED", "Support team notified.", Map.of("ticketNumber", "TKT-5001")));

        String json = objectMapper.writeValueAsString(result);

        assertThat(result.orderReference()).isNull();
        assertThat(result.details()).containsEntry("ticketNumber", "TKT-5001");
        assertThat(json).doesNotContain("\"orderReference\":\"ORD");
    }

    // ---- Pass 2B cleanup: maxReturnableQuantity typed as integer ----

    @Test
    void maxReturnableQuantityIsIntegerInDetails() {
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(false, "NOT_ELIGIBLE", "This item is not eligible for return.",
                        Map.of("orderReference", "ORD-10008", "itemName", "Clearance T-Shirt",
                                "denialReason", "ITEM_FINAL_SALE", "maxReturnableQuantity", "3")));

        assertThat(result.details().get("maxReturnableQuantity"))
                .isInstanceOf(Integer.class)
                .isEqualTo(3);
    }

    @Test
    void serializedJsonRendersMaxReturnableQuantityAsNumber() throws Exception {
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(false, "NOT_ELIGIBLE", "This item is not eligible for return.",
                        Map.of("orderReference", "ORD-10008", "itemName", "Clearance T-Shirt",
                                "denialReason", "ITEM_FINAL_SALE", "maxReturnableQuantity", "0")));

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"maxReturnableQuantity\":0");
        assertThat(json).doesNotContain("\"maxReturnableQuantity\":\"0\"");
    }

    @Test
    void malformedMaxReturnableQuantityMetadataIsOmitted() throws Exception {
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("RETURN",
                outcome(false, "NOT_ELIGIBLE", "This item is not eligible for return.",
                        Map.of("orderReference", "ORD-10008", "denialReason", "ITEM_FINAL_SALE",
                                "maxReturnableQuantity", "not-a-number")));

        assertThat(result.details()).doesNotContainKey("maxReturnableQuantity");
        assertThat(objectMapper.writeValueAsString(result)).doesNotContain("maxReturnableQuantity");
    }

    // ---- Pass 2B cleanup: whitelist otherwise unchanged ----

    @Test
    void allOtherSafeWhitelistFieldsStillMap() {
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("orderReference", "ORD-10009");
        metadata.put("itemName", "Coffee Maker");
        metadata.put("denialReason", "ITEM_NOT_DELIVERED");
        metadata.put("paymentConsequence", "REFUND_REQUIRED");
        metadata.put("returnNumber", "RET-7001");
        metadata.put("returnReason", "DAMAGED");
        metadata.put("claimNumber", "CLM-8001");
        metadata.put("claimReason", "MISSING_ITEM");
        metadata.put("problemDescription", "box arrived empty");
        metadata.put("ticketNumber", "TKT-5001");

        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("CLAIM",
                outcome(true, "CONFIRMATION_REQUIRED", "Just to confirm?", metadata));

        assertThat(result.orderReference()).isEqualTo("ORD-10009");
        assertThat(result.details())
                .containsEntry("itemName", "Coffee Maker")
                .containsEntry("denialReason", "ITEM_NOT_DELIVERED")
                .containsEntry("paymentConsequence", "REFUND_REQUIRED")
                .containsEntry("returnNumber", "RET-7001")
                .containsEntry("returnReason", "DAMAGED")
                .containsEntry("claimNumber", "CLM-8001")
                .containsEntry("claimReason", "MISSING_ITEM")
                .containsEntry("problemDescription", "box arrived empty")
                .containsEntry("ticketNumber", "TKT-5001")
                .doesNotContainKey("orderReference");
    }

    // ---- Pass 2C (Task 18): verification internals stay out of the model-facing contract ----

    @Test
    void pass2cVerificationMetadataIsNotCopiedIntoModelFacingDetails() {
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("CANCELLATION",
                outcome(true, "VERIFICATION_REQUIRED", "A code was sent.",
                        Map.of("orderReference", "ORD-10001",
                                "paymentConsequence", "NO_REFUND_REQUIRED",
                                "verificationIssue", "CHALLENGE_ISSUED",
                                "maskedDestination", "********4567",
                                "devOtp", "482916")));

        assertThat(result.details())
                .doesNotContainKeys("verificationIssue", "maskedDestination", "devOtp");
        assertThat(result.details()).containsEntry("paymentConsequence", "NO_REFUND_REQUIRED");
    }

    @Test
    void pass2cVerificationFailureReasonIsNotCopiedIntoModelFacingDetails() {
        ProcedureToolResult result = ProcedureToolResultMapper.toToolResult("CANCELLATION",
                outcome(false, "VERIFICATION_FAILED", "That code didn't match.",
                        Map.of("orderReference", "ORD-10001", "verificationReason", "WRONG_CODE")));

        assertThat(result.details()).doesNotContainKeys("verificationReason");
    }

}
