package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * Demonstrates {@code @Permute} on a record template using {@code @PermuteParam}
 * to expand the record component list.
 *
 * <p>
 * Generates immutable, type-safe tuple records with 3 to 6 typed components:
 * <ul>
 * <li>Tuple3&lt;A,B,C&gt;(A a, B b, C c)</li>
 * <li>Tuple4&lt;A,B,C,D&gt;(A a, B b, C c, D d)</li>
 * <li>Tuple5&lt;A,B,C,D,E&gt;(A a, B b, C c, D d, E e)</li>
 * <li>Tuple6&lt;A,B,C,D,E,F&gt;(A a, B b, C c, D d, E e, F f)</li>
 * </ul>
 *
 * <p>
 * The sentinel class {@code Tuple2} is the template — it does not appear in
 * the generated output. The range starts at {@code from="3"} to avoid a
 * template/generated name collision (Tuple2 would clash with {@code i=2}).
 *
 * <p>
 * Since the {@code className} starts with {@code ${i}} (no leading literal),
 * the prefix validation check (R1) is skipped — the sentinel name {@code Tuple2}
 * does not need to be a prefix of the generated names.
 */
@Permute(varName = "i", from = "3", to = "6", className = "Tuple${i}")
record Tuple2<@PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>(
        @PermuteParam(varName = "j", from = "1", to = "${i}", type = "${alpha(j)}", name = "${lower(j)}") A a) {
}
