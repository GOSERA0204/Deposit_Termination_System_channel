package deposit_early_termination_channel.dto.request;
import jakarta.validation.constraints.*;
public record ApprovalDecisionRequest(@NotNull @Positive Long approverId, @Size(max=200) String comment) {}
