package dev.kodari.zombieapocalypse.condition;

import java.util.UUID;

public record SurvivalConditionRecord(UUID playerId, double thirst, double stamina) {
}