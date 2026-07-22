package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.SavedFilterRequest;
import com.expense_management_service.dto.response.SavedFilterResponse;
import com.expense_management_service.entity.SavedFilter;
import com.expense_management_service.mapper.SavedFilterMapper;
import com.expense_management_service.repository.SavedFilterRepository;
import com.expense_management_service.service.SavedFilterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SavedFilterServiceImpl implements SavedFilterService {

    private final SavedFilterRepository savedFilterRepository;
    private final SavedFilterMapper savedFilterMapper;

    @Override
    public SavedFilterResponse create(SavedFilterRequest request) {
        SavedFilter entity = savedFilterMapper.toEntity(request);
        return savedFilterMapper.toResponse(savedFilterRepository.save(entity));
    }

    @Override
    public SavedFilterResponse update(UUID filterId, SavedFilterRequest request) {
        SavedFilter entity = findEntity(filterId);
        savedFilterMapper.updateEntity(entity, request);
        return savedFilterMapper.toResponse(savedFilterRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public SavedFilterResponse getById(UUID filterId) {
        return savedFilterMapper.toResponse(findEntity(filterId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SavedFilterResponse> getAll(Pageable pageable) {
        return savedFilterRepository.findAll(pageable).map(savedFilterMapper::toResponse);
    }

    @Override
    public void delete(UUID filterId) {
        savedFilterRepository.delete(findEntity(filterId));
    }

    private SavedFilter findEntity(UUID filterId) {
        return savedFilterRepository.findById(filterId)
                .orElseThrow(() -> new ResourceNotFoundException("SavedFilter not found with id: " + filterId));
    }
}
