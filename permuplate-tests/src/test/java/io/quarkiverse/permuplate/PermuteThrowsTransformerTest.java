package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteThrowsTransformerTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    @Test
    public void testAlwaysAddThrows() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                        "public class Join1 {\n" +
                        "    @PermuteThrows(value=\"java.io.IOException\")\n" +
                        "    public void call() {}\n" +
                        "}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join2").get()))
                .contains("throws java.io.IOException");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("throws java.io.IOException");
    }

    @Test
    public void testConditionalThrows() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                        "public class Join1 {\n" +
                        "    @PermuteThrows(when=\"${i > 2}\", value=\"java.io.IOException\")\n" +
                        "    public void call() throws RuntimeException {}\n" +
                        "}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join2").get()))
                .doesNotContain("IOException");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("java.io.IOException");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("RuntimeException");
    }

    @Test
    public void testMultipleThrows() {
        Compilation c = compile("io.example.Join1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Join${i}\")\n" +
                        "public class Join1 {\n" +
                        "    @PermuteThrows(value=\"java.io.IOException\")\n" +
                        "    @PermuteThrows(when=\"${i > 2}\", value=\"java.sql.SQLException\")\n" +
                        "    public void call() {}\n" +
                        "}");
        assertThat(c).succeeded();
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join2").get()))
                .contains("java.io.IOException");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join2").get()))
                .doesNotContain("SQLException");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("java.io.IOException");
        assertThat(sourceOf(c.generatedSourceFile("io.example.Join3").get()))
                .contains("java.sql.SQLException");
    }
}
