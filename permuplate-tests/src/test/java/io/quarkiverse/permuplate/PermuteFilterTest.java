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
        // Filter not yet applied in processor — all three are generated.
        // TODO: Once Task 3 wires up filter evaluation, Join4 will be absent.
        assertThat(compilation.generatedSourceFile("io.example.Join3").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.Join4").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.Join5").isPresent()).isTrue();
    }
}
