package io.quarkiverse.permuplate.example.drools;

import java.util.List;

/**
 * Returned by fn() — wraps a completed RuleDefinition and exposes the same
 * query API (run, executionCount, capturedFact, etc.) plus a no-op end().
 *
 * end() returns null (Void) for top-level rules — it exists so that
 * .fn(...).end() compiles, matching vol2's chain syntax.
 */
public class RuleResult<DS> {
    private final RuleDefinition<DS> rd;

    public RuleResult(RuleDefinition<DS> rd) {
        this.rd = rd;
    }

    public RuleResult<DS> run(DS ctx) {
        rd.run(ctx);
        return this;
    }

    public int executionCount() {
        return rd.executionCount();
    }

    public Object capturedFact(int execution, int position) {
        return rd.capturedFact(execution, position);
    }

    public List<Object> capturedFacts(int execution) {
        return rd.capturedFacts(execution);
    }

    public int filterCount() {
        return rd.filterCount();
    }

    public int sourceCount() {
        return rd.sourceCount();
    }

    public boolean hasAction() {
        return rd.hasAction();
    }

    public String name() {
        return rd.name();
    }

    /**
     * No-op terminator. Returns null (Void). Allows .fn(...).end() to compile
     * for rules written in vol2 style requiring explicit scope termination.
     */
    public Void end() {
        return null;
    }
}
