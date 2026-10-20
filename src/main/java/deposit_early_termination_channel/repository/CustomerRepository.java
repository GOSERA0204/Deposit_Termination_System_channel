package deposit_early_termination_channel.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CustomerRepository {

    private final JdbcTemplate jdbcTemplate;

    public void upsert(Long customerId, String name) {
        String sql = """
                INSERT INTO customer (id, name)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE name = ?
                """;

        jdbcTemplate.update(
                sql,
                customerId,
                name,
                name
        );
    }

    public String findName(Long customerId) {
        String sql = """
                SELECT name
                FROM customer
                WHERE id = ?
                """;

        return jdbcTemplate.queryForObject(
                sql,
                String.class,
                customerId
        );
    }
}