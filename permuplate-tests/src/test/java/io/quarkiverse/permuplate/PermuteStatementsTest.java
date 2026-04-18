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
 * Tests for @PermuteStatements — looped and single statement insertion into method bodies.
 */
public class PermuteStatementsTest {

    @Test
    public void testStatementsInsertedFirst() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Accum1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteStatements;
                        @Permute(varName="i", from="2", to="3", className="Accum${i}")
                        public class Accum1 {
                            int x1;
                            int x2;
                            @PermuteStatements(varName="k", from="1", to="${i-1}",
                                               position="first", body="this.x${k} = x${k};")
                            public void init(int x1) {
                                this.x1 = x1;
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(compilation).succeeded();

        // Accum3: from=1 to=2, inserts this.x1=x1; this.x2=x2; before this.x1=x1; (template)
        String src3 = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Accum3").orElseThrow());
        assertThat(src3).doesNotContain("@PermuteStatements");
        assertThat(src3).contains("this.x1 = x1");
        assertThat(src3).contains("this.x2 = x2");
    }

    @Test
    public void testSingleStatementInsertedLast() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Tail2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteStatements;
                        @Permute(varName="i", from="3", to="3", className="Tail${i}")
                        public class Tail2 {
                            int x;
                            @PermuteStatements(position="last", body="this.x = ${i};")
                            public void setup() {
                                this.x = 0;
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation.generatedSourceFile("io.permuplate.example.Tail3").orElseThrow());
        assertThat(src).contains("this.x = 0");
        assertThat(src).contains("this.x = 3");
        assertThat(src).doesNotContain("@PermuteStatements");
    }

    @Test
    public void testBodyWithStringLiteral() {
        // Regression: body with a Java string literal was silently dropped
        // because stripQuotes(toString()) left raw escape sequences.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Greeter1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteStatements;
                        @Permute(varName="i", from="2", to="2", className="Greeter${i}")
                        public class Greeter1 {
                            private String greeting;
                            @PermuteStatements(position="first", body="this.greeting = \\"hello\\";")
                            public void init() {
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Greeter2").orElseThrow());
        assertThat(src).contains("this.greeting = \"hello\"");
    }
}
