package deposit_early_termination_channel.repository;

import deposit_early_termination_channel.domain.enums.TellerRole;
import deposit_early_termination_channel.domain.model.Teller;
import deposit_early_termination_channel.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalTellerRepository {
    private final JdbcTemplate jdbc;
    public ApprovalTellerRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Teller findById(Long id) {
        return jdbc.query("SELECT id, name, role, approval_limit, branch_no FROM teller WHERE id = ?",
                        (rs, n) -> Teller.builder()
                                .id(rs.getLong("id"))
                                .name(rs.getString("name"))
                                .role(TellerRole.valueOf(rs.getString("role")))
                                .approvalLimit(rs.getObject("approval_limit", Long.class))
                                .branchNo(rs.getString("branch_no"))
                                .build(), id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "TELLER_NOT_FOUND", "직원 정보가 없습니다."));
    }
}