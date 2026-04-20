package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class AlphaGrowingTipInferenceTest {

    @Test
    public void testAlphaGrowingTipInferenceFillsTypeArgs() {
        // When @PermuteReturn has no typeArgs AND method has single-value @PermuteTypeParam
        // with alpha naming, typeArgs is inferred: current class type params + new alpha letter.
        // Generated: Step2, Step3. Step4 not generated → advance() boundary-omitted from Step3.
        var source = JavaFileObjects.forSourceString("io.ex.Step1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="4", className="Step${i}")
                        public class Step1<END,
                                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="${alpha(k)}") A> {
                            @PermuteTypeParam(varName="m", from="${i}", to="${i}", name="${alpha(m)}")
                            @PermuteReturn(className="Step${i+1}")
                            public <B> Object advance(java.util.function.Function<String, B> fn) {
                                return null;
                            }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Step2").orElseThrow());
        // Step2<END, A> → advance() returns Step3<END, A, B>
        assertThat(src2).contains("Step3<END, A, B> advance(");
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Step3").orElseThrow());
        // Step3<END, A, B> → advance() returns Step4<END, A, B, C>
        assertThat(src3).contains("Step4<END, A, B, C> advance(");
        String src4 = sourceOf(c.generatedSourceFile("io.ex.Step4").orElseThrow());
        // Step4<END, A, B, C> → advance() would return Step5, not generated → method omitted
        assertThat(src4).doesNotContain("advance(");
    }

    @Test
    public void testExplicitTypeArgsNotOverriddenByInference() {
        // When @PermuteReturn has explicit typeArgs, inference does NOT fire.
        // This test uses a 4-class range so that inference WOULD produce a valid return type,
        // then verifies that an explicit typeArgs expression produces a different (distinguishable)
        // result. We use typeArgVarName (loop style) as the explicit form — a distinct code path
        // from the alpha inference. If inference fired, the return type would contain 3 type args
        // (END, A, B); explicit typeArgVarName loop produces 2 (A, B) — provably different.
        //
        // For this test the generated class Explicit3 is in the set (from=2, to=3), so
        // compilation succeeds and we can check the generated source directly.
        var source = JavaFileObjects.forSourceString("io.ex.Explicit1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="3", className="Explicit${i}")
                        public class Explicit1<END,
                                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="${alpha(k)}") A> {
                            @PermuteTypeParam(varName="m", from="${i}", to="${i}", name="${alpha(m)}")
                            @PermuteReturn(className="Explicit${i+1}",
                                           typeArgVarName="k", typeArgFrom="1", typeArgTo="${i}",
                                           typeArgName="${alpha(k)}")
                            public <B> Object advance(Object fn) { return null; }
                        }
                        """);
        // Explicit2 advance() → Explicit3<A, B> via typeArgVarName loop (k=1..2 → alpha(1)=A, alpha(2)=B)
        // Inference would produce END, A, B (3 args). Explicit loop produces A, B (2 args).
        // Explicit3 has 3 type params and the compilation will fail due to wrong arg count,
        // but the annotation processor runs and we can verify the generated source text.
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        // Access the generated files field via reflection to bypass compile-testing's success guard
        try {
            java.lang.reflect.Field f = c.getClass().getDeclaredField("generatedFiles");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            com.google.common.collect.ImmutableList<javax.tools.JavaFileObject> files = (com.google.common.collect.ImmutableList<javax.tools.JavaFileObject>) f
                    .get(c);
            String src2 = files.stream()
                    .filter(file -> file.getName().contains("Explicit2"))
                    .findFirst()
                    .map(ProcessorTestSupport::sourceOf)
                    .orElseThrow(() -> new AssertionError("Explicit2 not generated"));
            // Explicit typeArgVarName loop → Explicit3<A, B>, NOT Explicit3<END, A, B>.
            // If inference had fired (ignoring explicit), it would produce "END, A, B".
            assertThat(src2).contains("Explicit3<A, B> advance(");
            assertThat(src2).doesNotContain("Explicit3<END, A, B>");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testBoundaryOmissionWithInferredTypeArgs() {
        // Boundary omission still works when typeArgs are inferred.
        // Generated: Bound2, Bound3. Bound4 not generated → advance() omitted from Bound3.
        var source = JavaFileObjects.forSourceString("io.ex.Bound1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="3", className="Bound${i}")
                        public class Bound1<END,
                                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="${alpha(k)}") A> {
                            @PermuteTypeParam(varName="m", from="${i}", to="${i}", name="${alpha(m)}")
                            @PermuteReturn(className="Bound${i+1}")
                            public <B> Object advance(Object fn) { return null; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Bound2").orElseThrow());
        // Bound3 IS in generated set → advance() present in Bound2 with inferred return type.
        assertThat(src2).contains("Bound3<END, A, B> advance(");
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Bound3").orElseThrow());
        // Bound4 NOT in generated set → advance() omitted from Bound3.
        assertThat(src3).doesNotContain("advance(");
    }
}
