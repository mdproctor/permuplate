package io.quarkiverse.permuplate.example.drools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Verifies generated Tuple classes have correct super() calls via inference
 * (no @PermuteStatements on the constructor).
 */
public class SuperCallInferenceTest {

    @Test
    public void testTuple2FullArgsConstructorSuperCallCorrect() {
        var t = new BaseTuple.Tuple2<>("hello", 42);
        assertThat((String) t.get(0)).isEqualTo("hello");
        assertThat((Integer) t.get(1)).isEqualTo(42);
        assertThat(t.size()).isEqualTo(2);
    }

    @Test
    public void testTuple3FullArgsConstructorSuperCallCorrect() {
        var t = new BaseTuple.Tuple3<>("hello", 42, 3.14);
        assertThat((String) t.get(0)).isEqualTo("hello");
        assertThat((Integer) t.get(1)).isEqualTo(42);
        assertThat((Double) t.get(2)).isEqualTo(3.14);
        assertThat(t.size()).isEqualTo(3);
    }

    @Test
    public void testTuple4FullArgsConstructorSuperCallCorrect() {
        var t = new BaseTuple.Tuple4<>("a", "b", "c", "d");
        assertThat((String) t.get(0)).isEqualTo("a");
        assertThat((String) t.get(3)).isEqualTo("d");
        assertThat(t.size()).isEqualTo(4);
    }

    @Test
    public void testTupleInheritanceChain() {
        // Tuple4 extends Tuple3 extends Tuple2 extends Tuple1 — verify chain integrity
        assertThat(BaseTuple.Tuple4.class.getSuperclass()).isEqualTo(BaseTuple.Tuple3.class);
        assertThat(BaseTuple.Tuple3.class.getSuperclass()).isEqualTo(BaseTuple.Tuple2.class);
        assertThat(BaseTuple.Tuple2.class.getSuperclass()).isEqualTo(BaseTuple.Tuple1.class);
    }
}
