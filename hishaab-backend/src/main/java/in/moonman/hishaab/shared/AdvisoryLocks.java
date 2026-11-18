package in.moonman.hishaab.shared;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * Postgres session-level advisory locks (HLD §4.7 "one processing worker at a time"). The lock is taken on a
 * dedicated pooled connection that is held for the whole unit of work, so the work itself can use any number of
 * short transactions on other connections.
 */
@Component
public class AdvisoryLocks {

    /** Lock family for message processing, rebuilds and scans (R-015-2, R-034-3, R-036-3). */
    public static final long PROCESSING = 0x4849_5348_0001L; // "HISH" + 1

    private final DataSource dataSource;

    public AdvisoryLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Runs {@code work} if the lock is free right now; otherwise returns empty without waiting. */
    public <T> Optional<T> tryWithLock(long key, Supplier<T> work) {
        try (Connection c = dataSource.getConnection()) {
            if (!lock(c, "SELECT pg_try_advisory_lock(?)", key)) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(work.get());
            } finally {
                unlock(c, key);
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("advisory lock " + key + " failed", e);
        }
    }

    /** Waits up to {@code timeout} for the lock, then runs {@code work}; empty if the lock never became free. */
    public <T> Optional<T> withLock(long key, Duration timeout, Supplier<T> work) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<Optional<T>> attempt = tryWithLock(key, () -> Optional.ofNullable(work.get()));
            if (attempt.isPresent()) {
                return attempt.get();
            }
            if (System.nanoTime() > deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    private static boolean lock(Connection c, String sql, long key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection c, long key) throws SQLException {
        lock(c, "SELECT pg_advisory_unlock(?)", key);
    }
}
