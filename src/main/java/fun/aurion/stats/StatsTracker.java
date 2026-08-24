package fun.aurion.stats;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

final class StatsTracker implements Listener {
    private final DatabaseManager database;
    private final ExecutorService databaseExecutor;
    private final Logger logger;
    private final Map<UUID, PlayerSession> onlineSessions = new ConcurrentHashMap<>();

    StatsTracker(DatabaseManager database, ExecutorService databaseExecutor, Logger logger) {
        this.database = database;
        this.databaseExecutor = databaseExecutor;
        this.logger = logger;
    }

    void trackAlreadyOnline(Player player) {
        startTracking(player.getUniqueId(), player.getName(), Instant.now());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        startTracking(player.getUniqueId(), player.getName(), Instant.now());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Instant quitAt = Instant.now();
        PlayerSession session = onlineSessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            submit("closing session for " + session.playerName(),
                    () -> database.persistSession(session, quitAt, true));
        }
    }

    private void startTracking(UUID uuid, String name, Instant joinedAt) {
        PlayerSession session = new PlayerSession(uuid, name, joinedAt);
        PlayerSession oldSession = onlineSessions.put(uuid, session);
        if (oldSession != null) {
            submit("closing duplicate session for " + oldSession.playerName(),
                    () -> database.persistSession(oldSession, joinedAt, true));
        }
        submit("opening session for " + name, () -> database.openSession(session));
    }

    void flushOnlinePlayers() {
        Instant now = Instant.now();
        for (PlayerSession session : onlineSessions.values()) {
            submit("saving session for " + session.playerName(),
                    () -> database.persistSession(session, now, false));
        }
    }

    void shutdownAndFlush() {
        Instant now = Instant.now();
        ArrayList<PlayerSession> sessions = new ArrayList<>(onlineSessions.values());
        onlineSessions.clear();

        CompletableFuture<Void> completed = new CompletableFuture<>();
        databaseExecutor.execute(() -> {
            try {
                for (PlayerSession session : sessions) {
                    try {
                        database.persistSession(session, now, true);
                    } catch (Exception exception) {
                        logger.log(Level.SEVERE, "Could not close session for " + session.playerName(), exception);
                    }
                }
            } finally {
                completed.complete(null);
            }
        });

        try {
            completed.get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Timed out while saving player statistics", exception);
        }
    }

    private void submit(String action, SqlAction operation) {
        databaseExecutor.execute(() -> {
            try {
                operation.run();
            } catch (Exception exception) {
                logger.log(Level.SEVERE, "MySQL error while " + action, exception);
            }
        });
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }
}
