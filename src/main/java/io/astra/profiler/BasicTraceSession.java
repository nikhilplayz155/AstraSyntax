package io.astra.profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The trace session behind {@code /astra trace <script>}.
 *
 * <p>One session is armed by the administrator for one script; the dispatcher attaches it
 * to the matching execution, the executor records a step per filter, condition and action,
 * and the admin command prints the collected steps. Sessions are single use - a trace that
 * stayed armed would keep allocating on the hot path.</p>
 */
public final class BasicTraceSession implements TraceSession {

    private final String ruleId;
    private final long startedNanos = System.nanoTime();
    private final List<String> steps = new ArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile long durationMicros;
    private volatile String outcome = "running";

    public BasicTraceSession(String ruleId) {
        this.ruleId = ruleId == null ? "-" : ruleId;
    }

    @Override
    public synchronized void step(String message) {
        if (!active.get()) return;
        steps.add((System.nanoTime() - startedNanos) / 1000L + "us " + message);
        if (steps.size() > 512) steps.remove(0);
    }

    @Override
    public synchronized void finish(String outcome, long micros) {
        this.outcome = outcome;
        this.durationMicros = micros;
        this.active.set(false);
    }

    @Override
    public boolean active() {
        return active.get();
    }

    @Override
    public String ruleId() {
        return ruleId;
    }

    @Override
    public synchronized List<String> steps() {
        return new ArrayList<>(steps);
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    /** The final outcome once the session finished. */
    public String outcome() {
        return outcome;
    }

    /** True when the session never saw an execution (used to time the trace out). */
    public boolean untouched() {
        return active.get() && steps.isEmpty();
    }
}
