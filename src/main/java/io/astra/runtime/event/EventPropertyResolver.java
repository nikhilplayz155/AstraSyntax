package io.astra.runtime.event;

import io.astra.runtime.ExecContext;
import io.astra.runtime.Value;
import io.astra.runtime.ValueType;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Resolves properties of the event currently being handled.
 *
 * <p>Event properties are resolved with plain {@code instanceof} checks against stable
 * Bukkit API interfaces rather than per-event reflection or NMS, so a property such as
 * {@code {damage}} or {@code {block}} works across every supported platform.</p>
 */
public final class EventPropertyResolver {

    private static final Set<String> OWNED = new LinkedHashSet<>(java.util.List.of(
        "block", "block type", "material", "entity", "entity type", "killer", "victim", "damage", "damage cause",
        "final damage", "message", "slot", "item", "amount", "item amount", "item material", "item name",
        "canceled", "cancelled", "reason", "command", "projectile", "source", "menu", "button", "sender",
        "region", "previous region", "npc",
        "world", "exp to drop", "x", "y", "z", "location",
        "killer is player", "victim is player", "entity is player"));

    private EventPropertyResolver() {}

    /** True when this resolver owns the property name. */
    public static boolean owns(String property) {
        return property != null && OWNED.contains(property.toLowerCase(Locale.ROOT));
    }

    /** All property names this resolver can answer. */
    public static Set<String> properties() {
        return OWNED;
    }

