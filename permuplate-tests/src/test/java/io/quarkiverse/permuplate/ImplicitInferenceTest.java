package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class ImplicitInferenceTest {

    @Test
    public void testImplicitReturnTypeInference() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Chain2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from="3", to="4", className="Chain${i}")
                        public class Chain2<T1, @PermuteTypeParam(varName="j", from="2", to="${i}", name="T${j}") T2> {
                            public Chain2<T1, T2> next() { return null; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Chain3: next() should return Chain3<T1, T2, T3>
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Chain3").orElseThrow());
        assertThat(src3).contains("Chain3<T1, T2, T3> next()");
        assertThat(src3).doesNotContain("Chain2<T1, T2> next()");

        // Chain4: next() would return Chain5 which is NOT generated → method omitted
        String src4 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Chain4").orElseThrow());
        assertThat(src4).doesNotContain("next()");
    }

    @Test
    public void testImplicitInferenceSkipsExplicitPermuteReturn() {
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Explicit2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from="3", to="3", className="Explicit${i}")
                        public class Explicit2<T1, @PermuteTypeParam(varName="j", from="2", to="${i}", name="T${j}") T2> {
                            @PermuteReturn(className="Explicit${i}", typeArgVarName="j", typeArgFrom="1", typeArgTo="${i}", typeArgName="T${j}")
                            public Explicit2<T1, T2> typed() { return this; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Explicit3").orElseThrow());
        assertThat(src).contains("Explicit3<T1, T2, T3> typed()");
    }
}
