package com.expense_management_service.enums;

/**
 * How a {@link com.expense_management_service.entity.Policy} is connected to an employee.
 * Precedence when resolving an employee's effective policy: {@code INDIVIDUAL} &gt; {@code GROUP}
 * &gt; {@code DEFAULT}. Only {@code DEFAULT} is reachable as of the Phase 1 bundle migration;
 * {@code INDIVIDUAL}/{@code GROUP} become usable once the assignment resolver and policy groups
 * are introduced.
 */
public enum PolicyAssignmentType {
    INDIVIDUAL,
    GROUP,
    DEFAULT
}
