package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import java.util.Map;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for built-in JEXL expression functions: alpha(n), lower(n), typeArgList(from,to,style).
 *
 * <p>
 * Unit tests call {@link EvaluationContext.PermuplateStringFunctions} static methods directly.
 * End-to-end tests compile inline templates with the processor and assert on generated source.
 */
public class ExpressionFunctionsTest {

    // -------------------------------------------------------------------------
    // alpha(n) unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testAlphaFirst() {
        assertThat(EvaluationContext.PermuplateStringFunctions.alpha(1)).isEqualTo("A");
    }

    @Test
    public void testAlphaMidpoint() {
        assertThat(EvaluationContext.PermuplateStringFunctions.alpha(13)).isEqualTo("M");
    }

    @Test
    public void testAlphaLast() {
        assertThat(EvaluationContext.PermuplateStringFunctions.alpha(26)).isEqualTo("Z");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAlphaBelowRange() {
        EvaluationContext.PermuplateStringFunctions.alpha(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAlphaAboveRange() {
        EvaluationContext.PermuplateStringFunctions.alpha(27);
    }

    // -------------------------------------------------------------------------
    // lower(n) unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testLowerFirst() {
        assertThat(EvaluationContext.PermuplateStringFunctions.lower(1)).isEqualTo("a");
    }

    @Test
    public void testLowerMidpoint() {
        assertThat(EvaluationContext.PermuplateStringFunctions.lower(13)).isEqualTo("m");
    }

    @Test
    public void testLowerLast() {
        assertThat(EvaluationContext.PermuplateStringFunctions.lower(26)).isEqualTo("z");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLowerBelowRange() {
        EvaluationContext.PermuplateStringFunctions.lower(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLowerAboveRange() {
        EvaluationContext.PermuplateStringFunctions.lower(27);
    }

    // -------------------------------------------------------------------------
    // typeArgList(from, to, style) unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testTypeArgListTStyle() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(2, 4, "T"))
                .isEqualTo("T2, T3, T4");
    }

    @Test
    public void testTypeArgListAlphaStyle() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(2, 4, "alpha"))
                .isEqualTo("B, C, D");
    }

    @Test
    public void testTypeArgListLowerStyle() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(2, 4, "lower"))
                .isEqualTo("b, c, d");
    }

    @Test
    public void testTypeArgListSingleElement() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(2, 2, "alpha"))
                .isEqualTo("B");
    }

    @Test
    public void testTypeArgListEmptyRange() {
        assertThat(EvaluationContext.PermuplateStringFunctions.typeArgList(3, 2, "T"))
                .isEqualTo("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTypeArgListUnknownStyle() {
        EvaluationContext.PermuplateStringFunctions.typeArgList(1, 3, "X");
    }

    // -------------------------------------------------------------------------
    // End-to-end: alpha in @Permute.className
    // -------------------------------------------------------------------------

    @Test
    public void testAlphaInClassName() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.StepA",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        // from="2", not from=1: alpha(1)="A" would generate a class named StepA,
                        // which collides with the template class name. The processor does not skip
                        // template-name collisions — it would fail. from=2 generates StepB..StepF.
                        @Permute(varName="i", from="2", to="6", className="Step${alpha(i)}")
                        public class StepA { }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        // StepA is excluded: alpha(1)="A" would name a generated class StepA, colliding with the
        // template class. The processor does not skip template-name collisions, so from=2 is used.
        assertThat(compilation.generatedSourceFile("io.quarkiverse.permuplate.example.StepB").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.quarkiverse.permuplate.example.StepC").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.quarkiverse.permuplate.example.StepE").isPresent()).isTrue();
    }

    // -------------------------------------------------------------------------
    // End-to-end: lower in @PermuteParam.name
    // -------------------------------------------------------------------------

    @Test
    public void testLowerInPermuteParamName() {
        // Template is JoinLower4 (arity-4 placeholder) so that generating JoinLower3
        // (i=3) does not collide with the template class name.
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.JoinLower4",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteParam;
                        @Permute(varName="i", from="3", to="3", className="JoinLower${i}")
                        public class JoinLower4 {
                            public void join(
                                // to="${i}" (not "${i-1}") because this template has no for-each loop;
                                // the CLAUDE.md ${i-1} convention applies only when a for-each variable
                                // occupies the last slot.
                                @PermuteParam(varName="j", from="1", to="${i}", type="Object", name="fact${lower(j)}")
                                Object facta) { }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.JoinLower3")
                .orElseThrow());
        assertThat(src).contains("Object facta");
        assertThat(src).contains("Object factb");
        assertThat(src).contains("Object factc");
    }

    // -------------------------------------------------------------------------
    // End-to-end: typeArgList unknown style throws via JEXL path
    // -------------------------------------------------------------------------

    @Test
    public void testTypeArgListUnknownStyleInJexlExpression() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BadStyle3",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteDeclr;
                        @Permute(varName="i", from="3", to="3", className="BadStyle${i}")
                        public class BadStyle3 {
                            @PermuteDeclr(type="${typeArgList(1, i, 'X')}", name="field${i}")
                            Object field3;
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("typeArgList");
    }

    // -------------------------------------------------------------------------
    // End-to-end: alpha out-of-range throws via JEXL path
    // -------------------------------------------------------------------------

    @Test
    public void testAlphaOutOfRangeInJexlExpression() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.StepZ",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        // from=27: alpha(27) is out of range — should cause generation-time error
                        @Permute(varName="i", from="27", to="27", className="Step${alpha(i)}")
                        public class StepZ { }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("alpha");
    }

    // -------------------------------------------------------------------------
    // max(a, b) static unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testMaxStaticFirstArgLarger() {
        assertThat(EvaluationContext.PermuplateStringFunctions.max(3, 1)).isEqualTo(3);
    }

    @Test
    public void testMaxStaticSecondArgLarger() {
        assertThat(EvaluationContext.PermuplateStringFunctions.max(1, 3)).isEqualTo(3);
    }

    @Test
    public void testMaxStaticEqual() {
        assertThat(EvaluationContext.PermuplateStringFunctions.max(2, 2)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // min(a, b) static unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testMinStaticFirstArgSmaller() {
        assertThat(EvaluationContext.PermuplateStringFunctions.min(1, 3)).isEqualTo(1);
    }

    @Test
    public void testMinStaticSecondArgSmaller() {
        assertThat(EvaluationContext.PermuplateStringFunctions.min(3, 1)).isEqualTo(1);
    }

    @Test
    public void testMinStaticEqual() {
        assertThat(EvaluationContext.PermuplateStringFunctions.min(2, 2)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // max(a, b) JEXL-path tests (via EvaluationContext.evaluate)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // min(a, b) JEXL-path tests (via EvaluationContext.evaluate)
    // -------------------------------------------------------------------------

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

    @Test
    public void testMinWithVariable_iIs1() {
        assertThat(eval("${min(2, i)}", Map.of("i", 1))).isEqualTo("1");
    }

    @Test
    public void testMinWithVariable_iIs3() {
        assertThat(eval("${min(2, i)}", Map.of("i", 3))).isEqualTo("2");
    }

    // -------------------------------------------------------------------------
    // Deferred tests — require G1 (@PermuteTypeParam) and G2 (@PermuteReturn)
    // These will be added to this class once the relevant annotations are
    // implemented. Listed here so they are not forgotten:
    //
    //   alpha in @PermuteTypeParam.name:
    //     Tuple1<A> → Tuple4<A, B, C, D> (depends on G1)
    //
    //   alpha in @PermuteReturn.typeArgName:
    //     return type args A, B, C from typeArgName="${alpha(j)}" (depends on G2)
    //
    //   Full Drools chain:
    //     Join1First<A> → Join5First<A,B,C,D,E>; Join5First has no join() (depends on G1+G2)
    // -------------------------------------------------------------------------
}
