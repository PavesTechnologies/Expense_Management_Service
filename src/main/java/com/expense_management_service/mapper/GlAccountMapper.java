package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;
import com.expense_management_service.entity.GlAccount;
import org.springframework.stereotype.Component;

@Component
public class GlAccountMapper {

    public GlAccount toEntity(GlAccountRequest request) {
        return GlAccount.builder()
                .glAccountCode(request.glAccountCode())
                .glAccountName(request.glAccountName())
                .accountType(request.accountType())
                .description(request.description())
                .status(request.status())
                .build();
    }

    public void updateEntity(GlAccount entity, GlAccountRequest request) {
        entity.setGlAccountCode(request.glAccountCode());
        entity.setGlAccountName(request.glAccountName());
        entity.setAccountType(request.accountType());
        entity.setDescription(request.description());
        entity.setStatus(request.status());
    }

    public GlAccountResponse toResponse(GlAccount entity) {
        return new GlAccountResponse(
                entity.getGlAccountId(),
                entity.getGlAccountCode(),
                entity.getGlAccountName(),
                entity.getAccountType(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
