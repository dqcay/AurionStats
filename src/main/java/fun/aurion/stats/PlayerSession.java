package fun.aurion.stats;

import java.time.Instant;
import java.util.UUID;

final class PlayerSession {
    private final UUID playerUuid;
    private final String playerName;
    private final Instant joinedAt;
    private Instant persistedUntil;
    private long databaseId = -1L;

    PlayerSession(UUID playerUuid, String playerName, Instant joinedAt) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.joinedAt = joinedAt;
        this.persistedUntil = joinedAt;
    }

    UUID playerUuid() {
        return playerUuid;
    }

    String playerName() {
        return playerName;
    }

    Instant joinedAt() {
        return joinedAt;
    }

    Instant persistedUntil() {
        return persistedUntil;
    }

    void markPersistedUntil(Instant persistedUntil) {
        this.persistedUntil = persistedUntil;
    }

    long databaseId() {
        return databaseId;
    }

    void setDatabaseId(long databaseId) {
        this.databaseId = databaseId;
    }
}
