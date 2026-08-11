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
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS owned_tools (
                      player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
                      tool_id TEXT NOT NULL,
                      acquired_at TEXT NOT NULL,
                      PRIMARY KEY (player_uuid, tool_id)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS item_grants (
                      grant_id TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      item BLOB NOT NULL,
                      delivered INTEGER NOT NULL DEFAULT 0 CHECK (delivered IN (0, 1)),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sale_intents (
                      intent_id TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      item BLOB NOT NULL,
                      item_id TEXT NOT NULL,
                      quantity INTEGER NOT NULL CHECK (quantity > 0),
                      unit_price INTEGER NOT NULL CHECK (unit_price >= 0),
                      total_price INTEGER NOT NULL CHECK (total_price >= 0),
                      state TEXT NOT NULL CHECK (state IN ('PREPARED', 'REMOVING', 'PAID', 'CANCELLED')),
                      balance_after INTEGER,
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS one_active_sale_per_player
                    ON sale_intents(player_uuid) WHERE state IN ('PREPARED', 'REMOVING')""");
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
        try (PreparedStatement query = database.prepareStatement("SELECT tool_id FROM owned_tools WHERE player_uuid=?")) {
            query.setString(1, id.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) profile.addTool(rows.getString(1));
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

    public CompletableFuture<Long> purchaseTool(PlayerProfile profile, long price, String toolId, byte[] physicalItem, String requestId) {
        if (price <= 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid price"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                try (PreparedStatement existing = database.prepareStatement("SELECT player_uuid, balance_after FROM economy_transactions WHERE idempotency_key=?")) {
                    existing.setString(1, "purchase:" + requestId);
                    try (ResultSet row = existing.executeQuery()) {
                        if (row.next()) {
                            if (!profile.playerId().toString().equals(row.getString(1))) throw new SQLException("Purchase request belongs to another player");
                            return row.getLong(2);
                        }
                    }
                }
                long expectedBalance = profile.money();
                long balance = Math.subtractExact(expectedBalance, price);
                if (balance < 0) throw new IllegalArgumentException("Insufficient balance");
                transaction(() -> {
                    try (PreparedStatement ownership = database.prepareStatement("INSERT OR IGNORE INTO owned_tools VALUES (?, ?, ?)")) {
                        ownership.setString(1, profile.playerId().toString());
                        ownership.setString(2, toolId);
                        ownership.setString(3, Instant.now().toString());
                        if (ownership.executeUpdate() != 1) throw new SQLException("Tool already owned: " + toolId);
                    }
                    try (PreparedStatement update = database.prepareStatement("UPDATE players SET money=?, updated_at=? WHERE uuid=? AND money=?")) {
                        update.setLong(1, balance);
                        update.setString(2, Instant.now().toString());
                        update.setString(3, profile.playerId().toString());
                        update.setLong(4, expectedBalance);
                        if (update.executeUpdate() != 1) throw new SQLException("Wallet changed concurrently");
                    }
                    try (PreparedStatement log = database.prepareStatement("INSERT INTO economy_transactions VALUES (?, ?, ?, 'TOOL_PURCHASE', ?, 1, ?, ?, ?, ?)")) {
                        log.setString(1, UUID.randomUUID().toString());
                        log.setString(2, "purchase:" + requestId);
                        log.setString(3, profile.playerId().toString());
                        log.setString(4, "tool:" + toolId);
                        log.setLong(5, price);
                        log.setLong(6, price);
                        log.setLong(7, balance);
                        log.setString(8, Instant.now().toString());
                        log.executeUpdate();
                    }
                    if (physicalItem != null) try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
                        grant.setString(1, requestId);
                        grant.setString(2, profile.playerId().toString());
                        grant.setBytes(3, physicalItem);
                        grant.setString(4, Instant.now().toString());
                        grant.executeUpdate();
                    }
                });
                return balance;
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> migrateTools(PlayerProfile profile, Set<String> toolIds, Set<String> absorbedGrantIds) {
        return CompletableFuture.runAsync(() -> {
            try {
                transaction(() -> {
                    try (PreparedStatement ownership = database.prepareStatement("INSERT OR IGNORE INTO owned_tools VALUES (?, ?, ?)")) {
                        for (String toolId : toolIds) {
                            ownership.setString(1, profile.playerId().toString());
                            ownership.setString(2, toolId);
                            ownership.setString(3, Instant.now().toString());
                            ownership.addBatch();
                        }
                        ownership.executeBatch();
                    }
                    try (PreparedStatement grant = database.prepareStatement("UPDATE item_grants SET delivered=1 WHERE grant_id=? AND player_uuid=?")) {
                        for (String grantId : absorbedGrantIds) {
                            grant.setString(1, grantId);
                            grant.setString(2, profile.playerId().toString());
                            grant.addBatch();
                        }
                        grant.executeBatch();
                    }
                });
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<List<ItemGrant>> pendingGrants(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT grant_id, item FROM item_grants WHERE player_uuid=? AND delivered=0 ORDER BY created_at")) {
                query.setString(1, playerId.toString());
                try (ResultSet rows = query.executeQuery()) {
                    java.util.ArrayList<ItemGrant> result = new java.util.ArrayList<>();
                    while (rows.next()) result.add(new ItemGrant(rows.getString(1), rows.getBytes(2)));
                    return result;
                }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> acknowledgeGrant(String grantId) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE item_grants SET delivered=1 WHERE grant_id=?")) {
                update.setString(1, grantId);
                update.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> beginSale(PlayerProfile profile, String intentId, byte[] item, String itemId, int quantity, long unitPrice) {
        long total;
        try { total = Math.multiplyExact(quantity, unitPrice); }
        catch (ArithmeticException error) { return CompletableFuture.failedFuture(error); }
        if (quantity <= 0 || unitPrice <= 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid sale"));
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO sale_intents VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED', NULL, ?)")) {
                insert.setString(1, intentId);
                insert.setString(2, profile.playerId().toString());
                insert.setBytes(3, item);
                insert.setString(4, itemId);
                insert.setInt(5, quantity);
                insert.setLong(6, unitPrice);
                insert.setLong(7, total);
                insert.setString(8, Instant.now().toString());
                insert.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> markSaleRemoving(String intentId) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE sale_intents SET state='REMOVING' WHERE intent_id=? AND state='PREPARED'")) {
                update.setString(1, intentId);
                if (update.executeUpdate() != 1) throw new SQLException("Sale is not prepared: " + intentId);
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Long> completeSale(PlayerProfile profile, String intentId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long[] result = new long[1];
                transaction(() -> {
                    String state;
                    long total;
                    Long paidBalance;
                    try (PreparedStatement query = database.prepareStatement("SELECT player_uuid, state, total_price, balance_after FROM sale_intents WHERE intent_id=?")) {
                        query.setString(1, intentId);
                        try (ResultSet row = query.executeQuery()) {
                            if (!row.next() || !profile.playerId().toString().equals(row.getString(1))) throw new SQLException("Sale not found: " + intentId);
                            state = row.getString(2);
                            total = row.getLong(3);
                            paidBalance = row.getObject(4) == null ? null : row.getLong(4);
                        }
                    }
                    if (state.equals("PAID")) { result[0] = paidBalance; return; }
                    if (!state.equals("REMOVING")) throw new SQLException("Sale is not removable: " + state);
                    long current;
                    try (PreparedStatement query = database.prepareStatement("SELECT money FROM players WHERE uuid=?")) {
                        query.setString(1, profile.playerId().toString());
                        try (ResultSet row = query.executeQuery()) {
                            if (!row.next()) throw new SQLException("Player row missing: " + profile.playerId());
                            current = row.getLong(1);
                        }
                    }
                    long balance = Math.addExact(current, total);
                    try (PreparedStatement update = database.prepareStatement("UPDATE players SET money=?, updated_at=? WHERE uuid=? AND money=?")) {
                        update.setLong(1, balance);
                        update.setString(2, Instant.now().toString());
                        update.setString(3, profile.playerId().toString());
                        update.setLong(4, current);
                        if (update.executeUpdate() != 1) throw new SQLException("Wallet changed concurrently");
                    }
                    try (PreparedStatement intent = database.prepareStatement("UPDATE sale_intents SET state='PAID', balance_after=? WHERE intent_id=? AND state='REMOVING'")) {
                        intent.setLong(1, balance);
                        intent.setString(2, intentId);
                        if (intent.executeUpdate() != 1) throw new SQLException("Sale changed concurrently");
                    }
                    try (PreparedStatement log = database.prepareStatement("INSERT INTO economy_transactions SELECT ?, ?, player_uuid, 'NPC_SALE', item_id, quantity, unit_price, total_price, ?, ? FROM sale_intents WHERE intent_id=?")) {
                        log.setString(1, UUID.randomUUID().toString());
                        log.setString(2, "sale:" + intentId);
                        log.setLong(3, balance);
                        log.setString(4, Instant.now().toString());
                        log.setString(5, intentId);
                        if (log.executeUpdate() != 1) throw new SQLException("Sale log failed");
                    }
                    result[0] = balance;
                });
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<SaleIntent>> pendingSale(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT intent_id, state FROM sale_intents WHERE player_uuid=? AND state IN ('PREPARED','REMOVING') ORDER BY created_at LIMIT 1")) {
                query.setString(1, playerId.toString());
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? Optional.of(new SaleIntent(row.getString(1), row.getString(2))) : Optional.empty();
                }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> cancelSale(String intentId) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE sale_intents SET state='CANCELLED' WHERE intent_id=? AND state IN ('PREPARED','REMOVING')")) {
                update.setString(1, intentId);
                update.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
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

    public record ItemGrant(String id, byte[] item) {}
    public record SaleIntent(String id, String state) {}
}
