package io.quarkiverse.permuplate.example.drools;

/**
 * Marker interface implemented by all generated JoinNGate classes.
 *
 * <p>
 * Exposes the underlying {@link RuleDefinition} so that bi-linear join bodies
 * can extract the right sub-network's definition without reflection. All generated
 * JoinNGate classes implement this via the {@code Join0Gate} template.
 *
 * <p>
 * Used as a cast target in {@code joinBilinear()} bodies:
 *
 * <pre>
 * JoinGate&lt;DS&gt; gate = (JoinGate&lt;DS&gt;) secondChain;
 * rd.addBilinearSource(gate.getRuleDefinition());
 * </pre>
 *
 * <p>
 * Since {@code JoinNFirst extends JoinNGate}, a pre-built {@code JoinNFirst}
 * satisfies this interface and can be passed wherever {@code JoinNGate} is expected —
 * the key property enabling bi-linear node-sharing patterns.
 */
public interface JoinGate<DS> {
    RuleDefinition<DS> getRuleDefinition();
}
