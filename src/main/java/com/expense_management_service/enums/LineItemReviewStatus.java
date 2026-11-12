package com.expense_management_service.enums;

/**
 * The actual per-line-item decision within one {@code ApprovalLevelInstance} - see
 * {@code ApprovalLineItemReview}. This is the real unit of approver action; a level only completes
 * once every line item on the report reaches {@code APPROVED} at that level.
 */
public enum LineItemReviewStatus {
    PENDING,
    APPROVED,
    /** Comment is required when transitioning to this status (§4.2). Non-terminal - loops back to the same approver. */
    NEEDS_CORRECTION
}
