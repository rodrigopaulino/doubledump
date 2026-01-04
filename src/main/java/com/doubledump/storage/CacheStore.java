package com.doubledump.storage;

import com.doubledump.config.DoubleDumpEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public final class CacheStore implements AutoCloseable {
    private final Connection connection;
    private final Object lock = new Object();
    private final DoubleDumpEnvironment env;

    public CacheStore(Path dbPath, DoubleDumpEnvironment env) throws SQLException {
        this.env = env;
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ignored) {
            // directory may already exist
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        init();
    }

    private void init() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
            stmt.execute("CREATE TABLE IF NOT EXISTS filehash (path TEXT PRIMARY KEY, mtime INTEGER, size INTEGER, hash TEXT, updated_at INTEGER)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mtime_size ON filehash(mtime, size)");
        }
    }

    public Optional<String> lookup(String path, long mtime, long size) {
        String sql = "SELECT hash FROM filehash WHERE path = ? AND mtime = ? AND size = ? LIMIT 1";
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, path);
                ps.setLong(2, mtime);
                ps.setLong(3, size);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.ofNullable(rs.getString(1));
                    }
                    return Optional.empty();
                }
            } catch (SQLException ex) {
                if (env.isDebugEnabled()) {
                    System.err.println("Cache lookup failed: " + ex.getMessage());
                }
                return Optional.empty();
            }
        }
    }

    public void store(String path, long mtime, long size, String hash) {
        String sql = "INSERT OR REPLACE INTO filehash(path, mtime, size, hash, updated_at) VALUES(?,?,?,?, strftime('%s','now'))";
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, path);
                ps.setLong(2, mtime);
                ps.setLong(3, size);
                ps.setString(4, hash);
                ps.executeUpdate();
            } catch (SQLException ex) {
                if (env.isDebugEnabled()) {
                    System.err.println("Cache store failed: " + ex.getMessage());
                }
            }
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
