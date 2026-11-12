package com.expense_management_service.service;

import com.expense_management_service.dto.response.CdcRetryResponse;

public interface CdcRetryService {

    /** Replays every retryable {@code CdcFailureLog} row through the consumer's upsert/delete logic. */
    CdcRetryResponse retryFailedEvents();
}
