package kr.hyuni.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

final class RpgDataStore {
    private static final Set<String> DATA_ROOTS = Set.of(
            "player-dialogues", "custom-items", "dismissed-examples", "player-variables",
            "shared-dialogues", "public-dialogues");
    private static final long BACKUP_INTERVAL_MILLIS = 5 * 60 * 1000L;
    private static final int MAXIMUM_BACKUPS = 20;
    private static final int MAXIMUM_GENERATION_BACKUPS = 10;

    private final Path folder;
    private final Logger logger;
    private final Map<Path, Long> lastBackup = new HashMap<>();

    RpgDataStore(Path folder, Logger logger) {
        this.folder = folder;
        this.logger = logger;
    }

    void loadInto(FileConfiguration live) throws IOException {
        Path players = folder.resolve("players");
        if (Files.isDirectory(players)) try (var files = Files.list(players)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                String name = file.getFileName().toString();
                UUID owner;
                try {
                    owner = UUID.fromString(name.substring(0, name.length() - 4));
                } catch (IllegalArgumentException ignored) {
                    logger.warning("Ignoring invalid RPGMaker player data file: " + name);
                    continue;
                }
                YamlConfiguration player = YamlConfiguration.loadConfiguration(file.toFile());
                copy(player, "dialogues", live, "player-dialogues." + owner, true);
                copy(player, "custom-items", live, "custom-items." + owner, true);
                copy(player, "dismissed-examples", live, "dismissed-examples." + owner, true);
                copy(player, "variables", live, "player-variables." + owner, true);
            }
        }

