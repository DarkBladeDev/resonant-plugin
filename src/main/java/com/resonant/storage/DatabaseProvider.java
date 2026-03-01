package com.resonant.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;

public class DatabaseProvider {

    private final JavaPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseProvider(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        String driver = plugin.getConfig().getString("moderation.database.driver", "sqlite");
        String url = plugin.getConfig().getString("moderation.database.url", "jdbc:sqlite:plugins/Resonant/mod.db");
        String username = plugin.getConfig().getString("moderation.database.username", "");
        String password = plugin.getConfig().getString("moderation.database.password", "");
        int maxPoolSize = plugin.getConfig().getInt("moderation.database.pool.maxPoolSize", 5);
        int minIdle = plugin.getConfig().getInt("moderation.database.pool.minIdle", 1);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        if (!username.isEmpty()) {
            config.setUsername(username);
        }
        if (!password.isEmpty()) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        if ("sqlite".equalsIgnoreCase(driver)) {
            config.setDriverClassName("org.sqlite.JDBC");
        }
        dataSource = new HikariDataSource(config);
    }

    public DataSource get() {
        return dataSource;
    }

    public void stop() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
