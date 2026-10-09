package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.SavedFilterRequest;
import com.expense_management_service.dto.response.SavedFilterResponse;
import com.expense_management_service.entity.SavedFilter;
import org.springframework.stereotype.Component;

@Component
public class SavedFilterMapper {

    public SavedFilter toEntity(SavedFilterRequest request, String employeeId) {
        return SavedFilter.builder()
                .employeeId(employeeId)
                .filterName(request.filterName().trim())
                .filterJson(request.filterJson())
                .build();
    }

    public void updateEntity(SavedFilter entity, SavedFilterRequest request) {
        entity.setFilterName(request.filterName().trim());
        entity.setFilterJson(request.filterJson());
    }

    public SavedFilterResponse toResponse(SavedFilter entity) {
        return new SavedFilterResponse(
                entity.getFilterId(),
                entity.getEmployeeId(),
                entity.getFilterName(),
                entity.getFilterJson(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
