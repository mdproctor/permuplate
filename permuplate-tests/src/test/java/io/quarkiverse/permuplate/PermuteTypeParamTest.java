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

    // -------------------------------------------------------------------------
    // Standalone method-level @PermuteTypeParam (not on @PermuteMethod)
    // -------------------------------------------------------------------------

    /**
     * @PermuteTypeParam on a non-@PermuteMethod method renames the type parameter
     *                   declaration AND propagates the rename into parameter types that reference it.
     *                   Here B is renamed to T2 (i=2) and T3 (i=3); List&lt;B&gt; propagates to List&lt;T2&gt;/List&lt;T3&gt;.
     */
    @Test
    public void testStandaloneMethodTypeParamRenameAndPropagate() {
        var source = JavaFileObjects.forSourceString("io.example.Gatherer1",
                """
                        package io.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName = "i", from = 2, to = 3, className = "Gatherer${i}")
                        public class Gatherer1 {
                            @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "T${m}")
                            public <B> void collect(java.util.List<B> items) {}
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();

        String src2 = sourceOf(compilation.generatedSourceFile("io.example.Gatherer2").orElseThrow());
        assertThat(src2).contains("<T2> void collect(java.util.List<T2> items)");

        String src3 = sourceOf(compilation.generatedSourceFile("io.example.Gatherer3").orElseThrow());
        assertThat(src3).contains("<T3> void collect(java.util.List<T3> items)");
    }

    /**
     * Propagation works when the type parameter appears nested multiple levels deep
     * in the parameter type — e.g. Function&lt;Object, List&lt;B&gt;&gt; where B is two levels deep.
     */
    @Test
    public void testPropagationIntoNestedGeneric() {
        var source = JavaFileObjects.forSourceString("io.example.Fetcher1",
                """
                        package io.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName = "i", from = 2, to = 3, className = "Fetcher${i}")
                        public class Fetcher1 {
                            @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "T${m}")
                            public <B> void fetch(
                                    java.util.function.Function<Object, java.util.List<B>> supplier) {}
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();

        String src2 = sourceOf(compilation.generatedSourceFile("io.example.Fetcher2").orElseThrow());
        assertThat(src2).contains("java.util.function.Function<Object, java.util.List<T2>>");
    }

    /**
     * @PermuteDeclr on a parameter takes explicit precedence over propagated renames.
     *               The first param (no @PermuteDeclr) receives the propagated rename:
     *               B → T2, so {@code List<B>} becomes {@code List<T2>}.
     *               The second param (@PermuteDeclr) uses its explicit type expression
     *               {@code Map<String, T${i}>}, which produces {@code Map<String, T2>} —
     *               clearly different from what propagation would give ({@code List<T2>}).
     *               This ensures the test cannot pass if @PermuteDeclr is silently ignored.
     */
    @Test
    public void testPermuteDeclrOverridesPropagation() {
        var source = JavaFileObjects.forSourceString("io.example.Dual1",
                """
                        package io.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteDeclr;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName = "i", from = 2, to = 3, className = "Dual${i}")
                        public class Dual1 {
                            @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "T${m}")
                            public <B> void handle(
                                    java.util.List<B> propagated,
                                    @PermuteDeclr(type = "java.util.Map<String, T${i}>") Object explicit) {}
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();

        String src2 = sourceOf(compilation.generatedSourceFile("io.example.Dual2").orElseThrow());
        // Propagated rename: B → T2 applied to List<B> → List<T2>
        assertThat(src2).contains("java.util.List<T2> propagated");
        // Explicit @PermuteDeclr wins: Map<String, T2> — NOT List<T2>
        assertThat(src2).contains("java.util.Map<String, T2> explicit");
    }

    /**
     * @PermuteMethod methods must be skipped by Step 5. They are processed later in
     *                applyPermuteMethod() with the inner (i,j) context; double-processing would corrupt
     *                the output. Verifies standalone method IS processed while @PermuteMethod method is not.
     */
    @Test
    public void testPermuteMethodGuardPreventsDoubleProcessing() {
        var source = JavaFileObjects.forSourceString("io.example.Guarded1",
                """
                        package io.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName = "i", from = 2, to = 2, className = "Guarded${i}")
                        public class Guarded1 {
                            @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "X${m}")
                            public <B> void standalone(java.util.List<B> items) {}
                            @PermuteMethod(varName = "j", from = "1", to = "1")
                            @PermuteReturn(className = "Guarded${i}", when = "true")
                            public Object overloaded() { return this; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();

        String src = sourceOf(compilation.generatedSourceFile("io.example.Guarded2").orElseThrow());
        assertThat(src).contains("<X2> void standalone(java.util.List<X2> items)");
        // @PermuteReturn(className="Guarded${i}") changes the return type via applyPermuteReturnSimple.
        // Step 5 skips this method (it has @PermuteMethod, no @PermuteTypeParam) — no double-processing.
        assertThat(src).contains("Guarded2 overloaded()");
    }

    /**
     * Full end-to-end APT test of the typed join() pattern:
     * class-level @PermuteTypeParam (expanding) + method-level @PermuteTypeParam
     * (single-value, standalone) + @PermuteReturn with boundary omission at the leaf.
     */
    @Test
    public void testTypedJoinPatternEndToEnd() {
        var source = JavaFileObjects.forSourceString("io.example.Chain0",
                """
                        package io.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName = "i", from = 1, to = 3, className = "Chain${i}")
                        public class Chain0<
                                @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "T${k}") T1> {
                            @PermuteTypeParam(varName = "m", from = "${i+1}", to = "${i+1}", name = "T${m}")
                            @PermuteReturn(className = "Chain${i+1}",
                                           typeArgs = "typeArgList(1, i+1, 'T')")
                            public <B> Object join(
                                    java.util.function.Function<Object, java.util.List<B>> source) {
                                return null;
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();

        String src1 = sourceOf(compilation.generatedSourceFile("io.example.Chain1").orElseThrow());
        assertThat(src1).contains("public <T2> Chain2<T1, T2> join(" +
                "java.util.function.Function<Object, java.util.List<T2>> source)");

        String src2 = sourceOf(compilation.generatedSourceFile("io.example.Chain2").orElseThrow());
        assertThat(src2).contains("public <T3> Chain3<T1, T2, T3> join(" +
                "java.util.function.Function<Object, java.util.List<T3>> source)");

        String src3 = sourceOf(compilation.generatedSourceFile("io.example.Chain3").orElseThrow());
        assertThat(src3).doesNotContain("join(");
    }
}
