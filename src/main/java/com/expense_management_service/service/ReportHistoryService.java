package com.expense_management_service.service;

import com.expense_management_service.dto.response.ReportHistoryEntryResponse;

import java.util.List;
import java.util.UUID;

/**
 * A report's full history across correction cycles, oldest first. Approval status only shows the
 * current cycle; this keeps what happened before a correction (who asked for what, and why) visible.
 */
public interface ReportHistoryService {

    /** Same visibility as the report itself (owner, or Admin/Finance/Manager/AP). */
    List<ReportHistoryEntryResponse> getHistory(UUID reportId);
}
