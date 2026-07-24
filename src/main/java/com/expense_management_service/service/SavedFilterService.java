package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.SavedFilterRequest;
import com.expense_management_service.dto.response.SavedFilterResponse;


import java.util.UUID;

public interface SavedFilterService {

    SavedFilterResponse create(SavedFilterRequest request);

    SavedFilterResponse update(UUID filterId, SavedFilterRequest request);

    SavedFilterResponse getById(UUID filterId);

    List<SavedFilterResponse> getAll();

    void delete(UUID filterId);
}
