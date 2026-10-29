package com.voxticket.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.policy.PaymentConsequence;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Procedure notification emails, with a mocked mail sender and no Spring
 * context: content, recipients, and the safety rules (disabled, no SMTP
 * host, no customer email, sender failure) are all verified here. No
 * transaction is active in these tests, so sends happen immediately
 * rather than after commit - the after-commit path is exercised by the
 * service-level integration tests, which run inside transactions.
 */
class ProcedureNotificationServiceTest {

    private static final String FROM = "no-reply@voxticket.example";

    private Customer customer() {
        return new Customer("Test", "User", "test@example.pk", "+923001234567");
    }

    private Order order(Customer customer) {
        return new Order("ORD-10001", customer, "PKR", new BigDecimal("1000.00"),
                BigDecimal.ZERO, new BigDecimal("1000.00"), Instant.now());
    }

    private OrderItem item(Order order) {
        OrderItem item = new OrderItem("Test Product", "SKU-1", 1, new BigDecimal("1000.00"), true, false);
        order.addItem(item);
        return item;
    }

    private Refund refund(Order order) {
        return new Refund("REF-10001", order, null, null, new BigDecimal("1000.00"), RefundReason.CANCELLATION);
    }

    private SimpleMailMessage sentMessage(JavaMailSender mailSender) {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void cancellationEmailCarriesOrderAndRefundDetails() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, true);
        Order order = order(customer());
        Refund refund = refund(order);

        service.notifyCancellation(order, PaymentConsequence.REFUND_REQUIRED, refund, null);

        SimpleMailMessage message = sentMessage(mailSender);
        assertThat(message.getTo()).containsExactly("test@example.pk");
        assertThat(message.getFrom()).isEqualTo(FROM);
        assertThat(message.getSubject()).contains("ORD-10001");
        assertThat(message.getText()).contains("ORD-10001").contains("REF-10001").contains("1000.00");
    }

    @Test
    void cancellationWithoutRefundSaysSoInsteadOfPromisingOne() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, true);

        service.notifyCancellation(order(customer()), PaymentConsequence.NO_REFUND_REQUIRED, null, null);

        SimpleMailMessage message = sentMessage(mailSender);
        assertThat(message.getText()).contains("nothing to refund");
        assertThat(message.getText()).doesNotContain("REF-");
    }

    @Test
    void cancellationWithManualReviewIncludesTicketNumber() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, true);
        Order order = order(customer());
        SupportTicket ticket = new SupportTicket("TKT-10001", order.getCustomer(), order,
                TicketCategory.PAYMENT, TicketPriority.HIGH, "Payment needs manual review.");

        service.notifyCancellation(order, PaymentConsequence.MANUAL_REVIEW_REQUIRED, null, ticket);

        assertThat(sentMessage(mailSender).getText()).contains("TKT-10001");
    }

    @Test
    void returnRequestedEmailCarriesReturnAndItemDetails() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, true);
        Order order = order(customer());
        OrderItem orderItem = item(order);
        ReturnRequest returnRequest = new ReturnRequest("RET-10001", order, ReturnReason.DEFECTIVE);
        returnRequest.addItem(new ReturnItem(orderItem, 1, ReturnReason.DEFECTIVE));

        service.notifyReturnRequested(returnRequest);

        SimpleMailMessage message = sentMessage(mailSender);
        assertThat(message.getTo()).containsExactly("test@example.pk");
        assertThat(message.getSubject()).contains("RET-10001");
        assertThat(message.getText())
                .contains("ORD-10001").contains("Test Product").contains("SKU-1").contains("DEFECTIVE");
    }

    @Test
    void claimFiledEmailCarriesClaimAndItemDetails() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, true);
        Order order = order(customer());
        OrderItem orderItem = item(order);
        OrderClaim claim = new OrderClaim("CLM-10001", order, orderItem,
                ClaimReason.DAMAGED, ClaimResolution.MANUAL_REVIEW);

        service.notifyClaimFiled(claim);

        SimpleMailMessage message = sentMessage(mailSender);
        assertThat(message.getTo()).containsExactly("test@example.pk");
        assertThat(message.getSubject()).contains("CLM-10001");
        assertThat(message.getText())
                .contains("ORD-10001").contains("Test Product").contains("DAMAGED");
    }

    @Test
    void refundInitiatedEmailNamesItsTriggerAndAmount() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, true);

        service.notifyRefundInitiated(refund(order(customer())), "Return RET-10001");

        SimpleMailMessage message = sentMessage(mailSender);
        assertThat(message.getSubject()).contains("ORD-10001");
        assertThat(message.getText()).contains("RET-10001").contains("REF-10001").contains("1000.00");
    }

    @Test
    void disabledNotificationsNeverSend() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, false);

        service.notifyCancellation(order(customer()), PaymentConsequence.NO_REFUND_REQUIRED, null, null);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void blankSmtpHostSkipsSendingInsteadOfFailing() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "", FROM, true);

        assertThatCode(() -> service.notifyCancellation(
                order(customer()), PaymentConsequence.NO_REFUND_REQUIRED, null, null))
                .doesNotThrowAnyException();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void blankCustomerEmailSkipsSendingInsteadOfFailing() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, true);
        Customer noEmail = new Customer("Test", "User", "", "+923001234567");

        assertThatCode(() -> service.notifyCancellation(
                order(noEmail), PaymentConsequence.NO_REFUND_REQUIRED, null, null))
                .doesNotThrowAnyException();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void mailSenderFailureNeverPropagatesToTheProcedure() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new RuntimeException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));
        ProcedureNotificationService service =
                new ProcedureNotificationService(mailSender, "smtp.gmail.com", FROM, true);

        assertThatCode(() -> service.notifyCancellation(
                order(customer()), PaymentConsequence.NO_REFUND_REQUIRED, null, null))
                .doesNotThrowAnyException();
    }
}
