package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;

import org.junit.Test;

/**
 * Verifies that {@code @PermuteMixin} on a non-template class (no {@code @Permute})
 * correctly expands mixin methods via {@code processNonTemplateMixins} in the Maven plugin.
 */
public class NonTemplateMixinTest {

    @Test
    public void ruleBuilder_has_six_extendsRule_overloads() throws Exception {
        long count = Arrays.stream(RuleBuilder.class.getMethods())
                .filter(m -> m.getName().equals("extendsRule"))
                .count();
        assertThat(count).isEqualTo(6);
    }

    @Test
    public void parametersFirst_has_six_extendsRule_overloads() throws Exception {
        long count = Arrays.stream(ParametersFirst.class.getMethods())
                .filter(m -> m.getName().equals("extendsRule"))
                .count();
        assertThat(count).isEqualTo(6);
    }
}
