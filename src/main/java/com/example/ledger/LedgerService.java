package com.example.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * The deterministic core. No AI in here, ever.
 * Rule: total debits must equal total credits, or nothing is saved.
 */
@Service
public class LedgerService {

    public record Line(String accountCode, char direction, long amountMinor, String currency) {}

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Posts one balanced transaction. Returns false if this reference was already posted. */
    @Transactional
    public boolean post(String reference, String description, List<Line> lines) {
        assertBalanced(lines);

        // UNIQUE(reference) makes a second post of the same payment a no-op.
        Integer already = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference = ?", Integer.class, reference);
        if (already != null && already > 0) {
            return false;
        }

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ledger_transactions (reference, description) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, reference);
            ps.setString(2, description);
            return ps;
        }, keys);
        long txnId = ((Number) keys.getKeys().get("id")).longValue();

        for (Line l : lines) {
            jdbc.update(
                    "INSERT INTO ledger_entries (transaction_id, account_code, direction, amount_minor, currency) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    txnId, l.accountCode(), String.valueOf(l.direction()), l.amountMinor(), l.currency());
        }
        return true;
    }

    private void assertBalanced(List<Line> lines) {
        long debits = lines.stream().filter(l -> l.direction() == 'D').mapToLong(Line::amountMinor).sum();
        long credits = lines.stream().filter(l -> l.direction() == 'C').mapToLong(Line::amountMinor).sum();
        if (debits != credits || debits == 0) {
            throw new IllegalArgumentException(
                    "Unbalanced transaction: debits=" + debits + " credits=" + credits);
        }
    }

    /** Whole-ledger check: should always be true. Use it on the dashboard. */
    public boolean isBalanced() {
        Long diff = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE direction WHEN 'D' THEN amount_minor ELSE -amount_minor END), 0) "
                        + "FROM ledger_entries", Long.class);
        return diff != null && diff == 0;
    }

    public Long getPaymentAmount(String captureId) {

    String reference = "capture:" + captureId;

    return jdbc.query(
            """
            SELECT COALESCE(SUM(amount_minor), 0)
            FROM ledger_entries
            WHERE transaction_id = (
                SELECT id
                FROM ledger_transactions
                WHERE reference = ?
            )
            AND account_code = 'PAYPAL_CLEARING'
            AND direction = 'D'
            """,
            rs -> rs.next() ? rs.getLong(1) : 0L,
            reference
    );
}

@Transactional
public void lockPayment(String captureId) {

    jdbc.queryForList(
            """
            SELECT id
            FROM ledger_transactions
            WHERE reference = ?
            FOR UPDATE
            """,
            Long.class,
            "capture:" + captureId
    );
}
}
