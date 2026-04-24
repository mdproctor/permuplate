package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

/**
 * Verifies that @PermuteMacros on the outer class is accessible inside nested templates.
 * The DSL uses this for alphaList= shared between Join0Gate and Join0First.
 */
public class PermuteMacrosTest {

    @Test
    public void testJoin3GateHasFnMethod() throws Exception {
        Class<?> join3Second = Class.forName(
                "io.quarkiverse.permuplate.example.drools.JoinBuilder$Join3Gate");
        Method fn = Arrays.stream(join3Second.getMethods())
                .filter(m -> m.getName().equals("fn"))
                .findFirst().orElse(null);
        // fn(Consumer4<DS,A,B,C>) — type erasure gives Object param at runtime
        assertThat(fn).isNotNull();
        assertThat(fn.getParameterCount()).isEqualTo(1);
    }

    @Test
    public void testJoin3FirstHasFilterMethod() throws Exception {
        Class<?> join3First = Class.forName(
                "io.quarkiverse.permuplate.example.drools.JoinBuilder$Join3First");
        long filterCount = Arrays.stream(join3First.getMethods())
                .filter(m -> m.getName().equals("filter"))
                .count();
        // filter(Predicate4), filter(Predicate2), filter(v1,v2,pred), filter(v1,v2,v3,pred) = 4
        assertThat(filterCount).isAtLeast(2L);
    }
}
