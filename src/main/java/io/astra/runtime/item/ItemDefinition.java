package io.astra.runtime.item;

import java.util.List;

/**
 * A custom item declared with {@code item <name>: ...} and resolved at compile time.
 *
 * <p>Only names, numbers and booleans are kept here - the {@code ItemStack} is built on
 * demand by {@link ItemService}, because a stack is mutable and per-player while the
 * definition is shared.</p>
 *
 * @param name        the identifier scripts use ({@code legendary_sword})
 * @param material    a Minecraft material name
 * @param amount      default stack size
 * @param displayName MiniMessage display name, or {@code null}
 * @param lore        lore lines, in order
 * @param enchants    enchantments applied on creation
 * @param unbreakable true to make the item unbreakable
 * @param flags       ItemFlags to hide ({@code hide-attributes} style names)
 */
public record ItemDefinition(String name, String material, int amount, String displayName, List<String> lore,
                             List<Enchant> enchants, boolean unbreakable, List<String> flags) {

    /** One enchantment on a custom item. */
    public record Enchant(String name, int level) {
    }

    public ItemDefinition {
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchants = enchants == null ? List.of() : List.copyOf(enchants);
        flags = flags == null ? List.of() : List.copyOf(flags);
        amount = Math.max(1, Math.min(64, amount));
    }

    /** One line for {@code /astra explain}. */
    public String describe() {
        StringBuilder out = new StringBuilder("item ").append(name).append(": ").append(material);
        if (amount > 1) out.append(" x").append(amount);
        if (displayName != null && !displayName.isBlank()) out.append(" (\"").append(displayName).append("\")");
        if (!enchants.isEmpty()) {
            out.append(" enchanted with ");
            for (int index = 0; index < enchants.size(); index++) {
                if (index > 0) out.append(", ");
                out.append(enchants.get(index).name()).append(' ').append(enchants.get(index).level());
            }
        }
        return out.toString();
    }
}
