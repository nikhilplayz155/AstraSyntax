package io.astra.profiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The shipped {@link Profiler}: per-rule counters plus a bounded history of slow rules.
 *
 * <p>It is switched on by {@code performance.yml: profiler.enabled} or
 * {@code config.yml: developer.profiler}. While it is off,
 * {@link io.astra.runtime.script.RuleExecutor} only reads one volatile boolean and never
 * allocates, which is what keeps a disabled profiler free.</p>
 */
public final class SimpleProfiler implements Profiler {

    private final long thresholdMicros;
    private final int historySize;
    private final AtomicBoolean enabled = new AtomicBoolean();
    private final Map<String, RuleStats> stats = new ConcurrentHashMap<>();
    private final java.util.Deque<String> slowHistory = new java.util.ArrayDeque<>();
    private final Map<String, Long> lastMicros = new ConcurrentHashMap<>();

    public SimpleProfiler(boolean enabled, long thresholdMicros, int historySize) {
        this.enabled.set(enabled);
        this.thresholdMicros = Math.max(1L, thresholdMicros);
        this.historySize = Math.max(10, historySize);
    }

    @Override
    public boolean enabled() {
        return enabled.get();
    }

    /** Turn collection on or off at runtime ({@code /astra debug}). */
    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    @Override
    public void record(String ruleId, String scriptName, long micros, boolean error) {
        if (!enabled.get() || ruleId == null) return;
        RuleStats entry = stats.computeIfAbsent(ruleId, key -> new RuleStats(key, scriptName));
        entry.record(micros, error);
        lastMicros.put(ruleId, micros);
        if (micros >= thresholdMicros) {
            synchronized (slowHistory) {
                slowHistory.addLast(ruleId + " took " + (micros / 1000.0) + "ms");
                while (slowHistory.size() > historySize) slowHistory.removeFirst();
            }
        }
    }

    @Override
    public RuleStats stats(String ruleId, String scriptName) {
        return stats.computeIfAbsent(ruleId, key -> new RuleStats(key, scriptName));
    }

    @Override
    public Collection<RuleStats> all() {
        return new ArrayList<>(stats.values());
    }

    @Override
    public List<RuleStats> slowest(int limit) {
        List<RuleStats> sorted = new ArrayList<>(stats.values());
        sorted.sort(Comparator.comparingLong(RuleStats::averageMicros).reversed());
        return sorted.size() > limit ? new ArrayList<>(sorted.subList(0, limit)) : sorted;
    }

    @Override
    public long lastMicros(String ruleId) {
        Long value = lastMicros.get(ruleId);
        return value == null ? 0L : value;
    }

    @Override
    public boolean isSlow(String ruleId) {
        return lastMicros(ruleId) >= thresholdMicros;
    }

    @Override
    public void reset() {
        stats.clear();
        lastMicros.clear();
        synchronized (slowHistory) {
            slowHistory.clear();
        }
    }

    @Override
    public long slowThresholdMicros() {
        return thresholdMicros;
    }

    /** The most recent slow-rule observations, newest last. */
    public List<String> slowHistory() {
        synchronized (slowHistory) {
            return new ArrayList<>(slowHistory);
        }
    }

    /** A snapshot for {@code /astra performance}, without exposing mutable state. */
    public Map<String, RuleStats> snapshot() {
        return new LinkedHashMap<>(stats);
    }
}
