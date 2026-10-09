package com.expense_management_service.service.impl;

import com.expense_management_service.service.ApprovalDomainEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitApprovalEventListenerTest {

    @Mock private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Test
    void onApprovalEvent_usesFinanceRoutingKeyPrefix_forFinanceEventTypes() {
        RabbitApprovalEventListener listener = new RabbitApprovalEventListener(rabbitTemplate);
        ApprovalDomainEvent event = new ApprovalDomainEvent("LINE_ITEM_VERIFIED", UUID.randomUUID(), "detail", LocalDateTime.now());

        listener.onApprovalEvent(event);

        ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.any(), routingKey.capture(), org.mockito.ArgumentMatchers.eq(event));
        assertThat(routingKey.getValue()).isEqualTo("finance.line_item_verified");
    }

    @Test
    void onApprovalEvent_usesApprovalRoutingKeyPrefix_forExistingEventTypes() {
        RabbitApprovalEventListener listener = new RabbitApprovalEventListener(rabbitTemplate);
        ApprovalDomainEvent event = new ApprovalDomainEvent("REPORT_SUBMITTED", UUID.randomUUID(), "detail", LocalDateTime.now());

        listener.onApprovalEvent(event);

        ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.any(), routingKey.capture(), org.mockito.ArgumentMatchers.eq(event));
        assertThat(routingKey.getValue()).isEqualTo("approval.report_submitted");
    }
}
