package dev.kodari.zombieapocalypse.storage;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.infection.InfectionRecord;
import dev.kodari.zombieapocalypse.infection.InfectionStage;

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

public final class InfectionStore implements AutoCloseable {
    private static final String HEADER = "schema=1";
    private final ZombieApocalypsePlugin plugin;
    private final Path file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ZombieApocalypse-Storage");
        thread.setDaemon(true);
        return thread;
    });

    public InfectionStore(ZombieApocalypsePlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("infections.dat");
    }

    public CompletableFuture<Map<UUID, InfectionRecord>> load() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, InfectionRecord> records = new HashMap<>();
            Path source = Files.exists(file) ? file : file.resolveSibling("infections.dat.bak");
            if (!Files.exists(source)) {
                return records;
            }
            try (BufferedReader reader = Files.newBufferedReader(source)) {
                String header = reader.readLine();
                if (!HEADER.equals(header)) {
                    throw new IOException("Unsupported or missing infection data schema header");
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\\|", -1);
                    if (fields.length != 4) {
                        throw new IOException("Malformed infection record in " + source.getFileName());
                    }
                    try {
                        UUID id = UUID.fromString(fields[0]);
                        InfectionStage stage = InfectionStage.valueOf(fields[1]);
                        long infectedAt = Long.parseLong(fields[2]);
                        long criticalAt = Long.parseLong(fields[3]);
                        records.put(id, new InfectionRecord(id, stage, infectedAt, criticalAt));
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("Invalid infection record in " + source.getFileName(), exception);
                    }
                }
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
            return records;
        }, worker);
    }

    public void save(Map<UUID, InfectionRecord> snapshot) {
        Map<UUID, InfectionRecord> copy = Map.copyOf(snapshot);
        worker.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling("infections.dat.tmp");
                StringBuilder contents = new StringBuilder(HEADER).append('\n');
                for (InfectionRecord record : copy.values()) {
                    contents.append(record.playerId()).append('|')
                            .append(record.stage().name()).append('|')
                            .append(record.infectedAtMillis()).append('|')
                            .append(record.criticalAtMillis()).append('\n');
                }
                Files.writeString(temporary, contents);
                if (Files.exists(file)) {
                    Files.copy(file, file.resolveSibling("infections.dat.bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException unsupportedAtomicMove) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not save infection data: " + exception.getMessage());
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