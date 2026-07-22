package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.VerificationQueryRequest;
import com.expense_management_service.dto.response.VerificationQueryResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.VerificationQuery;
import com.expense_management_service.mapper.VerificationQueryMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.VerificationQueryRepository;
import com.expense_management_service.service.VerificationQueryService;
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
public class VerificationQueryServiceImpl implements VerificationQueryService {

    private final VerificationQueryRepository verificationQueryRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final VerificationQueryMapper verificationQueryMapper;

    @Override
    public VerificationQueryResponse create(VerificationQueryRequest request) {
        VerificationQuery entity = verificationQueryMapper.toEntity(request);
        entity.setLineItem(findLineItem(request.lineItemId()));
        entity.setRaisedAt(LocalDateTime.now());
        return verificationQueryMapper.toResponse(verificationQueryRepository.save(entity));
    }

    @Override
    public VerificationQueryResponse update(UUID queryId, VerificationQueryRequest request) {
        VerificationQuery entity = findEntity(queryId);
        verificationQueryMapper.updateEntity(entity, request);
        entity.setLineItem(findLineItem(request.lineItemId()));
        return verificationQueryMapper.toResponse(verificationQueryRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public VerificationQueryResponse getById(UUID queryId) {
        return verificationQueryMapper.toResponse(findEntity(queryId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VerificationQueryResponse> getAll(Pageable pageable) {
        return verificationQueryRepository.findAll(pageable).map(verificationQueryMapper::toResponse);
    }

    @Override
    public void delete(UUID queryId) {
        verificationQueryRepository.delete(findEntity(queryId));
    }

    private ExpenseLineItem findLineItem(UUID lineItemId) {
        return expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
    }

    private VerificationQuery findEntity(UUID queryId) {
        return verificationQueryRepository.findById(queryId)
                .orElseThrow(() -> new ResourceNotFoundException("VerificationQuery not found with id: " + queryId));
    }
}
