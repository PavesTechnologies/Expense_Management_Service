package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceRepaymentResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.CashAdvanceSettlementResponse;

import java.util.UUID;

public interface CashAdvanceService {

    CashAdvanceResponse create(CashAdvanceRequest request);

    CashAdvanceResponse update(UUID advanceId, CashAdvanceRequest request);

    CashAdvanceResponse getById(UUID advanceId);

    List<CashAdvanceResponse> getAll();

    List<CashAdvanceResponse> getMyAdvances(String employeeId, String status);

    List<CashAdvanceResponse> getMyApprovals(String managerId, String status);

    List<CashAdvanceResponse> getFiltered(String employeeId, String status);

    CashAdvanceResponse submit(UUID advanceId);

    CashAdvanceResponse approve(UUID advanceId, String approverId);

    CashAdvanceResponse reject(UUID advanceId, String approverId, String reason);

    CashAdvanceResponse disburse(UUID advanceId, String financeUserId);

    CashAdvanceRepaymentResponse repay(CashAdvanceRepaymentRequest request);

    CashAdvanceSettlementResponse getSettlement(UUID advanceId);

    void processOverdueAdvances();

    CashAdvanceResponse cancel(UUID advanceId);

    void delete(UUID advanceId);
}


