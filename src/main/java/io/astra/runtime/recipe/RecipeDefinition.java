package io.astra.runtime.recipe;

import java.util.List;
import java.util.Map;

/**
 * A crafting recipe declared with {@code recipe <name>: ...}.
 *
 * <p>{@code shape} holds up to three rows of up to three characters; blank spaces are
 * empty slots. {@code ingredients} maps each character to a material (or a custom item
 * name). A recipe without a shape is registered as shapeless from its ingredient list.</p>
 */
public record RecipeDefinition(String name, boolean shaped, List<String> shape, Map<String, String> ingredients,
                               String resultMaterial, int resultAmount) {

    public RecipeDefinition {
        shape = shape == null ? List.of() : List.copyOf(shape);
        ingredients = ingredients == null ? Map.of() : Map.copyOf(ingredients);
        resultAmount = Math.max(1, Math.min(64, resultAmount));
    }

    /** Every distinct ingredient material referenced by the recipe. */
    public List<String> ingredientMaterials() {
        return ingredients.values().stream().distinct().toList();
    }

    public int width() {
        return shape.stream().mapToInt(String::length).max().orElse(0);
    }

    public int height() {
        return shape.size();
    }

    /** One line for {@code /astra explain}. */
    public String describe() {
        String kind = shaped ? "shaped " + width() + "x" + height() : "shapeless";
        return "recipe " + name + ": " + kind + " -> " + resultAmount + " " + resultMaterial;
    }
}
