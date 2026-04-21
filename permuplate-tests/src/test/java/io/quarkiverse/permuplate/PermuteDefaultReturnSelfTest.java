package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteDefaultReturnSelfTest {

    @Test
    public void testSelfReturnWithTypeParams() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.FluentTemplate1",
                        "package io.permuplate.test;\n"
                                + "import io.quarkiverse.permuplate.*;\n"
                                + "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Fluent${i}\")\n"
                                + "@PermuteDefaultReturn(className=\"self\")\n"
                                + "public class FluentTemplate1<\n"
                                + "    @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\","
                                + "                      name=\"T${k}\") T1> {\n"
                                + "    public Object withA(String a) { return this; }\n"
                                + "    public Object withB(int b) { return this; }\n"
                                + "}\n"));

        assertThat(compilation).succeeded();

        String src1 = sourceOf(compilation.generatedSourceFile("io.permuplate.test.Fluent1").orElseThrow());
        assertThat(src1).containsMatch("Fluent1<T1>\\s+withA\\(");
        assertThat(src1).containsMatch("Fluent1<T1>\\s+withB\\(");

        String src3 = sourceOf(compilation.generatedSourceFile("io.permuplate.test.Fluent3").orElseThrow());
        assertThat(src3).containsMatch("Fluent3<T1, T2, T3>\\s+withA\\(");
        assertThat(src3).containsMatch("Fluent3<T1, T2, T3>\\s+withB\\(");
    }

    @Test
    public void testSelfReturnNoTypeParams() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "io.permuplate.test.SimpleTemplate1",
                        "package io.permuplate.test;\n"
                                + "import io.quarkiverse.permuplate.*;\n"
                                + "@Permute(varName=\"i\", from=\"1\", to=\"2\", className=\"Simple${i}\")\n"
                                + "@PermuteDefaultReturn(className=\"self\")\n"
                                + "public class SimpleTemplate1 {\n"
                                + "    public Object build() { return this; }\n"
                                + "}\n"));

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.test.Simple1").orElseThrow());
        assertThat(src).containsMatch("Simple1\\s+build\\(\\)");
    }

    private static String sourceOf(javax.tools.JavaFileObject file) {
        try {
            return file.getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
