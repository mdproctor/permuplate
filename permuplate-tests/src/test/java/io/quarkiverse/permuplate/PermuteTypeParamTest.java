package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for @PermuteTypeParam — explicit and implicit type parameter expansion.
 *
 * <p>
 * All templates are compiled with the APT processor via Google compile-testing.
 * Assertions check the generated source text.
 */
public class PermuteTypeParamTest {

    // -------------------------------------------------------------------------
    // Explicit @PermuteTypeParam — no bounds
    // -------------------------------------------------------------------------

    @Test
    public void testExplicitExpansionNoBounds() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Condition1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Condition${i}")
                        public interface Condition1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> {
                            boolean test(T1 fact);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Condition3")
                .orElseThrow());
        assertThat(src).contains("Condition3<T1, T2, T3>");
        assertThat(src).doesNotContain("@PermuteTypeParam");
        assertThat(src).doesNotContain("@Permute");
    }

    // -------------------------------------------------------------------------
    // Explicit @PermuteTypeParam — with bounds
    // -------------------------------------------------------------------------

    @Test
    public void testExplicitExpansionWithBounds() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.SortedCondition1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="SortedCondition${i}")
                        public interface SortedCondition1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1 extends Comparable<T1>> {
                            boolean test(T1 fact);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.SortedCondition3")
                .orElseThrow());
        assertThat(src).contains("T1 extends Comparable<T1>");
        assertThat(src).contains("T2 extends Comparable<T2>");
        assertThat(src).contains("T3 extends Comparable<T3>");
    }

    // -------------------------------------------------------------------------
    // Explicit @PermuteTypeParam — range of generated classes
    // -------------------------------------------------------------------------

    @Test
    public void testExplicitExpansionRange() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.RangeCondition1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=2, to=4, className="RangeCondition${i}")
                        public interface RangeCondition1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> {
                            boolean test(T1 fact);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.RangeCondition2")
                .orElseThrow()))
                .contains("RangeCondition2<T1, T2>");
        assertThat(sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.RangeCondition4")
                .orElseThrow()))
                .contains("RangeCondition4<T1, T2, T3, T4>");
    }

    // -------------------------------------------------------------------------
    // Implicit expansion — @PermuteParam type="T${j}" triggers class type param expansion
    // -------------------------------------------------------------------------

    @Test
    public void testImplicitExpansionNoBounds() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Consumer1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteParam;
                        @Permute(varName="i", from=3, to=3, className="Consumer${i}")
                        public interface Consumer1<T1> {
                            void accept(
                                @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="arg${j}") T1 arg1);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Consumer3")
                .orElseThrow());
        assertThat(src).contains("Consumer3<T1, T2, T3>");
        assertThat(src).contains("void accept(T1 arg1, T2 arg2, T3 arg3)");
        assertThat(src).doesNotContain("@PermuteTypeParam");
        assertThat(src).doesNotContain("@PermuteParam");
    }

    @Test
    public void testImplicitExpansionWithBounds() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BoundedConsumer1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteParam;
                        @Permute(varName="i", from=3, to=3, className="BoundedConsumer${i}")
                        public interface BoundedConsumer1<T1 extends Comparable<T1>> {
                            void accept(
                                @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="arg${j}") T1 arg1);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.BoundedConsumer3")
                .orElseThrow());
        assertThat(src).contains("T1 extends Comparable<T1>");
        assertThat(src).contains("T2 extends Comparable<T2>");
        assertThat(src).contains("T3 extends Comparable<T3>");
        assertThat(src).contains("void accept(T1 arg1, T2 arg2, T3 arg3)");
    }

    @Test
    public void testImplicitExpansionFixedTypeParamSurvives() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Transformer1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteParam;
                        @Permute(varName="i", from=3, to=3, className="Transformer${i}")
                        public interface Transformer1<T1, R> {
                            R apply(
                                @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="input${j}") T1 input1);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Transformer3")
                .orElseThrow());
        assertThat(src).contains("Transformer3<T1, T2, T3, R>");
        assertThat(src).contains("R apply(T1 input1, T2 input2, T3 input3)");
    }

    // -------------------------------------------------------------------------
    // Validation: R1 — return type must not reference expanding type param
    // -------------------------------------------------------------------------

    @Test
    public void testR1ReturnTypeReferencesExpandingParam() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Mapper1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteParam;
                        @Permute(varName="i", from=3, to=3, className="Mapper${i}")
                        public interface Mapper1<T1> {
                            T1 apply(
                                @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="input${j}") T1 input1);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("return type");
        assertThat(compilation).hadErrorContaining("expanding type parameter");
    }

    // -------------------------------------------------------------------------
    // Validation: R3 — @PermuteTypeParam name prefix must match sentinel
    // -------------------------------------------------------------------------

    @Test
    public void testR3NamePrefixMismatch() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BadPrefix1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="BadPrefix${i}")
                        public interface BadPrefix1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="X${j}") T1> {
                            boolean test(T1 fact);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("prefix");
    }

    // -------------------------------------------------------------------------
    // Validation: R4 — from > to is invalid
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Explicit @PermuteTypeParam — alpha(j) naming (A, B, C)
    // -------------------------------------------------------------------------

    /**
     * @PermuteTypeParam with alpha(j) naming produces single-letter type parameters
     *                   (A, B, C) instead of T1, T2, T3. This is the canonical Drools-style convention.
     *                   Note: alpha naming requires explicit annotations everywhere — it does NOT trigger
     *                   implicit inference (which requires the T+number pattern).
     */
    @Test
    public void testExplicitExpansionAlphaNaming() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.AlphaCondition1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="AlphaCondition${i}")
                        public interface AlphaCondition1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="${alpha(j)}") A> {
                            boolean test(A fact);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.AlphaCondition3")
                .orElseThrow());
        // Sentinel A expanded to A, B, C
        assertThat(src).contains("AlphaCondition3<A, B, C>");
        assertThat(src).doesNotContain("@PermuteTypeParam");
        assertThat(src).doesNotContain("@Permute");
    }

    /**
     * Alpha naming with bounds: A extends Comparable&lt;A&gt; propagates correctly to
     * B extends Comparable&lt;B&gt;, C extends Comparable&lt;C&gt;.
     */
    @Test
    public void testExplicitExpansionAlphaWithBounds() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.SortedAlpha1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="SortedAlpha${i}")
                        public interface SortedAlpha1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="${alpha(j)}") A extends Comparable<A>> {
                            boolean test(A fact);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.SortedAlpha3")
                .orElseThrow());
        assertThat(src).contains("A extends Comparable<A>");
        assertThat(src).contains("B extends Comparable<B>");
        assertThat(src).contains("C extends Comparable<C>");
    }

    @Test
    public void testR4FromGreaterThanTo() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BadRange1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="BadRange${i}")
                        public interface BadRange1<@PermuteTypeParam(varName="j", from="5", to="2", name="T${j}") T1> {
                            boolean test(T1 fact);
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("invalid range");
    }
}
