package io.astra;

import io.astra.config.AstraSettings.Features;
import io.astra.config.FeatureFlags;
import io.astra.language.compiler.AstraCompiler;
import io.astra.language.compiler.CompilationResult;
import io.astra.language.diagnostics.Diagnostic;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.language.parser.AstraParser;
import io.astra.language.parser.ParseResult;
import io.astra.runtime.builtin.MaterialTable;
import io.astra.runtime.gui.MenuDefinition;
import io.astra.runtime.item.ItemDefinition;
import io.astra.runtime.recipe.RecipeDefinition;
import io.astra.runtime.region.RegionDefinition;
import io.astra.runtime.vocab.BuiltinVocabulary;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gameplay layer: the four new declaration kinds, their runtime maths, and the feature
 * switches that gate them.
 *
 * <p>The records are tested directly because they are pure, and the parser is tested through
 * the same entry point the loader uses, so these tests fail if the language or the runtime
 * starts disagreeing with itself.</p>
 */
class GameplayTest {

    private static final String SCRIPT = """
        item legendary_sword:
            material diamond_sword
            name "&6Legendary Sword"
            lore "&7Forged in Astra"
            amount 1
            enchant sharpness 5
            unbreakable true
            flags hide-attributes

        menu shop:
            title "&8Shop"
            rows 3
            slot 13:
                item diamond
                name "&bBuy a diamond"
                give player 1 diamond

        recipe legendary_sword:
            result 1 diamond_sword
            shape "D"
            shape "S"
            key D diamond
            key S stick

        region spawn:
            world world
            from 0 0 0
            to 100 100 100

        on player join:
            give player 1 custom item legendary_sword
        """;

    /** The same vocabulary the plugin installs, so the tests exercise the shipped syntax. */
    private static final BuiltinVocabulary VOCABULARY = new BuiltinVocabulary(
        io.astra.runtime.Registries.builtins(), MaterialTable.shared(), true, true);

    private static ParseResult parse(String source, DiagnosticCollector collector) {
        return new AstraParser(collector, VOCABULARY).parse(source, "gameplay-test.ar");
    }

    private static CompilationResult compile(String source) {
        DiagnosticCollector collector = new DiagnosticCollector();
        ParseResult parsed = parse(source, collector);
        assertFalse(collector.hasErrors(), () -> "parse errors: " + collector.format());
        CompilationResult result = new AstraCompiler().compile(parsed);
        assertTrue(result.errors().isEmpty(), () -> "compile errors: " + result.errors());
        return result;
    }

    @Test
    void itemsMenusRecipesAndRegionsCompileIntoTheScript() {
        CompilationResult result = compile(SCRIPT);

        List<ItemDefinition> items = result.script().items();
        assertEquals(1, items.size());
        ItemDefinition sword = items.get(0);
        assertEquals("legendary_sword", sword.name());
        assertEquals("diamond_sword", sword.material());
        assertEquals("&6Legendary Sword", sword.displayName());
        assertEquals(List.of("&7Forged in Astra"), sword.lore());
        assertTrue(sword.unbreakable());
        assertEquals(1, sword.enchants().size());
        assertEquals("sharpness", sword.enchants().get(0).name());
        assertEquals(5, sword.enchants().get(0).level());
        assertEquals(List.of("hide-attributes"), sword.flags());
        // The lookup used by the runtime is case-insensitive.
        assertNotNull(result.script().item("LEGENDARY_SWORD"));

        List<MenuDefinition> menus = result.script().menus();
        assertEquals(1, menus.size());
        MenuDefinition shop = result.script().menu("shop");
        assertNotNull(shop);
        assertEquals(27, shop.size());
        assertEquals("&8Shop", shop.title());
        assertEquals(1, shop.slots().size());
        MenuDefinition.Slot button = shop.slotAt(13);
        assertNotNull(button);
        assertEquals("diamond", button.material());
        assertEquals("&bBuy a diamond", button.displayName());
        assertFalse(button.body().isEmpty(), "the button must carry its compiled action");
        assertNull(shop.slotAt(12));
        assertEquals("gameplay-test.ar", shop.scriptName());

        List<RecipeDefinition> recipes = result.script().recipes();
        assertEquals(1, recipes.size());
        RecipeDefinition recipe = recipes.get(0);
        assertTrue(recipe.shaped());
        assertEquals(2, recipe.height());
        assertEquals(1, recipe.width());
        assertEquals("diamond_sword", recipe.resultMaterial());
        assertEquals(List.of("diamond", "stick"), recipe.ingredientMaterials());

        List<RegionDefinition> regions = result.script().regions();
        assertEquals(1, regions.size());
        assertEquals("spawn", regions.get(0).name());
        assertEquals(100.0 * 100.0 * 100.0, regions.get(0).volume());
        assertTrue(regions.get(0).contains("world", 50, 50, 50));
        assertFalse(regions.get(0).contains("nether", 50, 50, 50));
    }

    @Test
    void everyDeclarationIsSummarisedForExplain() {
        CompilationResult result = compile(SCRIPT);
        List<String> kinds = result.script().summaries().stream()
            .map(summary -> summary.kind().name())
            .toList();
        assertTrue(kinds.contains("ITEM"), "the item must be summarised: " + kinds);
        assertTrue(kinds.contains("MENU"), "the menu must be summarised: " + kinds);
        assertTrue(kinds.contains("RECIPE"), "the recipe must be summarised: " + kinds);
        assertTrue(kinds.contains("REGION"), "the region must be summarised: " + kinds);
    }

