package com.voxticket.notification;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.policy.PaymentConsequence;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Customer emails for procedure events: cancellation, return requested,
 * claim filed, and refund initiated. One email per event, with the
 * reference numbers and amounts the customer needs - no separate refund
 * email for a cancellation, because the cancellation email already
 * carries the refund details.
 *
 * <p>Safety rules, in order. A notification must never fail, delay, or
 * roll back a procedure, and must never go out for a procedure that
 * did not commit:
 * <ul>
 *   <li>Disabled ({@code voxticket.notifications.email.enabled=false}),
 *       no SMTP host configured, or no customer email: skip, log, return.</li>
 *   <li>The message is fully built inside the calling transaction, so
 *       lazy associations (customer, items) are still readable.</li>
 *   <li>The send itself is deferred to after-commit when a transaction
 *       is active, and sent immediately otherwise (unit tests, admin
 *       calls outside a transaction). A send failure is logged, never thrown.</li>
 * </ul>
 *
 * <p>The From address reuses {@code voxticket.otp.email-from} (MAIL_FROM),
 * so there is one From to keep consistent with the mail provider - Gmail
 * rejects a From that does not match the authenticated account. No new
 * environment variable is needed: notifications use the existing MAIL_*
 * settings, and OTP delivery ({@code voxticket.otp.delivery}) stays
 * independent - OTP can stay on dev while notifications go by email.
 *
 * <p>No delivery timeline is promised in any email. Refunds are PENDING
 * at initiation and the system has no provider timeline to quote; an
 * earlier bug told customers a refund would arrive "in a few days" when
 * no such guarantee existed.
 */