    /** Resolve a property of the current event. */
    public static Value resolve(ExecContext context, String property) {
        Event event = context == null ? null : context.event();
        if (event == null || property == null) return Value.NULL;
        String key = property.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');

        // Universal properties -------------------------------------------------
        switch (key) {
            case "canceled": case "cancelled":
                return Value.bool(context.isEventCancelled());
            case "world":
                return context.world() == null ? Value.NULL : Value.str(context.world().getName());
            case "location": {
                var location = context.location();
                return location == null ? Value.NULL : Value.location(location.getWorld().getName(), location.getX(),
                    location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            }
            case "x": case "y": case "z": {
                var location = context.location();
                if (location == null) return Value.NULL;
                return Value.dec(switch (key) {
                    case "x" -> location.getX();
                    case "y" -> location.getY();
                    default -> location.getZ();
                });
            }
            default:
                break;
        }

        // Gameplay properties: the region a crossing happened in, and whether the
        // entity an interaction targeted is an Astra NPC.
        if (event instanceof io.astra.runtime.region.RegionEvents.Transition crossing) {
            switch (key) {
                case "region":
                    return Value.str(crossing.regionName());
                case "previous region": {
                    var previous = crossing.previous();
                    return previous == null ? Value.NULL : Value.str(previous.name());
                }
                default:
                    break;
            }
        }
        if ("npc".equals(key)) {
            var gameplay = context.services() == null ? null : context.services().gameplay();
            var target = context.secondary() != null ? context.secondary() : context.actor();
            if (gameplay != null && gameplay.npcs() != null && target != null) {
                String name = gameplay.npcs().nameOf(target);
                return name == null ? Value.NULL : Value.str(name);
            }
            return Value.NULL;
        }

        if (event instanceof BlockEvent blockEvent) {
            Block block = blockEvent.getBlock();
            switch (key) {
                case "block": case "block type": case "material":
                    return Value.material(block.getType().name(), 1);
                case "entity": case "entity type":
                    return Value.NULL;
                default:
                    break;
            }
        }

        // Actor predicates: cheap boolean filters such as "the killer is a player",
        // which is what the "player kills a mob" trigger needs.
        if (key.endsWith(" is player")) {
            String subject = key.substring(0, key.length() - " is player".length());
            Entity subjectEntity = null;
            if (event instanceof EntityEvent entityEvent) {
                subjectEntity = switch (subject) {
                    case "killer" -> entityEvent.getEntity() instanceof org.bukkit.entity.LivingEntity living
                        ? living.getKiller() : null;
                    case "victim" -> entityEvent.getEntity();
                    case "entity" -> entityEvent.getEntity();
                    default -> null;
                };
            }
            if (subjectEntity == null && event instanceof EntityDamageEvent damage) {
                subjectEntity = damage.getEntity();
            }
            return Value.bool(subjectEntity instanceof org.bukkit.entity.Player);
        }

        if (event instanceof EntityDamageEvent damageEvent) {
            switch (key) {
                case "damage": case "amount":
                    return Value.dec(damageEvent.getDamage());
                case "final damage":
                    return Value.dec(damageEvent.getFinalDamage());
                case "damage cause":
                    return Value.str(damageEvent.getCause().name().toLowerCase(Locale.ROOT).replace('_', ' '));
                default:
                    break;
            }
        }

        if (event instanceof EntityEvent entityEvent) {
            Entity entity = entityEvent.getEntity();
            switch (key) {
                case "entity":
                    return Value.entity(entity, entity instanceof org.bukkit.entity.Player p ? ValueType.PLAYER : ValueType.ENTITY,
                        ExecContext.describeEntity(entity));
                case "entity type":
                    return Value.str(entity.getType().name().toLowerCase(Locale.ROOT));
                case "killer": case "attacker": {
                    if (event instanceof org.bukkit.event.entity.EntityDeathEvent deathEvent) {
                        org.bukkit.entity.Player killer = deathEvent.getEntity().getKiller();
                        return killer == null ? Value.NULL
                            : Value.entity(killer, ValueType.PLAYER, killer.getName());
                    }
                    return Value.NULL;
                }
                case "victim":
                    return Value.entity(entity, ValueType.ENTITY, ExecContext.describeEntity(entity));
                case "source": {
                    if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity) {
                        Entity damager = byEntity.getDamager();
                        return Value.entity(damager, damager instanceof org.bukkit.entity.Player p ? ValueType.PLAYER : ValueType.ENTITY,
                            ExecContext.describeEntity(damager));
                    }
                    return Value.NULL;
                }
                case "projectile": {
                    if (event instanceof org.bukkit.event.entity.ProjectileHitEvent hit) {
                        Entity projectile = hit.getEntity();
                        return Value.entity(projectile, ValueType.ENTITY, ExecContext.describeEntity(projectile));
                    }
                    return Value.NULL;
                }
                case "exp to drop": {
                    if (event instanceof org.bukkit.event.entity.EntityDeathEvent deathEvent) {
                        return Value.num(deathEvent.getDroppedExp());
                    }
                    return Value.NULL;
                }
                default:
                    break;
            }
        }

        if (event instanceof org.bukkit.event.player.PlayerEvent playerEvent && "message".equals(key)
            && event instanceof org.bukkit.event.player.AsyncPlayerChatEvent chat) {
            return Value.str(chat.getMessage());
        }

        if (event instanceof InventoryEvent inventoryEvent) {
            switch (key) {
                case "item": case "item material": case "item amount": case "amount": case "slot": {
                    if (event instanceof org.bukkit.event.inventory.InventoryClickEvent click) {
                        ItemStack item = click.getCurrentItem();
                        return switch (key) {
                            case "slot" -> Value.num(click.getSlot());
                            case "amount" -> Value.num(item == null ? 0 : item.getAmount());
                            case "item material" -> Value.material(item == null ? "AIR" : item.getType().name(), 1);
                            case "item name" -> Value.str(item == null || !item.hasItemMeta() || item.getItemMeta().getDisplayName() == null
                                ? "" : String.valueOf(item.getItemMeta().getDisplayName()));
                            default -> item == null || item.getType().isAir() ? Value.NULL
                                : Value.material(item.getType().name(), item.getAmount());
                        };
                    }
                    return Value.NULL;
                }
                case "menu": case "button": case "sender":
                    return Value.NULL;
                default:
                    break;
            }
        }

        if (event instanceof org.bukkit.event.player.PlayerCommandPreprocessEvent commandEvent && "command".equals(key)) {
            return Value.str(commandEvent.getMessage());
        }
        if (event instanceof org.bukkit.event.player.PlayerEvent playerEvent) {
            switch (key) {
                case "entity": case "player":
                    return Value.entity(playerEvent.getPlayer(), ValueType.PLAYER, playerEvent.getPlayer().getName());
                default:
                    break;
            }
        }
        return Value.NULL;
    }
}
