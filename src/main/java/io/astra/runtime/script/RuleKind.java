package io.astra.runtime.script;

/** What kind of trigger a compiled rule has. */
public enum RuleKind {

    /** A Minecraft event rule. */
    EVENT,
    /** A repeating timer. */
    TIMER,
    /** A script-defined command body. */
    COMMAND,
    /** A reusable action. */
    FUNCTION,
    /** A data threshold rule ("when they reach 100 coins"). */
    DATA_THRESHOLD,
    /** A GUI interaction rule. */
    MENU;

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
