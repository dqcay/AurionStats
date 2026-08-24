package fun.aurion.stats;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

record DatabaseSettings(
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl,
        int poolSize,
        long connectionTimeoutMillis
) {
    static DatabaseSettings from(FileConfiguration config) {
        String database = required(config, "database.name");
        String host = required(config, "database.host");
        String username = required(config, "database.username");
        String password = Objects.requireNonNull(config.getString("database.password"), "Missing database.password");
        if (host.startsWith("YOUR_") || database.startsWith("YOUR_")
                || username.startsWith("YOUR_") || password.equals("CHANGE_ME")) {
            throw new IllegalArgumentException("Replace the database placeholders in config.yml");
        }
        if (!database.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("database.name may contain only letters, numbers and underscores");
        }

        int port = config.getInt("database.port", 3306);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("database.port must be between 1 and 65535");
        }

        return new DatabaseSettings(
                host,
                port,
                database,
                username,
                password,
                config.getBoolean("database.use-ssl", false),
                Math.max(1, config.getInt("database.pool-size", 4)),
                Math.max(2_000L, config.getLong("database.connection-timeout-ms", 10_000L))
        );
    }

    private static String required(FileConfiguration config, String path) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + path);
        }
        return value.trim();
    }
}
