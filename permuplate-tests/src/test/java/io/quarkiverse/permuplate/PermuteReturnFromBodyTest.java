package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteReturnFromBodyTest {

    @Test
    public void testReturnTypeInferredFromNewExpression() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.ChainTemplate1",
                        "package io.permuplate.test;\n"
                                + "import io.quarkiverse.permuplate.*;\n"
                                + "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Chain${i}\")\n"
                                + "public class ChainTemplate1 {\n"
                                + "    @PermuteBody(when=\"i < 3\","
                                + "        body=\"{ return new Chain${i+1}(); }\")\n"
                                + "    @PermuteBody(when=\"i == 3\","
                                + "        body=\"{ return null; }\")\n"
                                + "    public Object next() { return null; }\n"
                                + "}\n"));

        assertThat(compilation).succeeded();

        String src1 = sourceOf(compilation.generatedSourceFile("io.permuplate.test.Chain1").orElseThrow());
        assertThat(src1).containsMatch("Chain2\\s+next\\(\\)");

        String src2 = sourceOf(compilation.generatedSourceFile("io.permuplate.test.Chain2").orElseThrow());
        assertThat(src2).containsMatch("Chain3\\s+next\\(\\)");

        String src3 = sourceOf(compilation.generatedSourceFile("io.permuplate.test.Chain3").orElseThrow());
        // Chain3: body is "return null" — no X in generated set — stays Object
        assertThat(src3).containsMatch("Object\\s+next\\(\\)");
    }

    @Test
    public void testNoInferenceWhenReturnClassNotInGeneratedSet() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.CrossRef1",
                        "package io.permuplate.test;\n"
                                + "import io.quarkiverse.permuplate.*;\n"
                                + "@Permute(varName=\"i\", from=\"1\", to=\"2\", className=\"Ref${i}\")\n"
                                + "public class CrossRef1 {\n"
                                + "    @PermuteBody(body=\"{ return new java.util.ArrayList<>(); }\")\n"
                                + "    public Object list() { return null; }\n"
                                + "}\n"));

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.test.Ref1").orElseThrow());
        // ArrayList not in generated set → stays Object
        assertThat(src).containsMatch("Object\\s+list\\(\\)");
    }

    @Test
    public void testNoInferenceWhenPermuteReturnPresent() {
        // Explicit @PermuteReturn on Exp1.next() → Exp2 (next class in set).
        // Without alwaysEmit, Exp2.next() is omitted by boundary omission (Exp3 not in set),
        // so no "cannot find symbol Exp3" compile error.
        // The body "return null" does not contain "return new X<>()", so inference
        // would not fire even if @PermuteReturn were absent — this test verifies
        // that explicit @PermuteReturn takes precedence over body inference.
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.Explicit1",
                        "package io.permuplate.test;\n"
                                + "import io.quarkiverse.permuplate.*;\n"
                                + "@Permute(varName=\"i\", from=\"1\", to=\"2\", className=\"Exp${i}\")\n"
                                + "public class Explicit1 {\n"
                                + "    @PermuteReturn(className=\"Exp${i+1}\")\n"
                                + "    @PermuteBody(body=\"{ return null; }\")\n"
                                + "    public Object next() { return null; }\n"
                                + "}\n"));

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.test.Exp1").orElseThrow());
        // Explicit @PermuteReturn says Exp2 — wins over body inference
        assertThat(src).containsMatch("Exp2\\s+next\\(\\)");
    }
}
