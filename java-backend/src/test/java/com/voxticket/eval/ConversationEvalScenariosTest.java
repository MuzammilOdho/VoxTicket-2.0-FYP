package com.voxticket.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ClarificationPolicy;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.DialogueSlotNames;
import com.voxticket.conversation.DialogueState;
import com.voxticket.conversation.DirectProcedureResponseRenderer;
import com.voxticket.conversation.InterruptionClass;
import com.voxticket.conversation.Slot;
import com.voxticket.conversation.SlotProvenance;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.ActionPolicyService;
import com.voxticket.policy.CancellationEligibility;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.PaymentConsequence;
import com.voxticket.policy.PolicyDecision;
import com.voxticket.policy.ReturnEligibility;
import com.voxticket.policy.ReturnPolicyService;
import com.voxticket.procedure.DeferredProcedureIntent;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureType;
import com.voxticket.service.CancellationService;
import com.voxticket.service.ClaimService;
import com.voxticket.service.ReferenceNumberGenerator;
import com.voxticket.service.ReturnService;
import com.voxticket.verification.VerificationOutcome;
import com.voxticket.verification.VerificationResult;
import com.voxticket.verification.VerificationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3.3: scenario-based evals against the real deterministic pipeline
 * (procedure layer + P2 dialogue state). Each scenario is a scripted
 * conversation: the drive lambdas stand in for the LLM's tool-call
 * proposals, and the assertions pin procedure transitions, dialogue state,
 * and rendered deterministic copy. No model, no network, no Docker.
 */
class ConversationEvalScenariosTest {

    private ProcedureCoordinator coordinator;
    private VerificationService verificationService;
    private CancellationService cancellationService;
    private DirectProcedureResponseRenderer renderer;
    private ScenarioRunner runner;
    private final AtomicReference<ScenarioDriver> currentDriver = new AtomicReference<>();

    private final Map<String, VerifiedOrderRef> refs = new HashMap<>();
    private final Map<UUID, Order> orders = new HashMap<>();
    private UUID customerId;

