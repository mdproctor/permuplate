package io.quarkiverse.permuplate.example.drools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Captures the structure of a rule built via the DSL and executes it.
 *
 * <p>
 * All JoinFirst and JoinSecond generated classes hold a reference to a shared
 * RuleDefinition. Typed predicates and consumers are wrapped into internal
 * NaryPredicate/NaryConsumer representations via reflection — called once at
 * rule-build time, not on every run().
 *
 * <p>
 * {@code asNext()} uses an unchecked cast: the fluent chain relies on Java's
 * type erasure so that the same RuleDefinition object satisfies any Join(N)First
 * declared return type. This is documented in DROOLS-DSL.md.
 */
public class RuleDefinition<DS> {

    @FunctionalInterface
    interface NaryPredicate {
        boolean test(Object ctx, Object[] facts);
    }

    @FunctionalInterface
    interface NaryConsumer {
        void accept(Object ctx, Object[] facts);
    }

    private final String name;
    private final List<Function<DS, DataSource<?>>> sources = new ArrayList<>();
    private final List<NaryPredicate> filters = new ArrayList<>();
    private NaryConsumer action;
    private final List<List<Object>> executions = new ArrayList<>();

    public RuleDefinition(String name) {
        this.name = name;
    }

    // -------------------------------------------------------------------------
    // Builder methods — called by generated JoinFirst/Second classes
    // -------------------------------------------------------------------------

    public void addSource(Object sourceSupplier) {
        @SuppressWarnings("unchecked")
        Function<DS, DataSource<?>> typed = (Function<DS, DataSource<?>>) sourceSupplier;
        sources.add(typed);
    }

    public void addFilter(Object typedPredicate) {
        // Capture the number of sources registered so far. This tells wrapPredicate
        // which fact index corresponds to the "latest" fact for single-fact filters.
        int registeredSourceCount = sources.size();
        filters.add(wrapPredicate(typedPredicate, registeredSourceCount));
    }

    public void setAction(Object typedConsumer) {
        this.action = wrapConsumer(typedConsumer);
    }

    /**
     * Returns this RuleDefinition as the next JoinFirst type in the chain.
     * Uses an unchecked cast — safe because the fluent chain never stores the
     * intermediate type; the JVM erases generics and the reference is valid.
     */
    @SuppressWarnings("unchecked")
    public <T> T asNext() {
        return (T) this;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    public RuleDefinition<DS> run(DS ctx) {
        executions.clear();
        // Build cross-product of all sources
        List<Object[]> combinations = new ArrayList<>();
        combinations.add(new Object[0]);
        for (Function<DS, DataSource<?>> supplier : sources) {
            List<Object[]> next = new ArrayList<>();
            for (Object item : supplier.apply(ctx).asList()) {
                for (Object[] combo : combinations) {
                    Object[] extended = Arrays.copyOf(combo, combo.length + 1);
                    extended[combo.length] = item;
                    next.add(extended);
                }
            }
            combinations = next;
        }
        // Apply filters, then execute action for each matching combination
        for (Object[] facts : combinations) {
            if (filters.stream().allMatch(f -> f.test(ctx, facts))) {
                if (action != null) {
                    action.accept(ctx, facts);
                }
                executions.add(Arrays.asList(facts));
            }
        }
        return this;
    }

    // -------------------------------------------------------------------------
    // Test assertions API
    // -------------------------------------------------------------------------

    public String name() {
        return name;
    }

    public int sourceCount() {
        return sources.size();
    }

    public int filterCount() {
        return filters.size();
    }

    public boolean hasAction() {
        return action != null;
    }

    public int executionCount() {
        return executions.size();
    }

    public List<Object> capturedFacts(int execution) {
        return Collections.unmodifiableList(executions.get(execution));
    }

    public Object capturedFact(int execution, int position) {
        return executions.get(execution).get(position);
    }

    // -------------------------------------------------------------------------
    // Reflection wrappers — called once at rule-build time
    // -------------------------------------------------------------------------

    private static NaryPredicate wrapPredicate(Object typed, int registeredSourceCount) {
        Method m = findMethod(typed, "test");
        // m.getParameterCount() - 1 gives the number of fact parameters (excluding ctx).
        int factArity = m.getParameterCount() - 1;
        return (ctx, facts) -> {
            try {
                Object[] trimmed;
                if (factArity == 1 && registeredSourceCount > 1) {
                    // Single-fact filter: the user wrote filter((ctx, b) -> ...) after joining
                    // multiple sources. Pick the fact at the registered position (0-based index
                    // = registeredSourceCount - 1), which is the latest-joined fact at registration.
                    trimmed = new Object[] { facts[registeredSourceCount - 1] };
                } else if (facts.length > factArity) {
                    // Intermediate all-facts filter: truncate to the facts in scope when registered.
                    trimmed = Arrays.copyOf(facts, factArity);
                } else {
                    trimmed = facts;
                }
                Object[] args = buildArgs(ctx, trimmed);
                return (Boolean) m.invoke(typed, args);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Predicate invocation failed", e);
            }
        };
    }

    private static NaryConsumer wrapConsumer(Object typed) {
        Method m = findMethod(typed, "accept");
        return (ctx, facts) -> {
            try {
                Object[] args = buildArgs(ctx, facts);
                m.invoke(typed, args);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Consumer invocation failed", e);
            }
        };
    }

    private static Method findMethod(Object target, String name) {
        return Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getName().equals(name) && !m.isSynthetic())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No method '" + name + "' on " + target.getClass()));
    }

    private static Object[] buildArgs(Object ctx, Object[] facts) {
        Object[] args = new Object[facts.length + 1];
        args[0] = ctx;
        System.arraycopy(facts, 0, args, 1, facts.length);
        return args;
    }
}
