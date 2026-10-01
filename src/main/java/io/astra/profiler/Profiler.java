package io.astra.profiler;

import java.util.Collection;
import java.util.List;

/**
 * Optional rule profiler.
 *
 * <p>Two switches feed it: {@code performance.yml: profiler.enabled} and
 * {@code config.yml: developer.profiler}. When both are off the executor calls a
 * profile method that immediately returns, and no per-rule counters are created -
 * the project requirement that disabled profiling must not cost anything.</p>
 */
public interface Profiler {

    /** True when statistics are being collected. */
    boolean enabled();

    /** Record one rule invocation. */
    void record(String ruleId, String scriptName, long micros, boolean error);

    /** Statistics for a rule, creating it on first use (only when enabled). */
    RuleStats stats(String ruleId, String scriptName);

    /** Every tracked rule. */
    Collection<RuleStats> all();

    /** The slowest rules, descending. */
    List<RuleStats> slowest(int limit);

    /** The most recently observed execution time for a rule, in microseconds. */
    long lastMicros(String ruleId);

    /** True when a rule exceeded the configured slow-rule threshold recently. */
    boolean isSlow(String ruleId);

    void reset();

    /** Threshold in microseconds from {@code performance.yml}. */
    long slowThresholdMicros();
}
