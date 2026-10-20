package deposit_early_termination_channel.repository;

import deposit_early_termination_channel.domain.enums.ApprovalReason;
import deposit_early_termination_channel.domain.enums.TerminationStatus;
import deposit_early_termination_channel.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

@Repository
public class TerminationApprovalRepository {
    private final JdbcTemplate jdbc;
    public TerminationApprovalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // 승인 업무에서 필요한 해지 접수 필드만 조회한다. A의 Repository와 파일 충돌 없음.
    public TerminationApprovalView lockTermination(Long terminationId) {
        return jdbc.query("""
                        SELECT id, teller_id, status, approval_reasons
                        FROM branch_termination WHERE id = ? FOR UPDATE
                        """, (rs, n) -> new TerminationApprovalView(
                        rs.getLong("id"), rs.getLong("teller_id"),
                        TerminationStatus.valueOf(rs.getString("status")),
                        parseReasons(rs.getString("approval_reasons"))), terminationId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "TERMINATION_NOT_FOUND", "해지 접수가 없습니다."));
    }

    public Long findQuotePayout(Long terminationId) {
        return jdbc.query("SELECT payout_amount FROM termination_quote WHERE termination_id = ?",
                        (rs, n) -> rs.getLong("payout_amount"), terminationId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(
                        HttpStatus.CONFLICT, "QUOTE_NOT_FOUND", "해지 견적이 없습니다."));
    }

    public int updateStatus(Long terminationId, TerminationStatus before, TerminationStatus after) {
        return jdbc.update("UPDATE branch_termination SET status = ? WHERE id = ? AND status = ?",
                after.name(), terminationId, before.name());
    }

    private List<ApprovalReason> parseReasons(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).map(ApprovalReason::valueOf).toList();
    }

    public record TerminationApprovalView(Long id, Long tellerId,
                                          TerminationStatus status, List<ApprovalReason> reasons) {}
}
