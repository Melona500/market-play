package kr.hyuni.marketplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProfileStore implements AutoCloseable {
    private final Connection database;
    private final long startingMoney;
    private final double maximumVitality;
    private final Map<UUID, PlayerProfile> loaded = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MarketPlay-SQLite");
        thread.setDaemon(true);
        return thread;
    });

    public ProfileStore(Path file, long startingMoney, double maximumVitality) throws Exception {
        Files.createDirectories(file.getParent());
        this.startingMoney = startingMoney;
        this.maximumVitality = maximumVitality;
        database = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = database.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                      uuid TEXT PRIMARY KEY,
                      money INTEGER NOT NULL CHECK (money >= 0),
                      inner_power INTEGER NOT NULL CHECK (inner_power >= 0),
                      vitality REAL NOT NULL CHECK (vitality >= 0),
                      updated_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS masteries (
                      player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
                      skill TEXT NOT NULL,
                      xp INTEGER NOT NULL CHECK (xp >= 0),
                      PRIMARY KEY (player_uuid, skill)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_transactions (
                      transaction_id TEXT PRIMARY KEY,
                      idempotency_key TEXT UNIQUE NOT NULL,
                      player_uuid TEXT NOT NULL,
                      type TEXT NOT NULL,
                      item_id TEXT NOT NULL,
                      quantity INTEGER NOT NULL CHECK (quantity > 0),
                      unit_price INTEGER NOT NULL CHECK (unit_price >= 0),
                      total_price INTEGER NOT NULL CHECK (total_price >= 0),
                      balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
                      timestamp TEXT NOT NULL
                    )""");
        }
    }

    public CompletableFuture<PlayerProfile> load(UUID id) {
        return CompletableFuture.supplyAsync(() -> {
            try { return loadSync(id); }
            catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    private PlayerProfile loadSync(UUID id) throws SQLException {
        PlayerProfile existing = loaded.get(id);
        if (existing != null) return existing;
        try (PreparedStatement create = database.prepareStatement("INSERT OR IGNORE INTO players VALUES (?, ?, 0, ?, ?)")) {
            create.setString(1, id.toString());
            create.setLong(2, startingMoney);
            create.setDouble(3, maximumVitality);
            create.setString(4, Instant.now().toString());
            create.executeUpdate();
        }
        PlayerProfile profile;
        try (PreparedStatement query = database.prepareStatement("SELECT money, inner_power, vitality FROM players WHERE uuid=?")) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Player row missing: " + id);
                profile = new PlayerProfile(id, row.getLong(1), row.getLong(2), row.getDouble(3));
            }
        }
        try (PreparedStatement query = database.prepareStatement("SELECT skill, xp FROM masteries WHERE player_uuid=?")) {
            query.setString(1, id.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) profile.setExperience(Skill.valueOf(rows.getString(1)), rows.getLong(2));
            }
        }
        loaded.put(id, profile);
        return profile;
    }

    public PlayerProfile get(UUID id) { return loaded.get(id); }

    public CompletableFuture<Void> save(PlayerProfile profile) {
        PlayerProfile snapshot = profile.copy();
        return CompletableFuture.runAsync(() -> {
            try { saveSync(snapshot); }
            catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    private void saveSync(PlayerProfile profile) throws SQLException {
        transaction(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE players SET inner_power=?, vitality=?, updated_at=? WHERE uuid=?")) {
                update.setLong(1, profile.innerPower());
                update.setDouble(2, profile.vitality());
                update.setString(3, Instant.now().toString());
                update.setString(4, profile.playerId().toString());
                if (update.executeUpdate() != 1) throw new SQLException("Player row missing: " + profile.playerId());
            }
            try (PreparedStatement upsert = database.prepareStatement("INSERT INTO masteries VALUES (?, ?, ?) ON CONFLICT(player_uuid, skill) DO UPDATE SET xp=excluded.xp")) {
                for (var entry : profile.experience().entrySet()) {
                    upsert.setString(1, profile.playerId().toString());
                    upsert.setString(2, entry.getKey().name());
                    upsert.setLong(3, entry.getValue());
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        });
    }

    public CompletableFuture<Long> changeMoney(PlayerProfile profile, long value, boolean set, String reason, String idempotencyKey) {
        long expectedBalance = profile.money();
        long balance;
        try { balance = set ? value : Math.addExact(expectedBalance, value); }
        catch (ArithmeticException error) { return CompletableFuture.failedFuture(error); }
        if (balance < 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Balance cannot be negative"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                try (PreparedStatement existing = database.prepareStatement("SELECT player_uuid, balance_after FROM economy_transactions WHERE idempotency_key=?")) {
                    existing.setString(1, idempotencyKey);
                    try (ResultSet row = existing.executeQuery()) {
                        if (row.next()) {
                            if (!profile.playerId().toString().equals(row.getString(1))) throw new SQLException("Idempotency key belongs to another player");
                            return row.getLong(2);
                        }
                    }
                }
                String transactionId = UUID.randomUUID().toString();
                transaction(() -> {
                    try (PreparedStatement update = database.prepareStatement("UPDATE players SET money=?, updated_at=? WHERE uuid=? AND money=?")) {
                        update.setLong(1, balance);
                        update.setString(2, Instant.now().toString());
                        update.setString(3, profile.playerId().toString());
                        update.setLong(4, expectedBalance);
                        if (update.executeUpdate() != 1) throw new SQLException("Wallet changed concurrently");
                    }
                    try (PreparedStatement log = database.prepareStatement("INSERT INTO economy_transactions VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)")) {
                        long delta = Math.subtractExact(balance, expectedBalance);
                        long absolute = Math.abs(delta);
                        log.setString(1, transactionId);
                        log.setString(2, idempotencyKey);
                        log.setString(3, profile.playerId().toString());
                        log.setString(4, set ? "ADMIN_SET" : "ADMIN_ADD");
                        log.setString(5, reason);
                        log.setLong(6, absolute);
                        log.setLong(7, absolute);
                        log.setLong(8, balance);
                        log.setString(9, Instant.now().toString());
                        log.executeUpdate();
                    }
                });
                return balance;
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> unload(UUID id) {
        PlayerProfile profile = loaded.remove(id);
        return profile == null ? CompletableFuture.completedFuture(null) : save(profile);
    }

    @Override public void close() throws Exception {
        CompletableFuture.runAsync(() -> {
            try {
                for (PlayerProfile profile : loaded.values()) saveSync(profile.copy());
                database.close();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer).join();
        writer.shutdown();
    }

    private void transaction(SqlWork work) throws SQLException {
        boolean automatic = database.getAutoCommit();
        database.setAutoCommit(false);
        try {
            work.run();
            database.commit();
        } catch (Exception error) {
            database.rollback();
            if (error instanceof SQLException sql) throw sql;
            throw new SQLException(error);
        } finally {
            database.setAutoCommit(automatic);
        }
    }

    @FunctionalInterface private interface SqlWork { void run() throws Exception; }
}
