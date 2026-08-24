package fun.aurion.stats;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class DatabaseManager implements AutoCloseable {
    private static final String CREATE_PLAYERS = """
            CREATE TABLE IF NOT EXISTS player_stats (
                player_uuid CHAR(36) NOT NULL,
                last_known_name VARCHAR(64) NOT NULL,
                status ENUM('ONLINE', 'OFFLINE') NOT NULL DEFAULT 'OFFLINE',
                last_join_at DATETIME(3) NULL,
                last_seen_at DATETIME(3) NULL,
                total_playtime_millis BIGINT UNSIGNED NOT NULL DEFAULT 0,
                created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                PRIMARY KEY (player_uuid),
                KEY idx_player_status (status),
                KEY idx_player_last_seen (last_seen_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_SESSIONS = """
            CREATE TABLE IF NOT EXISTS player_sessions (
                session_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                player_uuid CHAR(36) NOT NULL,
                joined_at DATETIME(3) NOT NULL,
                last_heartbeat_at DATETIME(3) NOT NULL,
                left_at DATETIME(3) NULL,
                duration_millis BIGINT UNSIGNED NOT NULL DEFAULT 0,
                PRIMARY KEY (session_id),
                KEY idx_sessions_player_joined (player_uuid, joined_at),
                KEY idx_sessions_open (left_at),
                CONSTRAINT fk_sessions_player FOREIGN KEY (player_uuid)
                    REFERENCES player_stats (player_uuid) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_DAILY = """
            CREATE TABLE IF NOT EXISTS player_daily_activity (
                player_uuid CHAR(36) NOT NULL,
                activity_date DATE NOT NULL,
                playtime_millis BIGINT UNSIGNED NOT NULL DEFAULT 0,
                updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                PRIMARY KEY (player_uuid, activity_date),
                KEY idx_daily_date (activity_date),
                CONSTRAINT fk_daily_player FOREIGN KEY (player_uuid)
                    REFERENCES player_stats (player_uuid) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private final HikariDataSource dataSource;

    DatabaseManager(DatabaseSettings settings) throws SQLException {
        HikariConfig hikari = new HikariConfig();
        String jdbcUrl = "jdbc:mysql://" + settings.host() + ':' + settings.port() + '/' + settings.database()
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                + "&tcpKeepAlive=true&rewriteBatchedStatements=true"
                + "&useSSL=" + settings.useSsl();
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(settings.username());
        hikari.setPassword(settings.password());
        hikari.setMaximumPoolSize(settings.poolSize());
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(settings.connectionTimeoutMillis());
        hikari.setPoolName("AurionStats-MySQL");
        hikari.setAutoCommit(true);
        hikari.setInitializationFailTimeout(settings.connectionTimeoutMillis());
        dataSource = new HikariDataSource(hikari);

        try {
            initializeSchema();
            recoverInterruptedSessions();
        } catch (SQLException exception) {
            dataSource.close();
            throw exception;
        }
    }

    private void initializeSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_PLAYERS);
            statement.executeUpdate(CREATE_SESSIONS);
            statement.executeUpdate(CREATE_DAILY);
        }
    }

    private void recoverInterruptedSessions() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE player_sessions
                        SET left_at = last_heartbeat_at
                        WHERE left_at IS NULL
                        """);
                statement.executeUpdate("""
                        UPDATE player_stats
                        SET status = 'OFFLINE'
                        WHERE status = 'ONLINE'
                        """);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    void openSession(PlayerSession session) throws SQLException {
        if (session.databaseId() >= 0) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertOnlinePlayer(connection, session);
                closeDanglingSessions(connection, session);

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO player_sessions
                            (player_uuid, joined_at, last_heartbeat_at, duration_millis)
                        VALUES (?, ?, ?, 0)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, session.playerUuid().toString());
                    statement.setTimestamp(2, timestamp(session.joinedAt()));
                    statement.setTimestamp(3, timestamp(session.joinedAt()));
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("MySQL did not return a session id");
                        }
                        long sessionId = keys.getLong(1);
                        connection.commit();
                        session.setDatabaseId(sessionId);
                    }
                }
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    void persistSession(PlayerSession session, Instant until, boolean close) throws SQLException {
        if (session.databaseId() < 0) {
            openSession(session);
        }

        Instant from = session.persistedUntil();
        if (until.isBefore(from)) {
            until = from;
        }
        long deltaMillis = Duration.between(from, until).toMillis();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                updatePlayer(connection, session, until, deltaMillis, close);
                updateSession(connection, session, until, deltaMillis, close);
                addDailyActivity(connection, session, from, until);
                connection.commit();
                session.markPersistedUntil(until);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void upsertOnlinePlayer(Connection connection, PlayerSession session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_stats
                    (player_uuid, last_known_name, status, last_join_at, last_seen_at)
                VALUES (?, ?, 'ONLINE', ?, ?)
                ON DUPLICATE KEY UPDATE
                    last_known_name = VALUES(last_known_name),
                    status = 'ONLINE',
                    last_join_at = VALUES(last_join_at),
                    last_seen_at = VALUES(last_seen_at)
                """)) {
            statement.setString(1, session.playerUuid().toString());
            statement.setString(2, session.playerName());
            statement.setTimestamp(3, timestamp(session.joinedAt()));
            statement.setTimestamp(4, timestamp(session.joinedAt()));
            statement.executeUpdate();
        }
    }

    private void closeDanglingSessions(Connection connection, PlayerSession session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_sessions
                SET left_at = last_heartbeat_at
                WHERE player_uuid = ? AND left_at IS NULL
                """)) {
            statement.setString(1, session.playerUuid().toString());
            statement.executeUpdate();
        }
    }

    private void updatePlayer(Connection connection, PlayerSession session, Instant until,
                              long deltaMillis, boolean close) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_stats
                SET last_known_name = ?,
                    status = ?,
                    last_seen_at = ?,
                    total_playtime_millis = total_playtime_millis + ?
                WHERE player_uuid = ?
                """)) {
            statement.setString(1, session.playerName());
            statement.setString(2, close ? "OFFLINE" : "ONLINE");
            statement.setTimestamp(3, timestamp(until));
            statement.setLong(4, deltaMillis);
            statement.setString(5, session.playerUuid().toString());
            statement.executeUpdate();
        }
    }

    private void updateSession(Connection connection, PlayerSession session, Instant until,
                               long deltaMillis, boolean close) throws SQLException {
        String sql = close
                ? """
                    UPDATE player_sessions
                    SET last_heartbeat_at = ?, left_at = ?, duration_millis = duration_millis + ?
                    WHERE session_id = ?
                    """
                : """
                    UPDATE player_sessions
                    SET last_heartbeat_at = ?, duration_millis = duration_millis + ?
                    WHERE session_id = ?
                    """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, timestamp(until));
            int index = 2;
            if (close) {
                statement.setTimestamp(index++, timestamp(until));
            }
            statement.setLong(index++, deltaMillis);
            statement.setLong(index, session.databaseId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Session " + session.databaseId() + " was not found");
            }
        }
    }

    private void addDailyActivity(Connection connection, PlayerSession session,
                                  Instant from, Instant until) throws SQLException {
        List<DailySlice> slices = splitByUtcDay(from, until);
        if (slices.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_daily_activity (player_uuid, activity_date, playtime_millis)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    playtime_millis = playtime_millis + VALUES(playtime_millis)
                """)) {
            for (DailySlice slice : slices) {
                statement.setString(1, session.playerUuid().toString());
                statement.setObject(2, slice.date());
                statement.setLong(3, slice.millis());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    static List<DailySlice> splitByUtcDay(Instant from, Instant until) {
        List<DailySlice> result = new ArrayList<>();
        Instant cursor = from;
        while (cursor.isBefore(until)) {
            LocalDate date = cursor.atZone(ZoneOffset.UTC).toLocalDate();
            Instant nextDay = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant sliceEnd = until.isBefore(nextDay) ? until : nextDay;
            long millis = Duration.between(cursor, sliceEnd).toMillis();
            if (millis > 0) {
                result.add(new DailySlice(date, millis));
            }
            cursor = sliceEnd;
        }
        return result;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    @Override
    public void close() {
        dataSource.close();
    }

    record DailySlice(LocalDate date, long millis) {
    }
}
