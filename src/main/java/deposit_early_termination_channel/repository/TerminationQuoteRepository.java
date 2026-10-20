package deposit_early_termination_channel.repository;

import deposit_early_termination_channel.domain.model.TerminationQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class TerminationQuoteRepository {

    private final JdbcTemplate jdbcTemplate;

    public void save(TerminationQuote quote) {
        String sql = """
                INSERT INTO termination_quote (
                    termination_id,
                    principal,
                    elapsed_days,
                    applied_rate,
                    interest,
                    tax,
                    payout_amount,
                    quoted_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.update(
                sql,
                quote.getTerminationId(),
                quote.getPrincipal(),
                quote.getElapsedDays(),
                quote.getAppliedRate(),
                quote.getInterest(),
                quote.getTax(),
                quote.getPayoutAmount(),
                quote.getQuotedAt()
        );
    }

    public TerminationQuote findByTerminationId(Long terminationId) {
        String sql = """
                SELECT *
                FROM termination_quote
                WHERE termination_id = ?
                """;

        return jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> TerminationQuote.builder()
                        .id(rs.getLong("id"))
                        .terminationId(rs.getLong("termination_id"))
                        .principal(rs.getLong("principal"))
                        .elapsedDays(rs.getInt("elapsed_days"))
                        .appliedRate(rs.getBigDecimal("applied_rate"))
                        .interest(rs.getLong("interest"))
                        .tax(rs.getLong("tax"))
                        .payoutAmount(rs.getLong("payout_amount"))
                        .quotedAt(
                                rs.getObject(
                                        "quoted_at",
                                        LocalDateTime.class
                                )
                        )
                        .build(),
                terminationId
        );
    }
}