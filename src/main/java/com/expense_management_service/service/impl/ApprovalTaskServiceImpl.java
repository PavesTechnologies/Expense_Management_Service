package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApprovalTaskRequest;
import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.entity.ApprovalTask;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.mapper.ApprovalTaskMapper;
import com.expense_management_service.repository.ApprovalTaskRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApprovalTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ApprovalTaskServiceImpl implements ApprovalTaskService {

    private final ApprovalTaskRepository approvalTaskRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final ApprovalTaskMapper approvalTaskMapper;

    @Override
    public ApprovalTaskResponse create(ApprovalTaskRequest request) {
        ApprovalTask entity = approvalTaskMapper.toEntity(request);
        entity.setReport(findReport(request.reportId()));
        entity.setAssignedAt(LocalDateTime.now());
        return approvalTaskMapper.toResponse(approvalTaskRepository.save(entity));
    }

    @Override
    public ApprovalTaskResponse update(UUID taskId, ApprovalTaskRequest request) {
        ApprovalTask entity = findEntity(taskId);
        approvalTaskMapper.updateEntity(entity, request);
        entity.setReport(findReport(request.reportId()));
        return approvalTaskMapper.toResponse(approvalTaskRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalTaskResponse getById(UUID taskId) {
        return approvalTaskMapper.toResponse(findEntity(taskId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApprovalTaskResponse> getAll(Pageable pageable) {
        return approvalTaskRepository.findAll(pageable).map(approvalTaskMapper::toResponse);
    }

    @Override
    public void delete(UUID taskId) {
        approvalTaskRepository.delete(findEntity(taskId));
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    private ApprovalTask findEntity(UUID taskId) {
        return approvalTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalTask not found with id: " + taskId));
    }
}
