package deposit_early_termination_channel.dto.response;
import deposit_early_termination_channel.domain.enums.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
public record ApprovalResponse(
    Long approvalId, Long terminationId, ApprovalStatus status,
    List<ApprovalReason> reasons, String customerName, String depositAccountNo,
    Long payoutAmount, Long requestedBy, LocalDateTime requestedAt, String memo,
    Long approverId, LocalDateTime decidedAt, String decisionNote,
    Map<String, LinkResponse> _links) {}
