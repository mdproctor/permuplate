package io.quarkiverse.permuplate.example.drools;

/**
 * Typed named binding for cross-fact predicates in the rule DSL.
 *
 * <p>
 * Create via {@code Variable.of("$person")} to match DRL naming conventions,
 * or via {@code new Variable<>()} for anonymous bindings. Pass to {@code var()}
 * to bind to a specific accumulated fact, then pass to variable-based
 * {@code filter()} overloads to reference that fact by name rather than position.
 *
 * <p>
 * Matches the {@code Variable<T>} pattern in Drools vol2 for migration fidelity.
 */
public class Variable<T> {
    private final String name;
    private int index = -1;

    /** Creates an anonymous variable (no diagnostic name). */
    public Variable() {
        this.name = "<anonymous>";
    }

    private Variable(String name) {
        this.name = name;
    }

    /**
     * Creates a named variable. Use DRL-style naming (e.g., "$person", "$account")
     * to match DRL migration conventions.
     */
    public static <T> Variable<T> of(String name) {
        return new Variable<>(name);
    }

    /** Binds this variable to the given fact index. Called by RuleDefinition.bindVariable(). */
    public void bind(int index) {
        this.index = index;
    }

    /** Returns the 0-based index of the bound fact in the accumulated facts array. */
    public int index() {
        return index;
    }

    /** Returns true if this variable has been bound via var(). */
    public boolean isBound() {
        return index >= 0;
    }

    /** Returns the variable name, for diagnostic messages. */
    public String name() {
        return name;
    }
}
