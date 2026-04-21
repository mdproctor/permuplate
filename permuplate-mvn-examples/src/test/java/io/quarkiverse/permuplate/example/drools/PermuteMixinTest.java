package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;

import org.junit.Test;

/**
 * Verifies that @PermuteMixin correctly injects extendsRule() overloads into both
 * RuleBuilder and ParametersFirst from a single shared mixin class.
 */
public class PermuteMixinTest {

    @Test
    public void testRuleBuilderHasExtendsRuleForAllArities() throws Exception {
        Class<?> cls = Class.forName(
                "io.quarkiverse.permuplate.example.drools.RuleBuilder");
        long count = Arrays.stream(cls.getMethods())
                .filter(m -> m.getName().equals("extendsRule"))
                .count();
        assertThat(count).isEqualTo(6); // j=2..7 → 6 overloads
    }

    @Test
    public void testParametersFirstHasExtendsRuleForAllArities() throws Exception {
        Class<?> cls = Class.forName(
                "io.quarkiverse.permuplate.example.drools.ParametersFirst");
        long count = Arrays.stream(cls.getMethods())
                .filter(m -> m.getName().equals("extendsRule"))
                .count();
        assertThat(count).isEqualTo(6);
    }
}