@Component
public class ProcedureNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ProcedureNotificationService.class);

    private final JavaMailSender mailSender;
    private final String mailHost;
    private final String fromAddress;
    private final boolean enabled;

    public ProcedureNotificationService(
            JavaMailSender mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${voxticket.otp.email-from:no-reply@voxticket.example}") String fromAddress,
            @Value("${voxticket.notifications.email.enabled:true}") boolean enabled) {
        this.mailSender = mailSender;
        this.mailHost = mailHost == null ? "" : mailHost;
        this.fromAddress = fromAddress;
        this.enabled = enabled;
    }

    /** Order cancelled, with the refund outcome (or manual-review ticket) included. No separate refund email follows. */
    public void notifyCancellation(Order order, PaymentConsequence consequence, Refund refund, SupportTicket manualReviewTicket) {
        Customer customer = order.getCustomer();
        String paymentLine = switch (consequence) {
            case NO_REFUND_REQUIRED -> "No payment was collected, so there is nothing to refund.";
            case VOID_AUTHORIZATION -> "The payment was only authorized and not captured, so there is nothing to refund.";
            case REFUND_REQUIRED -> refund == null
                    ? "A refund has been initiated for this order."
                    : "A refund has been initiated: " + refundLine(refund, order) + ".";
            case MANUAL_REVIEW_REQUIRED -> manualReviewTicket == null
                    ? "The payment on this order needs manual review before a refund decision can be made."
                    : "The payment on this order needs manual review before a refund decision can be made. "
                            + "Support ticket: " + manualReviewTicket.getTicketNumber() + ".";
        };
        send(customer,
                "Your VoxTicket order " + order.getOrderNumber() + " has been cancelled",
                "Hi " + customer.getFullName() + ",\n\n"
                        + "Your order " + order.getOrderNumber() + " has been cancelled.\n\n"
                        + paymentLine + "\n\n"
                        + "If you did not request this cancellation, please contact VoxTicket support.\n\n"
                        + "VoxTicket",
                "cancellation", order.getOrderNumber());
    }

    /** Return requested by the customer (or via admin): requested, awaiting approval. */
    public void notifyReturnRequested(ReturnRequest returnRequest) {
        Order order = returnRequest.getOrder();
        StringBuilder items = new StringBuilder();
        returnRequest.getItems().forEach(item -> items.append("- ")
                .append(item.getOrderItem().getProductName())
                .append(" (").append(item.getOrderItem().getSku()).append(")")
                .append(", quantity ").append(item.getQuantity())
                .append("\n"));
        send(order.getCustomer(),
                "Your VoxTicket return " + returnRequest.getReturnNumber() + " has been requested",
                "Hi " + order.getCustomer().getFullName() + ",\n\n"
                        + "A return has been requested for order " + order.getOrderNumber() + ".\n\n"
                        + "Return reference: " + returnRequest.getReturnNumber() + "\n"
                        + "Reason: " + returnRequest.getReason() + "\n"
                        + "Status: " + returnRequest.getStatus() + "\n"
                        + "Items:\n" + items
                        + "\nYour return is now awaiting approval. We will email you again when its status changes.\n\n"
                        + "VoxTicket",
                "return-requested", returnRequest.getReturnNumber());
    }

    /** Claim filed by the customer: open, with a support ticket for manual review. */
    public void notifyClaimFiled(OrderClaim claim) {
        Order order = claim.getOrder();
        String ticketLine = claim.getSupportTicket() == null
                ? ""
                : "Support ticket: " + claim.getSupportTicket().getTicketNumber() + "\n";
        send(order.getCustomer(),
                "Your VoxTicket claim " + claim.getClaimNumber() + " has been filed",
                "Hi " + order.getCustomer().getFullName() + ",\n\n"
                        + "A claim has been filed for order " + order.getOrderNumber() + ".\n\n"
                        + "Claim reference: " + claim.getClaimNumber() + "\n"
                        + "Item: " + claim.getOrderItem().getProductName() + " (" + claim.getOrderItem().getSku() + ")\n"
                        + "Reason: " + claim.getReason() + "\n"
                        + "Status: " + claim.getStatus() + "\n"
                        + ticketLine
                        + "\nOur team will review your claim. We will email you again when it is resolved.\n\n"
                        + "VoxTicket",
                "claim-filed", claim.getClaimNumber());
    }

    /**
     * Refund initiated outside a cancellation: after a return inspection
     * passes, or after a claim is resolved with a refund. The context
     * string names what triggered it, e.g. "Return R-123" or "Claim C-123".
     */
    public void notifyRefundInitiated(Refund refund, String context) {
        Order order = refund.getOrder();
        send(order.getCustomer(),
                "A refund has been initiated for your VoxTicket order " + order.getOrderNumber(),
                "Hi " + order.getCustomer().getFullName() + ",\n\n"
                        + "A refund has been initiated for order " + order.getOrderNumber() + ".\n\n"
                        + "Triggered by: " + context + "\n"
                        + refundLine(refund, order) + "\n"
                        + "Status: " + refund.getStatus() + "\n\n"
                        + "VoxTicket",
                "refund-initiated", refund.getRefundNumber());
    }

    private String refundLine(Refund refund, Order order) {
        return "Refund " + refund.getRefundNumber() + " for " + order.getCurrency() + " "
                + amount(refund.getAmount()) + " (status: " + refund.getStatus() + ")";
    }

    private String amount(BigDecimal amount) {
        return amount == null ? "0" : amount.toPlainString();
    }

    private void send(Customer customer, String subject, String body, String event, String reference) {
        if (!enabled) {
            log.debug("event=procedure_notification_skipped reason=disabled event={} reference={}", event, reference);
            return;
        }
        if (mailHost.isBlank()) {
            log.info("event=procedure_notification_skipped reason=no_smtp_host event={} reference={}", event, reference);
            return;
        }
        if (customer == null || customer.getEmail() == null || customer.getEmail().isBlank()) {
            log.warn("event=procedure_notification_skipped reason=no_customer_email event={} reference={}", event, reference);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(customer.getEmail());
        message.setSubject(subject);
        message.setText(body);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(message, event, reference);
                }
            });
        } else {
            doSend(message, event, reference);
        }
    }

    private void doSend(SimpleMailMessage message, String event, String reference) {
        try {
            mailSender.send(message);
            log.info("event=procedure_notification_sent event={} reference={}", event, reference);
        } catch (Exception e) {
            // Never fail the procedure because mail failed.
            log.warn("event=procedure_notification_failed event={} reference={} error={}", event, reference, e.getMessage());
        }
    }
}
