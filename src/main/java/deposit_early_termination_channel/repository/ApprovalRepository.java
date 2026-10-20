package deposit_early_termination_channel.repository;

import deposit_early_termination_channel.domain.enums.ApprovalReason;
import deposit_early_termination_channel.domain.enums.ApprovalStatus;
import deposit_early_termination_channel.domain.model.Approval;
import deposit_early_termination_channel.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class ApprovalRepository {
    private final JdbcTemplate jdbc;

    public ApprovalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Approval> MAPPER = (rs, rowNum) -> {
        String raw = rs.getString("reasons");
        List<ApprovalReason> reasons = (raw == null || raw.isBlank()) ? List.of()
                : Arrays.stream(raw.split(","))
                .map(String::trim).map(ApprovalReason::valueOf).toList();
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return Approval.builder()
                .id(rs.getLong("id"))
                .terminationId(rs.getLong("termination_id"))
                .reasons(reasons)
                .payoutAmount(rs.getLong("payout_amount"))
                .requestedBy(rs.getLong("requested_by"))
                .memo(rs.getString("memo"))
                .requestedAt(rs.getTimestamp("requested_at").toLocalDateTime())
                .status(ApprovalStatus.valueOf(rs.getString("status")))
                .approverId(rs.getObject("approver_id", Long.class))
                .decisionNote(rs.getString("decision_note"))
                .decidedAt(decidedAt == null ? null : decidedAt.toLocalDateTime())
                .build();
    };

    public Long insert(Long terminationId, List<ApprovalReason> reasons,
                       Long payoutAmount, Long requestedBy, String memo) {
        String sql = """
                INSERT INTO approval(termination_id, reasons, payout_amount, requested_by, memo)
                VALUES (?, ?, ?, ?, ?)
                """;
        String reasonText = reasons.stream().map(Enum::name).collect(Collectors.joining(","));
        KeyHolder kh = new GeneratedKeyHolder();
        int rows = jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, terminationId);
            ps.setString(2, reasonText);
            ps.setLong(3, payoutAmount);
            ps.setLong(4, requestedBy);
            ps.setString(5, memo);
            return ps;
        }, kh);
        if (rows != 1 || kh.getKey() == null) throw new BusinessException(HttpStatus.CONFLICT, "APPROVAL_INSERT_FAILED", "승인 요청 저장 실패");
        return kh.getKey().longValue();
    }

    public Approval findById(Long id) {
        return jdbc.query("SELECT * FROM approval WHERE id = ?", MAPPER, id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "승인 요청이 없습니다."));
    }

    public Approval findByIdForUpdate(Long id) {
        return jdbc.query("SELECT * FROM approval WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "승인 요청이 없습니다."));
    }

    public List<Approval> findByStatus(ApprovalStatus status) {
        return jdbc.query("SELECT * FROM approval WHERE status = ? ORDER BY requested_at DESC, id DESC",
                MAPPER, status.name());
    }

    public int decide(Long approvalId, ApprovalStatus decision, Long approverId, String decisionNote) {
        return jdbc.update("""
                UPDATE approval SET status = ?, approver_id = ?, decision_note = ?, decided_at = NOW()
                WHERE id = ? AND status = 'PENDING'
                """, decision.name(), approverId, decisionNote, approvalId);
    }
}