    @BeforeEach
    void setUp() {
        OwnedOrderResolver ownedOrderResolver = mock(OwnedOrderResolver.class);
        OwnedOrderItemResolver ownedOrderItemResolver = mock(OwnedOrderItemResolver.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        CancellationPolicyService cancellationPolicyService = mock(CancellationPolicyService.class);
        ReturnPolicyService returnPolicyService = mock(ReturnPolicyService.class);
        verificationService = mock(VerificationService.class);
        cancellationService = mock(CancellationService.class);
        ReturnService returnService = mock(ReturnService.class);
        ClaimService claimService = mock(ClaimService.class);
        ActionPolicyService actionPolicyService = mock(ActionPolicyService.class);
        SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        ReferenceNumberGenerator referenceNumberGenerator = mock(ReferenceNumberGenerator.class);
        renderer = new DirectProcedureResponseRenderer();

        coordinator = new ProcedureCoordinator(
                ownedOrderResolver, ownedOrderItemResolver, actionPolicyService,
                orderRepository, paymentRepository, customerRepository, supportTicketRepository,
                cancellationPolicyService, returnPolicyService, cancellationService,
                returnService, claimService, referenceNumberGenerator,
                verificationService, mock(TurnMetrics.class), mock(ConversationAuditService.class));

        customerId = UUID.randomUUID();
        Customer customer = new Customer("Test", "User", "user." + UUID.randomUUID() + "@example.pk", "+923001234567");
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        addOrder("ORD-10001");
        addOrder("ORD-10002");

        when(ownedOrderResolver.resolve(any(), any()))
                .thenAnswer(inv -> refs.get(inv.getArgument(1)));
        when(orderRepository.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(orders.get(inv.getArgument(0))));
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(any()))
                .thenAnswer(inv -> {
                    UUID orderId = inv.getArgument(0);
                    Order order = orders.get(orderId);
                    return Optional.of(new Payment(order, PaymentMethod.CARD, BigDecimal.TEN, "PKR", PaymentStatus.PAID));
                });
        when(cancellationPolicyService.evaluate(any(), any()))
                .thenReturn(CancellationEligibility.eligible(PaymentConsequence.NO_REFUND_REQUIRED));
        when(returnPolicyService.evaluate(any(), any()))
                .thenReturn(ReturnEligibility.eligible(5));
        when(verificationService.issueChallenge(any(), any(), any(), any()))
                .thenReturn(VerificationOutcome.challengeIssued("A verification code was sent.", Map.of()));
        when(verificationService.verify(any(), any(), any(), any()))
                .thenReturn(VerificationResult.success());
        when(actionPolicyService.evaluate(any(), any()))
                .thenReturn(PolicyDecision.allow());
        // Mutations are counted through the driver, never executed for real.
        when(cancellationService.cancel(any())).thenAnswer(inv -> {
            ScenarioDriver driver = currentDriver.get();
            if (driver != null) {
                driver.noteMutation("cancellation");
            }
            VerifiedOrderRef ref = inv.getArgument(0);
            return new CancellationService.CancellationResult(
                    ref.orderNumber(), PaymentConsequence.NO_REFUND_REQUIRED, null, null);
        });

        runner = new ScenarioRunner(() -> {
            ConversationSession session = ConversationSession.newSession(
                    "eval-" + UUID.randomUUID(), Channel.CHAT);
            session.applyResolvedIdentity(
                    new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567"));
            ScenarioDriver driver = new ScenarioDriver(session, coordinator, renderer);
            currentDriver.set(driver);
            return driver;
        });
    }

    private void addOrder(String orderNumber) {
        UUID orderId = UUID.randomUUID();
        Customer customer = new Customer("Test", "User", "user." + orderNumber + "@example.pk", "+923001234567");
        Order order = new Order(orderNumber, customer, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());
        order.addItem(new OrderItem("Blue Widget", "SKU-10001", 2, BigDecimal.TEN, true, false));
        orders.put(orderId, order);
        refs.put(orderNumber, new VerifiedOrderRef(orderId, orderNumber, customerId, IdentityAssurance.PHONE_MATCHED, Instant.now()));
    }

    @Test
    void happyPathCancellationWithOtp() {
        runner.run(EvalScenario.named("happy-path-cancellation")
                .step("cancel my order ORD-10001",
                        driver -> driver.startCancellation("ORD-10001"),
                        observed -> {
                            assertThat(observed.activeProcedureType()).isEqualTo("CANCELLATION");
                            assertThat(observed.activeProcedureStatus()).isEqualTo("AWAITING_VERIFICATION");
                            assertThat(observed.lastOutcomeCode()).isEqualTo("VERIFICATION_REQUIRED");
                            assertThat(observed.mutationsExecuted()).isEmpty();
                        })
                .step("my code is 482916",
                        driver -> driver.submitCode("482916"),
                        observed -> {
                            assertThat(observed.lastOutcomeCode()).isEqualTo("CANCELLED");
                            assertThat(observed.completedProcedures()).containsExactly("CANCELLATION");
                            assertThat(observed.activeProcedureType()).isNull();
                            assertThat(observed.mutationsExecuted()).containsExactly("cancellation");
                        })
                .build());
    }

    @Test
    void noMutationBeforeVerificationCompletes() {
        runner.run(EvalScenario.named("no-mutation-before-verification")
                .step("cancel my order ORD-10001",
                        driver -> driver.startCancellation("ORD-10001"),
                        observed -> {
                            assertThat(observed.activeProcedureStatus()).isEqualTo("AWAITING_VERIFICATION");
                            assertThat(observed.mutationsExecuted()).isEmpty();
                            assertThat(observed.completedProcedures()).isEmpty();
                        })
                .build());
    }

    @Test
    void bargeInCorrectionMergesInsteadOfRestarting() {
        runner.run(EvalScenario.named("barge-in-correction")
                .step("cancel my order ORD-10001",
                        driver -> {
                            driver.startCancellation("ORD-10001");
                            driver.setSlot(new Slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001",
                                    1.0, SlotProvenance.USER_SAID, 1));
                        },
                        observed -> assertThat(observed.slots())
                                .containsEntry(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001"))
                .step("no wait, I meant ORD-10002",
                        driver -> {
                            var signal = driver.classifyInterruption(
                                    "no wait, I meant ORD-10002", "Cancelling order ORD-10001.");
                            assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.CORRECTION);
                            // The runtime merges: record the antecedent and update the slot,
                            // keeping the rest of the request (the procedure) intact.
                            driver.recordCorrection(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 2);
                            driver.setSlot(new Slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10002",
                                    1.0, SlotProvenance.USER_SAID, 2));
                        },
                        observed -> {
                            assertThat(observed.correctionCount()).isEqualTo(1);
                            assertThat(observed.slots())
                                    .containsEntry(DialogueSlotNames.ORDER_REFERENCE, "ORD-10002");
                            assertThat(observed.activeProcedureType()).isEqualTo("CANCELLATION");
                        })
                .build());
    }

