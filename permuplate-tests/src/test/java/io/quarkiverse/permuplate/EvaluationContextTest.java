package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;

import org.junit.Test;

import io.quarkiverse.permuplate.core.EvaluationContext;

public class EvaluationContextTest {

    private static String eval(String template, Map<String, Object> vars) {
        return new EvaluationContext(vars).evaluate(template);
    }

    @Test
    public void testMaxFirstArgLarger() {
        assertThat(eval("${max(3, 1)}", Map.of())).isEqualTo("3");
    }

    @Test
    public void testMaxSecondArgLarger() {
        assertThat(eval("${max(1, 3)}", Map.of())).isEqualTo("3");
    }

    @Test
    public void testMaxEqual() {
        assertThat(eval("${max(2, 2)}", Map.of())).isEqualTo("2");
    }

    @Test
    public void testMaxWithVariable_iIs1() {
        assertThat(eval("${max(2, i)}", Map.of("i", 1))).isEqualTo("2");
    }

    @Test
    public void testMaxWithVariable_iIs3() {
        assertThat(eval("${max(2, i)}", Map.of("i", 3))).isEqualTo("3");
    }

    @Test
    public void testMinFirstArgSmaller() {
        assertThat(eval("${min(1, 3)}", Map.of())).isEqualTo("1");
    }

    @Test
    public void testMinSecondArgSmaller() {
        assertThat(eval("${min(3, 1)}", Map.of())).isEqualTo("1");
    }

    @Test
    public void testMinEqual() {
        assertThat(eval("${min(2, 2)}", Map.of())).isEqualTo("2");
    }
}
