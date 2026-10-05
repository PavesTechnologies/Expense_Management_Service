package com.expense_management_service.mapper;

import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalLevel;
import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the Catch-All Flow level-ordering bug: {@code level_id}/{@code entry_id}
 * are random UUIDs, so an unordered fetch of {@code ApprovalFlow.levels}/{@code
 * ApprovalLevel.approvers} can come back in effectively arbitrary order. {@code
 * ApprovalFlowMapper.toResponse} must never trust the collection's iteration order - it re-sorts by
 * the persisted {@code levelOrder}/{@code entryOrder} explicitly, as a defense-in-depth complement
 * to the entities' own {@code @OrderBy}.
 */
class ApprovalFlowMapperTest {

    private final ApprovalFlowMapper mapper = new ApprovalFlowMapper();

    private ApprovalLevel level(int order, String name, ApprovalLevelApprover... approvers) {
        ApprovalLevel level = ApprovalLevel.builder()
                .levelId(UUID.randomUUID())
                .levelOrder(order)
                .levelName(name)
                .quorum(LevelQuorum.SEQUENTIAL)
                .levelType(LevelType.APPROVAL)
                .approvers(new java.util.ArrayList<>(List.of(approvers)))
                .build();
        return level;
    }

    private ApprovalLevelApprover approver(Integer entryOrder, ApproverSourceType sourceType) {
        return ApprovalLevelApprover.builder()
                .entryId(UUID.randomUUID())
                .entryOrder(entryOrder)
                .sourceType(sourceType)
                .build();
    }

    @Test
    void toResponse_sortsLevelsByLevelOrder_regardlessOfCollectionIterationOrder() {
        ApprovalLevel level1 = level(1, "Reporting Manager Approval");
        ApprovalLevel level2 = level(2, "Cost Center Owner Approval");
        ApprovalLevel level3 = level(3, "Finance Verification");

        // Simulates exactly what an unordered SQL fetch could return - scrambled relative to levelOrder.
        ApprovalFlow flow = ApprovalFlow.builder()
                .flowId(UUID.randomUUID()).name("Catch-All").isCatchAll(true).status("ACTIVE")
                .levels(new java.util.ArrayList<>(List.of(level3, level1, level2)))
                .build();

        var response = mapper.toResponse(flow);

        assertThat(response.levels()).extracting("levelOrder").containsExactly(1, 2, 3);
        assertThat(response.levels()).extracting("levelName")
                .containsExactly("Reporting Manager Approval", "Cost Center Owner Approval", "Finance Verification");
    }

    @Test
    void toResponse_preservesLevelOrder_forEveryPossiblePermutation() {
        ApprovalLevel level1 = level(1, "A");
        ApprovalLevel level2 = level(2, "B");
        ApprovalLevel level3 = level(3, "C");
        List<List<ApprovalLevel>> permutations = List.of(
                List.of(level1, level2, level3),
                List.of(level1, level3, level2),
                List.of(level2, level1, level3),
                List.of(level2, level3, level1),
                List.of(level3, level1, level2),
                List.of(level3, level2, level1)
        );

        for (List<ApprovalLevel> permutation : permutations) {
            ApprovalFlow flow = ApprovalFlow.builder()
                    .flowId(UUID.randomUUID()).name("Catch-All").isCatchAll(true).status("ACTIVE")
                    .levels(new java.util.ArrayList<>(permutation))
                    .build();

            var response = mapper.toResponse(flow);

            assertThat(response.levels()).extracting("levelOrder").containsExactly(1, 2, 3);
        }
    }

    @Test
    void toResponse_sortsApproversByEntryOrder_regardlessOfCollectionIterationOrder() {
        ApprovalLevelApprover entry1 = approver(1, ApproverSourceType.NAMED_USER);
        ApprovalLevelApprover entry2 = approver(2, ApproverSourceType.REPORTING_MANAGER);
        ApprovalLevel level = level(1, "Level 1", entry2, entry1); // constructed out of order

        ApprovalFlow flow = ApprovalFlow.builder()
                .flowId(UUID.randomUUID()).name("Flow").isCatchAll(false).status("ACTIVE")
                .levels(new java.util.ArrayList<>(List.of(level)))
                .build();

        var response = mapper.toResponse(flow);

        assertThat(response.levels().get(0).approvers()).extracting("entryOrder").containsExactly(1, 2);
    }

    @Test
    void toResponse_sortsApprovers_withNullEntryOrderLast_forAnyOfAllOfQuorumEntries() {
        ApprovalLevelApprover namedFirst = approver(1, ApproverSourceType.NAMED_USER);
        ApprovalLevelApprover parallelEntry = approver(null, ApproverSourceType.COST_CENTER_OWNER); // ANY_OF/ALL_OF entry - order irrelevant
        ApprovalLevel level = level(1, "Level 1", parallelEntry, namedFirst);

        ApprovalFlow flow = ApprovalFlow.builder()
                .flowId(UUID.randomUUID()).name("Flow").isCatchAll(false).status("ACTIVE")
                .levels(new java.util.ArrayList<>(List.of(level)))
                .build();

        var response = mapper.toResponse(flow);

        assertThat(response.levels().get(0).approvers()).extracting("entryOrder").containsExactly(1, null);
    }
}
