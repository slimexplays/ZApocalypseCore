package dev.kodari.zombieapocalypse.storage;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class RegionalInfectionStore implements AutoCloseable {
    private static final String HEADER = "schema=1";
    private final ZombieApocalypsePlugin plugin;
    private final Path file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ZombieApocalypse-RegionalInfectionStorage");
        thread.setDaemon(true);
        return thread;
    });

    public RegionalInfectionStore(ZombieApocalypsePlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("regional-infection.dat");
    }

    public CompletableFuture<Map<String, Double>> load() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Double> levels = new HashMap<>();
            Path source = Files.exists(file) ? file : file.resolveSibling("regional-infection.dat.bak");
            if (!Files.exists(source)) {
                return levels;
            }
            try (BufferedReader reader = Files.newBufferedReader(source)) {
                if (!HEADER.equals(reader.readLine())) {
                    throw new IOException("Unsupported or missing regional infection schema header");
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (levels.size() >= 256) {
                        throw new IOException("Regional infection store exceeds the 256-region safety limit");
                    }
                    String[] fields = line.split("\\|", -1);
                    if (fields.length != 2 || !fields[0].matches("[a-z0-9_-]{1,32}")) {
                        throw new IOException("Malformed regional infection record");
                    }
                    try {
                        double level = Double.parseDouble(fields[1]);
                        if (!Double.isFinite(level) || level < 0 || level > 100 || levels.containsKey(fields[0])) {
                            throw new IllegalArgumentException();
                        }
                        levels.put(fields[0], level);
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("Invalid regional infection record", exception);
                    }
                }
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
            return levels;
        }, worker);
    }

    public void save(Map<String, Double> snapshot) {
        Map<String, Double> copy = Map.copyOf(snapshot);
        worker.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling("regional-infection.dat.tmp");
                StringBuilder contents = new StringBuilder(HEADER).append('\n');
                for (Map.Entry<String, Double> entry : copy.entrySet()) {
                    contents.append(entry.getKey()).append('|').append(entry.getValue()).append('\n');
                }
                Files.writeString(temporary, contents);
                if (Files.exists(file)) {
                    Files.copy(file, file.resolveSibling("regional-infection.dat.bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException unsupportedAtomicMove) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not save regional infection data: " + exception.getMessage());
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