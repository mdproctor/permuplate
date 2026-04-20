package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteSelfTest {

    @Test
    public void testPermuteSelfSetsReturnTypeToGeneratedClass() {
        // Happy path: @PermuteSelf on a method → return type becomes the generated class.
        // Template is Fluent1; generates Fluent2 and Fluent3.
        var source = JavaFileObjects.forSourceString("io.ex.Fluent1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSelf;
                        @Permute(varName="i", from="2", to="3", className="Fluent${i}")
                        public class Fluent1 {
                            @PermuteSelf
                            public Object self() { return this; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Fluent2").orElseThrow());
        assertThat(src2).contains("public Fluent2 self()");
        assertThat(src2).doesNotContain("@PermuteSelf");
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Fluent3").orElseThrow());
        assertThat(src3).contains("public Fluent3 self()");
    }

    @Test
    public void testPermuteSelfIncludesTypeParameters() {
        // @PermuteSelf includes the full expanded type parameter list.
        // Template is Builder1; generates Builder2 (END, T1) and Builder3 (END, T1, T2).
        var source = JavaFileObjects.forSourceString("io.ex.Builder1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="3", className="Builder${i}")
                        public class Builder1<END,
                                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="T${k}") T1> {
                            @PermuteSelf
                            public Object withValue() { return this; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Builder2").orElseThrow());
        assertThat(src2).contains("Builder2<END, T1> withValue()");
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Builder3").orElseThrow());
        // Builder3<END, T1, T2> withValue()
        assertThat(src3).contains("Builder3<END, T1, T2> withValue()");
    }

    @Test
    public void testPermuteSelfOnMultipleMethods() {
        // Multiple @PermuteSelf on same class — all updated independently.
        // Template is Chain1; generates Chain2.
        var source = JavaFileObjects.forSourceString("io.ex.Chain1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="Chain${i}")
                        public class Chain1 {
                            @PermuteSelf public Object step1() { return this; }
                            @PermuteSelf public Object step2() { return this; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.Chain2").orElseThrow());
        assertThat(src).contains("public Chain2 step1()");
        assertThat(src).contains("public Chain2 step2()");
        assertThat(src).doesNotContain("@PermuteSelf");
    }

    @Test
    public void testMethodsWithoutPermuteSelfUnchanged() {
        // Non-annotated methods keep their declared return types (regression guard).
        // Template is NoSelf1; generates NoSelf2.
        var source = JavaFileObjects.forSourceString("io.ex.NoSelf1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="NoSelf${i}")
                        public class NoSelf1 {
                            @PermuteSelf public Object chain() { return this; }
                            public String name() { return "hello"; }
                            public int count() { return 0; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.NoSelf2").orElseThrow());
        assertThat(src).contains("public NoSelf2 chain()");
        assertThat(src).contains("public String name()");
        assertThat(src).contains("public int count()");
    }
}
