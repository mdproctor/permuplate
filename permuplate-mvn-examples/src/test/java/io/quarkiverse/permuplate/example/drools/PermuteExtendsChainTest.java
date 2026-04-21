package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Verifies @PermuteExtendsChain correctly generates the extends-previous-in-family hierarchy.
 */
public class PermuteExtendsChainTest {

    @Test
    public void testTuple2ExtendsTuple1() {
        assertThat(BaseTuple.Tuple2.class.getSuperclass()).isEqualTo(BaseTuple.Tuple1.class);
    }

    @Test
    public void testTuple3ExtendsTuple2() {
        assertThat(BaseTuple.Tuple3.class.getSuperclass()).isEqualTo(BaseTuple.Tuple2.class);
    }

    @Test
    public void testTuple6ExtendsTuple5() {
        assertThat(BaseTuple.Tuple6.class.getSuperclass()).isEqualTo(BaseTuple.Tuple5.class);
    }

    @Test
    public void testInheritedGetWorksAcrossChain() {
        // Tuple3.get(0) delegates to Tuple2, Tuple2 delegates to Tuple1
        var t = new BaseTuple.Tuple3<>("x", "y", "z");
        assertThat((String) t.get(0)).isEqualTo("x");
        assertThat((String) t.get(1)).isEqualTo("y");
        assertThat((String) t.get(2)).isEqualTo("z");
    }
}
