package io.quarkiverse.permuplate.example.drools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.quarkiverse.permuplate.PermuteMixin;

/**
 * Captures the structure of a rule built via the DSL and executes it.
 *
 * <p>
 * Sources are represented as {@link TupleSource} entries — a unified abstraction
 * over single-fact sources (each contributes one fact per step) and bi-linear
 * sub-network sources (each contributes a tuple of N facts per step). This models
 * the Rete bi-linear beta node pattern where a right-input sub-network executes
 * independently and its matched tuples are cross-producted with the left chain.
 *
 * <p>
 * The {@code accumulatedFacts} field tracks the total number of fact columns
 * added so far. This is used by {@link #addFilter} to capture the correct
 * registration position for single-fact filters — {@code sources.size()} is no
 * longer correct since a bi-linear source is one entry but contributes N columns.
 */
@PermuteMixin(VariableFilterMixin.class)
public class RuleDefinition<DS> {

    @FunctionalInterface
    interface NaryPredicate {
        boolean test(Object ctx, Object[] facts);
    }

    @FunctionalInterface
    interface NaryConsumer {
        void accept(Object ctx, Object[] facts);
    }

    /**
     * A source that produces fact-tuples given a context. Linear sources produce
     * singleton-array tuples (one fact each). Bi-linear sources produce multi-element
     * tuples from an independent sub-network execution.
     */
    @FunctionalInterface
    interface TupleSource<DS> {
        List<Object[]> tuples(DS ctx);
    }

    private final String name;
    private final List<TupleSource<DS>> sources = new ArrayList<>();
    private int accumulatedFacts = 0;
    private final List<NaryPredicate> filters = new ArrayList<>();
    private NaryConsumer action;
    private final List<List<Object>> executions = new ArrayList<>();
    private final List<RuleDefinition<DS>> negations = new ArrayList<>();
    private final List<RuleDefinition<DS>> existences = new ArrayList<>();
    private int ooPathRootIndex = -1;
    private final List<OOPathStep> ooPathSteps = new ArrayList<>();

    public RuleDefinition(String name) {
        this.name = name;
    }

    // -------------------------------------------------------------------------
    // Builder methods — called by generated JoinFirst/Second classes
    // -------------------------------------------------------------------------

    /**
     * Adds a single-fact data source. Each element from the DataSource becomes
     * a singleton-array tuple in the cross-product.
     */
    public void addSource(Object sourceSupplier) {
        @SuppressWarnings("unchecked")
        Function<DS, DataSource<?>> fn = (Function<DS, DataSource<?>>) sourceSupplier;
        sources.add(ctx -> fn.apply(ctx).asList().stream()
                .map(f -> new Object[] { f })
                .collect(Collectors.toList()));
        accumulatedFacts += 1;
    }

    /**
     * Adds a bi-linear sub-network source. The sub-network's RuleDefinition is executed
     * independently against the same ctx; its matched tuples are cross-producted with
     * the current chain's tuples. The sub-network's internal filters apply only within
     * the sub-network — they gate which of its tuples enter the cross-product, not which
     * combined tuples pass overall.
     *
     * <p>
     * This models the Rete bi-linear beta node: the right-input sub-network executes
     * as an independent unit. Facts are flattened for method calls (predicates and
     * consumers see a flat Object[] array), matching the Rete convention of traversing
     * the tuple tree when invoking lambdas.
     */
    public void addBilinearSource(RuleDefinition<DS> subNetwork) {
        sources.add(ctx -> subNetwork.matchedTuples(ctx));
        accumulatedFacts += subNetwork.factArity();
    }

    /**
     * Returns the total number of fact columns this RuleDefinition contributes per
     * matched tuple. Used by the parent chain when this RuleDefinition is used as a
     * bi-linear source, so the parent can correctly track accumulatedFacts.
     */
    public int factArity() {
        return accumulatedFacts;
    }

    public void addFilter(Object typedPredicate) {
        // Capture the accumulated fact count at registration time — not sources.size().
        // A bi-linear source is one entry in sources but contributes N fact columns.
        // This count tells wrapPredicate which index is the "latest" fact for
        // single-fact (Predicate2) filters registered after a join.
        int registeredFactCount = accumulatedFacts;
        filters.add(wrapPredicate(typedPredicate, registeredFactCount));
    }

    public void setAction(Object typedConsumer) {
        this.action = wrapConsumer(typedConsumer);
    }

    /**
     * Registers a negation sub-network. During {@link #matchedTuples}, outer tuples
     * are excluded if this sub-network produces ANY matching result (zero-match required).
     * Called by {@code JoinNSecond.not()} before returning the NotScope.
     */
    public void addNot(RuleDefinition<DS> notScope) {
        negations.add(notScope);
    }

