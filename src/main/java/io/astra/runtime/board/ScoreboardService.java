package io.astra.runtime.board;

import io.astra.logging.AstraLogger;
import io.astra.platform.TextService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Sidebar scoreboards driven from scripts.
 *
 * <p>A script declares a board by name and updates its lines; the service keeps the
 * rendering, so a line update is a one-field change instead of a board rebuild. Each
 * player gets their own {@link Scoreboard} instance, which means two players can see
 * different boards and hiding a board restores the server's main scoreboard without
 * touching anybody else.</p>
 *
 * <p>Line text goes into the team prefix, and the score entry is an invisible unique
 * string - the standard technique that lets a sidebar line hold arbitrary text (including
 * colour codes and numbers) on every supported platform.</p>
 */
public final class ScoreboardService {

    /** Sidebar objective name; one per player board. */
    private static final String OBJECTIVE = "astra_sidebar";

    /** Team name prefix for line {@code n}. */
    private static final String TEAM = "astra_line_";

    /** A sidebar can show at most 15 lines. */
    public static final int MAX_LINES = 15;

    /** A named board definition, owned by the script that created it. */
    public record BoardDefinition(String name, String title, List<String> lines, String script) {
        public BoardDefinition {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** A copy with new lines. */
        public BoardDefinition withLines(List<String> newLines) {
            return new BoardDefinition(name, title, newLines, script);
        }

        /** A copy with a new title. */
        public BoardDefinition withTitle(String newTitle) {
            return new BoardDefinition(name, newTitle, lines, script);
        }
    }

    private final Map<String, BoardDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, String> shown = new ConcurrentHashMap<>();
    private final TextService text;
    private final AstraLogger logger;