    @Test
    void aTypoInAMaterialIsReportedWithASuggestion() {
        DiagnosticCollector collector = new DiagnosticCollector();
        parse("""
            item broken:
                material dimonds
            """, collector);
        assertTrue(collector.hasErrors(), "an unknown material must be an error");
        Diagnostic diagnostic = collector.diagnostics().stream()
            .filter(entry -> entry.message().contains("dimonds"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a diagnostic about dimonds: "
                + collector.format()));
        assertEquals("material", diagnostic.category());
        assertTrue(diagnostic.suggestions().stream().anyMatch(suggestion -> suggestion.contains("diamond")),
            () -> "expected a diamond suggestion, got " + diagnostic.suggestions());
    }

    @Test
    void anUnknownKeywordNamesTheAcceptedKeys() {
        DiagnosticCollector collector = new DiagnosticCollector();
        parse("""
            region spawn:
                world world
                from 0 0 0
                to 10 10 10
                colour red
            """, collector);
        assertTrue(collector.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("colour")),
            () -> "expected a report about 'colour': " + collector.format());
    }

    @Test
    void recipesAndMenusCarryTheirFeatureSwitches() {
        CompilationResult result = compile(SCRIPT);
        List<String> features = result.script().requiredFeatures();
        assertTrue(features.contains("custom-items"), features.toString());
        assertTrue(features.contains("gui"), features.toString());
        assertTrue(features.contains("recipes"), features.toString());
        assertTrue(features.contains("regions"), features.toString());
    }

    @Test
    void aDisabledFeatureStopsTheScriptInsteadOfFailingAtRuntime() {
        CompilationResult result = compile(SCRIPT);
        // Everything on except the crafting recipes.
        Features features = new Features(true, true, true, true, true, true, true, true, true, true, true,
            true, true, false, true, true, true);
        List<String> disabled = FeatureFlags.disabled(features, result.script().requiredFeatures());
        assertEquals(List.of("recipes"), disabled);
        assertEquals("features.recipes", FeatureFlags.configKey(disabled.get(0)));
    }

    // ------------------------------------------------------------- record behaviour

    @Test
    void itemAmountsAreClampedToARealStack() {
        assertEquals(64, new ItemDefinition("x", "stone", 999, null, List.of(), List.of(), false, List.of())
            .amount());
        assertEquals(1, new ItemDefinition("x", "stone", 0, null, List.of(), List.of(), false, List.of())
            .amount());
    }

    @Test
    void regionsNormaliseTheirCorners() {
        RegionDefinition region = RegionDefinition.of("box", "world", 10, 20, 30, 0, 0, 0);
        assertEquals(0, region.minX());
        assertEquals(10, region.maxX());
        assertTrue(region.contains("world", 5, 5, 5));
        assertFalse(region.contains("world", 5, 5, 50));
        assertFalse(region.contains("nether", 5, 5, 5));
        assertTrue(region.contains(null, 5, 5, 5), "a null world means any world");
        // 10 x 20 x 30, from the corner given in either order.
        assertEquals(6000.0, region.volume());
    }

    @Test
    void overlappingRegionsAreDetectedPerWorld() {
        RegionDefinition first = RegionDefinition.of("first", "world", 0, 0, 0, 10, 10, 10);
        RegionDefinition touching = RegionDefinition.of("second", "world", 10, 10, 10, 20, 20, 20);
        RegionDefinition apart = RegionDefinition.of("third", "world", 30, 0, 0, 40, 10, 10);
        RegionDefinition otherWorld = RegionDefinition.of("fourth", "nether", 0, 0, 0, 10, 10, 10);
        assertTrue(first.overlaps(touching));
        assertFalse(first.overlaps(apart));
        assertFalse(first.overlaps(otherWorld));
        assertFalse(first.overlaps(null));
    }

    @Test
    void menusRoundUpToWholeRowsAndClamp() {
        assertEquals(9, new MenuDefinition("a", "t", 1, List.of(), "s").size());
        assertEquals(18, new MenuDefinition("a", "t", 10, List.of(), "s").size());
        assertEquals(54, new MenuDefinition("a", "t", 200, List.of(), "s").size());
    }

    @Test
    void recipeGeometryIsReportedFromTheShape() {
        RecipeDefinition recipe = new RecipeDefinition("r", true, List.of("AA", "A"),
            java.util.Map.of("A", "oak_planks"), "stick", 4);
        assertEquals(2, recipe.width());
        assertEquals(2, recipe.height());
        assertEquals(List.of("oak_planks"), recipe.ingredientMaterials());
        assertEquals(4, recipe.resultAmount());
        assertTrue(recipe.describe().contains("2x2"));
    }

    @Test
    void materialsAndEntitiesResolveThroughTheSharedTable() {
        MaterialTable table = MaterialTable.shared();
        assertTrue(table.isMaterial("diamond"));
        assertFalse(table.isMaterial("dimonds"));
        assertNotNull(table.resolveEntity("zombie"));
        assertNull(table.resolveEntity("not_a_mob"));
        assertTrue(table.isEntityType("zombie"));
        assertFalse(table.isEntityType("not_a_mob"));
    }

    @Test
    void theVocabularyKnowsTheGameplaySentences() {
        BuiltinVocabulary vocabulary = new BuiltinVocabulary(
            io.astra.runtime.Registries.builtins(), MaterialTable.shared(), true, true);
        assertTrue(vocabulary.isMaterial("diamond_sword"));
        assertTrue(vocabulary.materialSuggestions("diamnd").stream().anyMatch(entry -> entry.contains("diamond")),
            () -> "expected diamond suggestions");
    }
}
