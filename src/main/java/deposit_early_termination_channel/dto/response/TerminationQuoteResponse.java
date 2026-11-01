package deposit_early_termination_channel.dto.response;
import java.math.BigDecimal;
public record TerminationQuoteResponse(
    Long principal, Integer elapsedDays, BigDecimal baseRate, BigDecimal appliedRate,
    Long interest, Long tax, Long payoutAmount) {}
