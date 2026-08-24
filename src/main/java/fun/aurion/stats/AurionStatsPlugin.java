package fun.aurion.stats;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AurionStatsPlugin extends JavaPlugin {
    private DatabaseManager database;
    private ExecutorService databaseExecutor;
    private StatsTracker tracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            DatabaseSettings settings = DatabaseSettings.from(getConfig());
            database = new DatabaseManager(settings);
        } catch (Exception exception) {
            getLogger().severe("Не удалось подключиться к MySQL или создать таблицы: " + exception.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "Database initialization error", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        databaseExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AurionStats-Database");
            thread.setDaemon(false);
            return thread;
        });
        tracker = new StatsTracker(database, databaseExecutor, getLogger());
        Bukkit.getPluginManager().registerEvents(tracker, this);
        Bukkit.getOnlinePlayers().forEach(tracker::trackAlreadyOnline);

        long intervalSeconds = Math.max(10L, getConfig().getLong("save-interval-seconds", 60L));
        long intervalTicks = intervalSeconds * 20L;
        Bukkit.getScheduler().runTaskTimer(this, tracker::flushOnlinePlayers, intervalTicks, intervalTicks);
        getLogger().info("AurionStats включён. Статистика сохраняется в MySQL каждые " + intervalSeconds + " сек.");
    }

    @Override
    public void onDisable() {
        if (tracker != null && databaseExecutor != null) {
            tracker.shutdownAndFlush();
        }
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
            try {
                if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    databaseExecutor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                databaseExecutor.shutdownNow();
            }
        }
        if (database != null) {
            database.close();
        }
    }
}
