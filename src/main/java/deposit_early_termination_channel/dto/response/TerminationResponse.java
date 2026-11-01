package deposit_early_termination_channel.dto.response;
import deposit_early_termination_channel.domain.enums.*;
import java.time.LocalDateTime;
import java.util.*;
public record TerminationResponse(
    Long id, String channelTxId, TerminationStatus status,
    Long tellerId, String terminalNo, String depositAccountNo, String customerName,
    boolean isAgent, boolean passbookPresented, PayoutResponse payout,
    TerminationQuoteResponse quote, List<ApprovalReason> approvalReasons, Long approvalId,
    String globalTxId, String failCode, LocalDateTime createdAt, LocalDateTime updatedAt,
    Map<String, LinkResponse> _links) {}
