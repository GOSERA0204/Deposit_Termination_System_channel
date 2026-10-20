package deposit_early_termination_channel.dto.response;

import deposit_early_termination_channel.domain.enums.ApprovalReason;
import deposit_early_termination_channel.domain.enums.ApprovalStatus;
import deposit_early_termination_channel.domain.model.Approval;

import java.time.LocalDateTime;
import java.util.List;

public record ApprovalResponse(
        Long id,
        Long terminationId,
        List<ApprovalReason> reasons,
        Long payoutAmount,
        Long requestedBy,
        String memo,
        LocalDateTime requestedAt,
        ApprovalStatus status,
        Long approverId,
        String decisionNote,
        LocalDateTime decidedAt) {
    public static ApprovalResponse from(Approval a) {
        return new ApprovalResponse(a.getId(), a.getTerminationId(), a.getReasons(),
                a.getPayoutAmount(), a.getRequestedBy(), a.getMemo(), a.getRequestedAt(),
                a.getStatus(), a.getApproverId(), a.getDecisionNote(), a.getDecidedAt());
    }
}