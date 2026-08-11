package kr.hyuni.marketplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.HashMap;
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_days (
                      day TEXT NOT NULL,
                      item_id TEXT NOT NULL,
                      unit_price INTEGER NOT NULL CHECK (unit_price > 0),
                      change_percent INTEGER NOT NULL,
                      sold_recent INTEGER NOT NULL CHECK (sold_recent >= 0),
                      royal_target INTEGER NOT NULL CHECK (royal_target >= 0),
                      created_at TEXT NOT NULL,
                      PRIMARY KEY (day, item_id)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bulletin_posts (
                      post_id TEXT PRIMARY KEY,
                      author_uuid TEXT NOT NULL,
                      author_name TEXT NOT NULL,
                      body TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      expires_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS bulletin_posts_expiry ON bulletin_posts(expires_at, created_at)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS royal_gift_claims (
                      token TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      reputation_after INTEGER NOT NULL CHECK (reputation_after >= 0),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exploration_nodes (
                      node_id TEXT PRIMARY KEY,
                      ready_at INTEGER NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exploration_intents (
                      intent_id TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      kind TEXT NOT NULL CHECK (kind IN ('CRAFT','ROYAL')),
                      input_a BLOB NOT NULL,
                      input_b BLOB,
                      output BLOB,
                      token TEXT,
                      grant_id TEXT,
                      state TEXT NOT NULL CHECK (state IN ('PREPARED','REMOVING','COMPLETED','CANCELLED')),
                      result_value INTEGER,
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_exploration_intent ON exploration_intents(player_uuid) WHERE state IN ('PREPARED','REMOVING')");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exploration_encounters (
                      encounter_id TEXT PRIMARY KEY,
                      state TEXT NOT NULL CHECK (state IN ('ACTIVE','DEFEATED')),
                      hp REAL NOT NULL CHECK (hp >= 0),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_exploration_encounter ON exploration_encounters(state) WHERE state='ACTIVE'");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exploration_rewards (
                      encounter_id TEXT NOT NULL,
                      player_uuid TEXT NOT NULL,
                      PRIMARY KEY (encounter_id, player_uuid)
                    )""");
            ensurePlayerColumn(statement, "deep_omen", "INTEGER NOT NULL DEFAULT 0 CHECK (deep_omen BETWEEN 0 AND 100)");
            ensurePlayerColumn(statement, "royal_reputation", "INTEGER NOT NULL DEFAULT 0 CHECK (royal_reputation >= 0)");
            ensurePlayerColumn(statement, "knight_state", "TEXT NOT NULL DEFAULT 'NONE' CHECK (knight_state IN ('NONE','ARCHERY','DUEL','APPRENTICE'))");
        }
    }

    private void ensurePlayerColumn(java.sql.Statement statement, String name, String definition) throws SQLException {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(players)")) {
            while (columns.next()) if (name.equals(columns.getString("name"))) return;
        }
        statement.executeUpdate("ALTER TABLE players ADD COLUMN " + name + " " + definition);
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
        try (PreparedStatement create = database.prepareStatement("INSERT OR IGNORE INTO players(uuid, money, inner_power, vitality, updated_at) VALUES (?, ?, 0, ?, ?)")) {
            create.setString(1, id.toString());
            create.setLong(2, startingMoney);
            create.setDouble(3, maximumVitality);
            create.setString(4, Instant.now().toString());
            create.executeUpdate();
        }
        PlayerProfile profile;
        try (PreparedStatement query = database.prepareStatement("SELECT money, inner_power, vitality, deep_omen, royal_reputation, knight_state FROM players WHERE uuid=?")) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Player row missing: " + id);
                profile = new PlayerProfile(id, row.getLong(1), row.getLong(2), row.getDouble(3));
                profile.setDeepOmen(row.getInt(4));
                profile.addRoyalReputation(row.getInt(5));
                profile.setKnightState(row.getString(6));
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
        return CompletableFuture.runAsync(() -> {
            try { saveSync(profile.copy()); }
            catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    private void saveSync(PlayerProfile profile) throws SQLException {
        transaction(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE players SET inner_power=?, vitality=?, deep_omen=?, royal_reputation=?, knight_state=?, updated_at=? WHERE uuid=?")) {
                update.setLong(1, profile.innerPower());
                update.setDouble(2, profile.vitality());
                update.setInt(3, profile.deepOmen());
                update.setInt(4, profile.royalReputation());
                update.setString(5, profile.knightState());
                update.setString(6, Instant.now().toString());
                update.setString(7, profile.playerId().toString());
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
                            long persisted = row.getLong(2);
                            profile.setMoney(persisted);
                            return persisted;
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
                            long persisted = row.getLong(2);
                            profile.setMoney(persisted);
                            return persisted;
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
                profile.setMoney(balance);
                profile.setMoney(balance);
                return balance;
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Long> purchaseItem(PlayerProfile profile, long price, String itemId, byte[] item, String requestId) {
        if (price <= 0 || item == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid purchase"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                try (PreparedStatement existing = database.prepareStatement("SELECT player_uuid, balance_after FROM economy_transactions WHERE idempotency_key=?")) {
                    existing.setString(1, "item-purchase:" + requestId);
                    try (ResultSet row = existing.executeQuery()) {
                        if (row.next()) {
                            if (!profile.playerId().toString().equals(row.getString(1))) throw new SQLException("Purchase request belongs to another player");
                            long persisted = row.getLong(2);
                            profile.setMoney(persisted);
                            return persisted;
                        }
                    }
                }
                long expectedBalance = profile.money();
                long balance = Math.subtractExact(expectedBalance, price);
                if (balance < 0) throw new IllegalArgumentException("Insufficient balance");
                transaction(() -> {
                    try (PreparedStatement update = database.prepareStatement("UPDATE players SET money=?, updated_at=? WHERE uuid=? AND money=?")) {
                        update.setLong(1, balance);
                        update.setString(2, Instant.now().toString());
                        update.setString(3, profile.playerId().toString());
                        update.setLong(4, expectedBalance);
                        if (update.executeUpdate() != 1) throw new SQLException("Wallet changed concurrently");
                    }
                    try (PreparedStatement log = database.prepareStatement("INSERT INTO economy_transactions VALUES (?, ?, ?, 'ITEM_PURCHASE', ?, 1, ?, ?, ?, ?)")) {
                        log.setString(1, UUID.randomUUID().toString());
                        log.setString(2, "item-purchase:" + requestId);
                        log.setString(3, profile.playerId().toString());
                        log.setString(4, itemId);
                        log.setLong(5, price);
                        log.setLong(6, price);
                        log.setLong(7, balance);
                        log.setString(8, Instant.now().toString());
                        log.executeUpdate();
                    }
                    try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
                        grant.setString(1, requestId);
                        grant.setString(2, profile.playerId().toString());
                        grant.setBytes(3, item);
                        grant.setString(4, Instant.now().toString());
                        grant.executeUpdate();
                    }
                });
                profile.setMoney(balance);
                return balance;
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<NodeHarvest> harvestNode(PlayerProfile profile, String nodeId, long now, long readyAt,
                                                       double vitalityCost, Skill skill, long experience, byte[] item, String grantId) {
        if (vitalityCost < 0 || profile.vitality() < vitalityCost || readyAt <= now || experience <= 0)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid node harvest"));
        double expectedVitality = profile.vitality(), remainingVitality = expectedVitality - vitalityCost;
        long expectedPower = profile.innerPower(), newPower = Math.addExact(expectedPower, 1);
        return CompletableFuture.supplyAsync(() -> {
            try {
                transaction(() -> {
                    try (PreparedStatement claim = database.prepareStatement("""
                            INSERT INTO exploration_nodes(node_id, ready_at) VALUES (?, ?)
                            ON CONFLICT(node_id) DO UPDATE SET ready_at=excluded.ready_at
                            WHERE exploration_nodes.ready_at <= ?""")) {
                        claim.setString(1, nodeId); claim.setLong(2, readyAt); claim.setLong(3, now);
                        if (claim.executeUpdate() != 1) throw new SQLException("Node is cooling down");
                    }
                    try (PreparedStatement update = database.prepareStatement("UPDATE players SET vitality=?, inner_power=?, updated_at=? WHERE uuid=? AND vitality=? AND inner_power=?")) {
                        update.setDouble(1, remainingVitality); update.setLong(2, newPower); update.setString(3, Instant.now().toString());
                        update.setString(4, profile.playerId().toString()); update.setDouble(5, expectedVitality); update.setLong(6, expectedPower);
                        if (update.executeUpdate() != 1) throw new SQLException("Player progress changed concurrently");
                    }
                    try (PreparedStatement mastery = database.prepareStatement("""
                            INSERT INTO masteries(player_uuid, skill, xp) VALUES (?, ?, ?)
                            ON CONFLICT(player_uuid, skill) DO UPDATE SET xp=xp+excluded.xp""")) {
                        mastery.setString(1, profile.playerId().toString()); mastery.setString(2, skill.name()); mastery.setLong(3, experience); mastery.executeUpdate();
                    }
                    try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
                        grant.setString(1, grantId); grant.setString(2, profile.playerId().toString()); grant.setBytes(3, item); grant.setString(4, Instant.now().toString()); grant.executeUpdate();
                    }
                });
                synchronized (profile) { profile.spendVitality(vitalityCost); profile.addExperience(skill, experience); profile.addInnerPower(1); }
                return new NodeHarvest(remainingVitality, newPower);
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> prepareExplorationIntent(ExplorationIntent intent) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO exploration_intents VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', NULL, ?)")) {
                insert.setString(1, intent.id()); insert.setString(2, intent.player().toString()); insert.setString(3, intent.kind());
                insert.setBytes(4, intent.inputA()); insert.setBytes(5, intent.inputB()); insert.setBytes(6, intent.output());
                insert.setString(7, intent.token()); insert.setString(8, intent.grantId()); insert.setString(9, Instant.now().toString()); insert.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> markExplorationRemoving(String id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE exploration_intents SET state='REMOVING' WHERE intent_id=? AND state='PREPARED'")) {
                update.setString(1, id); if (update.executeUpdate() != 1) throw new SQLException("Exploration intent is not prepared");
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<IntentResult> completeExplorationIntent(PlayerProfile profile, String id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ExplorationIntent intent = explorationIntent(id).orElseThrow(() -> new SQLException("Exploration intent missing"));
                if (intent.state().equals("COMPLETED")) return new IntentResult(intent.kind(), intent.resultValue());
                if (!intent.state().equals("REMOVING")) throw new SQLException("Exploration intent is not removing");
                int[] result = {profile.royalReputation()};
                transaction(() -> {
                    if (intent.kind().equals("CRAFT")) {
                        try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
                            grant.setString(1, intent.grantId()); grant.setString(2, profile.playerId().toString()); grant.setBytes(3, intent.output()); grant.setString(4, Instant.now().toString()); grant.executeUpdate();
                        }
                        try (PreparedStatement mastery = database.prepareStatement("""
                                INSERT INTO masteries(player_uuid, skill, xp) VALUES (?, 'JEWELCRAFTING', 5)
                                ON CONFLICT(player_uuid, skill) DO UPDATE SET xp=xp+5""")) {
                            mastery.setString(1, profile.playerId().toString()); mastery.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement existing = database.prepareStatement("SELECT player_uuid, reputation_after FROM royal_gift_claims WHERE token=?")) {
                            existing.setString(1, intent.token());
                            try (ResultSet row = existing.executeQuery()) {
                                if (row.next()) {
                                    if (!profile.playerId().toString().equals(row.getString(1))) throw new SQLException("Royal token belongs to another player");
                                    result[0] = row.getInt(2);
                                } else {
                                    result[0] = Math.addExact(profile.royalReputation(), 10);
                                    try (PreparedStatement update = database.prepareStatement("UPDATE players SET royal_reputation=?, updated_at=? WHERE uuid=? AND royal_reputation=?")) {
                                        update.setInt(1, result[0]); update.setString(2, Instant.now().toString()); update.setString(3, profile.playerId().toString()); update.setInt(4, profile.royalReputation());
                                        if (update.executeUpdate() != 1) throw new SQLException("Royal reputation changed concurrently");
                                    }
                                    try (PreparedStatement claim = database.prepareStatement("INSERT INTO royal_gift_claims VALUES (?, ?, ?, ?)")) {
                                        claim.setString(1, intent.token()); claim.setString(2, profile.playerId().toString()); claim.setInt(3, result[0]); claim.setString(4, Instant.now().toString()); claim.executeUpdate();
                                    }
                                }
                            }
                        }
                    }
                    try (PreparedStatement update = database.prepareStatement("UPDATE exploration_intents SET state='COMPLETED', result_value=? WHERE intent_id=? AND state='REMOVING'")) {
                        update.setInt(1, result[0]); update.setString(2, id); if (update.executeUpdate() != 1) throw new SQLException("Exploration intent completion race");
                    }
                });
                synchronized (profile) {
                    if (intent.kind().equals("CRAFT")) profile.addExperience(Skill.JEWELCRAFTING, 5);
                    else profile.setRoyalReputation(result[0]);
                }
                return new IntentResult(intent.kind(), result[0]);
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> cancelExplorationIntent(String id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE exploration_intents SET state='CANCELLED' WHERE intent_id=? AND state IN ('PREPARED','REMOVING')")) {
                update.setString(1, id); update.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<ExplorationIntent>> activeExplorationIntent(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT * FROM exploration_intents WHERE player_uuid=? AND state IN ('PREPARED','REMOVING')")) {
                query.setString(1, player.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(readExplorationIntent(row)) : Optional.empty(); }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    private Optional<ExplorationIntent> explorationIntent(String id) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM exploration_intents WHERE intent_id=?")) {
            query.setString(1, id); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(readExplorationIntent(row)) : Optional.empty(); }
        }
    }

    private ExplorationIntent readExplorationIntent(ResultSet row) throws SQLException {
        return new ExplorationIntent(row.getString("intent_id"), UUID.fromString(row.getString("player_uuid")), row.getString("kind"), row.getBytes("input_a"), row.getBytes("input_b"), row.getBytes("output"), row.getString("token"), row.getString("grant_id"), row.getString("state"), row.getInt("result_value"));
    }

    public CompletableFuture<Encounter> startEncounter(PlayerProfile trigger) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<Encounter> active = activeEncounterSync();
                if (active.isPresent()) return active.get();
                String id = UUID.randomUUID().toString();
                int expectedOmen = trigger.deepOmen();
                transaction(() -> {
                    try (PreparedStatement insert = database.prepareStatement("INSERT INTO exploration_encounters VALUES (?, 'ACTIVE', 200, ?)")) {
                        insert.setString(1, id); insert.setString(2, Instant.now().toString()); insert.executeUpdate();
                    }
                    try (PreparedStatement update = database.prepareStatement("UPDATE players SET deep_omen=0, updated_at=? WHERE uuid=? AND deep_omen=?")) {
                        update.setString(1, Instant.now().toString()); update.setString(2, trigger.playerId().toString()); update.setInt(3, expectedOmen);
                        if (update.executeUpdate() != 1) throw new SQLException("Deep omen changed concurrently");
                    }
                });
                trigger.setDeepOmen(0);
                return new Encounter(id, 200);
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<Encounter>> activeEncounter() {
        return CompletableFuture.supplyAsync(() -> {
            try { return activeEncounterSync(); }
            catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    private Optional<Encounter> activeEncounterSync() throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT encounter_id, hp FROM exploration_encounters WHERE state='ACTIVE' LIMIT 1"); ResultSet row = query.executeQuery()) {
            return row.next() ? Optional.of(new Encounter(row.getString(1), row.getDouble(2))) : Optional.empty();
        }
    }

    public CompletableFuture<Void> saveEncounterHp(String id, double hp) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE exploration_encounters SET hp=? WHERE encounter_id=? AND state='ACTIVE'")) {
                update.setDouble(1, Math.max(0, hp)); update.setString(2, id); update.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Map<UUID, Integer>> defeatEncounter(String id, List<BossReward> rewards) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<UUID, Integer> reputations = new HashMap<>();
                transaction(() -> {
                    try (PreparedStatement defeated = database.prepareStatement("UPDATE exploration_encounters SET state='DEFEATED', hp=0 WHERE encounter_id=? AND state='ACTIVE'")) {
                        defeated.setString(1, id); if (defeated.executeUpdate() != 1) throw new SQLException("Encounter is not active");
                    }
                    for (BossReward reward : rewards) {
                        try (PreparedStatement unique = database.prepareStatement("INSERT OR IGNORE INTO exploration_rewards VALUES (?, ?)")) {
                            unique.setString(1, id); unique.setString(2, reward.player().toString());
                            if (unique.executeUpdate() != 1) continue;
                        }
                        try (PreparedStatement update = database.prepareStatement("UPDATE players SET royal_reputation=royal_reputation+20, updated_at=? WHERE uuid=?")) {
                            update.setString(1, Instant.now().toString()); update.setString(2, reward.player().toString());
                            if (update.executeUpdate() != 1) throw new SQLException("Boss reward player missing");
                        }
                        try (PreparedStatement query = database.prepareStatement("SELECT royal_reputation FROM players WHERE uuid=?")) {
                            query.setString(1, reward.player().toString()); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Boss reward player missing"); reputations.put(reward.player(), row.getInt(1)); }
                        }
                        try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
                            grant.setString(1, reward.grantId()); grant.setString(2, reward.player().toString()); grant.setBytes(3, reward.item()); grant.setString(4, Instant.now().toString()); grant.executeUpdate();
                        }
                    }
                });
                reputations.forEach((player, reputation) -> {
                    PlayerProfile profile = loaded.get(player);
                    if (profile != null) profile.setRoyalReputation(reputation);
                });
                return reputations;
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
                profile.setMoney(result[0]);
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

    public CompletableFuture<MarketDay> marketDay(LocalDate day, Map<String, Long> basePrices, ZoneId zone) {
        Map<String, Long> bases = Map.copyOf(basePrices);
        return CompletableFuture.supplyAsync(() -> {
            try {
                MarketDay existing = loadMarketDay(day);
                if (existing != null) return existing;
                Instant supplyEnd = day.atStartOfDay(zone).toInstant();
                Instant supplyStart = supplyEnd.minus(Duration.ofDays(1));
                Map<String, Long> supplies = new HashMap<>();
                try (PreparedStatement query = database.prepareStatement("SELECT item_id, COALESCE(SUM(quantity), 0) FROM economy_transactions WHERE type='NPC_SALE' AND timestamp>=? AND timestamp<? GROUP BY item_id")) {
                    query.setString(1, supplyStart.toString());
                    query.setString(2, supplyEnd.toString());
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) supplies.put(rows.getString(1), rows.getLong(2));
                    }
                }
                MarketDay created = MarketDay.create(day, bases, supplies);
                transaction(() -> {
                    try (PreparedStatement insert = database.prepareStatement("INSERT INTO market_days VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        for (var entry : created.entries().entrySet()) {
                            insert.setString(1, day.toString());
                            insert.setString(2, entry.getKey());
                            insert.setLong(3, entry.getValue().unitPrice());
                            insert.setInt(4, entry.getValue().changePercent());
                            insert.setLong(5, entry.getValue().soldRecent());
                            insert.setInt(6, entry.getValue().royalTarget());
                            insert.setString(7, Instant.now().toString());
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                });
                return created;
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    private MarketDay loadMarketDay(LocalDate day) throws SQLException {
        Map<String, MarketDay.Entry> entries = new LinkedHashMap<>();
        try (PreparedStatement query = database.prepareStatement("SELECT item_id, unit_price, change_percent, sold_recent, royal_target FROM market_days WHERE day=? ORDER BY item_id")) {
            query.setString(1, day.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) entries.put(rows.getString(1), new MarketDay.Entry(rows.getLong(2), rows.getInt(3), rows.getLong(4), rows.getInt(5)));
            }
        }
        return entries.isEmpty() ? null : new MarketDay(day, entries);
    }

    public CompletableFuture<BulletinPost> postBulletin(UUID author, String name, String body, Instant now, Duration cooldown, Duration lifetime) {
        if (body.isBlank() || body.length() > 60 || body.chars().anyMatch(Character::isISOControl))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid bulletin body"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                String id = UUID.randomUUID().toString();
                transaction(() -> {
                    try (PreparedStatement latest = database.prepareStatement("SELECT created_at FROM bulletin_posts WHERE author_uuid=? ORDER BY created_at DESC LIMIT 1")) {
                        latest.setString(1, author.toString());
                        try (ResultSet row = latest.executeQuery()) {
                            if (row.next() && Instant.parse(row.getString(1)).plus(cooldown).isAfter(now)) throw new SQLException("Bulletin cooldown active");
                        }
                    }
                    try (PreparedStatement insert = database.prepareStatement("INSERT INTO bulletin_posts VALUES (?, ?, ?, ?, ?, ?)")) {
                        insert.setString(1, id);
                        insert.setString(2, author.toString());
                        insert.setString(3, name);
                        insert.setString(4, body);
                        insert.setString(5, now.toString());
                        insert.setString(6, now.plus(lifetime).toString());
                        insert.executeUpdate();
                    }
                });
                return new BulletinPost(id, author, name, body, now, now.plus(lifetime));
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<List<BulletinPost>> bulletins(Instant now, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT post_id, author_uuid, author_name, body, created_at, expires_at FROM bulletin_posts WHERE expires_at>? ORDER BY created_at DESC LIMIT ?")) {
                query.setString(1, now.toString());
                query.setInt(2, Math.max(1, Math.min(10, limit)));
                try (ResultSet rows = query.executeQuery()) {
                    java.util.ArrayList<BulletinPost> result = new java.util.ArrayList<>();
                    while (rows.next()) result.add(new BulletinPost(rows.getString(1), UUID.fromString(rows.getString(2)), rows.getString(3), rows.getString(4), Instant.parse(rows.getString(5)), Instant.parse(rows.getString(6))));
                    return result;
                }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Boolean> deleteBulletin(String idPrefix, UUID requester, boolean admin) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.util.ArrayList<String[]> matches = new java.util.ArrayList<>();
                try (PreparedStatement query = database.prepareStatement("SELECT post_id, author_uuid FROM bulletin_posts WHERE post_id LIKE ? LIMIT 2")) {
                    query.setString(1, idPrefix + "%");
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) matches.add(new String[]{rows.getString(1), rows.getString(2)}); }
                }
                if (matches.size() != 1 || (!admin && !requester.toString().equals(matches.getFirst()[1]))) return false;
                try (PreparedStatement delete = database.prepareStatement("DELETE FROM bulletin_posts WHERE post_id=?")) {
                    delete.setString(1, matches.getFirst()[0]);
                    return delete.executeUpdate() == 1;
                }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> unload(UUID id) {
        PlayerProfile profile = loaded.get(id);
        if (profile == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try { saveSync(profile.copy()); }
            catch (SQLException error) { throw new RuntimeException(error); }
            finally { loaded.remove(id, profile); }
        }, writer);
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
    public record NodeHarvest(double vitality, long innerPower) {}
    public record ExplorationIntent(String id, UUID player, String kind, byte[] inputA, byte[] inputB, byte[] output,
                                    String token, String grantId, String state, int resultValue) {
        public ExplorationIntent(String id, UUID player, String kind, byte[] inputA, byte[] inputB, byte[] output, String token, String grantId) {
            this(id, player, kind, inputA, inputB, output, token, grantId, "PREPARED", 0);
        }
    }
    public record IntentResult(String kind, int value) {}
    public record Encounter(String id, double hp) {}
    public record BossReward(UUID player, String grantId, byte[] item) {}
    public record BulletinPost(String id, UUID author, String authorName, String body, Instant createdAt, Instant expiresAt) {
        public String shortId() { return id.substring(0, 8); }
    }
}
