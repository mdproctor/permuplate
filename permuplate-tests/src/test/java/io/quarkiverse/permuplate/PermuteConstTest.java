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
 * Tests for @PermuteConst — literal initializer substitution on fields,
 * including combination with @PermuteDeclr.
 */
public class PermuteConstTest {

    // -------------------------------------------------------------------------
    // @PermuteConst on a class field — integer expression
    // -------------------------------------------------------------------------

    @Test
    public void testIntegerFieldConst() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Counter2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteConst;
                        import io.quarkiverse.permuplate.PermuteParam;
                        @Permute(varName="i", from=3, to=4, className="Counter${i}")
                        public class Counter2 {
                            @PermuteConst("${i}") public static final int ARITY = 2;

                            public int getArity() { return ARITY; }

                            public void accept(
                                    @PermuteParam(varName="j", from="1", to="${i}", type="Object", name="arg${j}") Object arg1) {
                                process(arg1);
                            }
                            private void process(Object... args) {}
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Counter3").orElseThrow());
        assertThat(src3).contains("int ARITY = 3");
        assertThat(src3).doesNotContain("int ARITY = 2");
        assertThat(src3).contains("return ARITY");
        assertThat(src3).contains("void accept(Object arg1, Object arg2, Object arg3)");

        String src4 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Counter4").orElseThrow());
        assertThat(src4).contains("int ARITY = 4");
        assertThat(src4).doesNotContain("int ARITY = 2");
        assertThat(src4).contains("void accept(Object arg1, Object arg2, Object arg3, Object arg4)");
    }

    // -------------------------------------------------------------------------
    // @PermuteConst on a local variable inside a method body
    // -------------------------------------------------------------------------

    @Test
    public void testIntegerLocalVariableConst() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Tracker2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteConst;
                        @Permute(varName="i", from=3, to=3, className="Tracker${i}")
                        public class Tracker2 {
                            public int getArity() {
                                @PermuteConst("${i}") int n = 2;
                                return n;
                            }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Tracker3").orElseThrow());
        assertThat(src).contains("int n = 3");
        assertThat(src).doesNotContain("int n = 2");
        assertThat(src).contains("return n");
    }
}
