package com.expense_management_service.service;

import com.expense_management_service.dto.request.SavedFilterRequest;
import com.expense_management_service.dto.response.SavedFilterResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface SavedFilterService {

    SavedFilterResponse create(SavedFilterRequest request);

    SavedFilterResponse update(UUID filterId, SavedFilterRequest request);

    SavedFilterResponse getById(UUID filterId);

    Page<SavedFilterResponse> getAll(Pageable pageable);

    void delete(UUID filterId);
}