    public ScoreboardService(TextService text, AstraLogger logger) {
        this.text = text;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ registry

    /** Creates or replaces a board. */
    public BoardDefinition register(String script, String name, String title, List<String> lines) {
        BoardDefinition definition = new BoardDefinition(name, title == null ? name : title,
            lines == null ? List.of() : lines, script == null ? "" : script);
        definitions.put(key(name), definition);
        return definition;
    }

    /** Drops every board owned by a script and hides it from the players showing it. */
    public void unregisterAll(String script) {
        if (script == null) return;
        List<String> removed = new ArrayList<>();
        definitions.entrySet().removeIf(entry -> {
            if (!script.equals(entry.getValue().script())) return false;
            removed.add(entry.getKey());
            return true;
        });
        if (removed.isEmpty()) return;
        // Anyone still displaying one of the removed boards falls back to the main board.
        for (Map.Entry<UUID, String> entry : new ArrayList<>(shown.entrySet())) {
            if (removed.contains(entry.getValue())) hide(Bukkit.getPlayer(entry.getKey()));
        }
    }

    /** The board with this name, or {@code null}. */
    public BoardDefinition definition(String name) {
        return name == null ? null : definitions.get(key(name));
    }

    public Collection<BoardDefinition> all() {
        return List.copyOf(definitions.values());
    }

    public int size() {
        return definitions.size();
    }

    // ------------------------------------------------------------------ updates

    /** Changes a board's title and pushes it to everyone showing that board. */
    public boolean setTitle(String name, String title) {
        BoardDefinition definition = definitions.get(key(name));
        if (definition == null) return false;
        definitions.put(key(name), definition.withTitle(title));
        refreshViewers(key(name));
        return true;
    }

    /** Sets one line (growing the board when needed) and refreshes its viewers. */
    public boolean setLine(String name, int line, String value) {
        BoardDefinition definition = definitions.get(key(name));
        if (definition == null || line < 0 || line >= MAX_LINES) return false;
        List<String> lines = new ArrayList<>(definition.lines());
        while (lines.size() <= line) lines.add("");
        lines.set(line, value == null ? "" : value);
        definitions.put(key(name), definition.withLines(lines));
        refreshViewers(key(name));
        return true;
    }

    /** Removes a line from a board. */
    public boolean clearLine(String name, int line) {
        BoardDefinition definition = definitions.get(key(name));
        if (definition == null || line < 0 || line >= definition.lines().size()) return false;
        List<String> lines = new ArrayList<>(definition.lines());
        lines.set(line, "");
        definitions.put(key(name), definition.withLines(lines));
        refreshViewers(key(name));
        return true;
    }

    /** Replaces every line at once (used when a script reloads). */
    public boolean setLines(String name, List<String> lines) {
        BoardDefinition definition = definitions.get(key(name));
        if (definition == null) return false;
        definitions.put(key(name), definition.withLines(lines));
        refreshViewers(key(name));
        return true;
    }

    // ------------------------------------------------------------------ display

    /** Shows a board to a player. Returns false when no such board exists. */
    public boolean show(Player player, String name) {
        if (player == null) return false;
        BoardDefinition definition = definitions.get(key(name));
        if (definition == null) return false;
        shown.put(player.getUniqueId(), key(name));
        render(player, definition);
        return true;
    }

    /** True when the player is currently showing a board managed by Astra. */
    public boolean isShowing(Player player) {
        return player != null && shown.containsKey(player.getUniqueId());
    }

    /** The board a player is showing, or {@code null}. */
    public BoardDefinition shown(Player player) {
        if (player == null) return null;
        String name = shown.get(player.getUniqueId());
        return name == null ? null : definitions.get(name);
    }

    /** Restores the server's main scoreboard for a player. */
    public void hide(Player player) {
        if (player == null) return;
        shown.remove(player.getUniqueId());
        boards.remove(player.getUniqueId());
        try {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        } catch (RuntimeException error) {
            logger.debug("Could not restore the main scoreboard for " + player.getName() + ": "
                + logger.describe(error));
        }
    }

    /** Re-renders the board a player is showing (used after a script reload). */
    public void refresh(Player player) {
        BoardDefinition definition = shown(player);
        if (definition != null) render(player, definition);
    }

    /** Players currently showing any Astra board. */
    public int viewerCount() {
        return shown.size();
    }

    private void refreshViewers(String boardKey) {
        for (Map.Entry<UUID, String> entry : shown.entrySet()) {
            if (!entry.getValue().equals(boardKey)) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            BoardDefinition definition = definitions.get(boardKey);
            if (player != null && definition != null) render(player, definition);
        }
    }

    /** Draws a definition on a player's own scoreboard. */
    private void render(Player player, BoardDefinition definition) {
        try {
            Scoreboard board = boards.computeIfAbsent(player.getUniqueId(),
                id -> Bukkit.getScoreboardManager().getNewScoreboard());
            Objective objective = board.getObjective(OBJECTIVE);
            if (objective == null) {
                objective = board.registerNewObjective(OBJECTIVE, "dummy", text.render(definition.title()));
            } else {
                objective.setDisplayName(text.render(definition.title()));
            }
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);

            List<String> lines = definition.lines();
            int count = Math.min(lines.size(), MAX_LINES);
            // Remove teams above the current line count so a shorter board leaves no ghosts.
            for (int index = count; index < MAX_LINES; index++) {
                Team stale = board.getTeam(TEAM + index);
                if (stale != null) {
                    stale.unregister();
                    board.resetScores(entry(index));
                }
            }
            for (int index = 0; index < count; index++) {
                String line = text.render(lines.get(index));
                String teamName = TEAM + index;
                Team team = board.getTeam(teamName);
                if (team == null) team = board.registerNewTeam(teamName);
                String entryName = entry(index);
                if (!team.hasEntry(entryName)) team.addEntry(entryName);
                // Long text is split across prefix and suffix, the classic 16/16 split.
                if (line.length() <= 16) {
                    team.setPrefix(line);
                    team.setSuffix("");
                } else {
                    team.setPrefix(line.substring(0, 16));
                    team.setSuffix(line.substring(16, Math.min(32, line.length())));
                }
                objective.getScore(entryName).setScore(count - index);
            }
            player.setScoreboard(board);
        } catch (RuntimeException error) {
            logger.warn("Could not update the scoreboard '" + definition.name() + "' for "
                + player.getName() + ": " + logger.describe(error));
        }
    }

    /** An invisible, unique score entry for line {@code index}. */
    private static String entry(int index) {
        return "\u00a7" + Integer.toHexString(index) + "\u00a7r";
    }

    /** Shuts the service down, restoring every viewer's scoreboard. */
    public void shutdown() {
        for (UUID id : new ArrayList<>(shown.keySet())) hide(Bukkit.getPlayer(id));
        definitions.clear();
        boards.clear();
        shown.clear();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
