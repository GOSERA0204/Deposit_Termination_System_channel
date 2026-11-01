package deposit_early_termination_channel.dto.response;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
public record TerminationReceiptResponse(
    Long terminationId, String globalTxId, String depositAccountNo, String customerName,
    Long principal, Integer elapsedDays, BigDecimal appliedRate, Long interest, Long tax,
    Long payoutAmount, PayoutResponse payout, Long tellerId, Long approverId,
    LocalDateTime completedAt, Map<String, LinkResponse> _links) {}
