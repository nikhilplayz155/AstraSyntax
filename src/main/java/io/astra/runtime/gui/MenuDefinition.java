package io.astra.runtime.gui;

import io.astra.language.ast.Span;
import io.astra.runtime.script.CompiledStmt;

import java.util.List;

/**
 * A chest menu declared with {@code menu <name>: ...}.
 *
 * <p>Each slot carries the item to draw and the compiled statements to run when a player
 * clicks it. The statements are ordinary compiled actions, so a menu button can do
 * anything a rule can - and it is bounded by the same {@code security.yml} gate.</p>
 */
public record MenuDefinition(String name, String title, int size, List<Slot> slots, String scriptName) {

    /** One clickable slot. */
    public record Slot(int index, String material, String displayName, List<String> lore,
                       List<CompiledStmt> body, Span span) {
        public Slot {
            lore = lore == null ? List.of() : List.copyOf(lore);
            body = body == null ? List.of() : List.copyOf(body);
        }

        /** One line for {@code /astra explain}. */
        public String describe() {
            return "slot " + index + ": " + material
                + (displayName == null || displayName.isBlank() ? "" : " (\"" + displayName + "\")")
                + " -> " + body.size() + " action(s)";
        }
    }

    public MenuDefinition {
        slots = slots == null ? List.of() : List.copyOf(slots);
        // Chest menus only exist in multiples of nine, one to six rows.
        size = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
    }

    /** The slot at an index, or {@code null} when nothing is bound there. */
    public Slot slotAt(int index) {
        for (Slot slot : slots) {
            if (slot.index() == index) return slot;
        }
        return null;
    }

    /** One line for {@code /astra explain}. */
    public String describe() {
        return "menu " + name + " (\"" + title + "\", " + size + " slots, " + slots.size() + " button(s))";
    }
}
