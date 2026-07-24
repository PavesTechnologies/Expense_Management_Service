package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.mapper.GlAccountMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.service.GlAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class GlAccountServiceImpl implements GlAccountService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String CATEGORY_STATUS_GL_MAPPING_INVALID = "GL_MAPPING_INVALID";

    private final GlAccountRepository glAccountRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final GlAccountMapper glAccountMapper;

    @Override
    public GlAccountResponse create(GlAccountRequest request) {
        assertCodeNotDuplicate(request.glAccountCode(), null);

        GlAccount entity = glAccountMapper.toEntity(request);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        GlAccount saved = glAccountRepository.save(entity);
        return glAccountMapper.toResponse(saved, 0L);
    }

    @Override
    public GlAccountResponse update(UUID glAccountId, GlAccountRequest request) {
        GlAccount entity = findEntity(glAccountId);
        assertCodeNotDuplicate(request.glAccountCode(), glAccountId);

        boolean wasActive = STATUS_ACTIVE.equalsIgnoreCase(entity.getStatus());
        glAccountMapper.updateEntity(entity, request);
        boolean nowInactive = STATUS_INACTIVE.equalsIgnoreCase(entity.getStatus());

        GlAccount saved = glAccountRepository.save(entity);

        if (wasActive && nowInactive) {
            flagMappedActiveCategoriesAsInvalid(glAccountId);
        }

        return glAccountMapper.toResponse(saved, countMappedCategories(glAccountId));
    }

    @Override
    @Transactional(readOnly = true)
    public GlAccountResponse getById(UUID glAccountId) {
        GlAccount entity = findEntity(glAccountId);
        return glAccountMapper.toResponse(entity, countMappedCategories(glAccountId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GlAccountResponse> getAll() {
        return glAccountRepository.findAll().stream()
                .map(entity -> glAccountMapper.toResponse(entity, countMappedCategories(entity.getGlAccountId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GlAccountResponse> getActiveAccounts() {
        return glAccountRepository.findByStatusIgnoreCaseOrderByGlAccountNameAsc(STATUS_ACTIVE).stream()
                .map(entity -> glAccountMapper.toResponse(entity, countMappedCategories(entity.getGlAccountId())))
                .toList();
    }

    @Override
    public void delete(UUID glAccountId) {
        GlAccount entity = findEntity(glAccountId);

        List<ExpenseCategory> mappedCategories = expenseCategoryRepository.findByGlAccount_GlAccountId(glAccountId);
        if (!mappedCategories.isEmpty()) {
            String categoryNames = mappedCategories.stream()
                    .map(ExpenseCategory::getCategoryName)
                    .collect(Collectors.joining(", "));
            throw new ResourceInUseException(
                    "GL Account cannot be deleted because it is mapped to the following Expense Categories: " + categoryNames);
        }

        glAccountRepository.delete(entity);
    }

    private void assertCodeNotDuplicate(String glAccountCode, UUID currentGlAccountId) {
        glAccountRepository.findByGlAccountCodeIgnoreCase(glAccountCode).ifPresent(existing -> {
            if (!existing.getGlAccountId().equals(currentGlAccountId)) {
                throw new DuplicateResourceException("GL Account code already exists: " + glAccountCode);
            }
        });
    }

    private void flagMappedActiveCategoriesAsInvalid(UUID glAccountId) {
        List<ExpenseCategory> activeMappedCategories =
                expenseCategoryRepository.findByGlAccount_GlAccountIdAndStatusIgnoreCase(glAccountId, STATUS_ACTIVE);
        activeMappedCategories.forEach(category -> category.setStatus(CATEGORY_STATUS_GL_MAPPING_INVALID));
        expenseCategoryRepository.saveAll(activeMappedCategories);
    }

    private long countMappedCategories(UUID glAccountId) {
        return expenseCategoryRepository.countByGlAccount_GlAccountId(glAccountId);
    }

    private GlAccount findEntity(UUID glAccountId) {
        return glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("GlAccount not found with id: " + glAccountId));
    }
}
