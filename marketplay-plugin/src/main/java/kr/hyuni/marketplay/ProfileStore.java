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
    private final SocialBalance socialBalance;
    private final Map<UUID, PlayerProfile> loaded = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MarketPlay-SQLite");
        thread.setDaemon(true);
        return thread;
    });

    public ProfileStore(Path file, long startingMoney, double maximumVitality) throws Exception {
        this(file, startingMoney, maximumVitality, new SocialBalance(64, 32, 2000, 100, 40));
    }

    public ProfileStore(Path file, long startingMoney, double maximumVitality, SocialBalance socialBalance) throws Exception {
        Files.createDirectories(file.getParent());
        this.startingMoney = startingMoney;
        this.maximumVitality = maximumVitality;
        this.socialBalance = socialBalance;
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
                    CREATE TABLE IF NOT EXISTS social_item_intents (
                      intent_id TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      kind TEXT NOT NULL CHECK (kind IN ('EXCHANGE','GUILD','RESTAURANT','PROJECT')),
                      target_id TEXT NOT NULL,
                      item BLOB NOT NULL,
                      item_id TEXT NOT NULL,
                      quantity INTEGER NOT NULL CHECK (quantity > 0),
                      unit_price INTEGER NOT NULL DEFAULT 0 CHECK (unit_price >= 0),
                      quality INTEGER NOT NULL DEFAULT 1 CHECK (quality BETWEEN 1 AND 5),
                      player_name TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state IN ('PREPARED','REMOVING','COMPLETED','CANCELLED')),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_social_intent ON social_item_intents(player_uuid) WHERE state IN ('PREPARED','REMOVING')");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exchange_listings (
                      listing_id TEXT PRIMARY KEY,
                      seller_uuid TEXT NOT NULL,
                      seller_name TEXT NOT NULL,
                      item BLOB NOT NULL,
                      item_id TEXT NOT NULL,
                      quantity INTEGER NOT NULL CHECK (quantity >= 0),
                      unit_price INTEGER NOT NULL CHECK (unit_price > 0),
                      quality INTEGER NOT NULL CHECK (quality BETWEEN 1 AND 5),
                      state TEXT NOT NULL CHECK (state IN ('ACTIVE','SOLD','CANCELLED')),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS exchange_active ON exchange_listings(state, created_at)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exchange_trades (
                      trade_id TEXT PRIMARY KEY,
                      listing_id TEXT NOT NULL,
                      item_id TEXT NOT NULL,
                      quantity INTEGER NOT NULL CHECK (quantity > 0),
                      unit_price INTEGER NOT NULL CHECK (unit_price > 0),
                      sold_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_stalls (
                      slot INTEGER PRIMARY KEY CHECK (slot BETWEEN 1 AND 4),
                      owner_uuid TEXT UNIQUE NOT NULL,
                      owner_name TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS merchant_guilds (
                      guild_id TEXT PRIMARY KEY,
                      name TEXT UNIQUE NOT NULL,
                      owner_uuid TEXT NOT NULL,
                      log_progress INTEGER NOT NULL DEFAULT 0 CHECK (log_progress >= 0),
                      iron_progress INTEGER NOT NULL DEFAULT 0 CHECK (iron_progress >= 0),
                      money_progress INTEGER NOT NULL DEFAULT 0 CHECK (money_progress >= 0),
                      project_state TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (project_state IN ('ACTIVE','COMPLETE')),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS merchant_guild_members (
                      player_uuid TEXT PRIMARY KEY,
                      player_name TEXT NOT NULL,
                      guild_id TEXT NOT NULL REFERENCES merchant_guilds(guild_id) ON DELETE CASCADE
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS merchant_guild_items (
                      deposit_id TEXT PRIMARY KEY,
                      guild_id TEXT NOT NULL REFERENCES merchant_guilds(guild_id) ON DELETE CASCADE,
                      depositor_uuid TEXT NOT NULL,
                      item BLOB NOT NULL,
                      item_id TEXT NOT NULL,
                      quantity INTEGER NOT NULL CHECK (quantity > 0),
                      quality INTEGER NOT NULL CHECK (quality BETWEEN 1 AND 5),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS service_offers (
                      offer_id TEXT PRIMARY KEY,
                      provider_uuid TEXT NOT NULL,
                      provider_name TEXT NOT NULL,
                      service_type TEXT NOT NULL,
                      price INTEGER NOT NULL CHECK (price > 0),
                      client_uuid TEXT,
                      state TEXT NOT NULL CHECK (state IN ('OPEN','HIRED','SUBMITTED','COMPLETED','CANCELLED')),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS restaurants (
                      owner_uuid TEXT PRIMARY KEY,
                      name TEXT UNIQUE NOT NULL,
                      rating_total INTEGER NOT NULL DEFAULT 0 CHECK (rating_total >= 0),
                      served_count INTEGER NOT NULL DEFAULT 0 CHECK (served_count >= 0),
                      revenue INTEGER NOT NULL DEFAULT 0 CHECK (revenue >= 0)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS restaurant_members (
                      member_uuid TEXT PRIMARY KEY,
                      member_name TEXT NOT NULL,
                      owner_uuid TEXT NOT NULL REFERENCES restaurants(owner_uuid) ON DELETE CASCADE,
                      role TEXT NOT NULL CHECK (role IN ('INGREDIENT','CHEF','SERVER'))
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS restaurant_orders (
                      order_id TEXT PRIMARY KEY,
                      owner_uuid TEXT UNIQUE NOT NULL REFERENCES restaurants(owner_uuid) ON DELETE CASCADE,
                      state TEXT NOT NULL CHECK (state IN ('OPEN','COOKING','FLIPPED','READY')),
                      crop_quality INTEGER,
                      protein_quality INTEGER,
                      extra_quality INTEGER,
                      supplier_uuid TEXT,
                      chef_uuid TEXT,
                      server_uuid TEXT,
                      score INTEGER NOT NULL DEFAULT 0,
                      action_at INTEGER NOT NULL DEFAULT 0,
                      created_at TEXT NOT NULL
                    )""");
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS endgame_sessions (
                      session_id TEXT PRIMARY KEY,
                      owner_uuid TEXT NOT NULL,
                      group_key TEXT NOT NULL,
                      scope TEXT NOT NULL CHECK (scope IN ('SOLO','GUILD')),
                      content TEXT NOT NULL CHECK (content IN ('TRASH','PIRATE','ANUBIS','TOWER')),
                      slot INTEGER NOT NULL CHECK (slot BETWEEN 0 AND 15),
                      stage TEXT NOT NULL,
                      progress INTEGER NOT NULL DEFAULT 0 CHECK (progress >= 0),
                      aux INTEGER NOT NULL DEFAULT 0 CHECK (aux >= 0),
                      state TEXT NOT NULL CHECK (state IN ('ACTIVE','CLEARED','FAILED')),
                      started_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_endgame_slot ON endgame_sessions(slot) WHERE state='ACTIVE'");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS endgame_session_members (
                      session_id TEXT NOT NULL REFERENCES endgame_sessions(session_id) ON DELETE CASCADE,
                      player_uuid TEXT NOT NULL,
                      PRIMARY KEY (session_id, player_uuid)
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS endgame_member_lookup ON endgame_session_members(player_uuid)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS endgame_item_intents (
                      intent_id TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      kind TEXT NOT NULL CHECK (kind IN ('HATCH','FEED','DELIVERY','HELP')),
                      item BLOB NOT NULL,
                      category TEXT NOT NULL,
                      quantity INTEGER NOT NULL CHECK (quantity > 0),
                      state TEXT NOT NULL CHECK (state IN ('PREPARED','REMOVING','COMPLETED','CANCELLED')),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_endgame_intent ON endgame_item_intents(player_uuid) WHERE state IN ('PREPARED','REMOVING')");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS dragons (
                      player_uuid TEXT PRIMARY KEY,
                      stage TEXT NOT NULL CHECK (stage IN ('EGG','HATCHLING','ADULT')),
                      feed_total INTEGER NOT NULL DEFAULT 0 CHECK (feed_total >= 0),
                      fish INTEGER NOT NULL DEFAULT 0 CHECK (fish >= 0),
                      vegetable INTEGER NOT NULL DEFAULT 0 CHECK (vegetable >= 0),
                      fruit INTEGER NOT NULL DEFAULT 0 CHECK (fruit >= 0),
                      meat INTEGER NOT NULL DEFAULT 0 CHECK (meat >= 0),
                      mineral INTEGER NOT NULL DEFAULT 0 CHECK (mineral >= 0),
                      cooking INTEGER NOT NULL DEFAULT 0 CHECK (cooking >= 0),
                      trait TEXT NOT NULL DEFAULT 'FOREST' CHECK (trait IN ('FOREST','SEA','MINERAL','SKY')),
                      updated_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS good_deeds (
                      player_uuid TEXT NOT NULL,
                      deed_type TEXT NOT NULL CHECK (deed_type IN ('DELIVERY','NPC_HELP','DONATION','ESCORT','PUBLIC_PROJECT')),
                      deed_count INTEGER NOT NULL DEFAULT 0 CHECK (deed_count >= 0),
                      PRIMARY KEY (player_uuid, deed_type)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS good_deed_claims (
                      claim_key TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      deed_type TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS warrior_paths (
                      player_uuid TEXT PRIMARY KEY,
                      combat_class TEXT NOT NULL CHECK (combat_class IN ('WARRIOR','GLADIATOR','HUNTER','MAGE')),
                      chosen_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS hero_tower_weekly (
                      week_key TEXT NOT NULL,
                      group_key TEXT NOT NULL,
                      scope TEXT NOT NULL CHECK (scope IN ('SOLO','GUILD')),
                      highest_floor INTEGER NOT NULL CHECK (highest_floor BETWEEN 1 AND 50),
                      best_millis INTEGER NOT NULL CHECK (best_millis >= 0),
                      party_size INTEGER NOT NULL CHECK (party_size > 0),
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (week_key, group_key)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS heaven_star_claims (
                      player_uuid TEXT NOT NULL,
                      node_id INTEGER NOT NULL,
                      week_key TEXT NOT NULL,
                      PRIMARY KEY (player_uuid, node_id, week_key)
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
                profile.setMoney(balance);
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

    public CompletableFuture<Void> prepareSocialIntent(SocialIntent intent) {
        if (intent.item() == null || intent.quantity() < 1 || intent.unitPrice() < 0 || intent.quality() < 1 || intent.quality() > 5)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid social item intent"));
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO social_item_intents VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)")) {
                insert.setString(1, intent.id()); insert.setString(2, intent.player().toString()); insert.setString(3, intent.kind()); insert.setString(4, intent.targetId());
                insert.setBytes(5, intent.item()); insert.setString(6, intent.itemId()); insert.setInt(7, intent.quantity()); insert.setLong(8, intent.unitPrice());
                insert.setInt(9, intent.quality()); insert.setString(10, intent.playerName()); insert.setString(11, Instant.now().toString()); insert.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> markSocialRemoving(String id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE social_item_intents SET state='REMOVING' WHERE intent_id=? AND state='PREPARED'")) {
                update.setString(1, id); if (update.executeUpdate() != 1) throw new SQLException("Social intent is not prepared");
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<SocialIntent>> pendingSocialIntent(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT intent_id,kind,target_id,item,item_id,quantity,unit_price,quality,player_name,state FROM social_item_intents WHERE player_uuid=? AND state IN ('PREPARED','REMOVING') LIMIT 1")) {
                query.setString(1, player.toString());
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? Optional.of(new SocialIntent(row.getString(1), player, row.getString(2), row.getString(3), row.getBytes(4), row.getString(5), row.getInt(6), row.getLong(7), row.getInt(8), row.getString(9), row.getString(10))) : Optional.empty();
                }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> cancelSocialIntent(String id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE social_item_intents SET state='CANCELLED' WHERE intent_id=? AND state IN ('PREPARED','REMOVING')")) {
                update.setString(1, id); update.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<SocialCompletion> completeSocialIntent(String id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SocialIntent intent = socialIntent(id).orElseThrow(() -> new SQLException("Social intent missing"));
                if (intent.state().equals("COMPLETED")) return new SocialCompletion(intent.kind(), intent.targetId());
                if (!intent.state().equals("REMOVING")) throw new SQLException("Social intent is not removing");
                transaction(() -> {
                    switch (intent.kind()) {
                        case "EXCHANGE" -> {
                            try (PreparedStatement insert = database.prepareStatement("INSERT INTO exchange_listings VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)")) {
                                insert.setString(1, intent.targetId()); insert.setString(2, intent.player().toString()); insert.setString(3, intent.playerName()); insert.setBytes(4, intent.item());
                                insert.setString(5, intent.itemId()); insert.setInt(6, intent.quantity()); insert.setLong(7, intent.unitPrice()); insert.setInt(8, intent.quality()); insert.setString(9, Instant.now().toString()); insert.executeUpdate();
                            }
                        }
                        case "GUILD" -> {
                            requireGuildMember(intent.player(), intent.targetId());
                            try (PreparedStatement insert = database.prepareStatement("INSERT INTO merchant_guild_items VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                                insert.setString(1, intent.id()); insert.setString(2, intent.targetId()); insert.setString(3, intent.player().toString()); insert.setBytes(4, intent.item());
                                insert.setString(5, intent.itemId()); insert.setInt(6, intent.quantity()); insert.setInt(7, intent.quality()); insert.setString(8, Instant.now().toString()); insert.executeUpdate();
                            }
                        }
                        case "RESTAURANT" -> supplyRestaurant(intent);
                        case "PROJECT" -> contributeProjectItem(intent);
                        default -> throw new SQLException("Unknown social intent kind");
                    }
                    try (PreparedStatement update = database.prepareStatement("UPDATE social_item_intents SET state='COMPLETED' WHERE intent_id=? AND state='REMOVING'")) {
                        update.setString(1, id); if (update.executeUpdate() != 1) throw new SQLException("Social intent completion race");
                    }
                });
                return new SocialCompletion(intent.kind(), intent.targetId());
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    private Optional<SocialIntent> socialIntent(String id) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT player_uuid,kind,target_id,item,item_id,quantity,unit_price,quality,player_name,state FROM social_item_intents WHERE intent_id=?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(new SocialIntent(id, UUID.fromString(row.getString(1)), row.getString(2), row.getString(3), row.getBytes(4), row.getString(5), row.getInt(6), row.getLong(7), row.getInt(8), row.getString(9), row.getString(10))) : Optional.empty();
            }
        }
    }

    public CompletableFuture<List<ExchangeListing>> exchangeListings(UUID seller, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = seller == null
                    ? "SELECT listing_id,seller_uuid,seller_name,item,item_id,quantity,unit_price,quality FROM exchange_listings WHERE state='ACTIVE' ORDER BY created_at LIMIT ?"
                    : "SELECT listing_id,seller_uuid,seller_name,item,item_id,quantity,unit_price,quality FROM exchange_listings WHERE state='ACTIVE' AND seller_uuid=? ORDER BY created_at LIMIT ?";
            try (PreparedStatement query = database.prepareStatement(sql)) {
                int index = 1; if (seller != null) query.setString(index++, seller.toString()); query.setInt(index, Math.max(1, Math.min(20, limit)));
                try (ResultSet rows = query.executeQuery()) {
                    java.util.ArrayList<ExchangeListing> result = new java.util.ArrayList<>();
                    while (rows.next()) result.add(new ExchangeListing(rows.getString(1), UUID.fromString(rows.getString(2)), rows.getString(3), rows.getBytes(4), rows.getString(5), rows.getInt(6), rows.getLong(7), rows.getInt(8)));
                    return result;
                }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<TradeStats> tradeStats(String itemId) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT COALESCE((SELECT unit_price FROM exchange_trades WHERE item_id=? ORDER BY sold_at DESC LIMIT 1),0), COALESCE(ROUND(SUM(unit_price*quantity)*1.0/SUM(quantity)),0) FROM exchange_trades WHERE item_id=? AND sold_at>=?")) {
                query.setString(1, itemId); query.setString(2, itemId); query.setString(3, Instant.now().minus(Duration.ofDays(7)).toString());
                try (ResultSet row = query.executeQuery()) { row.next(); return new TradeStats(row.getLong(1), row.getLong(2)); }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<ExchangePurchase> buyListing(PlayerProfile buyer, String prefix, String grantId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ExchangePurchase[] result = new ExchangePurchase[1];
                transaction(() -> {
                    ExchangeListing listing = uniqueListing(prefix, null);
                    if (listing.seller().equals(buyer.playerId())) throw new SQLException("Cannot buy own listing");
                    long total = Math.multiplyExact(listing.quantity(), listing.unitPrice());
                    long buyerBalance = wallet(buyer.playerId()), sellerBalance = wallet(listing.seller());
                    long buyerAfter = Math.subtractExact(buyerBalance, total), sellerAfter = Math.addExact(sellerBalance, total);
                    if (buyerAfter < 0) throw new SQLException("Insufficient balance");
                    updateWallet(buyer.playerId(), buyerBalance, buyerAfter); updateWallet(listing.seller(), sellerBalance, sellerAfter);
                    try (PreparedStatement close = database.prepareStatement("UPDATE exchange_listings SET quantity=0,state='SOLD' WHERE listing_id=? AND state='ACTIVE' AND quantity=?")) {
                        close.setString(1, listing.id()); close.setInt(2, listing.quantity()); if (close.executeUpdate() != 1) throw new SQLException("Listing changed concurrently");
                    }
                    try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
                        grant.setString(1, grantId); grant.setString(2, buyer.playerId().toString()); grant.setBytes(3, listing.item()); grant.setString(4, Instant.now().toString()); grant.executeUpdate();
                    }
                    logEconomy(buyer.playerId(), "exchange-buy:" + listing.id(), "EXCHANGE_BUY", listing.itemId(), listing.quantity(), listing.unitPrice(), total, buyerAfter);
                    logEconomy(listing.seller(), "exchange-sell:" + listing.id(), "EXCHANGE_SELL", listing.itemId(), listing.quantity(), listing.unitPrice(), total, sellerAfter);
                    try (PreparedStatement trade = database.prepareStatement("INSERT INTO exchange_trades VALUES (?, ?, ?, ?, ?, ?)")) {
                        trade.setString(1, UUID.randomUUID().toString()); trade.setString(2, listing.id()); trade.setString(3, listing.itemId()); trade.setInt(4, listing.quantity()); trade.setLong(5, listing.unitPrice()); trade.setString(6, Instant.now().toString()); trade.executeUpdate();
                    }
                    result[0] = new ExchangePurchase(listing, buyerAfter, sellerAfter, grantId);
                });
                buyer.setMoney(result[0].buyerBalance());
                PlayerProfile seller = loaded.get(result[0].listing().seller()); if (seller != null) seller.setMoney(result[0].sellerBalance());
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<ItemGrant> cancelListing(UUID seller, String prefix, String grantId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[][] item = new byte[1][];
                transaction(() -> {
                    ExchangeListing listing = uniqueListing(prefix, seller); item[0] = listing.item();
                    try (PreparedStatement close = database.prepareStatement("UPDATE exchange_listings SET quantity=0,state='CANCELLED' WHERE listing_id=? AND state='ACTIVE'")) {
                        close.setString(1, listing.id()); if (close.executeUpdate() != 1) throw new SQLException("Listing changed concurrently");
                    }
                    try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
                        grant.setString(1, grantId); grant.setString(2, seller.toString()); grant.setBytes(3, item[0]); grant.setString(4, Instant.now().toString()); grant.executeUpdate();
                    }
                });
                return new ItemGrant(grantId, item[0]);
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    private ExchangeListing uniqueListing(String prefix, UUID seller) throws SQLException {
        String sql = "SELECT listing_id,seller_uuid,seller_name,item,item_id,quantity,unit_price,quality FROM exchange_listings WHERE state='ACTIVE' AND listing_id LIKE ?" + (seller == null ? "" : " AND seller_uuid=?") + " LIMIT 2";
        try (PreparedStatement query = database.prepareStatement(sql)) {
            query.setString(1, prefix + "%"); if (seller != null) query.setString(2, seller.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Listing not found");
                ExchangeListing result = new ExchangeListing(rows.getString(1), UUID.fromString(rows.getString(2)), rows.getString(3), rows.getBytes(4), rows.getString(5), rows.getInt(6), rows.getLong(7), rows.getInt(8));
                if (rows.next()) throw new SQLException("Listing prefix is ambiguous");
                return result;
            }
        }
    }

    public CompletableFuture<Void> claimStall(int slot, UUID owner, String ownerName) {
        if (slot < 1 || slot > 4) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid stall"));
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO market_stalls VALUES (?, ?, ?) ON CONFLICT(owner_uuid) DO UPDATE SET slot=excluded.slot,owner_name=excluded.owner_name")) {
                insert.setInt(1, slot); insert.setString(2, owner.toString()); insert.setString(3, ownerName); insert.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> releaseStall(UUID owner) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement delete = database.prepareStatement("DELETE FROM market_stalls WHERE owner_uuid=?")) { delete.setString(1, owner.toString()); delete.executeUpdate(); }
            catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<List<Stall>> stalls() {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT slot,owner_uuid,owner_name FROM market_stalls ORDER BY slot"); ResultSet rows = query.executeQuery()) {
                java.util.ArrayList<Stall> result = new java.util.ArrayList<>(); while (rows.next()) result.add(new Stall(rows.getInt(1), UUID.fromString(rows.getString(2)), rows.getString(3))); return result;
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Guild> createGuild(UUID owner, String ownerName, String name) {
        if (!validName(name, 16)) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid guild name"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                String id = UUID.randomUUID().toString();
                transaction(() -> {
                    try (PreparedStatement insert = database.prepareStatement("INSERT INTO merchant_guilds(guild_id,name,owner_uuid,created_at) VALUES (?, ?, ?, ?)")) {
                        insert.setString(1, id); insert.setString(2, name); insert.setString(3, owner.toString()); insert.setString(4, Instant.now().toString()); insert.executeUpdate();
                    }
                    try (PreparedStatement member = database.prepareStatement("INSERT INTO merchant_guild_members VALUES (?, ?, ?)")) { member.setString(1, owner.toString()); member.setString(2, ownerName); member.setString(3, id); member.executeUpdate(); }
                });
                return new Guild(id, name, owner, 0, 0, 0, "ACTIVE");
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Guild> joinGuild(UUID player, String playerName, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Guild guild = guildByName(name);
                try (PreparedStatement insert = database.prepareStatement("INSERT INTO merchant_guild_members VALUES (?, ?, ?)")) { insert.setString(1, player.toString()); insert.setString(2, playerName); insert.setString(3, guild.id()); insert.executeUpdate(); }
                return guild;
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<Guild>> guildFor(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT g.guild_id,g.name,g.owner_uuid,g.log_progress,g.iron_progress,g.money_progress,g.project_state FROM merchant_guilds g JOIN merchant_guild_members m ON m.guild_id=g.guild_id WHERE m.player_uuid=?")) {
                query.setString(1, player.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(guild(row)) : Optional.empty(); }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> leaveGuild(UUID player) {
        return CompletableFuture.runAsync(() -> {
            try {
                Guild guild = guildForSync(player).orElseThrow(() -> new SQLException("Guild not found"));
                if (guild.owner().equals(player)) throw new SQLException("Guild owner cannot leave");
                try (PreparedStatement delete = database.prepareStatement("DELETE FROM merchant_guild_members WHERE player_uuid=?")) { delete.setString(1, player.toString()); delete.executeUpdate(); }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<List<GuildItem>> guildItems(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Guild guild = guildForSync(player).orElseThrow(() -> new SQLException("Guild not found"));
                try (PreparedStatement query = database.prepareStatement("SELECT deposit_id,item,item_id,quantity,quality FROM merchant_guild_items WHERE guild_id=? ORDER BY created_at LIMIT 20")) {
                    query.setString(1, guild.id()); try (ResultSet rows = query.executeQuery()) { java.util.ArrayList<GuildItem> result = new java.util.ArrayList<>(); while (rows.next()) result.add(new GuildItem(rows.getString(1), rows.getBytes(2), rows.getString(3), rows.getInt(4), rows.getInt(5))); return result; }
                }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<ItemGrant> withdrawGuildItem(UUID player, String prefix, String grantId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[][] item = new byte[1][];
                transaction(() -> {
                    Guild guild = guildForSync(player).orElseThrow(() -> new SQLException("Guild not found"));
                    try (PreparedStatement query = database.prepareStatement("SELECT deposit_id,item FROM merchant_guild_items WHERE guild_id=? AND deposit_id LIKE ? LIMIT 2")) {
                        query.setString(1, guild.id()); query.setString(2, prefix + "%"); try (ResultSet rows = query.executeQuery()) {
                            if (!rows.next()) throw new SQLException("Deposit not found"); String id = rows.getString(1); item[0] = rows.getBytes(2); if (rows.next()) throw new SQLException("Deposit prefix is ambiguous");
                            try (PreparedStatement delete = database.prepareStatement("DELETE FROM merchant_guild_items WHERE deposit_id=?")) { delete.setString(1, id); if (delete.executeUpdate() != 1) throw new SQLException("Deposit changed concurrently"); }
                        }
                    }
                    try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) { grant.setString(1, grantId); grant.setString(2, player.toString()); grant.setBytes(3, item[0]); grant.setString(4, Instant.now().toString()); grant.executeUpdate(); }
                });
                return new ItemGrant(grantId, item[0]);
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Guild> contributeGuildMoney(PlayerProfile player, long amount) {
        if (amount < 1) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid contribution"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                Guild[] result = new Guild[1]; long[] balanceAfter = new long[1];
                transaction(() -> {
                    Guild guild = guildForSync(player.playerId()).orElseThrow(() -> new SQLException("Guild not found"));
                    if (guild.projectState().equals("COMPLETE") || guild.money() + amount > socialBalance.guildMoney()) throw new SQLException("Project contribution exceeds target");
                    long balance = wallet(player.playerId()), after = Math.subtractExact(balance, amount); if (after < 0) throw new SQLException("Insufficient balance"); updateWallet(player.playerId(), balance, after); balanceAfter[0] = after;
                    try (PreparedStatement update = database.prepareStatement("UPDATE merchant_guilds SET money_progress=money_progress+? WHERE guild_id=? AND project_state='ACTIVE'")) { update.setLong(1, amount); update.setString(2, guild.id()); if (update.executeUpdate() != 1) throw new SQLException("Guild project changed"); }
                    finishProject(guild.id()); result[0] = guildForSync(player.playerId()).orElseThrow();
                    logEconomy(player.playerId(), "guild-project:" + UUID.randomUUID(), "GUILD_PROJECT", guild.id(), 1, amount, amount, after);
                });
                player.setMoney(balanceAfter[0]);
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<ServiceOffer> createService(UUID provider, String providerName, String type, long price) {
        if (!Set.of("PAINTER","FURNITURE","INTERIOR","CHEF","RESTAURANT","GARDENER","AQUARIUM").contains(type) || price < 1)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid service"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                String id = UUID.randomUUID().toString();
                try (PreparedStatement insert = database.prepareStatement("INSERT INTO service_offers VALUES (?, ?, ?, ?, ?, NULL, 'OPEN', ?)")) { insert.setString(1, id); insert.setString(2, provider.toString()); insert.setString(3, providerName); insert.setString(4, type); insert.setLong(5, price); insert.setString(6, Instant.now().toString()); insert.executeUpdate(); }
                return new ServiceOffer(id, provider, providerName, type, price, null, "OPEN");
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<List<ServiceOffer>> services() {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT offer_id,provider_uuid,provider_name,service_type,price,client_uuid,state FROM service_offers WHERE state IN ('OPEN','HIRED','SUBMITTED') ORDER BY created_at LIMIT 20"); ResultSet rows = query.executeQuery()) {
                java.util.ArrayList<ServiceOffer> result = new java.util.ArrayList<>(); while (rows.next()) result.add(service(rows)); return result;
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<ServiceOffer> hireService(PlayerProfile client, String prefix) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ServiceOffer[] result = new ServiceOffer[1]; long[] balanceAfter = new long[1];
                transaction(() -> {
                    ServiceOffer offer = uniqueService(prefix, "OPEN", null); if (offer.provider().equals(client.playerId())) throw new SQLException("Cannot hire own service");
                    long balance = wallet(client.playerId()), after = Math.subtractExact(balance, offer.price()); if (after < 0) throw new SQLException("Insufficient balance"); updateWallet(client.playerId(), balance, after);
                    try (PreparedStatement update = database.prepareStatement("UPDATE service_offers SET client_uuid=?,state='HIRED' WHERE offer_id=? AND state='OPEN'")) { update.setString(1, client.playerId().toString()); update.setString(2, offer.id()); if (update.executeUpdate() != 1) throw new SQLException("Service changed concurrently"); }
                    logEconomy(client.playerId(), "service-escrow:" + offer.id(), "SERVICE_ESCROW", offer.type(), 1, offer.price(), offer.price(), after);
                    result[0] = new ServiceOffer(offer.id(), offer.provider(), offer.providerName(), offer.type(), offer.price(), client.playerId(), "HIRED"); balanceAfter[0] = after;
                });
                client.setMoney(balanceAfter[0]);
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<ServiceOffer> submitService(UUID provider, String prefix) {
        return serviceState(prefix, "HIRED", "SUBMITTED", provider);
    }

    public CompletableFuture<ServiceOffer> approveService(PlayerProfile client, String prefix) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ServiceOffer[] result = new ServiceOffer[1]; long[] providerAfter = new long[1];
                transaction(() -> {
                    ServiceOffer offer = uniqueService(prefix, "SUBMITTED", null); if (!client.playerId().equals(offer.client())) throw new SQLException("Not service client");
                    long balance = wallet(offer.provider()); providerAfter[0] = Math.addExact(balance, offer.price()); updateWallet(offer.provider(), balance, providerAfter[0]);
                    try (PreparedStatement update = database.prepareStatement("UPDATE service_offers SET state='COMPLETED' WHERE offer_id=? AND state='SUBMITTED'")) { update.setString(1, offer.id()); if (update.executeUpdate() != 1) throw new SQLException("Service changed concurrently"); }
                    logEconomy(offer.provider(), "service-payment:" + offer.id(), "SERVICE_PAYMENT", offer.type(), 1, offer.price(), offer.price(), providerAfter[0]);
                    result[0] = new ServiceOffer(offer.id(), offer.provider(), offer.providerName(), offer.type(), offer.price(), offer.client(), "COMPLETED");
                });
                PlayerProfile provider = loaded.get(result[0].provider()); if (provider != null) provider.setMoney(providerAfter[0]); return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> cancelService(UUID provider, String prefix) {
        return serviceState(prefix, "OPEN", "CANCELLED", provider).thenApply(ignored -> null);
    }

    private CompletableFuture<ServiceOffer> serviceState(String prefix, String from, String to, UUID actor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ServiceOffer offer = uniqueService(prefix, from, actor);
                try (PreparedStatement update = database.prepareStatement("UPDATE service_offers SET state=? WHERE offer_id=? AND state=?")) { update.setString(1, to); update.setString(2, offer.id()); update.setString(3, from); if (update.executeUpdate() != 1) throw new SQLException("Service changed concurrently"); }
                return new ServiceOffer(offer.id(), offer.provider(), offer.providerName(), offer.type(), offer.price(), offer.client(), to);
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Restaurant> openRestaurant(UUID owner, String name) {
        if (!validName(name, 20)) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid restaurant name"));
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO restaurants VALUES (?, ?, 0, 0, 0)")) { insert.setString(1, owner.toString()); insert.setString(2, name); insert.executeUpdate(); return new Restaurant(owner, name, 0, 0, 0); }
            catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> assignRestaurantRole(UUID owner, UUID member, String memberName, String role) {
        if (!Set.of("INGREDIENT","CHEF","SERVER").contains(role)) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid role"));
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ownerCheck = database.prepareStatement("SELECT 1 FROM restaurants WHERE owner_uuid=? AND owner_uuid<>?"); PreparedStatement insert = database.prepareStatement("INSERT INTO restaurant_members VALUES (?, ?, ?, ?) ON CONFLICT(member_uuid) DO UPDATE SET member_name=excluded.member_name,role=excluded.role WHERE restaurant_members.owner_uuid=excluded.owner_uuid")) {
                requireRestaurant(owner); ownerCheck.setString(1, member.toString()); ownerCheck.setString(2, owner.toString()); try (ResultSet row = ownerCheck.executeQuery()) { if (row.next()) throw new SQLException("Restaurant owner cannot join another restaurant"); }
                insert.setString(1, member.toString()); insert.setString(2, memberName); insert.setString(3, owner.toString()); insert.setString(4, role); if (insert.executeUpdate() != 1) throw new SQLException("Restaurant member already belongs elsewhere");
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<RestaurantOrder> createRestaurantOrder(UUID owner) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireRestaurant(owner); String id = UUID.randomUUID().toString();
                try (PreparedStatement insert = database.prepareStatement("INSERT INTO restaurant_orders(order_id,owner_uuid,state,created_at) VALUES (?, ?, 'OPEN', ?)")) { insert.setString(1, id); insert.setString(2, owner.toString()); insert.setString(3, Instant.now().toString()); insert.executeUpdate(); }
                return restaurantOrder(id);
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<RestaurantOrder>> restaurantOrderFor(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                UUID owner = restaurantOwner(player);
                try (PreparedStatement query = database.prepareStatement("SELECT order_id,owner_uuid,state,crop_quality,protein_quality,extra_quality,supplier_uuid,chef_uuid,server_uuid,score,action_at FROM restaurant_orders WHERE owner_uuid=?")) {
                    query.setString(1, owner.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(order(row)) : Optional.empty(); }
                }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<RestaurantOrder> restaurantAction(UUID player, String action, long now) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RestaurantOrder current = restaurantOrderForSync(player).orElseThrow(() -> new SQLException("Order not found")); String role = restaurantRole(player, current.owner());
                String next; int score = current.score();
                if (action.equals("COOK") && current.state().equals("OPEN") && current.cropQuality() != null && current.proteinQuality() != null && current.extraQuality() != null && Set.of("CHEF", "OWNER").contains(role)) { next = "COOKING"; score = current.cropQuality() + current.proteinQuality() + current.extraQuality(); }
                else if (action.equals("FLIP") && current.state().equals("COOKING") && Set.of("CHEF", "OWNER").contains(role)) { next = "FLIPPED"; score += timingScore(now - current.actionAt(), 4000); }
                else if (action.equals("PLATE") && current.state().equals("FLIPPED") && Set.of("CHEF", "OWNER").contains(role)) { next = "READY"; score += timingScore(now - current.actionAt(), 2500); }
                else throw new SQLException("Restaurant action is not available");
                try (PreparedStatement update = database.prepareStatement("UPDATE restaurant_orders SET state=?,chef_uuid=?,score=?,action_at=? WHERE order_id=? AND state=?")) {
                    update.setString(1, next); update.setString(2, player.toString()); update.setInt(3, score); update.setLong(4, now); update.setString(5, current.id()); update.setString(6, current.state()); if (update.executeUpdate() != 1) throw new SQLException("Order changed concurrently");
                }
                return restaurantOrder(current.id());
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<RestaurantResult> serveRestaurant(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RestaurantResult[] result = new RestaurantResult[1];
                transaction(() -> {
                    RestaurantOrder order = restaurantOrderForSync(player).orElseThrow(() -> new SQLException("Order not found")); if (!order.state().equals("READY") || !Set.of("SERVER", "OWNER").contains(restaurantRole(player, order.owner()))) throw new SQLException("Order is not ready");
                    int rating = Math.max(1, Math.min(5, 1 + order.score() / 5)); long reward = Math.addExact(socialBalance.restaurantBaseReward(), Math.multiplyExact(rating, socialBalance.restaurantQualityReward())); long balance = wallet(order.owner()), after = Math.addExact(balance, reward); updateWallet(order.owner(), balance, after);
                    try (PreparedStatement update = database.prepareStatement("UPDATE restaurants SET rating_total=rating_total+?,served_count=served_count+1,revenue=revenue+? WHERE owner_uuid=?")) { update.setInt(1, rating); update.setLong(2, reward); update.setString(3, order.owner().toString()); if (update.executeUpdate() != 1) throw new SQLException("Restaurant missing"); }
                    try (PreparedStatement delete = database.prepareStatement("DELETE FROM restaurant_orders WHERE order_id=? AND state='READY'")) { delete.setString(1, order.id()); if (delete.executeUpdate() != 1) throw new SQLException("Order changed concurrently"); }
                    logEconomy(order.owner(), "restaurant:" + order.id(), "RESTAURANT", "market_meal", 1, reward, reward, after); result[0] = new RestaurantResult(rating, reward, after);
                });
                PlayerProfile owner = loaded.get(restaurantOwner(player)); if (owner != null) owner.setMoney(result[0].balance()); return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    private void supplyRestaurant(SocialIntent intent) throws SQLException {
        RestaurantOrder order = restaurantOrder(intent.targetId()); String role = restaurantRole(intent.player(), order.owner()); if (!(role.equals("INGREDIENT") || role.equals("OWNER")) || !order.state().equals("OPEN")) throw new SQLException("Ingredient role required");
        String column = switch (intent.itemId()) { case "CROP" -> "crop_quality"; case "PROTEIN" -> "protein_quality"; case "EXTRA" -> "extra_quality"; default -> throw new SQLException("Invalid ingredient category"); };
        try (PreparedStatement update = database.prepareStatement("UPDATE restaurant_orders SET " + column + "=?,supplier_uuid=? WHERE order_id=? AND state='OPEN' AND " + column + " IS NULL")) {
            update.setInt(1, intent.quality()); update.setString(2, intent.player().toString()); update.setString(3, order.id()); if (update.executeUpdate() != 1) throw new SQLException("Ingredient already supplied");
        }
    }

    private void contributeProjectItem(SocialIntent intent) throws SQLException {
        requireGuildMember(intent.player(), intent.targetId()); String column; int target;
        if (intent.itemId().equals("LOG")) { column = "log_progress"; target = socialBalance.guildLogs(); } else if (intent.itemId().equals("IRON")) { column = "iron_progress"; target = socialBalance.guildIron(); } else throw new SQLException("Invalid project item");
        try (PreparedStatement update = database.prepareStatement("UPDATE merchant_guilds SET " + column + "=" + column + "+? WHERE guild_id=? AND project_state='ACTIVE' AND " + column + "+?<=?")) {
            update.setInt(1, intent.quantity()); update.setString(2, intent.targetId()); update.setInt(3, intent.quantity()); update.setInt(4, target); if (update.executeUpdate() != 1) throw new SQLException("Project contribution exceeds target");
        }
        finishProject(intent.targetId());
    }

    private void finishProject(String guildId) throws SQLException {
        try (PreparedStatement update = database.prepareStatement("UPDATE merchant_guilds SET project_state='COMPLETE' WHERE guild_id=? AND log_progress>=? AND iron_progress>=? AND money_progress>=?")) { update.setString(1, guildId); update.setInt(2, socialBalance.guildLogs()); update.setInt(3, socialBalance.guildIron()); update.setLong(4, socialBalance.guildMoney()); update.executeUpdate(); }
    }

    public CompletableFuture<PartySnapshot> guildParty(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Guild guild = guildForSync(player).orElseThrow(() -> new SQLException("Guild not found"));
                java.util.ArrayList<UUID> members = new java.util.ArrayList<>();
                try (PreparedStatement query = database.prepareStatement("SELECT player_uuid FROM merchant_guild_members WHERE guild_id=? ORDER BY player_uuid")) {
                    query.setString(1, guild.id()); try (ResultSet rows = query.executeQuery()) { while (rows.next()) members.add(UUID.fromString(rows.getString(1))); }
                }
                return new PartySnapshot(guild.id(), List.copyOf(members));
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<EndgameSession> startEndgameSession(UUID owner, String scope, String groupKey, String content, List<UUID> requestedMembers) {
        if (!Set.of("SOLO", "GUILD").contains(scope) || !Set.of("TRASH", "PIRATE", "ANUBIS", "TOWER").contains(content))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid endgame session"));
        java.util.LinkedHashSet<UUID> unique = new java.util.LinkedHashSet<>(requestedMembers);
        if (!unique.contains(owner) || unique.isEmpty() || unique.size() > 8 || (scope.equals("SOLO") && unique.size() != 1))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid endgame members"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                EndgameSession[] result = new EndgameSession[1];
                transaction(() -> {
                    if (scope.equals("GUILD")) {
                        Guild guild = guildForSync(owner).orElseThrow(() -> new SQLException("Guild not found"));
                        if (!guild.id().equals(groupKey)) throw new SQLException("Guild changed");
                        for (UUID member : unique) requireGuildMember(member, groupKey);
                    } else if (!groupKey.equals(owner.toString())) throw new SQLException("Solo group key mismatch");
                    try (PreparedStatement activeGroup = database.prepareStatement("SELECT 1 FROM endgame_sessions WHERE group_key=? AND state='ACTIVE'")) {
                        activeGroup.setString(1, groupKey); try (ResultSet row = activeGroup.executeQuery()) { if (row.next()) throw new SQLException("Group already has an active session"); }
                    }
                    for (UUID member : unique) try (PreparedStatement active = database.prepareStatement("SELECT 1 FROM endgame_sessions s JOIN endgame_session_members m ON m.session_id=s.session_id WHERE m.player_uuid=? AND s.state='ACTIVE'")) {
                        active.setString(1, member.toString()); try (ResultSet row = active.executeQuery()) { if (row.next()) throw new SQLException("Member already has an active session"); }
                    }
                    boolean[] used = new boolean[16];
                    try (PreparedStatement query = database.prepareStatement("SELECT slot FROM endgame_sessions WHERE state='ACTIVE'"); ResultSet rows = query.executeQuery()) { while (rows.next()) used[rows.getInt(1)] = true; }
                    int slot = -1; for (int i = 0; i < used.length; i++) if (!used[i]) { slot = i; break; }
                    if (slot < 0) throw new SQLException("No free endgame slot");
                    String id = UUID.randomUUID().toString();
                    String stage = switch (content) { case "TRASH" -> "VERMIN"; case "PIRATE" -> "APPROACH"; case "ANUBIS" -> "STORM"; default -> "FLOOR"; };
                    int aux = content.equals("TOWER") ? 1 : 0; String now = Instant.now().toString();
                    try (PreparedStatement insert = database.prepareStatement("INSERT INTO endgame_sessions VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 'ACTIVE', ?, ?)")) {
                        insert.setString(1, id); insert.setString(2, owner.toString()); insert.setString(3, groupKey); insert.setString(4, scope); insert.setString(5, content);
                        insert.setInt(6, slot); insert.setString(7, stage); insert.setInt(8, aux); insert.setString(9, now); insert.setString(10, now); insert.executeUpdate();
                    }
                    try (PreparedStatement member = database.prepareStatement("INSERT INTO endgame_session_members VALUES (?, ?)")) {
                        for (UUID idMember : unique) { member.setString(1, id); member.setString(2, idMember.toString()); member.addBatch(); } member.executeBatch();
                    }
                    result[0] = new EndgameSession(id, owner, groupKey, scope, content, slot, stage, 0, aux, "ACTIVE", Instant.parse(now));
                });
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<EndgameSession>> activeEndgameSession(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT s.session_id,s.owner_uuid,s.group_key,s.scope,s.content,s.slot,s.stage,s.progress,s.aux,s.state,s.started_at FROM endgame_sessions s JOIN endgame_session_members m ON m.session_id=s.session_id WHERE m.player_uuid=? AND s.state='ACTIVE'")) {
                query.setString(1, player.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(endgameSession(row)) : Optional.empty(); }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<List<EndgameSession>> activeEndgameSessions() {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT session_id,owner_uuid,group_key,scope,content,slot,stage,progress,aux,state,started_at FROM endgame_sessions WHERE state='ACTIVE' ORDER BY slot"); ResultSet rows = query.executeQuery()) {
                java.util.ArrayList<EndgameSession> result = new java.util.ArrayList<>(); while (rows.next()) result.add(endgameSession(rows)); return result;
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Set<UUID>> endgameMembers(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT player_uuid FROM endgame_session_members WHERE session_id=?")) {
                query.setString(1, sessionId); java.util.LinkedHashSet<UUID> result = new java.util.LinkedHashSet<>();
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(UUID.fromString(rows.getString(1))); } return Set.copyOf(result);
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<EndgameSession> recordEndgameObjective(String sessionId, String expectedStage, int required, String nextStage) {
        if (required < 1) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid objective"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                EndgameSession[] result = new EndgameSession[1];
                transaction(() -> {
                    EndgameSession current = endgameSessionSync(sessionId);
                    if (!current.state().equals("ACTIVE") || !current.stage().equals(expectedStage)) throw new SQLException("Endgame stage changed");
                    int progress = current.progress() + 1; String stage = progress >= required ? nextStage : expectedStage; if (!stage.equals(expectedStage)) progress = 0;
                    try (PreparedStatement update = database.prepareStatement("UPDATE endgame_sessions SET stage=?,progress=?,updated_at=? WHERE session_id=? AND state='ACTIVE' AND stage=? AND progress=?")) {
                        update.setString(1, stage); update.setInt(2, progress); update.setString(3, Instant.now().toString()); update.setString(4, sessionId); update.setString(5, expectedStage); update.setInt(6, current.progress());
                        if (update.executeUpdate() != 1) throw new SQLException("Endgame objective race");
                    }
                    result[0] = endgameSessionSync(sessionId);
                });
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<EndgameSession> transitionEndgame(String sessionId, String expectedStage, String nextStage, int aux) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE endgame_sessions SET stage=?,progress=0,aux=?,updated_at=? WHERE session_id=? AND state='ACTIVE' AND stage=?")) {
                update.setString(1, nextStage); update.setInt(2, Math.max(0, aux)); update.setString(3, Instant.now().toString()); update.setString(4, sessionId); update.setString(5, expectedStage);
                if (update.executeUpdate() != 1) throw new SQLException("Endgame stage changed"); return endgameSessionSync(sessionId);
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> abandonEndgame(UUID owner, String sessionId) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE endgame_sessions SET state='FAILED',updated_at=? WHERE session_id=? AND owner_uuid=? AND state='ACTIVE'")) {
                update.setString(1, Instant.now().toString()); update.setString(2, sessionId); update.setString(3, owner.toString()); if (update.executeUpdate() != 1) throw new SQLException("Active session not found");
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<EndgameSession> completeEndgame(String sessionId, List<BossReward> rewards) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                EndgameSession[] result = new EndgameSession[1];
                transaction(() -> {
                    EndgameSession current = endgameSessionSync(sessionId);
                    try (PreparedStatement update = database.prepareStatement("UPDATE endgame_sessions SET state='CLEARED',updated_at=? WHERE session_id=? AND state='ACTIVE'")) {
                        update.setString(1, Instant.now().toString()); update.setString(2, sessionId); if (update.executeUpdate() != 1) throw new SQLException("Session already completed");
                    }
                    insertRewards(rewards);
                    result[0] = new EndgameSession(current.id(), current.owner(), current.groupKey(), current.scope(), current.content(), current.slot(), current.stage(), current.progress(), current.aux(), "CLEARED", current.startedAt());
                });
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<TowerAdvance> advanceTower(String sessionId, String weekKey, List<BossReward> finalRewards) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                TowerAdvance[] result = new TowerAdvance[1];
                transaction(() -> {
                    EndgameSession current = endgameSessionSync(sessionId);
                    if (!current.content().equals("TOWER") || !current.stage().equals("FLOOR") || !current.state().equals("ACTIVE")) throw new SQLException("Tower session changed");
                    int floor = current.aux(), required = floor % 10 == 0 ? 1 : Math.min(6, 2 + (floor - 1) / 10), progress = current.progress() + 1;
                    if (progress < required) {
                        try (PreparedStatement update = database.prepareStatement("UPDATE endgame_sessions SET progress=?,updated_at=? WHERE session_id=? AND progress=? AND state='ACTIVE'")) {
                            update.setInt(1, progress); update.setString(2, Instant.now().toString()); update.setString(3, sessionId); update.setInt(4, current.progress()); if (update.executeUpdate() != 1) throw new SQLException("Tower progress race");
                        }
                        result[0] = new TowerAdvance(floor, floor, progress, false);
                        return;
                    }
                    long elapsed = Math.max(0, Duration.between(current.startedAt(), Instant.now()).toMillis());
                    int partySize = endgameMemberCount(sessionId);
                    upsertTowerRecord(weekKey, current.groupKey(), current.scope(), floor, elapsed, partySize);
                    if (floor == 50) {
                        try (PreparedStatement update = database.prepareStatement("UPDATE endgame_sessions SET progress=0,state='CLEARED',updated_at=? WHERE session_id=? AND state='ACTIVE'")) {
                            update.setString(1, Instant.now().toString()); update.setString(2, sessionId); if (update.executeUpdate() != 1) throw new SQLException("Tower completion race");
                        }
                        insertRewards(finalRewards); result[0] = new TowerAdvance(50, 50, 0, true);
                    } else {
                        try (PreparedStatement update = database.prepareStatement("UPDATE endgame_sessions SET progress=0,aux=?,updated_at=? WHERE session_id=? AND state='ACTIVE' AND aux=?")) {
                            update.setInt(1, floor + 1); update.setString(2, Instant.now().toString()); update.setString(3, sessionId); update.setInt(4, floor); if (update.executeUpdate() != 1) throw new SQLException("Tower floor race");
                        }
                        result[0] = new TowerAdvance(floor, floor + 1, 0, false);
                    }
                });
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<List<TowerRecord>> towerRecords(String weekKey) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT group_key,scope,highest_floor,best_millis,party_size FROM hero_tower_weekly WHERE week_key=? ORDER BY highest_floor DESC,best_millis ASC LIMIT 10")) {
                query.setString(1, weekKey); try (ResultSet rows = query.executeQuery()) { java.util.ArrayList<TowerRecord> result = new java.util.ArrayList<>(); while (rows.next()) result.add(new TowerRecord(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getLong(4), rows.getInt(5))); return result; }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> prepareEndgameIntent(EndgameIntent intent) {
        if (!Set.of("HATCH", "FEED", "DELIVERY", "HELP").contains(intent.kind()) || intent.item() == null || intent.quantity() < 1)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid endgame item intent"));
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO endgame_item_intents VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?)")) {
                insert.setString(1, intent.id()); insert.setString(2, intent.player().toString()); insert.setString(3, intent.kind()); insert.setBytes(4, intent.item()); insert.setString(5, intent.category()); insert.setInt(6, intent.quantity()); insert.setString(7, Instant.now().toString()); insert.executeUpdate();
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> markEndgameRemoving(String id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE endgame_item_intents SET state='REMOVING' WHERE intent_id=? AND state='PREPARED'")) {
                update.setString(1, id); if (update.executeUpdate() != 1) throw new SQLException("Endgame intent is not prepared");
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<EndgameIntent>> pendingEndgameIntent(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT intent_id,kind,item,category,quantity,state FROM endgame_item_intents WHERE player_uuid=? AND state IN ('PREPARED','REMOVING') LIMIT 1")) {
                query.setString(1, player.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(new EndgameIntent(row.getString(1), player, row.getString(2), row.getBytes(3), row.getString(4), row.getInt(5), row.getString(6))) : Optional.empty(); }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Void> cancelEndgameIntent(String id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE endgame_item_intents SET state='CANCELLED' WHERE intent_id=? AND state IN ('PREPARED','REMOVING')")) { update.setString(1, id); update.executeUpdate(); }
            catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<EndgameCompletion> completeEndgameIntent(String id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                EndgameCompletion[] result = new EndgameCompletion[1];
                transaction(() -> {
                    EndgameIntent intent = endgameIntentSync(id);
                    if (intent.state().equals("COMPLETED")) { result[0] = new EndgameCompletion(intent.kind(), Set.of("HATCH", "FEED").contains(intent.kind()) ? dragonSync(intent.player()).orElse(null) : null, deedsSync(intent.player())); return; }
                    if (!intent.state().equals("REMOVING")) throw new SQLException("Endgame intent is not removing");
                    Dragon dragon = null; GoodDeeds deeds = null;
                    if (intent.kind().equals("HATCH")) {
                        if (!intent.category().equals("DRAGON_EGG")) throw new SQLException("Dragon egg required");
                        try (PreparedStatement insert = database.prepareStatement("INSERT INTO dragons VALUES (?, 'HATCHLING', 0, 0, 0, 0, 0, 0, 0, 'FOREST', ?)")) {
                            insert.setString(1, intent.player().toString()); insert.setString(2, Instant.now().toString()); insert.executeUpdate();
                        }
                        dragon = dragonSync(intent.player()).orElseThrow();
                    } else if (intent.kind().equals("FEED")) {
                        Dragon current = dragonSync(intent.player()).orElseThrow(() -> new SQLException("Dragon not found"));
                        if (!Set.of("FISH","VEGETABLE","FRUIT","MEAT","MINERAL","COOKING").contains(intent.category())) throw new SQLException("Invalid dragon food");
                        int fish=current.fish(), vegetable=current.vegetable(), fruit=current.fruit(), meat=current.meat(), mineral=current.mineral(), cooking=current.cooking();
                        switch (intent.category()) { case "FISH" -> fish++; case "VEGETABLE" -> vegetable++; case "FRUIT" -> fruit++; case "MEAT" -> meat++; case "MINERAL" -> mineral++; default -> cooking++; }
                        int total = current.feedTotal() + 1; String stage = total >= 12 ? "ADULT" : "HATCHLING"; String trait = dragonTrait(fish, vegetable, fruit, meat, mineral, cooking);
                        try (PreparedStatement update = database.prepareStatement("UPDATE dragons SET stage=?,feed_total=?,fish=?,vegetable=?,fruit=?,meat=?,mineral=?,cooking=?,trait=?,updated_at=? WHERE player_uuid=?")) {
                            update.setString(1, stage); update.setInt(2, total); update.setInt(3, fish); update.setInt(4, vegetable); update.setInt(5, fruit); update.setInt(6, meat); update.setInt(7, mineral); update.setInt(8, cooking); update.setString(9, trait); update.setString(10, Instant.now().toString()); update.setString(11, intent.player().toString()); update.executeUpdate();
                        }
                        dragon = dragonSync(intent.player()).orElseThrow();
                    } else {
                        String type = intent.kind().equals("DELIVERY") ? "DELIVERY" : "NPC_HELP";
                        recordDeed(intent.player(), type, "intent:" + id); deeds = deedsSync(intent.player());
                    }
                    try (PreparedStatement update = database.prepareStatement("UPDATE endgame_item_intents SET state='COMPLETED' WHERE intent_id=? AND state='REMOVING'")) {
                        update.setString(1, id); if (update.executeUpdate() != 1) throw new SQLException("Endgame intent completion race");
                    }
                    result[0] = new EndgameCompletion(intent.kind(), dragon, deeds == null ? deedsSync(intent.player()) : deeds);
                });
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<Dragon>> dragon(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try { return dragonSync(player); } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<GoodDeeds> donateGoodDeed(PlayerProfile profile, long amount, String dayKey) {
        if (amount < 500) return CompletableFuture.failedFuture(new IllegalArgumentException("Donation must be at least 500"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                GoodDeeds[] result = new GoodDeeds[1]; long[] after = new long[1];
                transaction(() -> {
                    long balance = wallet(profile.playerId()); after[0] = Math.subtractExact(balance, amount); if (after[0] < 0) throw new SQLException("Insufficient balance");
                    recordDeed(profile.playerId(), "DONATION", "donation:" + profile.playerId() + ":" + dayKey);
                    updateWallet(profile.playerId(), balance, after[0]);
                    logEconomy(profile.playerId(), "good-deed-donation:" + profile.playerId() + ":" + dayKey, "GOOD_DEED", "donation", 1, amount, amount, after[0]);
                    result[0] = deedsSync(profile.playerId());
                });
                profile.setMoney(after[0]); return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<GoodDeeds> claimPublicProjectDeed(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                GoodDeeds[] result = new GoodDeeds[1];
                transaction(() -> {
                    Guild guild = guildForSync(player).orElseThrow(() -> new SQLException("Guild not found"));
                    if (!guild.projectState().equals("COMPLETE")) throw new SQLException("Guild project is not complete");
                    recordDeed(player, "PUBLIC_PROJECT", "project:" + guild.id() + ":" + player); result[0] = deedsSync(player);
                });
                return result[0];
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<GoodDeeds> recordEscortDeed(UUID player, String claimKey) {
        return CompletableFuture.supplyAsync(() -> {
            try { transaction(() -> recordDeed(player, "ESCORT", "escort:" + player + ":" + claimKey)); return deedsSync(player); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<GoodDeeds> goodDeeds(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try { return deedsSync(player); } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<String> chooseWarriorPath(UUID player, String combatClass) {
        if (!Set.of("WARRIOR","GLADIATOR","HUNTER","MAGE").contains(combatClass)) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid combat class"));
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO warrior_paths VALUES (?, ?, ?)")) {
                insert.setString(1, player.toString()); insert.setString(2, combatClass); insert.setString(3, Instant.now().toString()); insert.executeUpdate(); return combatClass;
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<Optional<String>> warriorPath(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT combat_class FROM warrior_paths WHERE player_uuid=?")) {
                query.setString(1, player.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(row.getString(1)) : Optional.empty(); }
            } catch (SQLException error) { throw new RuntimeException(error); }
        }, writer);
    }

    public CompletableFuture<ItemGrant> claimHeavenStar(UUID player, int node, String weekKey, String grantId, byte[] item) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                transaction(() -> {
                    if (!deedsSync(player).heavenUnlocked()) throw new SQLException("Heaven is locked");
                    try (PreparedStatement claim = database.prepareStatement("INSERT INTO heaven_star_claims VALUES (?, ?, ?)")) { claim.setString(1, player.toString()); claim.setInt(2, node); claim.setString(3, weekKey); claim.executeUpdate(); }
                    try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) { grant.setString(1, grantId); grant.setString(2, player.toString()); grant.setBytes(3, item); grant.setString(4, Instant.now().toString()); grant.executeUpdate(); }
                });
                return new ItemGrant(grantId, item);
            } catch (Exception error) { throw new RuntimeException(error); }
        }, writer);
    }

    private EndgameSession endgameSessionSync(String id) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT session_id,owner_uuid,group_key,scope,content,slot,stage,progress,aux,state,started_at FROM endgame_sessions WHERE session_id=?")) {
            query.setString(1, id); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Endgame session missing"); return endgameSession(row); }
        }
    }

    private EndgameSession endgameSession(ResultSet row) throws SQLException {
        return new EndgameSession(row.getString(1), UUID.fromString(row.getString(2)), row.getString(3), row.getString(4), row.getString(5), row.getInt(6), row.getString(7), row.getInt(8), row.getInt(9), row.getString(10), Instant.parse(row.getString(11)));
    }

    private int endgameMemberCount(String sessionId) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT COUNT(*) FROM endgame_session_members WHERE session_id=?")) { query.setString(1, sessionId); try (ResultSet row = query.executeQuery()) { row.next(); return row.getInt(1); } }
    }

    private void insertRewards(List<BossReward> rewards) throws SQLException {
        try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
            for (BossReward reward : rewards) { grant.setString(1, reward.grantId()); grant.setString(2, reward.player().toString()); grant.setBytes(3, reward.item()); grant.setString(4, Instant.now().toString()); grant.addBatch(); } grant.executeBatch();
        }
    }

    private void upsertTowerRecord(String week, String group, String scope, int floor, long elapsed, int partySize) throws SQLException {
        try (PreparedStatement update = database.prepareStatement("""
                INSERT INTO hero_tower_weekly VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(week_key,group_key) DO UPDATE SET
                  highest_floor=MAX(highest_floor,excluded.highest_floor),
                  best_millis=CASE WHEN excluded.highest_floor>highest_floor OR (excluded.highest_floor=highest_floor AND excluded.best_millis<best_millis) THEN excluded.best_millis ELSE best_millis END,
                  party_size=CASE WHEN excluded.highest_floor>=highest_floor THEN excluded.party_size ELSE party_size END,
                  updated_at=excluded.updated_at""")) {
            update.setString(1, week); update.setString(2, group); update.setString(3, scope); update.setInt(4, floor); update.setLong(5, elapsed); update.setInt(6, partySize); update.setString(7, Instant.now().toString()); update.executeUpdate();
        }
    }

    private EndgameIntent endgameIntentSync(String id) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT player_uuid,kind,item,category,quantity,state FROM endgame_item_intents WHERE intent_id=?")) {
            query.setString(1, id); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Endgame intent missing"); return new EndgameIntent(id, UUID.fromString(row.getString(1)), row.getString(2), row.getBytes(3), row.getString(4), row.getInt(5), row.getString(6)); }
        }
    }

    private Optional<Dragon> dragonSync(UUID player) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT stage,feed_total,fish,vegetable,fruit,meat,mineral,cooking,trait FROM dragons WHERE player_uuid=?")) {
            query.setString(1, player.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(new Dragon(player, row.getString(1), row.getInt(2), row.getInt(3), row.getInt(4), row.getInt(5), row.getInt(6), row.getInt(7), row.getInt(8), row.getString(9))) : Optional.empty(); }
        }
    }

    private String dragonTrait(int fish, int vegetable, int fruit, int meat, int mineral, int cooking) {
        int forest = vegetable + fruit, sea = fish, sky = meat + cooking;
        if (sea > forest && sea >= mineral && sea >= sky) return "SEA";
        if (mineral > forest && mineral > sea && mineral >= sky) return "MINERAL";
        if (sky > forest && sky > sea && sky > mineral) return "SKY";
        return "FOREST";
    }

    private void recordDeed(UUID player, String type, String claimKey) throws SQLException {
        try (PreparedStatement claim = database.prepareStatement("INSERT INTO good_deed_claims VALUES (?, ?, ?, ?)")) {
            claim.setString(1, claimKey); claim.setString(2, player.toString()); claim.setString(3, type); claim.setString(4, Instant.now().toString()); claim.executeUpdate();
        }
        try (PreparedStatement upsert = database.prepareStatement("INSERT INTO good_deeds VALUES (?, ?, 1) ON CONFLICT(player_uuid,deed_type) DO UPDATE SET deed_count=deed_count+1")) {
            upsert.setString(1, player.toString()); upsert.setString(2, type); upsert.executeUpdate();
        }
    }

    private GoodDeeds deedsSync(UUID player) throws SQLException {
        int delivery=0, help=0, donation=0, escort=0, project=0;
        try (PreparedStatement query = database.prepareStatement("SELECT deed_type,deed_count FROM good_deeds WHERE player_uuid=?")) {
            query.setString(1, player.toString()); try (ResultSet rows = query.executeQuery()) { while (rows.next()) switch (rows.getString(1)) {
                case "DELIVERY" -> delivery=rows.getInt(2); case "NPC_HELP" -> help=rows.getInt(2); case "DONATION" -> donation=rows.getInt(2); case "ESCORT" -> escort=rows.getInt(2); default -> project=rows.getInt(2);
            } }
        }
        return new GoodDeeds(delivery, help, donation, escort, project);
    }

    private long wallet(UUID player) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT money FROM players WHERE uuid=?")) { query.setString(1, player.toString()); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Player row missing"); return row.getLong(1); } }
    }

    private void updateWallet(UUID player, long expected, long balance) throws SQLException {
        try (PreparedStatement update = database.prepareStatement("UPDATE players SET money=?,updated_at=? WHERE uuid=? AND money=?")) { update.setLong(1, balance); update.setString(2, Instant.now().toString()); update.setString(3, player.toString()); update.setLong(4, expected); if (update.executeUpdate() != 1) throw new SQLException("Wallet changed concurrently"); }
    }

    private void logEconomy(UUID player, String key, String type, String item, int quantity, long unit, long total, long balance) throws SQLException {
        try (PreparedStatement log = database.prepareStatement("INSERT INTO economy_transactions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            log.setString(1, UUID.randomUUID().toString()); log.setString(2, key); log.setString(3, player.toString()); log.setString(4, type); log.setString(5, item); log.setInt(6, quantity); log.setLong(7, unit); log.setLong(8, total); log.setLong(9, balance); log.setString(10, Instant.now().toString()); log.executeUpdate();
        }
    }

    private void requireGuildMember(UUID player, String guild) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT 1 FROM merchant_guild_members WHERE player_uuid=? AND guild_id=?")) { query.setString(1, player.toString()); query.setString(2, guild); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Guild membership required"); } }
    }

    private Optional<Guild> guildForSync(UUID player) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT g.guild_id,g.name,g.owner_uuid,g.log_progress,g.iron_progress,g.money_progress,g.project_state FROM merchant_guilds g JOIN merchant_guild_members m ON m.guild_id=g.guild_id WHERE m.player_uuid=?")) { query.setString(1, player.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(guild(row)) : Optional.empty(); } }
    }

    private Guild guildByName(String name) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT guild_id,name,owner_uuid,log_progress,iron_progress,money_progress,project_state FROM merchant_guilds WHERE name=?")) { query.setString(1, name); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Guild not found"); return guild(row); } }
    }

    private Guild guild(ResultSet row) throws SQLException { return new Guild(row.getString(1), row.getString(2), UUID.fromString(row.getString(3)), row.getInt(4), row.getInt(5), row.getLong(6), row.getString(7)); }

    private ServiceOffer uniqueService(String prefix, String state, UUID provider) throws SQLException {
        String sql = "SELECT offer_id,provider_uuid,provider_name,service_type,price,client_uuid,state FROM service_offers WHERE offer_id LIKE ? AND state=?" + (provider == null ? "" : " AND provider_uuid=?") + " LIMIT 2";
        try (PreparedStatement query = database.prepareStatement(sql)) { query.setString(1, prefix + "%"); query.setString(2, state); if (provider != null) query.setString(3, provider.toString()); try (ResultSet rows = query.executeQuery()) { if (!rows.next()) throw new SQLException("Service not found"); ServiceOffer result = service(rows); if (rows.next()) throw new SQLException("Service prefix is ambiguous"); return result; } }
    }

    private ServiceOffer service(ResultSet row) throws SQLException { String client = row.getString(6); return new ServiceOffer(row.getString(1), UUID.fromString(row.getString(2)), row.getString(3), row.getString(4), row.getLong(5), client == null ? null : UUID.fromString(client), row.getString(7)); }

    private void requireRestaurant(UUID owner) throws SQLException { try (PreparedStatement query = database.prepareStatement("SELECT 1 FROM restaurants WHERE owner_uuid=?")) { query.setString(1, owner.toString()); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Restaurant not found"); } } }

    private UUID restaurantOwner(UUID player) throws SQLException {
        try (PreparedStatement owned = database.prepareStatement("SELECT owner_uuid FROM restaurants WHERE owner_uuid=?")) { owned.setString(1, player.toString()); try (ResultSet row = owned.executeQuery()) { if (row.next()) return player; } }
        try (PreparedStatement member = database.prepareStatement("SELECT owner_uuid FROM restaurant_members WHERE member_uuid=?")) { member.setString(1, player.toString()); try (ResultSet row = member.executeQuery()) { if (!row.next()) throw new SQLException("Restaurant membership required"); return UUID.fromString(row.getString(1)); } }
    }

    private String restaurantRole(UUID player, UUID owner) throws SQLException {
        if (player.equals(owner)) return "OWNER";
        try (PreparedStatement query = database.prepareStatement("SELECT role FROM restaurant_members WHERE member_uuid=? AND owner_uuid=?")) { query.setString(1, player.toString()); query.setString(2, owner.toString()); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Restaurant role required"); return row.getString(1); } }
    }

    private Optional<RestaurantOrder> restaurantOrderForSync(UUID player) throws SQLException {
        UUID owner = restaurantOwner(player); try (PreparedStatement query = database.prepareStatement("SELECT order_id,owner_uuid,state,crop_quality,protein_quality,extra_quality,supplier_uuid,chef_uuid,server_uuid,score,action_at FROM restaurant_orders WHERE owner_uuid=?")) { query.setString(1, owner.toString()); try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(order(row)) : Optional.empty(); } }
    }

    private RestaurantOrder restaurantOrder(String id) throws SQLException { try (PreparedStatement query = database.prepareStatement("SELECT order_id,owner_uuid,state,crop_quality,protein_quality,extra_quality,supplier_uuid,chef_uuid,server_uuid,score,action_at FROM restaurant_orders WHERE order_id=?")) { query.setString(1, id); try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Restaurant order not found"); return order(row); } } }

    private RestaurantOrder order(ResultSet row) throws SQLException {
        Integer crop = integerOrNull(row, 4), protein = integerOrNull(row, 5), extra = integerOrNull(row, 6);
        return new RestaurantOrder(row.getString(1), UUID.fromString(row.getString(2)), row.getString(3), crop, protein, extra, uuidOrNull(row.getString(7)), uuidOrNull(row.getString(8)), uuidOrNull(row.getString(9)), row.getInt(10), row.getLong(11));
    }

    private Integer integerOrNull(ResultSet row, int column) throws SQLException { int value = row.getInt(column); return row.wasNull() ? null : value; }
    private UUID uuidOrNull(String value) { return value == null ? null : UUID.fromString(value); }
    private int timingScore(long elapsed, long ideal) { long difference = Math.abs(elapsed - ideal); return difference <= 750 ? 5 : difference <= 1750 ? 3 : difference <= 4000 ? 1 : 0; }
    private boolean validName(String value, int limit) { return value != null && !value.isBlank() && value.length() <= limit && value.chars().noneMatch(Character::isISOControl); }
    SocialBalance socialBalance() { return socialBalance; }

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
    public record SocialIntent(String id, UUID player, String kind, String targetId, byte[] item, String itemId, int quantity, long unitPrice, int quality, String playerName, String state) {
        public SocialIntent(String id, UUID player, String kind, String targetId, byte[] item, String itemId, int quantity, long unitPrice, int quality, String playerName) { this(id, player, kind, targetId, item, itemId, quantity, unitPrice, quality, playerName, "PREPARED"); }
    }
    public record SocialCompletion(String kind, String targetId) {}
    public record ExchangeListing(String id, UUID seller, String sellerName, byte[] item, String itemId, int quantity, long unitPrice, int quality) { public String shortId() { return id.substring(0, 8); } }
    public record ExchangePurchase(ExchangeListing listing, long buyerBalance, long sellerBalance, String grantId) {}
    public record TradeStats(long recentPrice, long averagePrice) {}
    public record Stall(int slot, UUID owner, String ownerName) {}
    public record Guild(String id, String name, UUID owner, int logs, int iron, long money, String projectState) {}
    public record GuildItem(String id, byte[] item, String itemId, int quantity, int quality) { public String shortId() { return id.substring(0, 8); } }
    public record ServiceOffer(String id, UUID provider, String providerName, String type, long price, UUID client, String state) { public String shortId() { return id.substring(0, 8); } }
    public record Restaurant(UUID owner, String name, int ratingTotal, int servedCount, long revenue) {}
    public record RestaurantOrder(String id, UUID owner, String state, Integer cropQuality, Integer proteinQuality, Integer extraQuality, UUID supplier, UUID chef, UUID server, int score, long actionAt) {}
    public record RestaurantResult(int rating, long reward, long balance) {}
    public record PartySnapshot(String groupKey, List<UUID> members) {}
    static boolean allowsEndgameDamage(Set<UUID> members, UUID attacker, String sourceSession, String session, boolean victimPlayer) { return attacker != null && members.contains(attacker) || victimPlayer && session.equals(sourceSession); }
    public record EndgameSession(String id, UUID owner, String groupKey, String scope, String content, int slot, String stage, int progress, int aux, String state, Instant startedAt) {}
    public record EndgameIntent(String id, UUID player, String kind, byte[] item, String category, int quantity, String state) {
        public EndgameIntent(String id, UUID player, String kind, byte[] item, String category, int quantity) { this(id, player, kind, item, category, quantity, "PREPARED"); }
    }
    public record EndgameCompletion(String kind, Dragon dragon, GoodDeeds deeds) {}
    public record Dragon(UUID player, String stage, int feedTotal, int fish, int vegetable, int fruit, int meat, int mineral, int cooking, String trait) {}
    public record GoodDeeds(int delivery, int npcHelp, int donation, int escort, int publicProject) {
        public int total() { return delivery + npcHelp + Math.min(3, donation) + escort + publicProject; }
        public int categories() { int count=0; if(delivery>0)count++; if(npcHelp>0)count++; if(donation>0)count++; if(escort>0)count++; if(publicProject>0)count++; return count; }
        public boolean heavenUnlocked() { return total() >= 10 && categories() >= 3; }
    }
    public record TowerAdvance(int clearedFloor, int nextFloor, int progress, boolean completed) {}
    public record TowerRecord(String groupKey, String scope, int highestFloor, long bestMillis, int partySize) {}
    public record SocialBalance(int guildLogs, int guildIron, long guildMoney, long restaurantBaseReward, long restaurantQualityReward) {
        public SocialBalance { if (guildLogs < 1 || guildIron < 1 || guildMoney < 1 || restaurantBaseReward < 0 || restaurantQualityReward < 0) throw new IllegalArgumentException("Invalid social balance"); }
    }
}
