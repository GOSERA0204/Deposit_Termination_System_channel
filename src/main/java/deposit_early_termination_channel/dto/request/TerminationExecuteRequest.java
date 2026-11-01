package deposit_early_termination_channel.dto.request;
import jakarta.validation.constraints.*;
public record TerminationExecuteRequest(@NotNull @Positive Long tellerId) {}
