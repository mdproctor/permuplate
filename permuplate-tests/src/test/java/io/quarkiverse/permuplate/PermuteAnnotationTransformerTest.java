package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteAnnotationTransformerTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    @Test
    public void testAlwaysApplyOnClass() {
        Compilation c = compile("io.example.Callable1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Callable${i}\")\n" +
                        "@PermuteAnnotation(value=\"@SuppressWarnings(\\\"unchecked\\\")\")\n" +
                        "public interface Callable1 {}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable2").get()))
                .contains("@SuppressWarnings(\"unchecked\")");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable3").get()))
                .contains("@SuppressWarnings(\"unchecked\")");
    }

    @Test
    public void testConditionalOnClass() {
        Compilation c = compile("io.example.Callable1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Callable${i}\")\n" +
                        "@PermuteAnnotation(when=\"${i == 2}\", value=\"@FunctionalInterface\")\n" +
                        "public interface Callable1 { void call(); }");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable2").get()))
                .contains("@FunctionalInterface");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable3").get()))
                .doesNotContain("@FunctionalInterface");
    }

    @Test
    public void testJexlInValue() {
        Compilation c = compile("io.example.Callable1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Callable${i}\")\n" +
                        "@PermuteAnnotation(value=\"@SuppressWarnings(\\\"arity${i}\\\")\")\n" +
                        "public interface Callable1 {}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable2").get()))
                .contains("@SuppressWarnings(\"arity2\")");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Callable3").get()))
                .contains("@SuppressWarnings(\"arity3\")");
    }

    @Test
    public void testMethodLevel() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                        "public class Join1 {\n" +
                        "    @PermuteAnnotation(when=\"${i > 2}\", value=\"@Deprecated\")\n" +
                        "    public void join() {}\n" +
                        "}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join2").get()))
                .doesNotContain("@Deprecated");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("@Deprecated");
    }

    @Test
    public void testFieldLevel() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                        "public class Join1 {\n" +
                        "    @PermuteAnnotation(when=\"${i == 3}\", value=\"@Deprecated\")\n" +
                        "    public int arity = 1;\n" +
                        "}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join2").get()))
                .doesNotContain("@Deprecated");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("@Deprecated");
    }
}
