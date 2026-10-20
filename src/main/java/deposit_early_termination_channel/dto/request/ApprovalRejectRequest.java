package deposit_early_termination_channel.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApprovalRejectRequest(
        @NotBlank @Size(max = 200) String decisionNote) {}