    @Test
    void withdrawalPopsPendingStack() {
        runner.run(EvalScenario.named("withdrawal-pops-stack")
                .step("cancel ORD-10001, and also check ORD-10002",
                        driver -> {
                            driver.startCancellation("ORD-10001");
                            driver.pushPending(new DeferredProcedureIntent(
                                    ProcedureType.CANCELLATION, "ORD-10002", UUID.randomUUID(),
                                    null, null, null, null, null, Instant.now()));
                        },
                        observed -> assertThat(observed.pendingStackDepth()).isEqualTo(1))
                .step("never mind, forget it",
                        driver -> {
                            var signal = driver.classifyInterruption(
                                    "never mind, forget it", "Cancelling order ORD-10001.");
                            assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.WITHDRAWAL);
                            driver.popPending();
                        },
                        observed -> assertThat(observed.pendingStackDepth()).isEqualTo(0))
                .build());
    }

    @Test
    void lowConfidenceSlotGetsTargetedClarification() {
        runner.run(EvalScenario.named("low-confidence-clarification")
                .step("cancel the order... I think it was 10001?",
                        driver -> {
                            driver.setSlot(new Slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001",
                                    0.6, SlotProvenance.INFERRED, 1));
                            assertThat(driver.checkClarification(DialogueSlotNames.ORDER_REFERENCE))
                                    .isEqualTo(ClarificationPolicy.Decision.TARGETED_CLARIFICATION);
                            driver.renderClarification(DialogueSlotNames.ORDER_REFERENCE);
                        },
                        observed -> {
                            // Targeted clarification names the digit tail,
                            // never a bare "which order?".
                            assertThat(observed.renderedReply()).contains("0001");
                            assertThat(observed.mutationsExecuted()).isEmpty();
                        })
                .build());
    }

    @Test
    void threeStrikesOnShakySlotOffersHandoff() {
        runner.run(EvalScenario.named("three-strikes-handoff")
                .step("cancel... uh... the order... maybe 10001?",
                        driver -> {
                            driver.recordStrike(DialogueSlotNames.ORDER_REFERENCE, 0.4);
                            driver.recordStrike(DialogueSlotNames.ORDER_REFERENCE, 0.35);
                            int strikes = driver.recordStrike(DialogueSlotNames.ORDER_REFERENCE, 0.4);
                            assertThat(strikes).isEqualTo(DialogueState.LOW_CONFIDENCE_STRIKE_LIMIT);
                        },
                        observed -> assertThat(observed.slotStrikes())
                                .isEqualTo(DialogueState.LOW_CONFIDENCE_STRIKE_LIMIT))
                .build());
    }

    @Test
    void idempotentResendDoesNotSpamCodes() {
        runner.run(EvalScenario.named("idempotent-resend")
                .step("cancel my order ORD-10001",
                        driver -> driver.startCancellation("ORD-10001"),
                        observed -> assertThat(observed.lastOutcomeCode()).isEqualTo("VERIFICATION_REQUIRED"))
                .step("I didn't get the code",
                        driver -> {
                            driver.resend("key-1");
                            driver.resend("key-1");
                        },
                        observed -> assertThat(observed.lastOutcomeCode()).isEqualTo("VERIFICATION_REQUIRED"))
                .build());
        // 1 challenge from startCancellation + 1 from the first resend; the
        // replay must not re-issue (a transport retry must not spam codes).
        verify(verificationService, times(2)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void pendingStackPromotesLifo() {
        runner.run(EvalScenario.named("pending-stack-lifo")
                .step("cancel ORD-10001, then also cancel ORD-10002",
                        driver -> {
                            driver.startCancellation("ORD-10001");
                            driver.pushPending(new DeferredProcedureIntent(
                                    ProcedureType.CANCELLATION, "ORD-10002", UUID.randomUUID(),
                                    null, null, null, null, null, Instant.now()));
                        },
                        observed -> {
                            assertThat(observed.activeProcedureType()).isEqualTo("CANCELLATION");
                            assertThat(observed.pendingStackDepth()).isEqualTo(1);
                        })
                .step("my code is 482916",
                        driver -> {
                            driver.submitCode("482916");
                            // First procedure done: promote the queued one.
                            var promoted = driver.promotePending();
                            assertThat(promoted).isPresent();
                        },
                        observed -> {
                            assertThat(observed.completedProcedures()).containsExactly("CANCELLATION");
                            assertThat(observed.pendingStackDepth()).isEqualTo(0);
                        })
                .build());
    }
}
