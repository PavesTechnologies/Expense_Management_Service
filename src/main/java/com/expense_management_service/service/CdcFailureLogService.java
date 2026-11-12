package com.expense_management_service.service;

import com.expense_management_service.entity.CdcFailureLog;

import java.util.List;
import java.util.UUID;

public interface CdcFailureLogService {

    /** Records a failed CDC event. Called by the consumer before it acknowledges the message. */
    CdcFailureLog logFailure(String sourceTopic, String employeeId, String employeeUuid, String operation,
                              String failureType, String errorMessage, String rawPayload,
                              Integer kafkaPartition, Long kafkaOffset);

    /** Rows still eligible for another retry attempt (status FAILED/RETRYING and under their retry limit). */
    List<CdcFailureLog> findRetryable();

    void markRetrySucceeded(UUID failureId);

    void markRetryFailed(UUID failureId, String errorMessage);
}
