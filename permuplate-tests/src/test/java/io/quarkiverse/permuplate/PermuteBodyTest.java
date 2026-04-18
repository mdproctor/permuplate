package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteBodyTest {

    @Test
    public void testBodyReplacesEntireMethod() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Holder1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteBody;
                        @Permute(varName="i", from="2", to="3", className="Holder${i}")
                        public class Holder1 {
                            @PermuteBody(body = "{ return ${i}; }")
                            public int arity() {
                                return 1; // template placeholder
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Holder2").orElseThrow());
        assertThat(src2).contains("return 2");
        assertThat(src2).doesNotContain("return 1");
        assertThat(src2).doesNotContain("@PermuteBody");

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Holder3").orElseThrow());
        assertThat(src3).contains("return 3");
        assertThat(src3).doesNotContain("return 1");
    }

    @Test
    public void testBodyWithStringLiteral() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Namer1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteBody;
                        @Permute(varName="i", from="2", to="2", className="Namer${i}")
                        public class Namer1 {
                            @PermuteBody(body = "{ return \\"arity-${i}\\"; }")
                            public String name() {
                                return "arity-1";
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Namer2").orElseThrow());
        assertThat(src).contains("return \"arity-2\"");
    }

    @Test
    public void testBodyOnConstructor() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Box1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteBody;
                        @Permute(varName="i", from="2", to="2", className="Box${i}")
                        public class Box1 {
                            private final int size;
                            @PermuteBody(body = "{ this.size = ${i}; }")
                            public Box1() {
                                this.size = 1;
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Box2").orElseThrow());
        assertThat(src).contains("this.size = 2");
        assertThat(src).doesNotContain("this.size = 1");
    }
}
