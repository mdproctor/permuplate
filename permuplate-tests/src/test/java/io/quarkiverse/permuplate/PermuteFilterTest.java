package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteFilterTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /**
     * @PermuteFilter exists as an annotation and can be placed on a @Permute template class
     *                without causing a compile error on its own.
     */
    @Test
    public void testAnnotationExistsAndCompiles() {
        Compilation compilation = compile("io.example.Join2",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteFilter;\n" +
                        "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                        "@PermuteFilter(\"${i} != 4\")\n" +
                        "public class Join2 {}");

        assertThat(compilation).succeeded();
    }

    // -------------------------------------------------------------------------
    // EvaluationContext.evaluateBoolean() — unit tests
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // APT processor — filter application
    // -------------------------------------------------------------------------

    /**
     * @PermuteFilter("${i} != 4") on range 3..5 skips Join4, generating only Join3 and Join5.
     */
    @Test
    public void testFilterSkipsSpecificValue() {
        Compilation compilation = compile("io.example.Join2",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteFilter;\n" +
                        "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                        "@PermuteFilter(\"${i} != 4\")\n" +
                        "public class Join2 {}");

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.example.Join3").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.Join5").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.Join4").isPresent()).isFalse();
    }

    /**
     * Multiple @PermuteFilter annotations are ANDed — range 3..6, skip 4 and skip 6 → Join3 and Join5 only.
     */
    @Test
    public void testMultipleFiltersAreAnded() {
        Compilation compilation = compile("io.example.Join2",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteFilter;\n" +
                        "@Permute(varName=\"i\", from=\"3\", to=\"6\", className=\"Join${i}\")\n" +
                        "@PermuteFilter(\"${i} != 4\")\n" +
                        "@PermuteFilter(\"${i} != 6\")\n" +
                        "public class Join2 {}");

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.example.Join3").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.Join5").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.Join4").isPresent()).isFalse();
        assertThat(compilation.generatedSourceFile("io.example.Join6").isPresent()).isFalse();
    }

    /**
     * When @PermuteFilter eliminates ALL combinations, the APT processor must report a compile error.
     */
    @Test
    public void testAllFilteredOutIsCompileError() {
        Compilation compilation = compile("io.example.Join2",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteFilter;\n" +
                        "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                        "@PermuteFilter(\"${i} > 100\")\n" + // always false for range 3..5
                        "public class Join2 {}");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("all permutations");
    }

    /**
     * @PermuteFilter works on @Permute on a method — skips generating specific overloads.
     *                Method permutation collects all N variants into one class.
     *                Uses @PermuteParam so each arity produces a distinct signature (i=3 → 3 params, i=5 → 5 params).
     */
    @Test
    public void testFilterOnMethodPermutation() {
        Compilation compilation = compile("io.example.Joiner",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteFilter;\n" +
                        "import io.quarkiverse.permuplate.PermuteParam;\n" +
                        "public class Joiner {\n" +
                        "    @Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"JoinMethods\")\n" +
                        "    @PermuteFilter(\"${i} != 4\")\n" +
                        "    public void join(@PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\", type=\"Object\", name=\"o${j}\") Object o1) {}\n"
                        +
                        "}");

        assertThat(compilation).succeeded();
        String src = io.quarkiverse.permuplate.ProcessorTestSupport
                .sourceOf(compilation.generatedSourceFile("io.example.JoinMethods").get());
        // i=3: join(Object o1, Object o2, Object o3); i=5: join(Object o1..o5); i=4 filtered
        assertThat(src).contains("Object o1, Object o2, Object o3");
        assertThat(src).contains("Object o1, Object o2, Object o3, Object o4, Object o5");
        assertThat(src).doesNotContain("Object o1, Object o2, Object o3, Object o4)");
    }

    /**
     * @PermuteFilter with @PermuteVar cross-product: i=2..3, j=1..2.
     *                Filter "${i} != ${j}" keeps (2,1),(3,1),(3,2) and drops (2,2).
     *                Template class name "BiJoinx2" contains both literal segments ("BiJoin" and "x") from className.
     */
    @Test
    public void testFilterOnCrossProductSkipsCombination() {
        Compilation compilation = compile("io.example.BiJoinx2",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteFilter;\n" +
                        "import io.quarkiverse.permuplate.PermuteVar;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"BiJoin${i}x${j}\",\n" +
                        "         extraVars={@PermuteVar(varName=\"j\", from=\"1\", to=\"2\")})\n" +
                        "@PermuteFilter(\"${i} != ${j}\")\n" +
                        "public class BiJoinx2 {}");

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.example.BiJoin2x1").isPresent()).isTrue(); // i=2,j=1 ✓
        assertThat(compilation.generatedSourceFile("io.example.BiJoin3x1").isPresent()).isTrue(); // i=3,j=1 ✓
        assertThat(compilation.generatedSourceFile("io.example.BiJoin3x2").isPresent()).isTrue(); // i=3,j=2 ✓
        assertThat(compilation.generatedSourceFile("io.example.BiJoin2x2").isPresent()).isFalse(); // i=2,j=2 ✗
    }

    // -------------------------------------------------------------------------
    // EvaluationContext.evaluateBoolean() — unit tests
    // -------------------------------------------------------------------------

    @Test
    public void testEvaluateBooleanTrueExpression() {
        io.quarkiverse.permuplate.core.EvaluationContext ctx = new io.quarkiverse.permuplate.core.EvaluationContext(
                java.util.Map.of("i", 3));
        assertThat(ctx.evaluateBoolean("${i} != 4")).isTrue();
    }

    @Test
    public void testEvaluateBooleanFalseExpression() {
        io.quarkiverse.permuplate.core.EvaluationContext ctx = new io.quarkiverse.permuplate.core.EvaluationContext(
                java.util.Map.of("i", 4));
        assertThat(ctx.evaluateBoolean("${i} != 4")).isFalse();
    }

    @Test
    public void testEvaluateBooleanWithTwoVariables() {
        io.quarkiverse.permuplate.core.EvaluationContext ctx = new io.quarkiverse.permuplate.core.EvaluationContext(
                java.util.Map.of("i", 2, "j", 2));
        assertThat(ctx.evaluateBoolean("${i} != ${j}")).isFalse();
        io.quarkiverse.permuplate.core.EvaluationContext ctx2 = new io.quarkiverse.permuplate.core.EvaluationContext(
                java.util.Map.of("i", 2, "j", 3));
        assertThat(ctx2.evaluateBoolean("${i} != ${j}")).isTrue();
    }
}
