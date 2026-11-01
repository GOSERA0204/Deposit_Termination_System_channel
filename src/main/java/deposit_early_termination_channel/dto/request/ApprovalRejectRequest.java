package deposit_early_termination_channel.dto.request;
import jakarta.validation.constraints.*;
public record ApprovalRejectRequest(@NotNull @Positive Long approverId, @NotBlank @Size(max=200) String reason) {}
