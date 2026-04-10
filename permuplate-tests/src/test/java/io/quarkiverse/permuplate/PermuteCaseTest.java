package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteCaseTest {

    @Test
    public void testSwitchCasesAccumulatePerArity() {
        // Template: an Object[] data array accessed by index.
        // @PermuteCase inserts cases 1..i-1 so Dispatch2 has cases 0+1, Dispatch3 has 0+1+2.
        // Bodies reference data[k] — array access avoids any field-rename interaction.
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Dispatch1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="2", to="3", className="Dispatch${i}")
                        public class Dispatch1 {
                            private final Object[] data;
                            public Dispatch1(Object[] data) { this.data = data; }
                            @PermuteCase(varName="k", from="1", to="${i-1}", index="${k}", body="return data[${k}];")
                            public Object get(int index) {
                                switch (index) {
                                    case 0: return data[0];
                                    default: throw new IndexOutOfBoundsException(index);
                                }
                            }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Dispatch2").orElseThrow());
        assertThat(src2).contains("case 0:");
        assertThat(src2).contains("case 1:");
        assertThat(src2).contains("return data[1]");
        assertThat(src2).doesNotContain("@PermuteCase");
        assertThat(src2).doesNotContain("super.get");

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Dispatch3").orElseThrow());
        assertThat(src3).contains("case 0:");
        assertThat(src3).contains("case 1:");
        assertThat(src3).contains("case 2:");
        assertThat(src3).contains("return data[1]");
        assertThat(src3).contains("return data[2]");
        assertThat(src3).doesNotContain("@PermuteCase");
    }

    @Test
    public void testEmptyRangeInsertsNoCases() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Single1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="2", to="2", className="Single${i}")
                        public class Single1 {
                            @PermuteCase(varName="k", from="1", to="${i-1}", index="${k}", body="return ${k};")
                            public int dispatch(int index) {
                                switch (index) {
                                    case 0: return 0;
                                    default: throw new IndexOutOfBoundsException(index);
                                }
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Single2").orElseThrow());
        assertThat(src).contains("case 0:");
        assertThat(src).contains("case 1:");
    }
}