        // common.yml now owns only persistent public dialogues. Older versions also
        // stored token-based /rpgmaker share data here, so load that legacy section
        // once and let shares.yml override it when the migrated file already exists.
        Path commonFile = folder.resolve("common.yml");
        if (Files.isRegularFile(commonFile)) {
            YamlConfiguration common = YamlConfiguration.loadConfiguration(commonFile.toFile());
            copy(common, "public-dialogues", live, "public-dialogues", true);
            if (common.contains("shared-dialogues"))
                copy(common, "shared-dialogues", live, "shared-dialogues", true);
        }
        Path sharesFile = folder.resolve("shares.yml");
        if (Files.isRegularFile(sharesFile)) {
            YamlConfiguration shares = YamlConfiguration.loadConfiguration(sharesFile.toFile());
            copy(shares, "shared-dialogues", live, "shared-dialogues", true);
        }
    }

    synchronized void save(FileConfiguration live) throws IOException {
        LinkedHashMap<Path, String> writes = new LinkedHashMap<>();

        YamlConfiguration settings = new YamlConfiguration();
        live.getValues(true).forEach((path, value) -> {
            if (!(value instanceof ConfigurationSection) && DATA_ROOTS.stream().noneMatch(root -> path.equals(root) || path.startsWith(root + ".")))
                settings.set(path, value);
        });
        writes.put(folder.resolve("config.yml"), settings.saveToString());

        Set<String> rawOwners = new HashSet<>();
        for (String root : List.of("player-dialogues", "custom-items", "dismissed-examples", "player-variables")) {
            ConfigurationSection section = live.getConfigurationSection(root);
            if (section != null) rawOwners.addAll(section.getKeys(false));
        }
        Set<UUID> owners = new HashSet<>();
        for (String rawOwner : rawOwners) {
            UUID owner;
            try {
                owner = UUID.fromString(rawOwner);
            } catch (IllegalArgumentException ignored) {
                logger.warning("Ignoring invalid RPGMaker player data owner: " + rawOwner);
                continue;
            }
            owners.add(owner);
            YamlConfiguration player = new YamlConfiguration();
            player.set("owner-uuid", owner.toString());
            copy(live, "player-dialogues." + owner, player, "dialogues", false);
            copy(live, "custom-items." + owner, player, "custom-items", false);
            copy(live, "dismissed-examples." + owner, player, "dismissed-examples", false);
            copy(live, "player-variables." + owner, player, "variables", false);
            writes.put(folder.resolve("players").resolve(owner + ".yml"), player.saveToString());
        }

        LinkedHashSet<Path> deletes = new LinkedHashSet<>();
        Path players = folder.resolve("players");
        if (Files.isDirectory(players)) try (var files = Files.list(players)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                String name = file.getFileName().toString();
                try {
                    if (owners.contains(UUID.fromString(name.substring(0, name.length() - 4)))) continue;
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                deletes.add(file);
            }
        }

        YamlConfiguration common = new YamlConfiguration();
        copy(live, "public-dialogues", common, "public-dialogues", false);
        writes.put(folder.resolve("common.yml"), common.saveToString());

        YamlConfiguration shares = new YamlConfiguration();
        copy(live, "shared-dialogues", shares, "shared-dialogues", false);
        writes.put(folder.resolve("shares.yml"), shares.saveToString());

        commitBatch(writes, deletes);
    }

    private static void copy(ConfigurationSection source, String sourcePath,
                             ConfigurationSection destination, String destinationPath, boolean clear) {
        if (clear) destination.set(destinationPath, null);
        Object direct = source.get(sourcePath);
        if (!(direct instanceof ConfigurationSection section)) {
            if (direct != null) destination.set(destinationPath, direct);
            return;
        }
        section.getValues(true).forEach((path, value) -> {
            if (!(value instanceof ConfigurationSection)) destination.set(destinationPath + "." + path, value);
        });
    }

    private void commitBatch(Map<Path, String> writes, Set<Path> deletes) throws IOException {
        LinkedHashSet<Path> affected = new LinkedHashSet<>(writes.keySet());
        affected.addAll(deletes);
        Path snapshot = createGenerationSnapshot(affected);
        LinkedHashMap<Path, Path> staged = new LinkedHashMap<>();
        try {
            // Stage every new file before replacing any live file. A serialization or
            // disk-write failure therefore cannot leave half of a save generation live.
            for (Map.Entry<Path, String> entry : writes.entrySet()) {
                Path target = entry.getKey();
                Files.createDirectories(target.getParent());
                Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".txn");
                Files.writeString(temporary, entry.getValue(), StandardCharsets.UTF_8);
                staged.put(target, temporary);
            }

            for (Map.Entry<Path, Path> entry : staged.entrySet()) {
                try {
                    backup(entry.getKey());
                } catch (IOException error) {
                    logger.warning("Could not back up " + entry.getKey().getFileName() + ": " + error.getMessage());
                }
                replaceAtomic(entry.getValue(), entry.getKey());
            }
            for (Path target : deletes) {
                try {
                    backup(target);
                } catch (IOException error) {
                    logger.warning("Could not back up " + target.getFileName() + ": " + error.getMessage());
                }
                deleteWithRetry(target);
            }
        } catch (IOException failure) {
            try {
                restoreGeneration(snapshot, affected);
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
                logger.severe("RPGMaker save rollback failed: " + restoreFailure.getMessage());
            }
            throw failure;
        } finally {
            for (Path temporary : staged.values()) Files.deleteIfExists(temporary);
        }
    }

    private Path createGenerationSnapshot(Set<Path> affected) throws IOException {
        long now = System.currentTimeMillis();
        Path backups = folder.resolve("backups");
        Files.createDirectories(backups);
        Path snapshot = backups.resolve("generation-" + now);
        Files.createDirectories(snapshot);
        for (Path target : affected) {
            if (!Files.isRegularFile(target)) continue;
            Path relative = folder.relativize(target);
            Path copy = snapshot.resolve(relative);
            Files.createDirectories(copy.getParent());
            Files.copy(target, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        pruneGenerationSnapshots(backups);
        return snapshot;
    }

    private void restoreGeneration(Path snapshot, Set<Path> affected) throws IOException {
        IOException failure = null;
        for (Path target : affected) {
            Path backup = snapshot.resolve(folder.relativize(target));
            try {
                if (Files.isRegularFile(backup)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(target);
                }
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private void pruneGenerationSnapshots(Path backups) throws IOException {
        try (var files = Files.list(backups)) {
            List<Path> old = files
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("generation-"))
                    .sorted((left, right) -> right.getFileName().toString().compareTo(left.getFileName().toString()))
                    .skip(MAXIMUM_GENERATION_BACKUPS)
                    .toList();
            for (Path path : old) deleteTree(path);
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList())
                Files.deleteIfExists(path);
        }
    }

    private void replaceAtomic(Path temporary, Path target) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException error) {
                failure = error;
                retryDelay(target, attempt);
            }
        }
        throw failure;
    }

    private void deleteWithRetry(Path target) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.deleteIfExists(target);
                return;
            } catch (IOException error) {
                failure = error;
                retryDelay(target, attempt);
            }
        }
        throw failure;
    }

    private void retryDelay(Path target, int attempt) throws IOException {
        if (attempt >= 9) return;
        try {
            Thread.sleep(50L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving " + target, interrupted);
        }
    }

    private void backup(Path target) throws IOException {
        if (!Files.isRegularFile(target)) return;
        long now = System.currentTimeMillis();
        if (now - lastBackup.getOrDefault(target, 0L) < BACKUP_INTERVAL_MILLIS) return;
        Path backups = folder.resolve("backups");
        Files.createDirectories(backups);
        String prefix = target.getFileName() + "-";
        Files.copy(target, backups.resolve(prefix + now + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        lastBackup.put(target, now);
        try (var files = Files.list(backups)) {
            List<Path> old = files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().startsWith(prefix))
                    .sorted((left, right) -> right.getFileName().toString().compareTo(left.getFileName().toString()))
                    .skip(MAXIMUM_BACKUPS).toList();
            for (Path path : old) Files.deleteIfExists(path);
        }
    }
}
