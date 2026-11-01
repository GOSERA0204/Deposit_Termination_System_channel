package deposit_early_termination_channel.dto.request;
import jakarta.validation.constraints.*;
public record ApprovalCreateRequest(@NotNull @Positive Long requestedBy, @Size(max=200) String memo) {}
