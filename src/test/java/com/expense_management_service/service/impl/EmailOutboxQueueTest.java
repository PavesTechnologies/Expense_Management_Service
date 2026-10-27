package com.expense_management_service.service.impl;

import com.expense_management_service.entity.OutboundEmail;
import com.expense_management_service.integration.email.UnconfiguredEmailTransport;
import com.expense_management_service.repository.OutboundEmailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailOutboxQueueTest {

    @Mock private OutboundEmailRepository repository;

    @Test
    void withoutAMailServer_messagesStayQueued_andAreNeverMarkedFailed() {
        OutboundEmail email = OutboundEmail.builder().outboundEmailId(UUID.randomUUID())
                .toAddress("lokeswari353@gmail.com").subject("s").body("b")
                .status(OutboundEmail.PENDING).attempts(0).build();
        List<OutboundEmail> queue = new ArrayList<>(List.of(email));
        lenient().when(repository.findByStatusOrderByCreatedAtAsc(anyString(), any(Pageable.class))).thenReturn(queue);
        EmailOutboxService outbox = new EmailOutboxService(repository, new UnconfiguredEmailTransport());

        for (int run = 0; run < 10; run++) {
            assertThat(outbox.dispatchPending()).isZero();
        }

        assertThat(email.getStatus()).isEqualTo(OutboundEmail.PENDING);
        assertThat(email.getAttempts()).isZero();
        assertThat(email.getLastError()).isNull();
    }
}
