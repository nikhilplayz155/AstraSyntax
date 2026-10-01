package io.astra.profiler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Invocation statistics for one rule.
 *
 * <p>Counters are atomics updated with {@code addAndGet}, never locks, so enabling the
 * profiler costs a few nanoseconds per invocation instead of perturbing the timing it
 * is supposed to measure.</p>
 */
public final class RuleStats {

    private final String ruleId;
    private final String scriptName;
    private final AtomicLong invocations = new AtomicLong();
    private final AtomicLong totalMicros = new AtomicLong();
    private final AtomicLong maxMicros = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong lastMicros = new AtomicLong();
    private final long windowStart = System.currentTimeMillis();

    public RuleStats(String ruleId, String scriptName) {
        this.ruleId = ruleId;
        this.scriptName = scriptName;
    }

    public String ruleId() {
        return ruleId;
    }

    public String scriptName() {
        return scriptName;
    }

    public long invocations() {
        return invocations.get();
    }

    public long totalMicros() {
        return totalMicros.get();
    }

    public long errors() {
        return errors.get();
    }

    public long lastMicros() {
        return lastMicros.get();
    }

    public long maxMicros() {
        return maxMicros.get();
    }

    public long averageMicros() {
        long count = invocations.get();
        return count == 0 ? 0 : totalMicros.get() / count;
    }

    public long windowStartMillis() {
        return windowStart;
    }

    /** Invocations per second since this rule was first loaded. */
    public double invocationsPerSecond() {
        long elapsed = Math.max(1L, System.currentTimeMillis() - windowStart);
        return invocations.get() * 1000.0 / elapsed;
    }

    /** Record one invocation. */
    public void record(long micros, boolean error) {
        invocations.incrementAndGet();
        totalMicros.addAndGet(micros);
        lastMicros.set(micros);
        if (error) errors.incrementAndGet();
        long currentMax = maxMicros.get();
        if (micros > currentMax) maxMicros.compareAndSet(currentMax, micros);
    }

    /** Reset all counters (used by {@code /astra performance reset}). */
    public void reset() {
        invocations.set(0);
        totalMicros.set(0);
        maxMicros.set(0);
        errors.set(0);
        lastMicros.set(0);
    }
}
