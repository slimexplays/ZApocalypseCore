package dev.kodari.zombieapocalypse.storage;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class StorageStore implements AutoCloseable {
    private final ZombieApocalypsePlugin plugin;
    private final Path file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ZombieApocalypse-StorageData");
        thread.setDaemon(true);
        return thread;
    });

    public StorageStore(ZombieApocalypsePlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("storages.yml");
    }

    public CompletableFuture<String> load() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(file)) {
                    Path backup = file.resolveSibling("storages.yml.bak");
                    return Files.exists(backup) ? Files.readString(backup) : "schema: 1\nstorages: {}\n";
                }
                return Files.readString(file);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, worker);
    }

    public void save(String contents) {
        worker.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling("storages.yml.tmp");
                Files.writeString(temporary, contents);
                if (Files.exists(file)) {
                    Files.copy(file, file.resolveSibling("storages.yml.bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException unsupportedAtomicMove) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not save custom storage data: " + exception.getMessage());
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