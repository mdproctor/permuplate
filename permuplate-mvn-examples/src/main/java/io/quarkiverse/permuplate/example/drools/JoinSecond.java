package io.quarkiverse.permuplate.example.drools;

/**
 * Marker interface implemented by all generated JoinNSecond classes.
 *
 * <p>
 * Exposes the underlying {@link RuleDefinition} so that bi-linear join bodies
 * can extract the right sub-network's definition without reflection. All generated
 * JoinNSecond classes implement this via the {@code Join0Second} template.
 *
 * <p>
 * Used as a cast target in {@code joinBilinear()} bodies:
 *
 * <pre>
 * JoinSecond&lt;DS&gt; second = (JoinSecond&lt;DS&gt;) secondChain;
 * rd.addBilinearSource(second.getRuleDefinition());
 * </pre>
 *
 * <p>
 * Since {@code JoinNFirst extends JoinNSecond}, a pre-built {@code JoinNFirst}
 * satisfies this interface and can be passed wherever {@code JoinNSecond} is expected —
 * the key property enabling bi-linear node-sharing patterns.
 */
public interface JoinSecond<DS> {
    RuleDefinition<DS> getRuleDefinition();
}
