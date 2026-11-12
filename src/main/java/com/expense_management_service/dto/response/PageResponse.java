package com.expense_management_service.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Generic paginated response envelope - the first server-side pagination convention in this
 * backend (established for the Approval Engine's queue/history endpoints, which can grow
 * unbounded per approver over time). Decouples the wire contract from Spring Data's own
 * {@code Page}/{@code PageImpl}, whose default Jackson serialization is verbose and
 * implementation-specific.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast());
    }
}
