package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.SavedFilterRequest;
import com.expense_management_service.dto.response.SavedFilterResponse;
import com.expense_management_service.entity.SavedFilter;
import com.expense_management_service.mapper.SavedFilterMapper;
import com.expense_management_service.repository.SavedFilterRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.SavedFilterService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Saved filters are personal: every operation is scoped to the caller's own employee ID. Another
 * employee's filter is reported as not found rather than forbidden, so filter IDs can't be probed.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SavedFilterServiceImpl implements SavedFilterService {

    private final SavedFilterRepository savedFilterRepository;
    private final SavedFilterMapper savedFilterMapper;
    private final CurrentUserService currentUserService;

    @Override
    public SavedFilterResponse create(SavedFilterRequest request) {
        String me = currentUserService.getEmployeeId();
        assertNameNotDuplicate(me, request.filterName(), null);
        SavedFilter entity = savedFilterMapper.toEntity(request, me);
        return savedFilterMapper.toResponse(savedFilterRepository.save(entity));
    }

    @Override
    public SavedFilterResponse update(UUID filterId, SavedFilterRequest request) {
        SavedFilter entity = findOwnEntity(filterId);
        assertNameNotDuplicate(entity.getEmployeeId(), request.filterName(), filterId);
        savedFilterMapper.updateEntity(entity, request);
        return savedFilterMapper.toResponse(savedFilterRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public SavedFilterResponse getById(UUID filterId) {
        return savedFilterMapper.toResponse(findOwnEntity(filterId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SavedFilterResponse> getAll() {
        return savedFilterRepository.findByEmployeeIdOrderByFilterNameAsc(currentUserService.getEmployeeId()).stream()
                .map(savedFilterMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID filterId) {
        savedFilterRepository.delete(findOwnEntity(filterId));
    }

    private void assertNameNotDuplicate(String employeeId, String filterName, UUID currentFilterId) {
        savedFilterRepository.findByEmployeeIdAndFilterNameIgnoreCase(employeeId, filterName.trim())
                .filter(existing -> !existing.getFilterId().equals(currentFilterId))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("You already have a saved filter named \"" + filterName.trim() + "\"");
                });
    }

    private SavedFilter findOwnEntity(UUID filterId) {
        return savedFilterRepository.findById(filterId)
                .filter(filter -> filter.getEmployeeId().equals(currentUserService.getEmployeeId()))
                .orElseThrow(() -> new ResourceNotFoundException("SavedFilter not found with id: " + filterId));
    }
}