    /**
     * Registers an existence sub-network. During {@link #matchedTuples}, outer tuples
     * are excluded if this sub-network produces ZERO matching results (at-least-one required).
     * Called by {@code JoinNSecond.exists()} before returning the ExistsScope.
     */
    public void addExists(RuleDefinition<DS> existsScope) {
        existences.add(existsScope);
    }

    /**
     * Registers an OOPath traversal pipeline. Called by RuleOOPathBuilder.Path2.path()
     * when the chain completes. rootIndex is the 0-based index of the root fact
     * (= factArity()-1 at the time pathN() was called).
     */
    public void addOOPathPipeline(int rootIndex, List<OOPathStep> steps) {
        this.ooPathRootIndex = rootIndex;
        this.ooPathSteps.addAll(steps);
    }

    /**
     * Marks that a params value will occupy fact index 0 at runtime.
     * Called by ParametersFirst before returning a chain builder, so that
     * wrapPredicate()'s trim logic accounts for the injected params fact.
     */
    void addParamsFact() {
        accumulatedFacts++;
    }

    public void bindVariable(Variable<?> v, int factIndex) {
        v.bind(factIndex);
    }

    /**
     * Generic variable filter for m=2..6 variables. Checks all variables are bound,
     * snapshots indices to prevent rebinding corruption, then uses reflection for
     * runtime predicate invocation — consistent with wrapPredicate/wrapConsumer.
     * Called by the generated typed addVariableFilter(v1..vm, predicate) overloads.
     */
    void addVariableFilterGeneric(Object predicate, Variable<?>... variables) {
        for (Variable<?> v : variables) {
            if (!v.isBound())
                throw new IllegalStateException(
                        "Variable '" + v.name() + "' not bound — call var() before filter()");
        }
        int[] indices = new int[variables.length];
        for (int k = 0; k < variables.length; k++) {
            indices[k] = variables[k].index();
        }
        Method m = findMethod(predicate, "test");
        // Intentionally bypasses wrapPredicate — variable indices are explicit snapshots;
        // wrapPredicate's positional-trim logic would be wrong here.
        filters.add((ctx, facts) -> {
            Object[] args = new Object[indices.length + 1];
            args[0] = ctx;
            for (int k = 0; k < indices.length; k++) args[k + 1] = facts[indices[k]];
            try {
                return (Boolean) m.invoke(predicate, args);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Variable filter invocation failed", e);
            }
        });
    }

