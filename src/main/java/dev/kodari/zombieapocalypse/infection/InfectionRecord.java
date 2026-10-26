package dev.kodari.zombieapocalypse.infection;

import java.util.UUID;

public record InfectionRecord(UUID playerId, InfectionStage stage, long infectedAtMillis, long criticalAtMillis) {
}