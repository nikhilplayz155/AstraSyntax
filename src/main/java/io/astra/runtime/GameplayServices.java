package io.astra.runtime;

import io.astra.runtime.board.BossBarService;
import io.astra.runtime.board.ScoreboardService;
import io.astra.runtime.display.HologramService;
import io.astra.runtime.gui.MenuService;
import io.astra.runtime.item.ItemService;
import io.astra.runtime.npc.NpcService;
import io.astra.runtime.quest.QuestService;
import io.astra.runtime.recipe.RecipeService;
import io.astra.runtime.region.RegionService;
import io.astra.runtime.region.RegionTracker;

/**
 * The gameplay services, bundled so the runtime has one thing to hand around.
 *
 * <p>They are separate services because they have separate lifetimes and separate reasons to
 * change, but they are created and shut down as a group: a script that unloads gives back
 * its items, menus, recipes, regions, boards, bars, holograms and NPCs through this record.
 * A {@code null} instance means the gameplay layer is not installed (a test runtime), and
 * the gameplay actions report that instead of throwing a null pointer.</p>
 */
public record GameplayServices(ItemService items, RecipeService recipes, RegionService regions,
                               RegionTracker regionTracker, QuestService quests,
                               ScoreboardService scoreboards, BossBarService bossbars,
                               HologramService holograms, MenuService menus, NpcService npcs) {

    /** Calls {@code unregisterAll} on every service that is script-owned. */
    public void unregisterAll(String scriptName) {
        if (scriptName == null) return;
        if (items != null) items.unregisterAll(scriptName);
        if (recipes != null) recipes.unregisterAll(scriptName);
        if (regions != null) regions.unregisterAll(scriptName);
        if (scoreboards != null) scoreboards.unregisterAll(scriptName);
        if (bossbars != null) bossbars.unregisterAll(scriptName);
        if (holograms != null) holograms.unregisterAll(scriptName);
        if (menus != null) menus.unregisterAll(scriptName);
        if (npcs != null) npcs.unregisterAll(scriptName);
    }

    /** Shuts every service down, hiding or removing what it put into the world. */
    public void shutdown() {
        if (scoreboards != null) scoreboards.shutdown();
        if (bossbars != null) bossbars.shutdown();
        if (holograms != null) holograms.shutdown();
        if (menus != null) menus.shutdown();
        if (npcs != null) npcs.shutdown();
        if (recipes != null) recipes.shutdown();
        if (regionTracker != null) regionTracker.clear();
    }

    /** A one-line summary for {@code /astra info}. */
    public String describe() {
        return "items " + count(items == null ? 0 : items.size())
            + ", recipes " + count(recipes == null ? 0 : recipes.size())
            + ", regions " + count(regions == null ? 0 : regions.size())
            + ", menus " + count(menus == null ? 0 : menus.size())
            + ", boards " + count(scoreboards == null ? 0 : scoreboards.size())
            + ", bars " + count(bossbars == null ? 0 : bossbars.size())
            + ", holograms " + count(holograms == null ? 0 : holograms.size())
            + ", npcs " + count(npcs == null ? 0 : npcs.size());
    }

    private static String count(int value) {
        return Integer.toString(value);
    }
}
