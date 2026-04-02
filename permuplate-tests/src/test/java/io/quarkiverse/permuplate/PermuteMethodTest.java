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
 * Tests for @PermuteMethod (multiple overloads per class) and extends/implements expansion.
 */
public class PermuteMethodTest {

    private static String generateInline(String source, String templateClass,
            String varName, int from, int to, String classNameTemplate, int forI) {
        CompilationUnit cu = StaticJavaParser.parse(source);
        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(templateClass)).orElseThrow();
        PermuteConfig config = new PermuteConfig(varName, from, to, classNameTemplate,
                new String[0], new PermuteVarConfig[0], true, false);
        return InlineGenerator.generate(cu, template, config,
                List.of(Map.of(varName, forI))).toString();
    }

    // =========================================================================
    // @PermuteMethod — inline mode with inferred to
    // =========================================================================

    @Test
    public void testBasicInferredToAndLeafNode() {
        // @Permute(from=1, to=3): to = @Permute.to - i = 3-i
        // i=1: j=1..2 → 2 join() overloads
        // i=3: j=1..0 → 0 overloads (leaf)
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1Second<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="j")
                        public Join2First<T1, T2> join(Join1First<T2> fromJ) { return null; }
                        public void execute() {}
                    }
                }
                """;

        String out1 = generateInline(template, "Join1Second", "i", 1, 3, "Join${i}Second", 1);
        assertThat(out1).contains("Join2First<T1, T2>"); // j=1 overload
        assertThat(out1).contains("Join3First<T1, T2, T3>"); // j=2 overload
        assertThat(out1).contains("execute()");
        assertThat(out1).doesNotContain("@PermuteMethod");

        String out3 = generateInline(template, "Join1Second", "i", 1, 3, "Join${i}Second", 3);
        assertThat(out3).doesNotContain("join("); // leaf — no overloads
        assertThat(out3).contains("execute()");
    }

    @Test
    public void testInferredParamTypesForAllJ() {
        // i=1, to=4: j=1,2,3 → 3 overloads; each has different param type
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1Second<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="j")
                        public Join2First<T1, T2> join(Join1First<T2> fromJ) { return null; }
                    }
                }
                """;

        String out1 = generateInline(template, "Join1Second", "i", 1, 4, "Join${i}Second", 1);
        // j=1: Join2First<T1,T2>, Join1First<T2>
        assertThat(out1).contains("Join2First<T1, T2>");
        assertThat(out1).contains("Join1First<T2>");
        // j=2: Join3First<T1,T2,T3>, Join2First<T2,T3>
        assertThat(out1).contains("Join3First<T1, T2, T3>");
        assertThat(out1).contains("Join2First<T2, T3>");
        // j=3: Join4First<T1,T2,T3,T4>, Join3First<T2,T3,T4>
        assertThat(out1).contains("Join4First<T1, T2, T3, T4>");
        assertThat(out1).contains("Join3First<T2, T3, T4>");
    }

    // =========================================================================
    // Extends/implements clause expansion
    // =========================================================================

    @Test
    public void testExtendsClauseImplicitExpansion() {
        // Join1First<T1> extends Join1Second<T1>
        // At i=2: Join3First<T1,T2,T3> extends Join3Second<T1,T2,T3>
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1First<T1> extends Join1Second<T1> {
                        public void filter() {}
                    }
                }
                """;

        String out2 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 2);
        assertThat(out2).contains("extends Join3Second");
        assertThat(out2).contains("T1, T2, T3");

        String out1 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 1);
        assertThat(out1).contains("extends Join2Second");
        assertThat(out1).contains("T1, T2");
    }

    @Test
    public void testExtendsClauseUnchangedWhenNotInGeneratedSet() {
        // Extends a non-generated class → unchanged
        String template = """
                package com.example;
                public class Parent {
                    public static class Step1<T1> extends BaseStep<T1> {
                    }
                }
                """;

        String out1 = generateInline(template, "Step1", "i", 1, 3, "Step${i}", 1);
        assertThat(out1).contains("extends BaseStep<T1>");
    }

    // =========================================================================
    // APT mode — explicit @PermuteMethod with @PermuteReturn
    // =========================================================================

    @Test
    public void testAptExplicitPermuteMethod() {
        // Template is Apt0Second (not in generated set {Apt1Second, Apt2Second, Apt3Second})
        // to avoid self-collision when writing the i=1 output file.
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Apt0Second",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from=1, to=3, className="Apt${i}Second",
                                 strings={"max=3"})
                        public class Apt0Second {
                            @PermuteMethod(varName="j", from="1", to="${max - i}", name="join${j}")
                            @PermuteReturn(className="Apt${i+j}First")
                            public Object join(Object src) { return null; }
                            public void execute() {}
                        }
                        """);
        // Stub classes for the return types referenced in generated overloads
        var apt2First = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Apt2First",
                "package io.quarkiverse.permuplate.example; public class Apt2First {}");
        var apt3First = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.Apt3First",
                "package io.quarkiverse.permuplate.example; public class Apt3First {}");

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source, apt2First, apt3First);

        assertThat(compilation).succeeded();

        // Apt1Second (i=1): j=1..2 → 2 overloads returning Apt2First and Apt3First
        String src1 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Apt1Second").orElseThrow());
        assertThat(src1).contains("Apt2First");
        assertThat(src1).contains("Apt3First");
        assertThat(src1).contains("execute()");
        assertThat(src1).doesNotContain("@PermuteMethod");

        // Apt3Second (i=3): j=1..0 → 0 overloads (leaf)
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.Apt3Second").orElseThrow());
        assertThat(src3).doesNotContain("join(");
        assertThat(src3).contains("execute()");
    }
}
