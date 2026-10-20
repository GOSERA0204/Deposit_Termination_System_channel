package deposit_early_termination_channel.repository;

import deposit_early_termination_channel.domain.model.BranchTermination;
import deposit_early_termination_channel.domain.enums.ApprovalReason;
import deposit_early_termination_channel.domain.enums.PayoutMethod;
import deposit_early_termination_channel.domain.enums.TerminationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class TerminationRepository {

    private final JdbcTemplate jdbcTemplate;

    public Long save(BranchTermination termination) {
        String sql = """
                INSERT INTO branch_termination (
                    teller_id,
                    terminal_no,
                    customer_id,
                    deposit_account_no,
                    id_verified,
                    is_agent,
                    passbook_presented,
                    pledged,
                    payout_method,
                    payout_account_no,
                    approval_reasons,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );

            ps.setLong(1, termination.getTellerId());
            ps.setString(2, termination.getTerminalNo());
            ps.setLong(3, termination.getCustomerId());
            ps.setString(4, termination.getDepositAccountNo());
            ps.setBoolean(5, termination.isIdVerified());
            ps.setBoolean(6, termination.isAgent());
            ps.setBoolean(7, termination.isPassbookPresented());
            ps.setBoolean(8, termination.isPledged());
            ps.setString(9, termination.getPayoutMethod().name());
            ps.setString(10, termination.getPayoutAccountNo());

            ps.setString(
                    11,
                    toDatabase(termination.getApprovalReasons())
            );

            ps.setString(12, termination.getStatus().name());

            return ps;
        }, keyHolder);

        return Objects.requireNonNull(
                keyHolder.getKey()
        ).longValue();
    }

    public Optional<BranchTermination> findById(Long id) {
        String sql = """
                SELECT *
                FROM branch_termination
                WHERE id = ?
                """;

        return jdbcTemplate.query(
                sql,
                ROW_MAPPER,
                id
        ).stream().findFirst();
    }

    public List<BranchTermination> findAll(TerminationStatus status) {
        if (status == null) {
            return jdbcTemplate.query("""
                    SELECT *
                    FROM branch_termination
                    ORDER BY created_at DESC, id DESC
                    """,
                    ROW_MAPPER
            );
        }

        return jdbcTemplate.query("""
                SELECT *
                FROM branch_termination
                WHERE status = ?
                ORDER BY created_at DESC, id DESC
                """,
                ROW_MAPPER,
                status.name()
        );
    }

    // B 담당의 승인 서비스에서도 사용
    public int changeStatus(
            Long id,
            TerminationStatus expected,
            TerminationStatus next
    ) {
        String sql = """
                UPDATE branch_termination
                SET status = ?
                WHERE id = ?
                  AND status = ?
                """;

        return jdbcTemplate.update(
                sql,
                next.name(),
                id,
                expected.name()
        );
    }

    // 동일 접수의 동시 실행을 한 건만 허용
    public int startProcessing(Long id) {
        String sql = """
                UPDATE branch_termination
                SET status = 'PROCESSING'
                WHERE id = ?
                  AND (
                      (
                          status = 'RECEIVED'
                          AND (
                              approval_reasons IS NULL
                              OR approval_reasons = ''
                          )
                      )
                      OR status = 'APPROVED'
                  )
                """;

        return jdbcTemplate.update(sql, id);
    }

    public int complete(
            Long id,
            String globalTxId,
            Long principal,
            Long interest,
            Long tax,
            Long payoutAmount
    ) {
        String sql = """
                UPDATE branch_termination
                SET status = 'COMPLETED',
                    global_tx_id = ?,
                    actual_principal = ?,
                    actual_interest = ?,
                    actual_tax = ?,
                    actual_payout = ?,
                    fail_reason = NULL,
                    completed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'PROCESSING'
                """;

        return jdbcTemplate.update(
                sql,
                globalTxId,
                principal,
                interest,
                tax,
                payoutAmount,
                id
        );
    }

    public int finishWithError(
            Long id,
            TerminationStatus status,
            String globalTxId,
            String errorCode
    ) {
        String sql = """
                UPDATE branch_termination
                SET status = ?,
                    global_tx_id = COALESCE(?, global_tx_id),
                    fail_reason = ?
                WHERE id = ?
                  AND status = 'PROCESSING'
                """;

        return jdbcTemplate.update(
                sql,
                status.name(),
                globalTxId,
                errorCode,
                id
        );
    }

    private static String toDatabase(
            List<ApprovalReason> reasons
    ) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }

        return reasons.stream()
                .map(ApprovalReason::name)
                .collect(Collectors.joining(","));
    }

    private static List<ApprovalReason> fromDatabase(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(ApprovalReason::valueOf)
                .toList();
    }

    private static final RowMapper<BranchTermination> ROW_MAPPER =
            (rs, rowNum) -> BranchTermination.builder()
                    .id(rs.getLong("id"))
                    .tellerId(rs.getLong("teller_id"))
                    .terminalNo(rs.getString("terminal_no"))
                    .customerId(rs.getLong("customer_id"))
                    .depositAccountNo(
                            rs.getString("deposit_account_no")
                    )
                    .idVerified(rs.getBoolean("id_verified"))
                    .isAgent(rs.getBoolean("is_agent"))
                    .passbookPresented(
                            rs.getBoolean("passbook_presented")
                    )
                    .pledged(rs.getBoolean("pledged"))
                    .payoutMethod(
                            PayoutMethod.valueOf(
                                    rs.getString("payout_method")
                            )
                    )
                    .payoutAccountNo(
                            rs.getString("payout_account_no")
                    )
                    .approvalReasons(
                            fromDatabase(
                                    rs.getString("approval_reasons")
                            )
                    )
                    .status(
                            TerminationStatus.valueOf(
                                    rs.getString("status")
                            )
                    )
                    .globalTxId(rs.getString("global_tx_id"))
                    .failReason(rs.getString("fail_reason"))
                    .actualPrincipal(
                            rs.getObject("actual_principal", Long.class)
                    )
                    .actualInterest(
                            rs.getObject("actual_interest", Long.class)
                    )
                    .actualTax(
                            rs.getObject("actual_tax", Long.class)
                    )
                    .actualPayout(
                            rs.getObject("actual_payout", Long.class)
                    )
                    .completedAt(
                            rs.getObject(
                                    "completed_at",
                                    LocalDateTime.class
                            )
                    )
                    .createdAt(
                            rs.getObject(
                                    "created_at",
                                    LocalDateTime.class
                            )
                    )
                    .updatedAt(
                            rs.getObject(
                                    "updated_at",
                                    LocalDateTime.class
                            )
                    )
                    .build();
}