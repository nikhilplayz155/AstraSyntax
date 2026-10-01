package io.astra.profiler;

import java.util.List;

/**
 * Collects the steps of one rule execution for {@code /astra trace}.
 *
 * <p>Tracing is opt-in: sessions are only created when a trace is armed, and the
 * executor checks a single boolean before recording a step, so the overhead in normal
 * operation is one null check.</p>
 */
public interface TraceSession {

    /** Record one step ("Matched rewards.ar:14"). */
    void step(String message);

    /** Finish the session with an outcome summary. */
    void finish(String outcome, long micros);

    /** True while the session is collecting. */
    boolean active();

    /** The rule being traced. */
    String ruleId();

    /** Collected steps in order. */
    List<String> steps();

    /** Total duration in microseconds once finished. */
    long durationMicros();

    /** A session that discards everything. */
    TraceSession NOOP = new TraceSession() {
        @Override public void step(String message) { }
        @Override public void finish(String outcome, long micros) { }
        @Override public boolean active() { return false; }
        @Override public String ruleId() { return "-"; }
        @Override public List<String> steps() { return List.of(); }
        @Override public long durationMicros() { return 0L; }
    };
}
