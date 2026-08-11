package kr.hyuni.marketplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HousingStore implements AutoCloseable {
    private final Connection database;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("marketplay-housing-db").daemon(true).unstarted(r));

    HousingStore(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        database = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = database.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_names (
                      player_uuid TEXT PRIMARY KEY,
                      player_name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                      updated_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_worlds (
                      owner_uuid TEXT PRIMARY KEY,
                      owner_name TEXT NOT NULL,
                      world_name TEXT NOT NULL UNIQUE,
                      level INTEGER NOT NULL CHECK (level BETWEEN 1 AND 5),
                      visibility TEXT NOT NULL CHECK (visibility IN ('private','invite','public')),
                      state TEXT NOT NULL CHECK (state IN ('CREATING','READY')),
                      created_at TEXT NOT NULL,
                      last_used_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_permissions (
                      owner_uuid TEXT NOT NULL,
                      subject_uuid TEXT NOT NULL,
                      flags INTEGER NOT NULL CHECK (flags BETWEEN 0 AND 127),
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (owner_uuid, subject_uuid)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_furniture (
                      owner_uuid TEXT NOT NULL,
                      world_name TEXT NOT NULL,
                      x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      type TEXT NOT NULL,
                      quality TEXT NOT NULL,
                      placed_by TEXT NOT NULL,
                      PRIMARY KEY (world_name, x, y, z)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_guestbook (
                      entry_id TEXT PRIMARY KEY,
                      owner_uuid TEXT NOT NULL,
                      author_uuid TEXT NOT NULL,
                      author_name TEXT NOT NULL,
                      body TEXT NOT NULL,
                      reported INTEGER NOT NULL DEFAULT 0 CHECK (reported IN (0,1)),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_blocks (
                      owner_uuid TEXT NOT NULL,
                      blocked_uuid TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      PRIMARY KEY (owner_uuid, blocked_uuid)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_mail (
                      mail_id TEXT PRIMARY KEY,
                      sender_uuid TEXT,
                      sender_name TEXT NOT NULL,
                      recipient_uuid TEXT NOT NULL,
                      kind TEXT NOT NULL CHECK (kind IN ('LETTER','GIFT','INVITE','NPC')),
                      body TEXT NOT NULL,
                      grant_id TEXT,
                      read_at TEXT,
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS housing_mail_recipient ON housing_mail(recipient_uuid, created_at)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_gift_intents (
                      intent_id TEXT PRIMARY KEY,
                      sender_uuid TEXT NOT NULL,
                      sender_name TEXT NOT NULL,
                      recipient_uuid TEXT NOT NULL,
                      body TEXT NOT NULL,
                      grant_id TEXT NOT NULL UNIQUE,
                      item BLOB NOT NULL,
                      state TEXT NOT NULL CHECK (state IN ('PREPARED','REMOVING','READY','CANCELLED')),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_gift_per_sender ON housing_gift_intents(sender_uuid) WHERE state IN ('PREPARED','REMOVING')");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_upgrade_intents (
                      intent_id TEXT PRIMARY KEY,
                      owner_uuid TEXT NOT NULL,
                      target_level INTEGER NOT NULL CHECK (target_level BETWEEN 2 AND 5),
                      state TEXT NOT NULL CHECK (state IN ('PREPARED','REMOVING','DONE','CANCELLED','REFUNDED')),
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_upgrade_items (
                      intent_id TEXT NOT NULL REFERENCES housing_upgrade_intents(intent_id) ON DELETE CASCADE,
                      grant_id TEXT NOT NULL UNIQUE,
                      item BLOB NOT NULL,
                      PRIMARY KEY (intent_id, grant_id)
                    )""");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_active_upgrade_per_owner ON housing_upgrade_intents(owner_uuid) WHERE state IN ('PREPARED','REMOVING')");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS housing_meals (
                      owner_uuid TEXT NOT NULL,
                      diner_uuid TEXT NOT NULL,
                      last_at TEXT NOT NULL,
                      PRIMARY KEY (owner_uuid, diner_uuid)
                    )""");
        }
    }

    CompletableFuture<Void> remember(UUID player, String name) {
        return run(() -> transaction(() -> {
            try (PreparedStatement stale = database.prepareStatement("DELETE FROM player_names WHERE player_name=? COLLATE NOCASE AND player_uuid<>?")) {
                stale.setString(1, name); stale.setString(2, player.toString()); stale.executeUpdate();
            }
            try (PreparedStatement statement = database.prepareStatement("INSERT INTO player_names VALUES (?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET player_name=excluded.player_name, updated_at=excluded.updated_at")) {
                statement.setString(1, player.toString()); statement.setString(2, name); statement.setString(3, Instant.now().toString()); statement.executeUpdate();
            }
            return null;
        }));
    }

    CompletableFuture<Optional<UUID>> playerId(String name) {
        return supply(() -> {
            try (PreparedStatement statement = database.prepareStatement("SELECT player_uuid FROM player_names WHERE player_name=? COLLATE NOCASE")) {
                statement.setString(1, name);
                try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(UUID.fromString(row.getString(1))) : Optional.empty(); }
            }
        });
    }

    CompletableFuture<House> ensureHouse(UUID owner, String ownerName) {
        return supply(() -> {
            String world = "mp_house_" + owner.toString().replace("-", "");
            Instant now = Instant.now();
            try (PreparedStatement insert = database.prepareStatement("INSERT OR IGNORE INTO housing_worlds VALUES (?, ?, ?, 1, 'private', 'CREATING', ?, ?)")) {
                insert.setString(1, owner.toString()); insert.setString(2, ownerName); insert.setString(3, world); insert.setString(4, now.toString()); insert.setString(5, now.toString()); insert.executeUpdate();
            }
            try (PreparedStatement update = database.prepareStatement("UPDATE housing_worlds SET owner_name=? WHERE owner_uuid=?")) {
                update.setString(1, ownerName); update.setString(2, owner.toString()); update.executeUpdate();
            }
            return house(owner).orElseThrow();
        });
    }

    CompletableFuture<Optional<House>> houseByOwner(UUID owner) { return supply(() -> house(owner)); }

    CompletableFuture<Optional<House>> houseByName(String ownerName) {
        return supply(() -> {
            try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_worlds WHERE owner_name=? COLLATE NOCASE")) {
                statement.setString(1, ownerName);
                try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(readHouse(row)) : Optional.empty(); }
            }
        });
    }

    CompletableFuture<List<House>> creatingHouses() {
        return supply(() -> {
            List<House> result = new ArrayList<>();
            try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_worlds WHERE state='CREATING'" ); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readHouse(rows));
            }
            return result;
        });
    }

    CompletableFuture<Void> markReady(UUID owner) { return update("UPDATE housing_worlds SET state='READY', last_used_at=? WHERE owner_uuid=?", owner); }
    CompletableFuture<Void> touch(UUID owner) { return update("UPDATE housing_worlds SET last_used_at=? WHERE owner_uuid=?", owner); }

    CompletableFuture<House> visibility(UUID owner, String visibility) {
        if (!List.of("private", "invite", "public").contains(visibility)) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid visibility"));
        return supply(() -> {
            try (PreparedStatement statement = database.prepareStatement("UPDATE housing_worlds SET visibility=?, last_used_at=? WHERE owner_uuid=?")) {
                statement.setString(1, visibility); statement.setString(2, Instant.now().toString()); statement.setString(3, owner.toString());
                if (statement.executeUpdate() != 1) throw new SQLException("House not found");
            }
            return house(owner).orElseThrow();
        });
    }

    CompletableFuture<Map<UUID, Integer>> permissions(UUID owner) {
        return supply(() -> {
            Map<UUID, Integer> result = new LinkedHashMap<>();
            try (PreparedStatement statement = database.prepareStatement("SELECT subject_uuid, flags FROM housing_permissions WHERE owner_uuid=?")) {
                statement.setString(1, owner.toString());
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.put(UUID.fromString(rows.getString(1)), rows.getInt(2)); }
            }
            return result;
        });
    }

    CompletableFuture<Integer> permission(UUID owner, UUID subject, HousePermission permission, boolean enabled) {
        return supply(() -> transaction(() -> {
            int flags = 0;
            try (PreparedStatement query = database.prepareStatement("SELECT flags FROM housing_permissions WHERE owner_uuid=? AND subject_uuid=?")) {
                query.setString(1, owner.toString()); query.setString(2, subject.toString());
                try (ResultSet row = query.executeQuery()) { if (row.next()) flags = row.getInt(1); }
            }
            flags = enabled ? flags | permission.bit : flags & ~permission.bit;
            try (PreparedStatement statement = database.prepareStatement("INSERT INTO housing_permissions VALUES (?, ?, ?, ?) ON CONFLICT(owner_uuid, subject_uuid) DO UPDATE SET flags=excluded.flags, updated_at=excluded.updated_at")) {
                statement.setString(1, owner.toString()); statement.setString(2, subject.toString()); statement.setInt(3, flags); statement.setString(4, Instant.now().toString()); statement.executeUpdate();
            }
            return flags;
        }));
    }

    CompletableFuture<UpgradeResult> upgrade(PlayerProfile profile, int expectedLevel, long price, long innerPower, long carpentryXp, int furnitureTypes, String requestId) {
        return supply(() -> transaction(() -> {
            try (PreparedStatement existing = database.prepareStatement("SELECT balance_after FROM economy_transactions WHERE idempotency_key=?")) {
                existing.setString(1, "house-upgrade:" + requestId);
                try (ResultSet row = existing.executeQuery()) { if (row.next()) return new UpgradeResult(expectedLevel + 1, row.getLong(1)); }
            }
            House current = house(profile.playerId()).orElseThrow();
            if (current.level() != expectedLevel || expectedLevel >= 5) throw new IllegalArgumentException("Invalid house level");
            if (profile.innerPower() < innerPower || profile.experience(Skill.CARPENTRY) < carpentryXp) throw new IllegalArgumentException("Requirements not met");
            try (PreparedStatement catalog = database.prepareStatement("SELECT COUNT(DISTINCT type) FROM housing_furniture WHERE owner_uuid=?")) {
                catalog.setString(1, profile.playerId().toString());
                try (ResultSet row = catalog.executeQuery()) { if (!row.next() || row.getInt(1) < furnitureTypes) throw new IllegalArgumentException("Furniture catalog too small"); }
            }
            long balance = Math.subtractExact(profile.money(), price);
            if (balance < 0) throw new IllegalArgumentException("Insufficient balance");
            try (PreparedStatement wallet = database.prepareStatement("UPDATE players SET money=?, updated_at=? WHERE uuid=? AND money=?")) {
                wallet.setLong(1, balance); wallet.setString(2, Instant.now().toString()); wallet.setString(3, profile.playerId().toString()); wallet.setLong(4, profile.money());
                if (wallet.executeUpdate() != 1) throw new SQLException("Wallet changed concurrently");
            }
            try (PreparedStatement house = database.prepareStatement("UPDATE housing_worlds SET level=level+1, state='CREATING', last_used_at=? WHERE owner_uuid=? AND level=?")) {
                house.setString(1, Instant.now().toString()); house.setString(2, profile.playerId().toString()); house.setInt(3, expectedLevel);
                if (house.executeUpdate() != 1) throw new SQLException("House level changed concurrently");
            }
            try (PreparedStatement log = database.prepareStatement("INSERT INTO economy_transactions VALUES (?, ?, ?, 'HOUSE_UPGRADE', ?, 1, ?, ?, ?, ?)")) {
                log.setString(1, UUID.randomUUID().toString()); log.setString(2, "house-upgrade:" + requestId); log.setString(3, profile.playerId().toString()); log.setString(4, "house:" + (expectedLevel + 1)); log.setLong(5, price); log.setLong(6, price); log.setLong(7, balance); log.setString(8, Instant.now().toString()); log.executeUpdate();
            }
            return new UpgradeResult(expectedLevel + 1, balance);
        }));
    }

    CompletableFuture<UpgradeIntent> prepareUpgrade(UUID owner, int targetLevel, String intentId, List<RefundItem> refunds) {
        return supply(() -> transaction(() -> {
            if (house(owner).orElseThrow().level() != targetLevel - 1) throw new IllegalArgumentException("House level changed");
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO housing_upgrade_intents VALUES (?, ?, ?, 'PREPARED', ?)")) {
                insert.setString(1, intentId); insert.setString(2, owner.toString()); insert.setInt(3, targetLevel); insert.setString(4, Instant.now().toString()); insert.executeUpdate();
            }
            try (PreparedStatement item = database.prepareStatement("INSERT INTO housing_upgrade_items VALUES (?, ?, ?)")) {
                for (RefundItem refund : refunds) { item.setString(1, intentId); item.setString(2, refund.grantId()); item.setBytes(3, refund.item()); item.addBatch(); }
                item.executeBatch();
            }
            return new UpgradeIntent(intentId, owner, targetLevel, "PREPARED");
        }));
    }

    CompletableFuture<Void> markUpgradeRemoving(String intentId) {
        return run(() -> {
            try (PreparedStatement statement = database.prepareStatement("UPDATE housing_upgrade_intents SET state='REMOVING' WHERE intent_id=? AND state='PREPARED'")) {
                statement.setString(1, intentId);
                if (statement.executeUpdate() != 1) throw new SQLException("Upgrade state changed");
            }
        });
    }

    CompletableFuture<Void> finishUpgrade(String intentId) {
        return run(() -> {
            try (PreparedStatement statement = database.prepareStatement("UPDATE housing_upgrade_intents SET state='DONE' WHERE intent_id=? AND state='REMOVING'")) {
                statement.setString(1, intentId);
                if (statement.executeUpdate() != 1) throw new SQLException("Upgrade completion raced");
            }
        });
    }

    CompletableFuture<Void> cancelUpgrade(String intentId) {
        return run(() -> {
            try (PreparedStatement statement = database.prepareStatement("UPDATE housing_upgrade_intents SET state='CANCELLED' WHERE intent_id=? AND state='PREPARED'")) {
                statement.setString(1, intentId); statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> refundUpgrade(String intentId) {
        return run(() -> transaction(() -> {
            UpgradeIntent intent = upgradeIntent(intentId).orElseThrow();
            if (intent.state().equals("REFUNDED")) return null;
            try (PreparedStatement grants = database.prepareStatement("INSERT OR IGNORE INTO item_grants SELECT grant_id, ?, item, 0, ? FROM housing_upgrade_items WHERE intent_id=?")) {
                grants.setString(1, intent.owner().toString()); grants.setString(2, Instant.now().toString()); grants.setString(3, intentId); grants.executeUpdate();
            }
            try (PreparedStatement update = database.prepareStatement("UPDATE housing_upgrade_intents SET state='REFUNDED' WHERE intent_id=? AND state IN ('PREPARED','REMOVING')")) {
                update.setString(1, intentId); update.executeUpdate();
            }
            return null;
        }));
    }

    CompletableFuture<Optional<UpgradeIntent>> activeUpgrade(UUID owner) {
        return supply(() -> {
            try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_upgrade_intents WHERE owner_uuid=? AND state IN ('PREPARED','REMOVING') ORDER BY created_at LIMIT 1")) {
                statement.setString(1, owner.toString());
                try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(readUpgrade(row)) : Optional.empty(); }
            }
        });
    }

    CompletableFuture<Void> putFurniture(UUID owner, String world, int x, int y, int z, String type, String quality, UUID placedBy) {
        return run(() -> {
            try (PreparedStatement statement = database.prepareStatement("INSERT OR REPLACE INTO housing_furniture VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, owner.toString()); statement.setString(2, world); statement.setInt(3, x); statement.setInt(4, y); statement.setInt(5, z); statement.setString(6, type); statement.setString(7, quality); statement.setString(8, placedBy.toString()); statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Boolean> claimMeal(UUID owner, UUID diner, Instant now, java.time.Duration cooldown) {
        return supply(() -> transaction(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT last_at FROM housing_meals WHERE owner_uuid=? AND diner_uuid=?")) {
                query.setString(1, owner.toString()); query.setString(2, diner.toString());
                try (ResultSet row = query.executeQuery()) { if (row.next() && Instant.parse(row.getString(1)).plus(cooldown).isAfter(now)) return false; }
            }
            try (PreparedStatement update = database.prepareStatement("INSERT INTO housing_meals VALUES (?, ?, ?) ON CONFLICT(owner_uuid,diner_uuid) DO UPDATE SET last_at=excluded.last_at")) {
                update.setString(1, owner.toString()); update.setString(2, diner.toString()); update.setString(3, now.toString()); update.executeUpdate();
            }
            return true;
        }));
    }

    CompletableFuture<Void> removeFurniture(String world, int x, int y, int z) {
        return run(() -> {
            try (PreparedStatement statement = database.prepareStatement("DELETE FROM housing_furniture WHERE world_name=? AND x=? AND y=? AND z=?")) {
                statement.setString(1, world); statement.setInt(2, x); statement.setInt(3, y); statement.setInt(4, z); statement.executeUpdate();
            }
        });
    }

    CompletableFuture<List<Furniture>> furniture(UUID owner) {
        return supply(() -> {
            List<Furniture> result = new ArrayList<>();
            try (PreparedStatement statement = database.prepareStatement("SELECT world_name,x,y,z,type,quality FROM housing_furniture WHERE owner_uuid=?")) {
                statement.setString(1, owner.toString());
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(new Furniture(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getInt(4), rows.getString(5), rows.getString(6))); }
            }
            return result;
        });
    }

    CompletableFuture<GuestbookEntry> writeGuestbook(UUID owner, UUID author, String authorName, String body) {
        if (body.isBlank() || body.length() > 60 || body.chars().anyMatch(Character::isISOControl)) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid body"));
        return supply(() -> {
            try (PreparedStatement blocked = database.prepareStatement("SELECT 1 FROM housing_blocks WHERE owner_uuid=? AND blocked_uuid=?")) {
                blocked.setString(1, owner.toString()); blocked.setString(2, author.toString());
                try (ResultSet row = blocked.executeQuery()) { if (row.next()) throw new IllegalArgumentException("Blocked"); }
            }
            GuestbookEntry entry = new GuestbookEntry(UUID.randomUUID().toString(), owner, author, authorName, body, false, Instant.now());
            try (PreparedStatement statement = database.prepareStatement("INSERT INTO housing_guestbook VALUES (?, ?, ?, ?, ?, 0, ?)")) {
                statement.setString(1, entry.id()); statement.setString(2, owner.toString()); statement.setString(3, author.toString()); statement.setString(4, authorName); statement.setString(5, body); statement.setString(6, entry.createdAt().toString()); statement.executeUpdate();
            }
            return entry;
        });
    }

    CompletableFuture<List<GuestbookEntry>> guestbook(UUID owner) {
        return supply(() -> {
            List<GuestbookEntry> result = new ArrayList<>();
            try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_guestbook WHERE owner_uuid=? ORDER BY created_at DESC LIMIT 20")) {
                statement.setString(1, owner.toString());
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(readGuestbook(rows)); }
            }
            return result;
        });
    }

    CompletableFuture<Boolean> deleteGuestbook(UUID owner, String prefix) { return guestbookAction(owner, prefix, "DELETE FROM housing_guestbook WHERE entry_id=?"); }
    CompletableFuture<Boolean> reportGuestbook(UUID owner, String prefix) { return guestbookAction(owner, prefix, "UPDATE housing_guestbook SET reported=1 WHERE entry_id=?"); }

    CompletableFuture<Void> blockGuest(UUID owner, UUID blocked) {
        return run(() -> {
            try (PreparedStatement statement = database.prepareStatement("INSERT OR IGNORE INTO housing_blocks VALUES (?, ?, ?)")) {
                statement.setString(1, owner.toString()); statement.setString(2, blocked.toString()); statement.setString(3, Instant.now().toString()); statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Mail> sendMail(UUID sender, String senderName, UUID recipient, String kind, String body, String grantId) {
        if (body.isBlank() || body.length() > 120 || body.chars().anyMatch(Character::isISOControl)) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid mail"));
        return supply(() -> {
            Mail mail = new Mail(UUID.randomUUID().toString(), sender, senderName, recipient, kind, body, grantId, false, Instant.now());
            try (PreparedStatement statement = database.prepareStatement("INSERT INTO housing_mail VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)")) {
                statement.setString(1, mail.id()); statement.setString(2, sender == null ? null : sender.toString()); statement.setString(3, senderName); statement.setString(4, recipient.toString()); statement.setString(5, kind); statement.setString(6, body); statement.setString(7, grantId); statement.setString(8, mail.createdAt().toString()); statement.executeUpdate();
            }
            return mail;
        });
    }

    CompletableFuture<GiftIntent> prepareGift(UUID sender, String senderName, UUID recipient, String body, String grantId, byte[] item) {
        if (body.isBlank() || body.length() > 120 || body.chars().anyMatch(Character::isISOControl)) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid gift"));
        return supply(() -> {
            GiftIntent intent = new GiftIntent(UUID.randomUUID().toString(), sender, senderName, recipient, body, grantId, item, "PREPARED");
            try (PreparedStatement statement = database.prepareStatement("INSERT INTO housing_gift_intents VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)")) {
                statement.setString(1, intent.id()); statement.setString(2, sender.toString()); statement.setString(3, senderName); statement.setString(4, recipient.toString()); statement.setString(5, body); statement.setString(6, grantId); statement.setBytes(7, item); statement.setString(8, Instant.now().toString()); statement.executeUpdate();
            }
            return intent;
        });
    }

    CompletableFuture<Void> markGiftRemoving(String intentId) {
        return run(() -> {
            try (PreparedStatement statement = database.prepareStatement("UPDATE housing_gift_intents SET state='REMOVING' WHERE intent_id=? AND state='PREPARED'")) {
                statement.setString(1, intentId);
                if (statement.executeUpdate() != 1) throw new SQLException("Gift state changed");
            }
        });
    }

    CompletableFuture<Mail> completeGift(String intentId) {
        return supply(() -> transaction(() -> {
            GiftIntent intent = gift(intentId).orElseThrow();
            String mailId = "gift-" + intent.id();
            if (intent.state().equals("READY")) return mailById(mailId).orElseThrow();
            if (!intent.state().equals("REMOVING")) throw new SQLException("Gift is not removing");
            Instant now = Instant.now();
            try (PreparedStatement grant = database.prepareStatement("INSERT OR IGNORE INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
                grant.setString(1, intent.grantId()); grant.setString(2, intent.recipient().toString()); grant.setBytes(3, intent.item()); grant.setString(4, now.toString()); grant.executeUpdate();
            }
            try (PreparedStatement mail = database.prepareStatement("INSERT OR IGNORE INTO housing_mail VALUES (?, ?, ?, ?, 'GIFT', ?, ?, NULL, ?)")) {
                mail.setString(1, mailId); mail.setString(2, intent.sender().toString()); mail.setString(3, intent.senderName()); mail.setString(4, intent.recipient().toString()); mail.setString(5, intent.body()); mail.setString(6, intent.grantId()); mail.setString(7, now.toString()); mail.executeUpdate();
            }
            try (PreparedStatement update = database.prepareStatement("UPDATE housing_gift_intents SET state='READY' WHERE intent_id=? AND state='REMOVING'")) {
                update.setString(1, intent.id());
                if (update.executeUpdate() != 1) throw new SQLException("Gift completion raced");
            }
            return mailById(mailId).orElseThrow();
        }));
    }

    CompletableFuture<Void> cancelGift(String intentId) {
        return run(() -> {
            try (PreparedStatement statement = database.prepareStatement("UPDATE housing_gift_intents SET state='CANCELLED' WHERE intent_id=? AND state='PREPARED'")) {
                statement.setString(1, intentId); statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Optional<GiftIntent>> activeGift(UUID sender) {
        return supply(() -> {
            try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_gift_intents WHERE sender_uuid=? AND state IN ('PREPARED','REMOVING') ORDER BY created_at LIMIT 1")) {
                statement.setString(1, sender.toString());
                try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(readGift(row)) : Optional.empty(); }
            }
        });
    }

    CompletableFuture<List<Mail>> mail(UUID recipient) {
        return supply(() -> {
            List<Mail> result = new ArrayList<>();
            try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_mail WHERE recipient_uuid=? ORDER BY created_at DESC LIMIT 30")) {
                statement.setString(1, recipient.toString());
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(readMail(rows)); }
            }
            return result;
        });
    }

    CompletableFuture<Boolean> readMail(UUID recipient, String prefix) {
        return supply(() -> {
            List<String> matches = new ArrayList<>();
            try (PreparedStatement query = database.prepareStatement("SELECT mail_id FROM housing_mail WHERE recipient_uuid=? AND mail_id LIKE ? LIMIT 2")) {
                query.setString(1, recipient.toString()); query.setString(2, prefix.replace("%", "\\%").replace("_", "\\_") + "%");
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) matches.add(rows.getString(1)); }
            }
            if (matches.size() != 1) return false;
            try (PreparedStatement update = database.prepareStatement("UPDATE housing_mail SET read_at=? WHERE mail_id=?")) {
                update.setString(1, Instant.now().toString()); update.setString(2, matches.getFirst()); return update.executeUpdate() == 1;
            }
        });
    }

    private Optional<House> house(UUID owner) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_worlds WHERE owner_uuid=?")) {
            statement.setString(1, owner.toString());
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(readHouse(row)) : Optional.empty(); }
        }
    }

    private Optional<GiftIntent> gift(String id) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_gift_intents WHERE intent_id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(readGift(row)) : Optional.empty(); }
        }
    }

    private Optional<UpgradeIntent> upgradeIntent(String id) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_upgrade_intents WHERE intent_id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(readUpgrade(row)) : Optional.empty(); }
        }
    }

    private Optional<Mail> mailById(String id) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement("SELECT * FROM housing_mail WHERE mail_id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(readMail(row)) : Optional.empty(); }
        }
    }

    private CompletableFuture<Boolean> guestbookAction(UUID owner, String prefix, String sql) {
        return supply(() -> {
            List<String> matches = new ArrayList<>();
            try (PreparedStatement query = database.prepareStatement("SELECT entry_id FROM housing_guestbook WHERE owner_uuid=? AND entry_id LIKE ? LIMIT 2")) {
                query.setString(1, owner.toString()); query.setString(2, prefix.replace("%", "\\%").replace("_", "\\_") + "%");
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) matches.add(rows.getString(1)); }
            }
            if (matches.size() != 1) return false;
            try (PreparedStatement action = database.prepareStatement(sql)) { action.setString(1, matches.getFirst()); return action.executeUpdate() == 1; }
        });
    }

    private CompletableFuture<Void> update(String sql, UUID owner) {
        return run(() -> { try (PreparedStatement statement = database.prepareStatement(sql)) { statement.setString(1, Instant.now().toString()); statement.setString(2, owner.toString()); statement.executeUpdate(); } });
    }

    private House readHouse(ResultSet row) throws SQLException { return new House(UUID.fromString(row.getString("owner_uuid")), row.getString("owner_name"), row.getString("world_name"), row.getInt("level"), row.getString("visibility"), row.getString("state"), Instant.parse(row.getString("last_used_at"))); }
    private GuestbookEntry readGuestbook(ResultSet row) throws SQLException { return new GuestbookEntry(row.getString("entry_id"), UUID.fromString(row.getString("owner_uuid")), UUID.fromString(row.getString("author_uuid")), row.getString("author_name"), row.getString("body"), row.getInt("reported") == 1, Instant.parse(row.getString("created_at"))); }
    private Mail readMail(ResultSet row) throws SQLException { String sender = row.getString("sender_uuid"); return new Mail(row.getString("mail_id"), sender == null ? null : UUID.fromString(sender), row.getString("sender_name"), UUID.fromString(row.getString("recipient_uuid")), row.getString("kind"), row.getString("body"), row.getString("grant_id"), row.getString("read_at") != null, Instant.parse(row.getString("created_at"))); }
    private GiftIntent readGift(ResultSet row) throws SQLException { return new GiftIntent(row.getString("intent_id"), UUID.fromString(row.getString("sender_uuid")), row.getString("sender_name"), UUID.fromString(row.getString("recipient_uuid")), row.getString("body"), row.getString("grant_id"), row.getBytes("item"), row.getString("state")); }
    private UpgradeIntent readUpgrade(ResultSet row) throws SQLException { return new UpgradeIntent(row.getString("intent_id"), UUID.fromString(row.getString("owner_uuid")), row.getInt("target_level"), row.getString("state")); }

    private <T> CompletableFuture<T> supply(SqlSupplier<T> work) { return CompletableFuture.supplyAsync(() -> { try { return work.get(); } catch (Exception error) { throw new RuntimeException(error); } }, writer); }
    private CompletableFuture<Void> run(SqlRunnable work) { return CompletableFuture.runAsync(() -> { try { work.run(); } catch (Exception error) { throw new RuntimeException(error); } }, writer); }
    private <T> T transaction(SqlSupplier<T> work) throws Exception { boolean auto = database.getAutoCommit(); database.setAutoCommit(false); try { T result = work.get(); database.commit(); return result; } catch (Exception error) { database.rollback(); throw error; } finally { database.setAutoCommit(auto); } }
    @Override public void close() throws Exception { writer.shutdown(); if (!writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) throw new SQLException("Housing DB did not stop"); database.close(); }

    record House(UUID owner, String ownerName, String worldName, int level, String visibility, String state, Instant lastUsedAt) {}
    record UpgradeResult(int level, long balance) {}
    record Furniture(String world, int x, int y, int z, String type, String quality) { String key() { return world + ':' + x + ':' + y + ':' + z; } }
    record GuestbookEntry(String id, UUID owner, UUID author, String authorName, String body, boolean reported, Instant createdAt) { String shortId() { return id.substring(0, 8); } }
    record Mail(String id, UUID sender, String senderName, UUID recipient, String kind, String body, String grantId, boolean read, Instant createdAt) { String shortId() { return id.substring(0, 8); } }
    record GiftIntent(String id, UUID sender, String senderName, UUID recipient, String body, String grantId, byte[] item, String state) {}
    record UpgradeIntent(String id, UUID owner, int targetLevel, String state) {}
    record RefundItem(String grantId, byte[] item) {}
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
    @FunctionalInterface private interface SqlRunnable { void run() throws Exception; }
}
