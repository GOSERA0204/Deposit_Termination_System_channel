package deposit_early_termination_channel.dto.request;

import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(@Size(max = 200) String decisionNote) {}
