package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.response.AuditLogResponse;
import com.expense_management_service.entity.AuditLog;
import org.springframework.stereotype.Component;

@Component
public class AuditLogMapper {

    public AuditLog toEntity(AuditLogRequest request) {
        return AuditLog.builder()
                .entityName(request.entityName())
                .entityId(request.entityId())
                .action(request.action())
                .oldValue(request.oldValue())
                .newValue(request.newValue())
                .performedBy(request.performedBy())
                .build();
    }

    public void updateEntity(AuditLog entity, AuditLogRequest request) {
        entity.setEntityName(request.entityName());
        entity.setEntityId(request.entityId());
        entity.setAction(request.action());
        entity.setOldValue(request.oldValue());
        entity.setNewValue(request.newValue());
        entity.setPerformedBy(request.performedBy());
    }

    public AuditLogResponse toResponse(AuditLog entity) {
        return new AuditLogResponse(
                entity.getAuditId(),
                entity.getEntityName(),
                entity.getEntityId(),
                entity.getAction(),
                entity.getOldValue(),
                entity.getNewValue(),
                entity.getPerformedBy(),
                entity.getPerformedAt()
        );
    }
}
