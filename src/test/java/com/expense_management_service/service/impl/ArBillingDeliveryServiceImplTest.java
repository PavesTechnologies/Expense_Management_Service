package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.config.ArBillingMessagingConfig;
import com.expense_management_service.dto.external.ArBillingMessage;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.InvoiceSync;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.enums.ArDeliveryStatus;
import com.expense_management_service.mapper.InvoiceSyncMapper;
import com.expense_management_service.repository.InvoiceSyncRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArBillingDeliveryServiceImplTest {

    @Mock private InvoiceSyncRepository invoiceSyncRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    /** What the scoped send inside {@code rabbitTemplate.invoke} talks to. */
    @Mock private RabbitOperations channel;

    private ArBillingDeliveryServiceImpl service;
    private InvoiceSync handoff;

    @BeforeEach
    void setUp() {
        service = new ArBillingDeliveryServiceImpl(invoiceSyncRepository, new InvoiceSyncMapper(), rabbitTemplate);
        ReflectionTestUtils.setField(service, "baseCurrencyCode", "INR");
        ReflectionTestUtils.setField(service, "maxAttempts", 10);
        ReflectionTestUtils.setField(service, "confirmTimeoutMs", 10_000L);
        when(rabbitTemplate.invoke(any())).thenAnswer(inv ->
                inv.<RabbitOperations.OperationsCallback<Object>>getArgument(0).doInRabbit(channel));
        when(invoiceSyncRepository.save(any(InvoiceSync.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).reportNumber("ER-9").employeeId("E1").build();
        ExpenseLineItem line = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report)
                .expenseDate(LocalDate.of(2026, 10, 10)).amount(new BigDecimal("2500.00")).baseAmount(new BigDecimal("2500.00"))
                .clientBillable(true).resolvedClientId(UUID.randomUUID()).resolvedClientName("Acme Corp")
                .project(ProjectCache.builder().projectId(UUID.randomUUID()).pmsProjectId(1001L).projectCode("PRJ-ACME").projectName("Acme").build())
                .build();
        handoff = InvoiceSync.builder().syncId(UUID.randomUUID()).lineItem(line).invoiceReference("INV-1")
                .syncStatus("HANDED_OFF").syncDate(LocalDateTime.now()).arDeliveryStatus(ArDeliveryStatus.PENDING).arDeliveryAttempts(0).build();
        when(invoiceSyncRepository.findById(handoff.getSyncId())).thenReturn(Optional.of(handoff));
    }

    private ArBillingMessage sentMessage() {
        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(channel).convertAndSend(eq(ArBillingMessagingConfig.EXCHANGE_NAME), eq(ArBillingMessagingConfig.ROUTING_KEY), message.capture());
        return (ArBillingMessage) message.getValue();
    }

    @Test
    void deliver_publishesTheBillingPayload_waitsForTheBrokerConfirm_andMarksItSent() {
        var response = service.deliver(handoff.getSyncId());

        ArBillingMessage message = sentMessage();
        assertThat(message.handoffId()).isEqualTo(handoff.getSyncId());
        assertThat(message.reprocess()).isFalse();
        assertThat(message.billing().pmsProjectId()).isEqualTo(1001L);
        assertThat(message.billing().costBasis()).isEqualByComparingTo("2500.00");
        assertThat(message.billing().handoffStatus()).isEqualTo("HANDED_OFF");
        assertThat(message.billing().invoiceReference()).isEqualTo("INV-1");
        verify(channel).waitForConfirmsOrDie(10_000L);

        assertThat(handoff.getArDeliveryStatus()).isEqualTo(ArDeliveryStatus.SENT);
        assertThat(handoff.getArDeliveredAt()).isNotNull();
        assertThat(handoff.getArDeliveryAttempts()).isEqualTo(1);
        assertThat(response.arDeliveryStatus()).isEqualTo("SENT");
    }

    @Test
    void deliver_whenTheBrokerIsDown_marksItFailed_withTheReason_andCountsTheAttempt() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused"))).when(channel)
                .convertAndSend(any(String.class), any(String.class), any(Object.class));

        var response = service.deliver(handoff.getSyncId());

        assertThat(handoff.getArDeliveryStatus()).isEqualTo(ArDeliveryStatus.FAILED);
        assertThat(handoff.getArDeliveryError()).contains("ConnectException").contains("Connection refused");
        assertThat(handoff.getArDeliveryAttempts()).isEqualTo(1);
        assertThat(handoff.getArDeliveredAt()).isNull();
        assertThat(response.arDeliveryStatus()).isEqualTo("FAILED");
        verify(invoiceSyncRepository).save(handoff);
    }

    @Test
    void deliver_whenTheBrokerDoesNotConfirm_isNotSent() {
        doThrow(new org.springframework.amqp.AmqpTimeoutException("Timed out waiting for confirms"))
                .when(channel).waitForConfirmsOrDie(anyLong());

        service.deliver(handoff.getSyncId());

        assertThat(handoff.getArDeliveryStatus()).isEqualTo(ArDeliveryStatus.FAILED);
        assertThat(handoff.getArDeliveryError()).contains("Timed out waiting for confirms");
    }

    @Test
    void deliver_ofAHandoffAlreadySent_sendsNothing() {
        handoff.setArDeliveryStatus(ArDeliveryStatus.SENT);

        service.deliver(handoff.getSyncId());

        verify(rabbitTemplate, never()).invoke(any());
    }

    @Test
    void aFailedAttemptThenASuccessfulRetry_clearsTheError() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .doNothing()
                .when(channel).convertAndSend(any(String.class), any(String.class), any(Object.class));

        service.deliver(handoff.getSyncId());
        service.deliver(handoff.getSyncId());

        assertThat(handoff.getArDeliveryStatus()).isEqualTo(ArDeliveryStatus.SENT);
        assertThat(handoff.getArDeliveryAttempts()).isEqualTo(2);
        assertThat(handoff.getArDeliveryError()).isNull();
    }

    @Test
    void aReprocessedHandoff_tellsArItIsAReprocess_andWhy() {
        handoff.setRemarks("Reprocessed: Invoice voided and reissued");

        service.deliver(handoff.getSyncId());

        ArBillingMessage message = sentMessage();
        assertThat(message.reprocess()).isTrue();
        assertThat(message.reprocessReason()).isEqualTo("Invoice voided and reissued");
    }

    @Test
    void redeliver_resendsAFailedHandoff() {
        handoff.setArDeliveryStatus(ArDeliveryStatus.FAILED);
        handoff.setArDeliveryAttempts(10);

        assertThat(service.redeliver(handoff.getSyncId()).arDeliveryStatus()).isEqualTo("SENT");
        assertThat(handoff.getArDeliveryAttempts()).isEqualTo(11);
    }

    @Test
    void redeliver_sendsAHandoffRecordedBeforeDeliveryExisted() {
        handoff.setArDeliveryStatus(null);
        handoff.setArDeliveryAttempts(null);

        service.redeliver(handoff.getSyncId());

        assertThat(handoff.getArDeliveryStatus()).isEqualTo(ArDeliveryStatus.SENT);
        assertThat(handoff.getArDeliveryAttempts()).isEqualTo(1);
    }

    @Test
    void redeliver_refusesAHandoffAlreadySent() {
        handoff.setArDeliveryStatus(ArDeliveryStatus.SENT);

        assertThatThrownBy(() -> service.redeliver(handoff.getSyncId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already reached AR");
        verify(rabbitTemplate, never()).invoke(any());
    }

    @Test
    void deliver_refusesARecordThatIsNotAHandoff() {
        handoff.setSyncStatus("FAILED");

        assertThatThrownBy(() -> service.deliver(handoff.getSyncId()))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(rabbitTemplate, never()).invoke(any());
    }

    @Test
    void findDueForDelivery_asksForFailedUnderTheLimit_andPendingPastTheGracePeriod() {
        List<UUID> due = List.of(UUID.randomUUID());
        when(invoiceSyncRepository.findDueForArDelivery(eq("HANDED_OFF"), eq(ArDeliveryStatus.FAILED), eq(ArDeliveryStatus.PENDING),
                eq(10), any(LocalDateTime.class))).thenReturn(due);

        assertThat(service.findDueForDelivery()).isEqualTo(due);
    }
}
