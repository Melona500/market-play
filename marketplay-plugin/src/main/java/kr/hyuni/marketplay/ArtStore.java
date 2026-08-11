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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArtStore implements AutoCloseable {
    private final Connection database;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("marketplay-art-db").unstarted(r));

    ArtStore(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        database = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = database.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS artworks (
                      artwork_id TEXT PRIMARY KEY,
                      author_uuid TEXT NOT NULL,
                      author_name TEXT NOT NULL,
                      title TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      width INTEGER NOT NULL CHECK (width > 0),
                      height INTEGER NOT NULL CHECK (height > 0),
                      pixels BLOB NOT NULL,
                      map_id INTEGER NOT NULL UNIQUE,
                      state TEXT NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED'))
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS artwork_ownership (
                      artwork_id TEXT PRIMARY KEY REFERENCES artworks(artwork_id) ON DELETE CASCADE,
                      owner_uuid TEXT NOT NULL,
                      price INTEGER CHECK (price > 0),
                      updated_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS artwork_exhibits (
                      slot INTEGER PRIMARY KEY CHECK (slot BETWEEN 0 AND 7),
                      artwork_id TEXT NOT NULL UNIQUE REFERENCES artworks(artwork_id) ON DELETE CASCADE,
                      updated_at TEXT NOT NULL
                    )""");
        }
    }

    CompletableFuture<Artwork> create(UUID author, String authorName, String title, int mapId) {
        return supply(() -> transaction(() -> {
            Artwork artwork = new Artwork(UUID.randomUUID().toString(), author, authorName, title, Instant.now(), 9, 5, new byte[45], mapId, "DRAFT", author, null);
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO artworks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement owner = database.prepareStatement("INSERT INTO artwork_ownership VALUES (?, ?, NULL, ?)")) {
                insert.setString(1, artwork.id()); insert.setString(2, author.toString()); insert.setString(3, authorName); insert.setString(4, title);
                insert.setString(5, artwork.createdAt().toString()); insert.setInt(6, artwork.width()); insert.setInt(7, artwork.height());
                insert.setBytes(8, artwork.pixels()); insert.setInt(9, mapId); insert.setString(10, artwork.state()); insert.executeUpdate();
                owner.setString(1, artwork.id()); owner.setString(2, author.toString()); owner.setString(3, artwork.createdAt().toString()); owner.executeUpdate();
            }
            return artwork;
        }));
    }

    CompletableFuture<List<Artwork>> all() { return supply(this::allSync); }

    CompletableFuture<Artwork> remap(String id, int mapId) {
        return supply(() -> transaction(() -> {
            try (PreparedStatement update = database.prepareStatement("UPDATE artworks SET map_id=? WHERE artwork_id=?")) {
                update.setInt(1, mapId); update.setString(2, id);
                if (update.executeUpdate() != 1) throw new SQLException("Artwork missing");
            }
            return artwork(id).orElseThrow();
        }));
    }

    CompletableFuture<Void> saveDraft(String id, UUID owner, byte[] pixels) {
        return run(() -> {
            try (PreparedStatement update = database.prepareStatement("""
                    UPDATE artworks SET pixels=? WHERE artwork_id=? AND state='DRAFT'
                    AND EXISTS (SELECT 1 FROM artwork_ownership WHERE artwork_id=? AND owner_uuid=?)""")) {
                update.setBytes(1, pixels); update.setString(2, id); update.setString(3, id); update.setString(4, owner.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Draft ownership changed");
            }
        });
    }

    CompletableFuture<Artwork> publish(String id, UUID owner) {
        return supply(() -> transaction(() -> {
            try (PreparedStatement update = database.prepareStatement("""
                    UPDATE artworks SET state='PUBLISHED' WHERE artwork_id=? AND state='DRAFT'
                    AND EXISTS (SELECT 1 FROM artwork_ownership WHERE artwork_id=? AND owner_uuid=?)""")) {
                update.setString(1, id); update.setString(2, id); update.setString(3, owner.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Draft not publishable");
            }
            return artwork(id).orElseThrow();
        }));
    }

    CompletableFuture<Void> listForSale(String id, UUID owner, long price) {
        return run(() -> {
            if (price <= 0) throw new IllegalArgumentException("Price must be positive");
            try (PreparedStatement update = database.prepareStatement("""
                    UPDATE artwork_ownership SET price=?, updated_at=? WHERE artwork_id=? AND owner_uuid=?
                    AND EXISTS (SELECT 1 FROM artworks WHERE artwork_id=? AND state='PUBLISHED')""")) {
                update.setLong(1, price); update.setString(2, Instant.now().toString()); update.setString(3, id); update.setString(4, owner.toString()); update.setString(5, id);
                if (update.executeUpdate() != 1) throw new SQLException("Artwork not listable");
            }
        });
    }

    CompletableFuture<Artwork> gift(String id, UUID sender, String senderName, UUID recipient, String grantId, byte[] item) {
        return supply(() -> transaction(() -> {
            Artwork artwork = artwork(id).orElseThrow();
            if (!artwork.state().equals("PUBLISHED") || !artwork.owner().equals(sender) || sender.equals(recipient)) throw new SQLException("Artwork not giftable");
            Instant now = Instant.now();
            transfer(id, sender, recipient, now);
            grant(grantId, recipient, item, now);
            try (PreparedStatement mail = database.prepareStatement("INSERT INTO housing_mail VALUES (?, ?, ?, ?, 'GIFT', ?, ?, NULL, ?)")) {
                mail.setString(1, "art-gift-" + grantId); mail.setString(2, sender.toString()); mail.setString(3, senderName); mail.setString(4, recipient.toString());
                mail.setString(5, "작품 선물: " + artwork.title()); mail.setString(6, grantId); mail.setString(7, now.toString()); mail.executeUpdate();
            }
            return withOwner(artwork, recipient, null);
        }));
    }

    CompletableFuture<BuyResult> buy(String id, UUID buyer, String grantId, byte[] item) {
        return supply(() -> transaction(() -> {
            Artwork artwork = artwork(id).orElseThrow();
            if (!artwork.state().equals("PUBLISHED") || artwork.price() == null || artwork.owner().equals(buyer)) throw new SQLException("Artwork not purchasable");
            long buyerMoney = balance(buyer), sellerMoney = balance(artwork.owner()), price = artwork.price();
            if (buyerMoney < price) throw new SQLException("Insufficient funds");
            long buyerAfter = buyerMoney - price, sellerAfter = Math.addExact(sellerMoney, price);
            updateBalance(buyer, buyerMoney, buyerAfter);
            updateBalance(artwork.owner(), sellerMoney, sellerAfter);
            Instant now = Instant.now();
            transfer(id, artwork.owner(), buyer, now);
            grant(grantId, buyer, item, now);
            economy(grantId + "-buyer", buyer, "ART_BUY", id, price, buyerAfter, now);
            economy(grantId + "-seller", artwork.owner(), "ART_SELL", id, price, sellerAfter, now);
            return new BuyResult(withOwner(artwork, buyer, null), buyerAfter, artwork.owner(), sellerAfter);
        }));
    }

    CompletableFuture<Integer> exhibit(String id, UUID owner) {
        return supply(() -> transaction(() -> {
            Artwork artwork = artwork(id).orElseThrow();
            if (!artwork.owner().equals(owner) || !artwork.state().equals("PUBLISHED")) throw new SQLException("Artwork not exhibitable");
            try (PreparedStatement existing = database.prepareStatement("SELECT slot FROM artwork_exhibits WHERE artwork_id=?")) {
                existing.setString(1, id);
                try (ResultSet row = existing.executeQuery()) { if (row.next()) return row.getInt(1); }
            }
            int slot = 0;
            try (PreparedStatement used = database.prepareStatement("SELECT slot FROM artwork_exhibits ORDER BY slot"); ResultSet rows = used.executeQuery()) {
                while (rows.next()) { int current = rows.getInt(1); if (current == slot) slot++; else if (current > slot) break; }
            }
            if (slot > 7) throw new SQLException("Gallery full");
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO artwork_exhibits VALUES (?, ?, ?)")) {
                insert.setInt(1, slot); insert.setString(2, id); insert.setString(3, Instant.now().toString()); insert.executeUpdate();
            }
            return slot;
        }));
    }

    CompletableFuture<List<Exhibit>> exhibits() {
        return supply(() -> {
            ArrayList<Exhibit> result = new ArrayList<>();
            try (PreparedStatement query = database.prepareStatement("SELECT slot, artwork_id FROM artwork_exhibits ORDER BY slot"); ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new Exhibit(rows.getInt(1), rows.getString(2)));
            }
            return result;
        });
    }

    private List<Artwork> allSync() throws SQLException {
        ArrayList<Artwork> result = new ArrayList<>();
        try (PreparedStatement query = database.prepareStatement("""
                SELECT a.*, o.owner_uuid, o.price FROM artworks a JOIN artwork_ownership o USING(artwork_id) ORDER BY a.created_at"""); ResultSet rows = query.executeQuery()) {
            while (rows.next()) result.add(read(rows));
        }
        return result;
    }

    private Optional<Artwork> artwork(String id) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT a.*, o.owner_uuid, o.price FROM artworks a JOIN artwork_ownership o USING(artwork_id) WHERE artwork_id=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        }
    }

    private Artwork read(ResultSet row) throws SQLException {
        long price = row.getLong("price");
        boolean unlisted = row.wasNull();
        return new Artwork(row.getString("artwork_id"), UUID.fromString(row.getString("author_uuid")), row.getString("author_name"), row.getString("title"),
                Instant.parse(row.getString("created_at")), row.getInt("width"), row.getInt("height"), row.getBytes("pixels"), row.getInt("map_id"), row.getString("state"),
                UUID.fromString(row.getString("owner_uuid")), unlisted ? null : price);
    }

    private void transfer(String id, UUID from, UUID to, Instant now) throws SQLException {
        try (PreparedStatement update = database.prepareStatement("UPDATE artwork_ownership SET owner_uuid=?, price=NULL, updated_at=? WHERE artwork_id=? AND owner_uuid=?")) {
            update.setString(1, to.toString()); update.setString(2, now.toString()); update.setString(3, id); update.setString(4, from.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Artwork ownership changed");
        }
    }

    private void grant(String id, UUID player, byte[] item, Instant now) throws SQLException {
        try (PreparedStatement grant = database.prepareStatement("INSERT INTO item_grants VALUES (?, ?, ?, 0, ?)")) {
            grant.setString(1, id); grant.setString(2, player.toString()); grant.setBytes(3, item); grant.setString(4, now.toString()); grant.executeUpdate();
        }
    }

    private long balance(UUID player) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT money FROM players WHERE uuid=?")) {
            query.setString(1, player.toString());
            try (ResultSet row = query.executeQuery()) { if (row.next()) return row.getLong(1); }
        }
        throw new SQLException("Player profile missing");
    }

    private void updateBalance(UUID player, long expected, long value) throws SQLException {
        try (PreparedStatement update = database.prepareStatement("UPDATE players SET money=?, updated_at=? WHERE uuid=? AND money=?")) {
            update.setLong(1, value); update.setString(2, Instant.now().toString()); update.setString(3, player.toString()); update.setLong(4, expected);
            if (update.executeUpdate() != 1) throw new SQLException("Concurrent balance change");
        }
    }

    private void economy(String id, UUID player, String type, String artwork, long price, long balance, Instant now) throws SQLException {
        try (PreparedStatement insert = database.prepareStatement("INSERT INTO economy_transactions VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, id); insert.setString(3, player.toString()); insert.setString(4, type);
            insert.setString(5, "art:" + artwork); insert.setLong(6, price); insert.setLong(7, price); insert.setLong(8, balance); insert.setString(9, now.toString()); insert.executeUpdate();
        }
    }

    private Artwork withOwner(Artwork artwork, UUID owner, Long price) {
        return new Artwork(artwork.id(), artwork.author(), artwork.authorName(), artwork.title(), artwork.createdAt(), artwork.width(), artwork.height(), artwork.pixels(), artwork.mapId(), artwork.state(), owner, price);
    }

    private <T> CompletableFuture<T> supply(SqlSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> { try { return action.get(); } catch (Exception error) { throw new RuntimeException(error); } }, writer);
    }

    private CompletableFuture<Void> run(SqlRunnable action) { return supply(() -> { action.run(); return null; }); }

    private <T> T transaction(SqlSupplier<T> action) throws Exception {
        database.setAutoCommit(false);
        try { T result = action.get(); database.commit(); return result; }
        catch (Exception error) { database.rollback(); throw error; }
        finally { database.setAutoCommit(true); }
    }

    @Override public void close() throws Exception { writer.shutdown(); writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); database.close(); }

    record Artwork(String id, UUID author, String authorName, String title, Instant createdAt, int width, int height, byte[] pixels, int mapId, String state, UUID owner, Long price) {}
    record BuyResult(Artwork artwork, long buyerBalance, UUID seller, long sellerBalance) {}
    record Exhibit(int slot, String artworkId) {}
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
    @FunctionalInterface private interface SqlRunnable { void run() throws Exception; }
}
