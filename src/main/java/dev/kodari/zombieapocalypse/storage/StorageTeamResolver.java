package dev.kodari.zombieapocalypse.storage;

import java.util.UUID;

@FunctionalInterface
public interface StorageTeamResolver {
    boolean areTeammates(UUID ownerId, UUID playerId);
}