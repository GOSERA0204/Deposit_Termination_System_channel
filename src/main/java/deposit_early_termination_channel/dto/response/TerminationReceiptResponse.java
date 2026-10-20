package deposit_early_termination_channel.dto.response;

import deposit_early_termination_channel.domain.enums.PayoutMethod;

import java.time.LocalDateTime;

public record TerminationReceiptResponse(
        Long terminationId,
        String globalTxId,
        String customerName,
        String depositAccountNo,
        Long principal,
        Long interest,
        Long tax,
        Long payoutAmount,
        PayoutMethod payoutMethod,
        String payoutAccountNo,
        LocalDateTime completedAt
) {
}
