package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A saved filter always belongs to the caller - the owner is taken from the JWT, never the request,
 * so one employee can't create or overwrite another's filters. {@code filterJson} is capped at 255
 * because the column is a Hibernate-generated TINYTEXT ({@code @Lob} with no column definition).
 */
public record SavedFilterRequest(
        @NotBlank @Size(max = 255) String filterName,
        @Size(max = 255) String filterJson
) {
}
