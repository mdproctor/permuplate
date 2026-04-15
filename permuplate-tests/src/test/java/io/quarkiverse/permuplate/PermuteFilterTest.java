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