    /**
     * Copies this rule's sources, accumulated fact count, filters, and constraint
     * scopes (not/exists) into the target RuleDefinition. Called by
     * RuleBuilder.extendsRule() to inline base patterns into the child at build time.
     *
     * Does NOT copy: action, executions, or OOPath pipeline — those belong to each
     * rule independently.
     */
    void copyInto(RuleDefinition<DS> target) {
        target.sources.addAll(this.sources);
        target.accumulatedFacts = this.accumulatedFacts;
        target.filters.addAll(this.filters);
        // Constraint scopes propagate — they are part of the pattern being inherited.
        target.negations.addAll(this.negations);
        target.existences.addAll(this.existences);
        // OOPath pipeline intentionally NOT copied — it is a post-match traversal
        // applied per rule, not a pattern constraint. Each child rule defines its
        // own traversal independently.
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Executes this rule against the given context, recording matched fact combinations.
     */
    public RuleDefinition<DS> run(DS ctx) {
        executions.clear();
        for (Object[] facts : matchedTuples(ctx)) {
            if (action != null)
                action.accept(ctx, facts);
            executions.add(Arrays.asList(facts));
        }
        return this;
    }

    /**
     * Executes this rule with a params value as the first fact (index 0).
     * The params value is injected as a single-element initial combination;
     * all data sources cross-product on top of it.
     */
    public RuleDefinition<DS> run(DS ctx, Object params) {
        executions.clear();
        List<Object[]> initial = new java.util.ArrayList<>();
        initial.add(new Object[] { params });
        for (Object[] facts : matchedTuplesFrom(ctx, initial)) {
            if (action != null)
                action.accept(ctx, facts);
            executions.add(Arrays.asList(facts));
        }
        return this;
    }

    /**
     * Executes sources and filters, returning the list of matched fact-tuple arrays.
     * Called by {@link #run} and also by parent chains when this RuleDefinition is
     * used as a bi-linear source via {@link #addBilinearSource}.
     */
    List<Object[]> matchedTuples(DS ctx) {
        List<Object[]> initial = new ArrayList<>();
        initial.add(new Object[0]);
        return matchedTuplesFrom(ctx, initial);
    }

    /**
     * Executes sources and filters starting from the given initial combinations.
     * Used by {@link #matchedTuples} (starting from an empty tuple) and by
     * {@link #run(Object, Object)} (starting from a params-seeded tuple).
     */
    List<Object[]> matchedTuplesFrom(DS ctx, List<Object[]> initial) {
        List<Object[]> combinations = initial;

        for (TupleSource<DS> source : sources) {
            List<Object[]> next = new ArrayList<>();
            for (Object[] tuple : source.tuples(ctx)) {
                for (Object[] combo : combinations) {
                    Object[] extended = Arrays.copyOf(combo, combo.length + tuple.length);
                    System.arraycopy(tuple, 0, extended, combo.length, tuple.length);
                    next.add(extended);
                }
            }
            combinations = next;
        }

        List<Object[]> filtered = combinations.stream()
                .filter(facts -> filters.stream().allMatch(f -> f.test(ctx, facts)))
                // not() constraint: outer tuple valid only if scope produces ZERO matches.
                // Scope evaluates independently against ctx (sandbox simplification —
                // full Drools tracks per-outer-tuple via beta memory).
                .filter(facts -> negations.stream()
                        .allMatch(neg -> neg.matchedTuples(ctx).isEmpty()))
                // exists() constraint: outer tuple valid only if scope produces AT LEAST ONE match.
                .filter(facts -> existences.stream()
                        .allMatch(ex -> !ex.matchedTuples(ctx).isEmpty()))
                .collect(Collectors.toList());
        return applyOOPath(filtered, ctx);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> applyOOPath(List<Object[]> combinations, DS ctx) {
        if (ooPathRootIndex < 0)
            return combinations;
        List<Object[]> expanded = new ArrayList<>();
        for (Object[] facts : combinations) {
            Object rootFact = facts[ooPathRootIndex];
            BaseTuple emptyTuple = createEmptyTuple(ooPathSteps.size() + 1);
            emptyTuple.set(0, rootFact);
            PathContext<BaseTuple> pathCtx = new PathContext<>(emptyTuple);
            for (BaseTuple t : executePipeline(rootFact, pathCtx, 0)) {
                Object[] extended = Arrays.copyOf(facts, facts.length + 1);
                extended[facts.length] = t;
                expanded.add(extended);
            }
        }
        return expanded;
    }

    private List<BaseTuple> executePipeline(Object currentFact,
            PathContext<BaseTuple> pathCtx, int stepIndex) {
        if (stepIndex >= ooPathSteps.size()) {
            // Copy the tuple — pathCtx tuple is shared/mutable across sibling branches
            return java.util.Collections.singletonList(copyTuple(pathCtx.getTuple()));
        }
        OOPathStep step = ooPathSteps.get(stepIndex);
        List<BaseTuple> results = new ArrayList<>();
        for (Object child : step.traversal.apply(pathCtx, currentFact)) {
            if (step.filter.test(pathCtx, child)) {
                pathCtx.getTuple().set(stepIndex + 1, child);
                results.addAll(executePipeline(child, pathCtx, stepIndex + 1));
            }
        }
        return results;
    }

    private BaseTuple copyTuple(BaseTuple source) {
        BaseTuple copy = createEmptyTuple(source.size());
        for (int i = 0; i < source.size(); i++) {
            copy.set(i, source.get(i));
        }
        return copy;
    }

    private BaseTuple createEmptyTuple(int size) {
        return switch (size) {
            case 1 -> new BaseTuple.Tuple1<>();
            case 2 -> new BaseTuple.Tuple2<>();
            case 3 -> new BaseTuple.Tuple3<>();
            case 4 -> new BaseTuple.Tuple4<>();
            case 5 -> new BaseTuple.Tuple5<>();
            case 6 -> new BaseTuple.Tuple6<>();
            default -> throw new IllegalArgumentException("OOPath depth " + size + " exceeds maximum of 6");
        };
    }

    // -------------------------------------------------------------------------
    // Test assertions API
    // -------------------------------------------------------------------------

    public String name() {
        return name;
    }

    /** Number of source entries (not fact columns — use factArity() for columns). */
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

    private static NaryPredicate wrapPredicate(Object typed, int registeredFactCount) {
        Method m = findMethod(typed, "test");
        int factArity = m.getParameterCount() - 1;
        return (ctx, facts) -> {
            try {
                Object[] trimmed;
                if (factArity == 1 && registeredFactCount > 1) {
                    // Single-fact filter: pick the fact at the registered position.
                    // registeredFactCount - 1 is the 0-based index of the latest fact
                    // at the time this filter was registered via addFilter().
                    trimmed = new Object[] { facts[registeredFactCount - 1] };
                } else if (facts.length > factArity) {
                    // Multi-fact filter registered before all joins were added:
                    // truncate to the facts that were in scope at registration time.
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
