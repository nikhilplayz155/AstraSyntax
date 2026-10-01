package io.astra.runtime.quest;

import io.astra.data.DataStore;
import io.astra.runtime.Value;
import io.astra.runtime.ValueMath;

import java.util.List;

import org.bukkit.entity.Entity;

/**
 * Quests, built on top of the data store rather than beside it.
 *
 * <p>A quest is three data keys per holder - {@code quest.<name>.progress},
 * {@code quest.<name>.goal} and {@code quest.<name>.done} - written through
 * {@link DataStore}. That choice is deliberate: quest progress inherits everything the data
 * layer already guarantees (typing, per-player association, autosave, persistence across
 * restarts) instead of growing a second storage path that could drift from the first.</p>
 */
public final class QuestService {

    private static final String PREFIX = "quest.";

    private final DataStore data;

    public QuestService(DataStore data) {
        this.data = data;
    }

    /** Current progress of a quest for a holder (0 when nothing is stored). */
    public int progress(Entity holder, String quest) {
        if (holder == null || quest == null || quest.isBlank()) return 0;
        Value value = data.get(holder, key(quest, "progress"));
        return (int) Math.max(0, value.asDouble());
    }

    /** The goal of a quest for a holder (0 when the quest was never started). */
    public int goal(Entity holder, String quest) {
        if (holder == null || quest == null || quest.isBlank()) return 0;
        return (int) Math.max(0, data.get(holder, key(quest, "goal")).asDouble());
    }

    /**
     * Adds progress and marks the quest complete when the goal is reached.
     *
     * <p>{@code goal} is written on the first call, so a script may set the target once and
     * then only add progress.</p>
     *
     * @return true when this call completed the quest
     */
    public boolean addProgress(Entity holder, String quest, int amount, int goal) {
        if (holder == null || quest == null || quest.isBlank()) return false;
        String progressKey = key(quest, "progress");
        String goalKey = key(quest, "goal");
        if (!data.has(holder, goalKey) || goal > 0) {
            data.set(holder, goalKey, Value.num(Math.max(1, goal)));
        }
        Value current = data.get(holder, progressKey);
        Value updated = ValueMath.add(current, Value.num(amount));
        data.set(holder, progressKey, updated);

        int target = (int) Math.max(1, data.get(holder, goalKey).asDouble());
        boolean complete = updated.asDouble() >= target;
        if (complete) {
            data.set(holder, key(quest, "done"), Value.bool(true));
        }
        return complete;
    }

    /** True when the quest has been completed by the holder. */
    public boolean isComplete(Entity holder, String quest) {
        if (holder == null || quest == null || quest.isBlank()) return false;
        return data.get(holder, key(quest, "done")).asBoolean();
    }

    /** Marks a quest as completed without touching its progress. */
    public void complete(Entity holder, String quest) {
        if (holder == null || quest == null || quest.isBlank()) return;
        data.set(holder, key(quest, "done"), Value.bool(true));
    }

    /** Clears progress, goal and completion for one quest. */
    public void reset(Entity holder, String quest) {
        if (holder == null || quest == null || quest.isBlank()) return;
        for (String suffix : List.of("progress", "goal", "done")) {
            data.remove(holder, key(quest, suffix));
        }
    }

    /** Progress as a percentage (0-100), useful for scoreboard lines. */
    public int percent(Entity holder, String quest) {
        int goal = goal(holder, quest);
        if (goal <= 0) return isComplete(holder, quest) ? 100 : 0;
        return (int) Math.min(100, Math.round(100.0 * progress(holder, quest) / goal));
    }

    private static String key(String quest, String suffix) {
        return PREFIX + quest.toLowerCase(java.util.Locale.ROOT) + "." + suffix;
    }
}
