package io.astra.platform;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Default {@link TaskHandle} implementation backing both scheduler backends. */
public final class SimpleTaskHandle implements TaskHandle {

    private final Object owner;
    private final Runnable canceller;
    private final boolean repeating;
    private final String description;
    private final BooleanSupplier externalState;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public SimpleTaskHandle(Object owner, Runnable canceller, boolean repeating, String description) {
        this(owner, canceller, repeating, description, null);
    }

    /**
     * @param externalState optional supplier reporting the underlying scheduler's own
     *                      state (for example {@code BukkitTask#isCancelled}); it lets
     *                      callers observe tasks cancelled outside AstraSyntax
     */
    public SimpleTaskHandle(Object owner, Runnable canceller, boolean repeating, String description,
                            BooleanSupplier externalState) {
        this.owner = owner;
        this.canceller = canceller;
        this.repeating = repeating;
        this.description = description;
        this.externalState = externalState;
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true) && canceller != null) {
            try {
                canceller.run();
            } catch (Throwable ignored) {
                // Cancellation must never propagate into the server thread pool.
            }
        }
    }

    @Override
    public boolean isCancelled() {
        if (cancelled.get()) return true;
        if (externalState == null) return false;
        try {
            return externalState.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean isRepeating() {
        return repeating;
    }

    @Override
    public String describe() {
        return description;
    }

    /** The object that owns the task (used by script lifecycle accounting). */
    public Object owner() {
        return owner;
    }
}
