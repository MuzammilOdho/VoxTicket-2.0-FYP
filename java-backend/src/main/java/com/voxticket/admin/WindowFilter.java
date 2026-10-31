package com.voxticket.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Phase 1 (admin rework): shared date-window clause for admin JDBC queries.
 *
 * <p>Semantics, identical everywhere it is used: {@code from} is inclusive,
 * {@code to} is exclusive, {@code null} means unbounded on that side. The
 * caller is responsible for calling {@link #and} in the exact order the
 * clauses appear in the SQL string, because JDBC parameters are positional.
 *
 * <p>Callers write {@code WHERE 1=1} before the first window clause so the
 * generated {@code AND ...} is always valid, including when both bounds are
 * null (the clause is then empty and the query is unchanged).
 */
final class WindowFilter {

    private WindowFilter() {
    }

    static String and(String column, Instant from, Instant to, List<Object> params) {
        StringBuilder clause = new StringBuilder();
        if (from != null) {
            clause.append(" AND ").append(column).append(" >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            clause.append(" AND ").append(column).append(" < ?");
            params.add(Timestamp.from(to));
        }
        return clause.toString();
    }
}
