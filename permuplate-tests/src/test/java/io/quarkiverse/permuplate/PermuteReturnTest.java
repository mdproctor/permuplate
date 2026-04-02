package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteVarConfig;
import io.quarkiverse.permuplate.maven.InlineGenerator;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for @PermuteReturn — return type narrowing by arity.
 *
 * <p>
 * APT explicit tests use Google compile-testing with PermuteProcessor.
 * Inline implicit tests call InlineGenerator directly with JavaParser-parsed source.
 */
public class PermuteReturnTest {

    // =========================================================================
    // APT mode — explicit @PermuteReturn with Object sentinel
    // =========================================================================

    @Test
    public void testAptExplicitReturnType() {
        // Step1..Step4 generated; join() returns Step${i+1}<T1..T${i+1}>; Step4.join() omitted
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Step1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        import io.quarkiverse.permuplate.PermuteDeclr;
                        @Permute(varName="i", from=1, to=4, className="Step${i}")
                        public class Step1 {
                            @PermuteReturn(className="Step${i+1}",
                                           typeArgVarName="j", typeArgFrom="1", typeArgTo="${i+1}", typeArgName="T${j}")
                            public Object join(@PermuteDeclr(type="Object") Object src) { return null; }
                            public void execute() {}
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Step1: join() → Step2<T1, T2>
        String src1 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Step1").orElseThrow());
        assertThat(src1).contains("Step2<T1, T2>");
        assertThat(src1).contains("execute()");
        assertThat(src1).doesNotContain("@PermuteReturn");

        // Step3: join() → Step4<T1, T2, T3, T4>
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Step3").orElseThrow());
        assertThat(src3).contains("Step4<T1, T2, T3, T4>");

        // Step4: join() omitted (Step5 not in generated set)
        String src4 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Step4").orElseThrow());
        assertThat(src4).doesNotContain("join(");
        assertThat(src4).contains("execute()");
    }

    @Test
    public void testAptExplicitWhenOverride() {
        // when="true" forces generation even on the last class
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.ForcedChain1",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from=1, to=2, className="ForcedChain${i}")
                        public class ForcedChain1 {
                            @PermuteReturn(className="ForcedChain${i+1}", when="true")
                            public Object next() { return null; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        // ForcedChain2.next() generated (when="true" overrides boundary omission)
        String src2 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.ForcedChain2").orElseThrow());
        assertThat(src2).contains("next()");
        assertThat(src2).contains("ForcedChain3");
    }

    // =========================================================================
    // Inline mode — implicit return type inference via InlineGenerator
    // =========================================================================

    /** Parses a source string, finds the named template class, generates one permutation. */
    private static String generateInline(String source, String templateClassName,
            String varName, int from, int to, String classNameTemplate, int forI) {
        CompilationUnit cu = StaticJavaParser.parse(source);
        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(templateClassName)).orElseThrow();
        PermuteConfig config = new PermuteConfig(varName, from, to, classNameTemplate,
                new String[0], new PermuteVarConfig[0], true, false);
        CompilationUnit output = InlineGenerator.generate(cu, template,
                config, List.of(Map.of(varName, forI)));
        return output.toString();
    }

    @Test
    public void testImplicitBasicBoundaryOmission() {
        // Step1<T1> with join() returning Step2<T1,T2>
        // Step4 (leaf): join() omitted (Step5 not in generated set)
        String template = """
                package com.example;
                public class Parent {
                    public static class Step1<T1> {
                        public Step2<T1, T2> join(Object src) { return null; }
                        public void execute() {}
                    }
                }
                """;

        // i=1: join() → Step2<T1,T2>
        String out1 = generateInline(template, "Step1", "i", 1, 4, "Step${i}", 1);
        assertThat(out1).contains("Step2<T1, T2>");
        assertThat(out1).contains("execute()");

        // i=3: join() → Step4<T1,T2,T3,T4>
        String out3 = generateInline(template, "Step1", "i", 1, 4, "Step${i}", 3);
        assertThat(out3).contains("Step4<T1, T2, T3, T4>");

        // i=4 (leaf): join() omitted
        String out4 = generateInline(template, "Step1", "i", 1, 4, "Step${i}", 4);
        assertThat(out4).doesNotContain("join(");
        assertThat(out4).contains("execute()");
    }

    @Test
    public void testImplicitParameterTypeInferred() {
        // Source<T2> in join() parameter — T2 undeclared, inferred per permutation
        String template = """
                package com.example;
                public class Parent {
                    public static class Step1<T1> {
                        public Step2<T1, T2> join(Source<T2> src) { return null; }
                    }
                }
                """;

        String out1 = generateInline(template, "Step1", "i", 1, 3, "Step${i}", 1);
        assertThat(out1).contains("Step2<T1, T2>");
        assertThat(out1).contains("Source<T2> src");

        String out2 = generateInline(template, "Step1", "i", 1, 3, "Step${i}", 2);
        assertThat(out2).contains("Step3<T1, T2, T3>");
        assertThat(out2).contains("Source<T3> src");
    }

    @Test
    public void testImplicitFixedTypeParamSurvives() {
        // R is fixed, T1 expands — R should pass through in return type
        String template = """
                package com.example;
                public class Parent {
                    public static class Step1<T1, R> {
                        public Step2<T1, T2, R> join(Object src) { return null; }
                    }
                }
                """;

        String out1 = generateInline(template, "Step1", "i", 1, 3, "Step${i}", 1);
        assertThat(out1).contains("Step2<T1, T2, R>");

        String out2 = generateInline(template, "Step1", "i", 1, 3, "Step${i}", 2);
        assertThat(out2).contains("Step3<T1, T2, T3, R>");
    }

    // =========================================================================
    // Validation rules — APT mode
    // =========================================================================

    @Test
    public void testV1PermuteReturnOutsidePermute() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Bare",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        public class Bare {
                            @PermuteReturn(className="Foo")
                            public Object method() { return null; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        // V1: @PermuteReturn outside @Permute — should fail or warn
        // The annotation processor only processes @Permute elements; @PermuteReturn
        // on a non-@Permute class is simply ignored (no error in current implementation).
        // This test documents the current behavior: compilation succeeds, annotation ignored.
        // Full V1 enforcement deferred.
        assertThat(compilation).succeeded();
    }

    @Test
    public void testV2TypeArgVarNameWithoutTypeArgTo() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BadV2",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from=2, to=3, className="BadV2Step${i}")
                        public class BadV2 {
                            @PermuteReturn(className="BadV2Step${i+1}", typeArgVarName="j")
                            public Object method() { return null; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("typeArgVarName");
        assertThat(compilation).hadErrorContaining("typeArgTo");
    }

    @Test
    public void testV3TypeArgFromGreaterThanTo() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BadV3",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from=2, to=3, className="BadV3Step${i}")
                        public class BadV3 {
                            @PermuteReturn(className="BadV3Step${i+1}", typeArgVarName="j",
                                           typeArgFrom="5", typeArgTo="2", typeArgName="T${j}")
                            public Object method() { return null; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("invalid");
    }

    @Test
    public void testV6TypeArgsAndTypeArgVarNameBothSet() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.BadV6",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from=2, to=3, className="BadV6Step${i}")
                        public class BadV6 {
                            @PermuteReturn(className="BadV6Step${i+1}",
                                           typeArgVarName="j", typeArgTo="${i+1}", typeArgName="T${j}",
                                           typeArgs="T1, T2")
                            public Object method() { return null; }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("typeArgs");
        assertThat(compilation).hadErrorContaining("typeArgVarName");
    }
}
