package com.expense_management_service.scheduler;

import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.service.ArBillingDeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArBillingDeliveryRetrySchedulerTest {

    @Mock private ArBillingDeliveryService arBillingDeliveryService;
    @InjectMocks private ArBillingDeliveryRetryScheduler scheduler;

    private static InvoiceSyncResponse result(UUID syncId, String status) {
        return new InvoiceSyncResponse(syncId, UUID.randomUUID(), "INV-1", "HANDED_OFF", LocalDateTime.now(), 0, null,
                status, 1, null, null);
    }

    @Test
    void retry_sendsEveryDueHandoff_evenWhenOneOfThemThrows() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(arBillingDeliveryService.findDueForDelivery()).thenReturn(List.of(first, second, third));
        when(arBillingDeliveryService.deliver(first)).thenReturn(result(first, "SENT"));
        when(arBillingDeliveryService.deliver(second)).thenThrow(new IllegalStateException("record vanished"));
        when(arBillingDeliveryService.deliver(third)).thenReturn(result(third, "FAILED"));

        scheduler.retry();

        verify(arBillingDeliveryService).deliver(first);
        verify(arBillingDeliveryService).deliver(second);
        verify(arBillingDeliveryService).deliver(third);
    }

    @Test
    void retry_withNothingDue_sendsNothing() {
        when(arBillingDeliveryService.findDueForDelivery()).thenReturn(List.of());

        scheduler.retry();

        verify(arBillingDeliveryService, never()).deliver(any());
    }
}
