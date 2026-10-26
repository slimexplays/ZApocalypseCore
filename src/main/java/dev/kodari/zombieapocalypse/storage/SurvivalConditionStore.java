package dev.kodari.zombieapocalypse.storage;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.condition.SurvivalConditionRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SurvivalConditionStore implements AutoCloseable {
    private static final String HEADER = "schema=1";
    private final ZombieApocalypsePlugin plugin;
    private final Path file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ZombieApocalypse-Conditions");
        thread.setDaemon(true);
        return thread;
    });

    public SurvivalConditionStore(ZombieApocalypsePlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("survival-conditions.dat");
    }

    public CompletableFuture<Map<UUID, SurvivalConditionRecord>> load() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, SurvivalConditionRecord> records = new HashMap<>();
            Path source = Files.exists(file) ? file : file.resolveSibling("survival-conditions.dat.bak");
            if (!Files.exists(source)) {
                return records;
            }
            try (BufferedReader reader = Files.newBufferedReader(source)) {
                if (!HEADER.equals(reader.readLine())) {
                    throw new IOException("Unsupported or missing survival condition schema header");
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\\|", -1);
                    if (fields.length != 3) {
                        throw new IOException("Malformed survival condition record in " + source.getFileName());
                    }
                    try {
                        UUID playerId = UUID.fromString(fields[0]);
                        double thirst = Double.parseDouble(fields[1]);
                        double stamina = Double.parseDouble(fields[2]);
                        if (!Double.isFinite(thirst) || !Double.isFinite(stamina) || thirst < 0 || stamina < 0) {
                            throw new IllegalArgumentException("Condition values must be finite and nonnegative");
                        }
                        records.put(playerId, new SurvivalConditionRecord(playerId, thirst, stamina));
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("Invalid survival condition record in " + source.getFileName(), exception);
                    }
                }
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
            return records;
        }, worker);
    }

    public void save(Map<UUID, SurvivalConditionRecord> snapshot) {
        Map<UUID, SurvivalConditionRecord> copy = Map.copyOf(snapshot);
        worker.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling("survival-conditions.dat.tmp");
                StringBuilder contents = new StringBuilder(HEADER).append('\n');
                for (SurvivalConditionRecord record : copy.values()) {
                    contents.append(record.playerId()).append('|')
                            .append(record.thirst()).append('|')
                            .append(record.stamina()).append('\n');
                }
                Files.writeString(temporary, contents);
                if (Files.exists(file)) {
                    Files.copy(file, file.resolveSibling("survival-conditions.dat.bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException unsupportedAtomicMove) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not save survival condition data: " + exception.getMessage());
            }
        });
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}