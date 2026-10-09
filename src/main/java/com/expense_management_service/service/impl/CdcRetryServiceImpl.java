package com.expense_management_service.service.impl;

import com.expense_management_service.consumer.EmployeeCdcConsumer;
import com.expense_management_service.dto.external.EmployeeCdcEvent;
import com.expense_management_service.dto.response.CdcRetryResponse;
import com.expense_management_service.entity.CdcFailureLog;
import com.expense_management_service.service.CdcFailureLogService;
import com.expense_management_service.service.CdcRetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Kept separate from {@code CdcFailureLogService} to avoid a circular bean
 * dependency: {@code EmployeeCdcConsumer} depends on {@code CdcFailureLogService}
 * to record failures, so {@code CdcFailureLogService} cannot also depend on
 * {@code EmployeeCdcConsumer} to replay them. This service sits above both.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CdcRetryServiceImpl implements CdcRetryService {

    private final CdcFailureLogService cdcFailureLogService;
    private final EmployeeCdcConsumer employeeCdcConsumer;

    // Not constructor-injected - see the identical note on EmployeeCdcConsumer.objectMapper.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    public CdcRetryResponse retryFailedEvents() {
        List<CdcFailureLog> retryable = cdcFailureLogService.findRetryable();
        int succeeded = 0;
        int failed = 0;

        for (CdcFailureLog failure : retryable) {
            try {
                retryOne(failure);
                cdcFailureLogService.markRetrySucceeded(failure.getFailureId());
                succeeded++;
            } catch (Exception e) {
                cdcFailureLogService.markRetryFailed(failure.getFailureId(), e.getMessage());
                failed++;
                log.warn("Retry failed for CdcFailureLog {}: {}", failure.getFailureId(), e.getMessage());
            }
        }

        String note = retryable.isEmpty()
                ? "No retryable CDC failures found."
                : "Retried " + retryable.size() + " CDC failure(s): " + succeeded + " succeeded, " + failed + " still failing.";
        log.info("CDC retry run finished: attempted={}, succeeded={}, failed={}", retryable.size(), succeeded, failed);
        return new CdcRetryResponse(retryable.size(), succeeded, failed, LocalDateTime.now(), note);
    }

    private void retryOne(CdcFailureLog failure) throws Exception {
        if (failure.getRawPayload() == null || failure.getRawPayload().isBlank()) {
            throw new IllegalStateException("No raw payload stored for this failure, cannot replay");
        }
        EmployeeCdcEvent event = objectMapper.readValue(failure.getRawPayload(), EmployeeCdcEvent.class);
        if (event.isDelete()) {
            employeeCdcConsumer.handleDelete(event);
        } else {
            employeeCdcConsumer.handleUpsert(event);
        }
    }
}
