package io.astra.runtime.script;

/**
 * Lifecycle state of a script.
 *
 * <p>The states are ordered so the manager can always tell whether a script is safe to
 * activate, and so a failed reload can keep the previous version running.</p>
 */
public enum ScriptState {

    /** Found on disk, not read yet. */
    DISCOVERED,
    /** Currently being read/lexed/parsed. */
    PARSING,
    /** Parsed and compiled successfully, not active yet. */
    COMPILED,
    /** Resources registered (listeners, commands, tasks). */
    LOADED,
    /** Actively handling events. */
    ENABLED,
    /** Disabled by an administrator. */
    DISABLED,
    /** Compilation or loading failed; the script is not active. */
    ERROR,
    /** Unloaded and its resources released. */
    UNLOADED;

    /** True when the script currently handles events. */
    public boolean isActive() {
        return this == ENABLED;
    }

    /** True when the script holds registered resources that must be released. */
    public boolean holdsResources() {
        return this == ENABLED || this == LOADED;
    }
}
