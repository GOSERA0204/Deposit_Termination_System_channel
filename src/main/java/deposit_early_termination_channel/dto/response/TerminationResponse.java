package deposit_early_termination_channel.dto.response;

import deposit_early_termination_channel.domain.enums.ApprovalReason;
import deposit_early_termination_channel.domain.enums.PayoutMethod;
import deposit_early_termination_channel.domain.enums.TerminationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record TerminationResponse(
        Long id,
        TerminationStatus status,

        Long tellerId,
        String terminalNo,

        Long customerId,
        String customerName,
        String depositAccountNo,

        PayoutMethod payoutMethod,
        String payoutAccountNo,

        TerminationQuoteResponse quote,
        List<ApprovalReason> approvalReasons,

        String globalTxId,
        Long actualPrincipal,
        Long actualInterest,
        Long actualTax,
        Long actualPayout,

        String failReason,
        LocalDateTime createdAt,
        LocalDateTime completedAt,

        Map<String, LinkResponse> _links
) {
}