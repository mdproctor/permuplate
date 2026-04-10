package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteExtendsExpansionTest {

    @Test
    public void testExtendsExpansionTNumber() {
        var secondTemplate = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Step2Second",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Step${i}Second")
                        public class Step2Second<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> {}
                        """);
        var firstTemplate = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Step2First",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Step${i}First")
                        public class Step2First<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1>
                                extends Step2Second<T1> {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(secondTemplate, firstTemplate);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Step3First").orElseThrow());
        assertThat(src).contains("extends Step3Second<T1, T2, T3>");
        assertThat(src).doesNotContain("Step2Second");
    }

    @Test
    public void testExtendsExpansionAlpha() {
        var secondTemplate = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Alpha2Second",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Alpha${i}Second")
                        public class Alpha2Second<A, @PermuteTypeParam(varName="j", from="2", to="${i}", name="${alpha(j)}") B>
                                extends Object {}
                        """);
        var firstTemplate = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Alpha2First",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Alpha${i}First")
                        public class Alpha2First<A, @PermuteTypeParam(varName="j", from="2", to="${i}", name="${alpha(j)}") B>
                                extends Alpha2Second<A, B> {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(secondTemplate, firstTemplate);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Alpha3First").orElseThrow());
        assertThat(src).contains("extends Alpha3Second<A, B, C>");
        assertThat(src).doesNotContain("Alpha2Second");
    }

    @Test
    public void testThirdPartyBaseClassNotExpanded() {
        var template = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.Widget2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteTypeParam;
                        @Permute(varName="i", from=3, to=3, className="Widget${i}")
                        public class Widget2<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1>
                                extends java.util.ArrayList<T1> {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(template);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Widget3").orElseThrow());
        assertThat(src).contains("extends java.util.ArrayList");
        assertThat(src).doesNotContain("ArrayList3");
    }
}
