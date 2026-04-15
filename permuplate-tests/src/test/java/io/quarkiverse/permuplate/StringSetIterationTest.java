package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class StringSetIterationTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /**
     * @Permute with values={"Byte","Short","Int"} and className="To${T}Function"
     *          generates ToByteFunction, ToShortFunction, ToIntFunction.
     *          Template class is ToTypeFunction (not in values set) to avoid name collision.
     *          Leading literal "To" is a prefix of "ToTypeFunction" — R1 passes.
     */
    @Test
    public void testStringSetGeneratesClassForEachValue() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "@Permute(varName=\"T\", values={\"Byte\",\"Short\",\"Int\"},\n" +
                        "         className=\"To${T}Function\")\n" +
                        "public interface ToTypeFunction {}");

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.example.ToByteFunction").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.ToShortFunction").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.ToIntFunction").isPresent()).isTrue();
    }
}
