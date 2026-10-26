package dev.kodari.zombieapocalypse.recipe;

import java.util.Map;

public record RecipeDefinition(String id, String resultItemId, int resultAmount, String station,
                               Map<String, Integer> ingredients) {
    public RecipeDefinition {
        ingredients = Map.copyOf(ingredients);
    }
}