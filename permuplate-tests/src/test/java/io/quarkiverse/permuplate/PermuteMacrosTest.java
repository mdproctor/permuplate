package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteMacrosTest {

    @Test
    public void testMacroAvailableInClassNameExpression() {
        // Macro defined via macros= and used in className template.
        var source = JavaFileObjects.forSourceString("io.ex.Item1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.Permute;
                        @Permute(varName="i", from="2", to="3", className="${prefix}Item${i}",
                                 macros={"prefix=\\"Fancy\\""})
                        public class Item1 {}
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        assertThat(c.generatedSourceFile("io.ex.FancyItem2").isPresent()).isTrue();
        assertThat(c.generatedSourceFile("io.ex.FancyItem3").isPresent()).isTrue();
    }

    @Test
    public void testMacroEvaluatedPerPermutation() {
        // Macro references the loop variable — evaluated fresh for each i.
        var source = JavaFileObjects.forSourceString("io.ex.Widget1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="3", className="Widget${i}",
                                 macros={"doubled=i*2"})
                        public class Widget1 {
                            @PermuteValue(index=0, value="${doubled}")
                            public int factor = 0;
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Widget2").orElseThrow());
        assertThat(src2).contains("int factor = 4"); // i=2, doubled=4
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Widget3").orElseThrow());
        assertThat(src3).contains("int factor = 6"); // i=3, doubled=6
    }

    @Test
    public void testMacroChaining() {
        // Later macros may reference earlier macros (evaluated in declaration order).
        var source = JavaFileObjects.forSourceString("io.ex.Chain1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="Chain${i}",
                                 macros={"base=i+1", "doubled=base*2"})
                        public class Chain1 {
                            @PermuteValue(index=0, value="${doubled}")
                            public int x = 0;
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.Chain2").orElseThrow());
        // base = 2+1 = 3, doubled = 3*2 = 6
        assertThat(src).contains("int x = 6");
    }

    @Test
    public void testMalformedMacroSkipped() {
        // A macro with missing '=' separator is silently skipped (doesn't crash).
        var source = JavaFileObjects.forSourceString("io.ex.Safe1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.Permute;
                        @Permute(varName="i", from="2", to="2", className="Safe${i}",
                                 macros={"badformat"})
                        public class Safe1 {}
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        assertThat(c.generatedSourceFile("io.ex.Safe2").isPresent()).isTrue();
    }
}
