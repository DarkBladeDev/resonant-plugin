package com.resonant.storage;

import org.bukkit.plugin.java.JavaPlugin;

import com.resonant.models.ModerationAction;
import com.resonant.models.ModerationActionType;
import com.resonant.models.ModerationScope;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ModerationRepository {

    private final DataSource dataSource;

    public ModerationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void init(JavaPlugin plugin) {
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS vc_moderation_actions (" +
                    "id TEXT PRIMARY KEY," +
                    "target_uuid TEXT NOT NULL," +
                    "target_name TEXT NOT NULL," +
                    "moderator_uuid TEXT NOT NULL," +
                    "moderator_name TEXT NOT NULL," +
                    "type TEXT NOT NULL," +
                    "reason TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "expires_at INTEGER," +
                    "duration_seconds INTEGER," +
                    "scope TEXT NOT NULL," +
                    "server_id TEXT NOT NULL," +
                    "metadata TEXT," +
                    "appeal_status TEXT" +
                    ")");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS vc_pending_confirmations (" +
                    "id TEXT PRIMARY KEY," +
                    "moderator_uuid TEXT NOT NULL," +
                    "type TEXT NOT NULL," +
                    "payload TEXT NOT NULL," +
                    "expires_at INTEGER NOT NULL" +
                    ")");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS vc_rate_limits (" +
                    "moderator_uuid TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL" +
                    ")");
        } catch (Exception ex) {
            plugin.getLogger().severe("No se pudo inicializar la base de datos de moderación: " + ex.getMessage());
        }
    }

    public void insertAction(ModerationAction action) {
        String sql = "INSERT INTO vc_moderation_actions (id, target_uuid, target_name, moderator_uuid, moderator_name, type, reason, created_at, expires_at, duration_seconds, scope, server_id, metadata, appeal_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, action.id());
            stmt.setString(2, action.targetUuid().toString());
            stmt.setString(3, action.targetName());
            stmt.setString(4, action.moderatorUuid().toString());
            stmt.setString(5, action.moderatorName());
            stmt.setString(6, action.type().name());
            stmt.setString(7, action.reason());
            stmt.setLong(8, action.createdAt());
            if (action.expiresAt() != null) {
                stmt.setLong(9, action.expiresAt());
            } else {
                stmt.setNull(9, java.sql.Types.BIGINT);
            }
            if (action.durationSeconds() != null) {
                stmt.setLong(10, action.durationSeconds());
            } else {
                stmt.setNull(10, java.sql.Types.BIGINT);
            }
            stmt.setString(11, action.scope().name());
            stmt.setString(12, action.serverId());
            stmt.setString(13, action.metadata());
            stmt.setString(14, action.appealStatus());
            stmt.executeUpdate();
        } catch (Exception ex) {
            throw new RuntimeException("No se pudo guardar la acción de moderación", ex);
        }
    }

    public List<ModerationAction> findHistory(UUID targetUuid, String type, Long since, Long until) {
        StringBuilder sql = new StringBuilder("SELECT * FROM vc_moderation_actions WHERE target_uuid = ?");
        List<Object> params = new ArrayList<>();
        params.add(targetUuid.toString());
        if (type != null && !type.isEmpty()) {
            sql.append(" AND type = ?");
            params.add(type);
        }
        if (since != null) {
            sql.append(" AND created_at >= ?");
            params.add(since);
        }
        if (until != null) {
            sql.append(" AND created_at <= ?");
            params.add(until);
        }
        sql.append(" ORDER BY created_at DESC");
        try (Connection connection = dataSource.getConnection(); PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            List<ModerationAction> actions = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    actions.add(mapAction(rs));
                }
            }
            return actions;
        } catch (Exception ex) {
            throw new RuntimeException("No se pudo cargar el historial", ex);
        }
    }

    private ModerationAction mapAction(ResultSet rs) throws Exception {
        return new ModerationAction(
                rs.getString("id"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                UUID.fromString(rs.getString("moderator_uuid")),
                rs.getString("moderator_name"),
                ModerationActionType.valueOf(rs.getString("type")),
                rs.getString("reason"),
                rs.getLong("created_at"),
                rs.getObject("expires_at") != null ? rs.getLong("expires_at") : null,
                rs.getObject("duration_seconds") != null ? rs.getLong("duration_seconds") : null,
                ModerationScope.valueOf(rs.getString("scope")),
                rs.getString("server_id"),
                rs.getString("metadata"),
                rs.getString("appeal_status")
        );
    }
}
