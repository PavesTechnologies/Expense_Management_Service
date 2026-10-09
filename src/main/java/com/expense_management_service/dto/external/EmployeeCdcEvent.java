package com.expense_management_service.dto.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Flattened Debezium change event for {@code eos.employee_details}, as
 * published by the Employee CDC pipeline's {@code ExtractNewRecordState} SMT
 * ({@code add.fields=op,ts_ms}, {@code delete.handling.mode=rewrite}). The
 * metadata fields ({@code __op}, {@code __ts_ms}, {@code __deleted}) sit flat
 * alongside the source columns rather than nested under a {@code payload}
 * envelope - this is the exact contract the Leave Management System's
 * already-merged CDC consumer relies on, confirmed against its
 * {@code EmployeeCdcEvent} DTO.
 *
 * <p>{@code reportingManagerUuid} is a misnomer inherited from EOS: despite
 * its name, it holds the manager's {@code employee_id}, not a UUID.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmployeeCdcEvent(
        @JsonProperty("employee_uuid") String employeeUuid,
        @JsonProperty("employee_id") String employeeId,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("work_email") String workEmail,
        @JsonProperty("gender") String gender,
        @JsonProperty("contact_number") String contactNumber,
        @JsonProperty("joining_date") String joiningDate,
        @JsonProperty("designation_uuid") String designationUuid,
        @JsonProperty("department_uuid") String departmentUuid,
        @JsonProperty("employment_status") String employmentStatus,
        @JsonProperty("employment_type") String employmentType,
        @JsonProperty("reporting_manager_uuid") String reportingManagerUuid,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("__op") String op,
        @JsonProperty("__ts_ms") Long tsMs,
        @JsonProperty("__deleted") String deleted
) {
    /** True for a Debezium rewrite-mode delete, by either signal it uses. */
    public boolean isDelete() {
        return "true".equalsIgnoreCase(deleted) || "d".equalsIgnoreCase(op);
    }
}
