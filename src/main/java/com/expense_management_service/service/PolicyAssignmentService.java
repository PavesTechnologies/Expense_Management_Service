package com.expense_management_service.service;

import com.expense_management_service.dto.request.PolicyAssignmentRequest;
import com.expense_management_service.dto.response.PolicyAssignmentResponse;

import java.util.List;
import java.util.UUID;

public interface PolicyAssignmentService {

    PolicyAssignmentResponse create(PolicyAssignmentRequest request);

    PolicyAssignmentResponse getById(UUID assignmentId);

    List<PolicyAssignmentResponse> getAll();

    void delete(UUID assignmentId);

    /** Repoints the single system-wide DEFAULT assignment to a different policy - never creates a second one. */
    PolicyAssignmentResponse updateDefaultPolicy(UUID newPolicyId);
}
