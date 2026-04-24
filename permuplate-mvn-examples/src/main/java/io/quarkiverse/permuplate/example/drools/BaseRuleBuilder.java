package io.quarkiverse.permuplate.example.drools;

/**
 * Base class for all generated JoinNFirst and JoinNGate classes.
 *
 * <p>
 * The {@code END} phantom type parameter enables typed nested scopes. When a
 * scope-creating operation ({@code not()}, {@code exists()}) is called on a JoinNFirst,
 * it captures {@code this} as the END for the inner scope's builder. Calling {@code end()}
 * inside the inner scope returns that outer builder — fully typed — allowing the fluent
 * chain to continue at the outer arity.
 *
 * <p>
 * For top-level rules (created via {@link RuleBuilder#from}), END is {@code Void}
 * and {@code end()} returns {@code null}. It is never called on top-level chains.
 *
 * <p>
 * Arity trace showing END and end() in action (from real Drools pattern):
 *
 * <pre>
 *   .params()           → From1First&lt;Void,DS,Params3&gt;                  arity: 1
 *   .join(persons)      → Join2First&lt;Void,DS,Params3,Person&gt;            arity: 2
 *   .not()              → Not2&lt;Join2Gate&lt;Void,...&gt;, DS, Params3, Person&gt;
 *       .join(misc)     → Join3First&lt;Join2Gate&lt;Void,...&gt;, ...&gt;         arity: 3 (inside scope)
 *       .join(libs)     → Join4First&lt;Join2Gate&lt;Void,...&gt;, ...&gt;         arity: 4 (inside scope)
 *   .end()              → Join2Gate&lt;Void,DS,Params3,Person&gt;            arity: 2 (reset!)
 *   .fn((a,b,c) -&gt; ...)  Consumer3&lt;Context&lt;DS&gt;,Params3,Person&gt;          arity 2 confirmed
 * </pre>
 *
 * <p>
 * The not-scope facts (misc, libs) are NOT added to the outer chain's arity — they
 * only constrain which outer (Params3, Person) combinations are valid. This matches the
 * Rete NegativeExistsNode pattern: the inner sub-network filters the outer tuples but
 * does not contribute additional fact types to the outer chain.
 */
public class BaseRuleBuilder<END> {

    private final END end;

    public BaseRuleBuilder(END end) {
        this.end = end;
    }

    /**
     * Returns to the outer builder context, resetting the chain's arity to what it
     * was before the current scope was entered. For top-level chains (END = Void),
     * returns null and should never be called.
     */
    public END end() {
        return end;
    }
}
