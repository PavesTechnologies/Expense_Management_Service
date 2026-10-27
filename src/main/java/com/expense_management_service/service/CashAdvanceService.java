package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.CashAdvanceDisbursement;
import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceRepaymentResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.CashAdvanceSettlementResponse;
import com.expense_management_service.dto.response.StoredProof;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CashAdvanceService {

    CashAdvanceResponse create(CashAdvanceRequest request);

    CashAdvanceResponse update(UUID advanceId, CashAdvanceRequest request);

    CashAdvanceResponse getById(UUID advanceId);

    List<CashAdvanceResponse> getAll();

    Page<CashAdvanceResponse> getMyAdvances(String employeeId, String status, Pageable pageable);

    Page<CashAdvanceResponse> getMyApprovals(String managerId, String status, Pageable pageable);

    Page<CashAdvanceResponse> getFiltered(String employeeId, String status, Pageable pageable);

    CashAdvanceResponse submit(UUID advanceId);

    CashAdvanceResponse approve(UUID advanceId, String approverId);

    CashAdvanceResponse reject(UUID advanceId, String approverId, String reason);

    CashAdvanceResponse disburse(UUID advanceId, String financeUserId, CashAdvanceDisbursement disbursement);

    CashAdvanceRepaymentResponse repay(CashAdvanceRepaymentRequest request);

    CashAdvanceSettlementResponse getSettlement(UUID advanceId);

    /** The proof Finance uploaded when it paid the advance out. Throws when none is on file. */
    StoredProof disbursementProof(UUID advanceId);

    /** The proof uploaded for one employee payment on this advance. Throws when it does not belong to the advance. */
    StoredProof repaymentProof(UUID advanceId, UUID repaymentId);

    CashAdvanceUtilizationResponse getUtilization(UUID advanceId);

    void processOverdueAdvances();

    CashAdvanceResponse cancel(UUID advanceId);

    void delete(UUID advanceId);
}


