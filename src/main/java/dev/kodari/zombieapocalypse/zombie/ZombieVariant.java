package dev.kodari.zombieapocalypse.zombie;

import java.util.Arrays;
import java.util.Optional;

public enum ZombieVariant {
    NORMAL("normal"), RUNNER("runner"), BRUTE("brute"), CRAWLER("crawler"), SCREAMER("screamer"),
    ARMORED("armored"), TOXIC("toxic"), DEMOLISHER("demolisher"), ALPHA("alpha"), BOSS("boss"),
    STEALTH("stealth"), MUTATED("mutated"), REVENANT("revenant");

    private final String id;

    ZombieVariant(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<ZombieVariant> fromId(String id) {
        return Arrays.stream(values()).filter(variant -> variant.id.equalsIgnoreCase(id)).findFirst();
    }
}