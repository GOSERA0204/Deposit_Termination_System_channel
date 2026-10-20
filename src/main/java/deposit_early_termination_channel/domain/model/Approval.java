package deposit_early_termination_channel.domain.model;


import deposit_early_termination_channel.domain.enums.ApprovalReason;
import deposit_early_termination_channel.domain.enums.ApprovalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Approval {

    private Long id;

    private Long terminationId;

    @Builder.Default
    private List<ApprovalReason> reasons = List.of();

    // 승인 요청 당시 견적 지급액
    private Long payoutAmount;

    private Long requestedBy;

    private String memo;

    private LocalDateTime requestedAt;

    @Builder.Default
    private ApprovalStatus status = ApprovalStatus.PENDING;

    // 승인·반려 결정 전에는 null
    private Long approverId;

    private String decisionNote;

    private LocalDateTime decidedAt;
}
