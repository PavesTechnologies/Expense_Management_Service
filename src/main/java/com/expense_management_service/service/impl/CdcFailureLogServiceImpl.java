package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.entity.CdcFailureLog;
import com.expense_management_service.repository.CdcFailureLogRepository;
import com.expense_management_service.service.CdcFailureLogService;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.repository.NotificationRepository;
import com.expense_management_service.service.NotificationDraft;
import com.expense_management_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CdcFailureLogServiceImpl implements CdcFailureLogService {

    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_RETRYING = "RETRYING";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_EXHAUSTED = "EXHAUSTED";
    private static final String EVENT_CDC_FAILED = "CDC_SYNC_FAILED";
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final CdcFailureLogRepository cdcFailureLogRepository;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;

    @Override
    public CdcFailureLog logFailure(String sourceTopic, String employeeId, String employeeUuid, String operation,
                                     String failureType, String errorMessage, String rawPayload,
                                     Integer kafkaPartition, Long kafkaOffset) {
        CdcFailureLog failure = CdcFailureLog.builder()
                .sourceTopic(sourceTopic)
                .employeeId(employeeId)
                .employeeUuid(employeeUuid)
                .operation(operation)
                .failureType(failureType)
                .errorMessage(errorMessage)
                .rawPayload(rawPayload)
                .status(STATUS_FAILED)
                .retryCount(0)
                .maxRetries(DEFAULT_MAX_RETRIES)
                .kafkaPartition(kafkaPartition)
                .kafkaOffset(kafkaOffset)
                .build();

        CdcFailureLog saved = cdcFailureLogRepository.save(failure);
        log.error("Recorded CDC failure {} for topic {} (employeeUuid={}, type={}): {}",
                saved.getFailureId(), sourceTopic, employeeUuid, failureType, errorMessage);
        alertAdmins(saved);
        return saved;
    }

    /** At most one admin alert per hour - failures tend to arrive in bursts. Never fails the caller. */
    private void alertAdmins(CdcFailureLog failure) {
        try {
            if (notificationRepository.existsByRecipientRoleAndEventTypeAndSentAtAfter(
                    NotificationService.ROLE_ADMIN, EVENT_CDC_FAILED, LocalDateTime.now().minusHours(1))) {
                return;
            }
            notificationService.notifyRole(NotificationService.ROLE_ADMIN, NotificationDraft.builder()
                    .category(NotificationCategory.FAILED)
                    .eventType(EVENT_CDC_FAILED)
                    .title("Employee data sync failed")
                    .message("A " + failure.getFailureType() + " error occurred processing " + failure.getSourceTopic()
                            + (failure.getEmployeeId() != null ? " for employee " + failure.getEmployeeId() : "")
                            + ". It will be retried automatically; check the CDC failure log if it keeps happening.")
                    .actorName("System")
                    .statusLabel("Retry pending")
                    .build());
        } catch (Exception ex) {
            log.warn("Could not raise admin alert for CDC failure {}", failure.getFailureId(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CdcFailureLog> findRetryable() {
        return cdcFailureLogRepository.findByStatusInAndRetryCountLessThan(
                List.of(STATUS_FAILED, STATUS_RETRYING), DEFAULT_MAX_RETRIES);
    }

    @Override
    public void markRetrySucceeded(UUID failureId) {
        CdcFailureLog failure = findEntity(failureId);
        failure.setStatus(STATUS_RESOLVED);
        failure.setResolvedAt(LocalDateTime.now());
        cdcFailureLogRepository.save(failure);
        log.info("CDC failure {} resolved on retry", failureId);
    }

    @Override
    public void markRetryFailed(UUID failureId, String errorMessage) {
        CdcFailureLog failure = findEntity(failureId);
        int nextRetryCount = failure.getRetryCount() + 1;
        failure.setRetryCount(nextRetryCount);
        failure.setLastRetriedAt(LocalDateTime.now());
        failure.setErrorMessage(errorMessage);
        failure.setStatus(nextRetryCount >= failure.getMaxRetries() ? STATUS_EXHAUSTED : STATUS_RETRYING);
        cdcFailureLogRepository.save(failure);
        log.warn("CDC failure {} retry {}/{} failed: {}", failureId, nextRetryCount, failure.getMaxRetries(), errorMessage);
    }

    private CdcFailureLog findEntity(UUID failureId) {
        return cdcFailureLogRepository.findById(failureId)
                .orElseThrow(() -> new ResourceNotFoundException("CdcFailureLog not found with id: " + failureId));
    }
}
