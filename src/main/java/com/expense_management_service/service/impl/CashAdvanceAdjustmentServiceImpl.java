package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.mapper.CashAdvanceAdjustmentMapper;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.CashAdvanceAdjustmentService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CashAdvanceAdjustmentServiceImpl implements CashAdvanceAdjustmentService {

    private final CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    private final CashAdvanceRepository cashAdvanceRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final CashAdvanceAdjustmentMapper cashAdvanceAdjustmentMapper;

    @Override
    public CashAdvanceAdjustmentResponse create(CashAdvanceAdjustmentRequest request) {
        CashAdvanceAdjustment entity = cashAdvanceAdjustmentMapper.toEntity(request);
        entity.setCashAdvance(findCashAdvance(request.advanceId()));
        entity.setReport(findReport(request.reportId()));
        entity.setAdjustedAt(LocalDateTime.now());
        return cashAdvanceAdjustmentMapper.toResponse(cashAdvanceAdjustmentRepository.save(entity));
    }

    @Override
    public CashAdvanceAdjustmentResponse update(UUID adjustmentId, CashAdvanceAdjustmentRequest request) {
        CashAdvanceAdjustment entity = findEntity(adjustmentId);
        cashAdvanceAdjustmentMapper.updateEntity(entity, request);
        entity.setCashAdvance(findCashAdvance(request.advanceId()));
        entity.setReport(findReport(request.reportId()));
        return cashAdvanceAdjustmentMapper.toResponse(cashAdvanceAdjustmentRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public CashAdvanceAdjustmentResponse getById(UUID adjustmentId) {
        return cashAdvanceAdjustmentMapper.toResponse(findEntity(adjustmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashAdvanceAdjustmentResponse> getAll() {
        return cashAdvanceAdjustmentRepository.findAll().stream().map(cashAdvanceAdjustmentMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID adjustmentId) {
        cashAdvanceAdjustmentRepository.delete(findEntity(adjustmentId));
    }

    private CashAdvance findCashAdvance(UUID advanceId) {
        return cashAdvanceRepository.findById(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("CashAdvance not found with id: " + advanceId));
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    private CashAdvanceAdjustment findEntity(UUID adjustmentId) {
        return cashAdvanceAdjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new ResourceNotFoundException("CashAdvanceAdjustment not found with id: " + adjustmentId));
    }
}
