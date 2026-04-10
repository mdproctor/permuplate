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
 * Tests for @PermuteValue — initializer replacement on fields and method statement RHS replacement.
 */
public class PermuteValueTest {

    @Test
    public void testFieldInitializerReplaced() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Sized2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteValue;
                        @Permute(varName="i", from="3", to="3", className="Sized${i}")
                        public class Sized2 {
                            @PermuteValue("${i}") static int ARITY = 2;
                        }
                        """);
        Compilation compilation = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Sized3").orElseThrow());
        assertThat(src).contains("ARITY = 3");
        assertThat(src).doesNotContain("ARITY = 2");
        assertThat(src).doesNotContain("@PermuteValue");
    }

    @Test
    public void testMethodStatementRhsReplaced() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Counted2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteValue;
                        @Permute(varName="i", from="3", to="3", className="Counted${i}")
                        public class Counted2 {
                            int size;
                            @PermuteValue(index = 1, value = "${i}")
                            public void init() {
                                this.size = 99;
                                this.size = 2;
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Counted3").orElseThrow());
        assertThat(src).contains("this.size = 99");
        assertThat(src).contains("this.size = 3");
        assertThat(src).doesNotContain("this.size = 2");
        assertThat(src).doesNotContain("@PermuteValue");
    }
}
