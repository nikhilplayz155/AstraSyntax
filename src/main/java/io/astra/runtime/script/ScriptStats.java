package io.astra.runtime.script;

/**
 * Per-script runtime counters.
 *
 * <p>These are cheap atomics on purpose: they are updated on the hot path, so they must
 * never allocate or lock. They feed {@code /astra scripts} and the startup summary.</p>
 */
public final class ScriptStats {

    private final java.util.concurrent.atomic.AtomicLong invocations = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong errors = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalMicros = new java.util.concurrent.atomic.AtomicLong();

    public void record(long micros, boolean error) {
        invocations.incrementAndGet();
        totalMicros.addAndGet(micros);
        if (error) errors.incrementAndGet();
    }

    public long invocations() {
        return invocations.get();
    }

    public long errors() {
        return errors.get();
    }

    public long totalMicros() {
        return totalMicros.get();
    }

    public long averageMicros() {
        long count = invocations.get();
        return count == 0 ? 0 : totalMicros.get() / count;
    }
}
