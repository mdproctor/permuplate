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
 * Target-state tests for record template support — all four currently fail (TDD red phase).
 *
 * <p>Blocker 1 (already fixed): StaticJavaParser configured for Java 17 in
 * PermuteProcessor.init() — records now parse without ParseProblemException.
 *
 * <p>Blocker 2 (fixed in Tasks 2–4): Transformer signatures generalized from
 * ClassOrInterfaceDeclaration to TypeDeclaration&lt;?&gt;, and PermuteProcessor updated
 * to find RecordDeclaration alongside ClassOrInterfaceDeclaration. Until then, these
 * tests fail because the processor cannot locate the record template class.
 */
public class RecordExpansionTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /**
     * Basic record permutation: @Permute on a record with fixed components
     * generates correctly named records with all components preserved.
     * from=3 avoids collision between template name Point2D and generated Point2D.
     */
    @Test
    public void testBasicRecordPermutation() {
        Compilation compilation = compile("io.example.Point2D",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Point${i}D\")\n" +
                        "public record Point2D(double x, double y) {}");

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.example.Point3D").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.Point4D").isPresent()).isTrue();
        String p3 = sourceOf(compilation.generatedSourceFile("io.example.Point3D").get());
        assertThat(p3).contains("record Point3D");
        assertThat(p3).contains("double x");
        assertThat(p3).contains("double y");
    }

    /**
     * @PermuteDeclr(type="${T}") on a record component renames its type per permutation.
     *                            Uses string-set iteration. StringBox has: String value. IntegerBox has: Integer value.
     */
    @Test
    public void testRecordWithPermuteDeclrOnComponent() {
        Compilation compilation = compile("io.example.Box2",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteDeclr;\n" +
                        "@Permute(varName=\"T\", values={\"String\",\"Integer\"}, className=\"${T}Box\")\n" +
                        "public record Box2(\n" +
                        "    @PermuteDeclr(type=\"${T}\") Object value\n" +
                        ") {}");

        assertThat(compilation).succeeded();
        String strSrc = sourceOf(compilation.generatedSourceFile("io.example.StringBox").get());
        assertThat(strSrc).contains("record StringBox");
        assertThat(strSrc).contains("String value");
        String intSrc = sourceOf(compilation.generatedSourceFile("io.example.IntegerBox").get());
        assertThat(intSrc).contains("record IntegerBox");
        assertThat(intSrc).contains("Integer value");
    }

    /**
     * @PermuteTypeParam on a record's type parameter expands correctly.
     *                   Wrapper2<A>(A item) with i=3 → Wrapper3 with 3 type params.
     */
    @Test
    public void testRecordWithPermuteTypeParam() {
        Compilation compilation = compile("io.example.Wrapper2",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteTypeParam;\n" +
                        "@Permute(varName=\"i\", from=\"3\", to=\"3\", className=\"Wrapper${i}\")\n" +
                        "public record Wrapper2<\n" +
                        "    @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                        "                      name=\"${alpha(k)}\") A\n" +
                        ">(A item) {}");

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.example.Wrapper3").get());
        assertThat(src).contains("record Wrapper3");
        assertThat(src).contains("A");
        assertThat(src).contains("B");
        assertThat(src).contains("C");
        assertThat(src).contains("item");
    }

    /**
     * @PermuteParam on a record component expands the component list — the Tuple pattern.
     *               Tuple2<A>(A a) → Tuple3<A,B,C>(A a, B b, C c), Tuple4<A,B,C,D>(A a, B b, C c, D d).
     */
    @Test
    public void testRecordWithPermuteParam() {
        Compilation compilation = compile("io.example.Tuple2",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteParam;\n" +
                        "import io.quarkiverse.permuplate.PermuteTypeParam;\n" +
                        "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Tuple${i}\")\n" +
                        "public record Tuple2<\n" +
                        "    @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                        "                      name=\"${alpha(k)}\") A\n" +
                        ">(\n" +
                        "    @PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\",\n" +
                        "                  type=\"${alpha(j)}\", name=\"${lower(j)}\")\n" +
                        "    A a\n" +
                        ") {}");

        assertThat(compilation).succeeded();
        String t3 = sourceOf(compilation.generatedSourceFile("io.example.Tuple3").get());
        assertThat(t3).contains("record Tuple3");
        assertThat(t3).contains("A a");
        assertThat(t3).contains("B b");
        assertThat(t3).contains("C c");
        String t4 = sourceOf(compilation.generatedSourceFile("io.example.Tuple4").get());
        assertThat(t4).contains("record Tuple4");
        assertThat(t4).contains("D d");
    }
}
