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
        PermuteConfig config = new PermuteConfig(varName, String.valueOf(from), String.valueOf(to), classNameTemplate,
                new String[0], new PermuteVarConfig[0], true, false);
        return InlineGenerator.generate(cu, template, config,
                List.of(Map.of(varName, forI))).toString();
    }

    // =========================================================================
    // @PermuteMethod — inline mode with inferred to
    // =========================================================================

    @Test
    public void testBasicInferredToAndLeafNode() {
        // @Permute(from="1", to="3"): to = @Permute.to - i = 3-i
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
        // Same-N: JoinNFirst extends JoinNSecond with N type args (not forward-reference)
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1First<T1> extends Join1Second<T1> {
                        public void filter() {}
                    }
                }
                """;

        // forI=1: Join1First<T1> extends Join1Second<T1>
        String out1 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 1);
        assertThat(out1).contains("extends Join1Second");
        assertThat(out1).contains("T1");
        assertThat(out1).doesNotContain("T2");

        // forI=2: Join2First<T1, T2> extends Join2Second<T1, T2>
        String out2 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 2);
        assertThat(out2).contains("extends Join2Second");
        assertThat(out2).contains("T1, T2");
        assertThat(out2).doesNotContain("T3");

        // forI=3: Join3First<T1, T2, T3> extends Join3Second<T1, T2, T3>
        String out3 = generateInline(template, "Join1First", "i", 1, 4, "Join${i}First", 3);
        assertThat(out3).contains("extends Join3Second");
        assertThat(out3).contains("T1, T2, T3");
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

    @Test
    public void testExtendsClauseAlphaNaming() {
        // Join0First<DS, A> extends Join0Second<DS, A> with @PermuteTypeParam alpha naming.
        // G1 (@PermuteTypeParam) expands the class type params first (A → A,B,C,...).
        // G3 fires when (a) the parent class shares the same name prefix + embedded number,
        // and (b) the extends clause type args are a prefix of postG1TypeParams [DS, A, B, ...].
        //
        // i=1: Join1First<DS, A>        extends Join1Second<DS, A>
        // i=2: Join2First<DS, A, B>     extends Join2Second<DS, A, B>
        // i=3: Join3First<DS, A, B, C>  extends Join3Second<DS, A, B, C>
        String template = """
                package com.example;
                public class Parent {
                    public static class Join0First<DS,
                            @io.quarkiverse.permuplate.PermuteTypeParam(
                                varName="k", from="1", to="${i}", name="${alpha(k)}") A>
                            extends Join0Second<DS, A> {
                        public void filter() {}
                    }
                }
                """;

        String out1 = generateInline(template, "Join0First", "i", 1, 3, "Join${i}First", 1);
        assertThat(out1).contains("Join1First<DS, A>");
        assertThat(out1).contains("extends Join1Second<DS, A>");
        assertThat(out1).doesNotContain("B"); // i=1 has no expansion — A is the only fact param

        String out2 = generateInline(template, "Join0First", "i", 1, 3, "Join${i}First", 2);
        assertThat(out2).contains("Join2First<DS, A, B>");
        assertThat(out2).contains("extends Join2Second<DS, A, B>");

        String out3 = generateInline(template, "Join0First", "i", 1, 3, "Join${i}First", 3);
        assertThat(out3).contains("Join3First<DS, A, B, C>");
        assertThat(out3).contains("extends Join3Second<DS, A, B, C>");
    }

    @Test
    public void testExtendsClauseAlphaNamingNoFixedPrefix() {
        // Alpha expanding type params with no fixed prefix (template: <A> extends Sibling<A>).
        // At i=1 extArgNames == postG1TypeParams ([A] == [A]) — full match, not strict prefix.
        // The isPrefix check uses <= so this passes; class name still gets renumbered.
        //
        // i=1: Step1First<A>     extends Step1Second<A>
        // i=2: Step2First<A, B>  extends Step2Second<A, B>
        String template = """
                package com.example;
                public class Parent {
                    public static class Step0First<
                            @io.quarkiverse.permuplate.PermuteTypeParam(
                                varName="k", from="1", to="${i}", name="${alpha(k)}") A>
                            extends Step0Second<A> {
                        public void action() {}
                    }
                }
                """;

        String out1 = generateInline(template, "Step0First", "i", 1, 2, "Step${i}First", 1);
        assertThat(out1).contains("Step1First<A>");
        assertThat(out1).contains("extends Step1Second<A>");
        assertThat(out1).doesNotContain("B");

        String out2 = generateInline(template, "Step0First", "i", 1, 2, "Step${i}First", 2);
        assertThat(out2).contains("Step2First<A, B>");
        assertThat(out2).contains("extends Step2Second<A, B>");
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
                        @Permute(varName="i", from="1", to="3", className="Apt${i}Second",
                                 strings={"maxN=3"})
                        public class Apt0Second {
                            @PermuteMethod(varName="j", from="1", to="${maxN - i}", name="join${j}")
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

    // =========================================================================
    // Method-level @PermuteTypeParam — G4
    // =========================================================================

    @Test
    public void testMethodLevelTypeParamExplicit() {
        // @PermuteMethod(k=2..4, name="path${k}") + @PermuteTypeParam(j=1..k-1, name="P${j}")
        // k=2: path2<P1>(); k=3: path3<P1, P2>(); k=4: path4<P1, P2, P3>()
        String template = """
                package com.example;
                public class Parent {
                    public static class Step1<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="k", from="2", to="4", name="path${k}")
                        public <@io.quarkiverse.permuplate.PermuteTypeParam(varName="j", from="1", to="${k-1}", name="P${j}") PB>
                               Object path2() { return null; }
                    }
                }
                """;

        String out = generateInline(template, "Step1", "i", 1, 1, "Step${i}", 1);
        assertThat(out).contains("<P1>");
        assertThat(out).contains("path2");
        assertThat(out).contains("<P1, P2>");
        assertThat(out).contains("path3");
        assertThat(out).contains("<P1, P2, P3>");
        assertThat(out).contains("path4");
        assertThat(out).doesNotContain("@PermuteTypeParam");
        assertThat(out).doesNotContain("@PermuteMethod");
    }

    @Test
    public void testMethodLevelTypeParamTConvention() {
        // T${j} convention: k=2 → <T1>, k=3 → <T1, T2>
        String template = """
                package com.example;
                public class Parent {
                    public static class Base1<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="k", from="2", to="3", name="step${k}")
                        public <@io.quarkiverse.permuplate.PermuteTypeParam(varName="j", from="1", to="${k-1}", name="T${j}") A>
                               Object step2() { return null; }
                    }
                }
                """;

        String out = generateInline(template, "Base1", "i", 1, 1, "Base${i}", 1);
        assertThat(out).contains("step2");
        assertThat(out).contains("<T1>");
        assertThat(out).contains("step3");
        assertThat(out).contains("<T1, T2>");
    }

    @Test
    public void testMethodLevelTypeParamAlphaNaming() {
        // alpha(j+1) naming: k=2 → <B>, k=3 → <B, C>, k=4 → <B, C, D>
        String template = """
                package com.example;
                public class Parent {
                    public static class Join1<T1> {
                        @io.quarkiverse.permuplate.PermuteMethod(varName="k", from="2", to="4", name="path${k}")
                        public <@io.quarkiverse.permuplate.PermuteTypeParam(varName="j", from="1", to="${k-1}", name="${alpha(j+1)}") PB>
                               Object path2() { return null; }
                    }
                }
                """;

        String out = generateInline(template, "Join1", "i", 1, 1, "Join${i}", 1);
        assertThat(out).contains("path2");
        assertThat(out).contains("<B>");
        assertThat(out).contains("path3");
        assertThat(out).contains("<B, C>");
        assertThat(out).contains("path4");
        assertThat(out).contains("<B, C, D>");
    }

    // =========================================================================
    // @PermuteMethod ternary from expression — conditional method generation
    // =========================================================================

    /**
     * A JEXL ternary expression in @PermuteMethod.from can suppress method generation
     * for specific outer values of i. Here from="${i > 1 ? i : i+1}" with to="${i}"
     * produces an empty range (from > to) at i=1 — the method is silently omitted —
     * and a single-clone range at i≥2.
     *
     * <p>
     * This is the mechanism used by filterLatest in JoinBuilder: at arity 1 the
     * single-fact and all-facts filter have identical signatures, so the single-fact
     * sentinel must be suppressed. At arity 2+ they are distinct overloads.
     */
    @Test
    public void testTernaryFromExpressionSuppressesAtArity1() {
        String parentSource = """
                package com.example;
                public class Selector {
                    @io.quarkiverse.permuplate.Permute(varName = "i", from = "1", to = "3",
                            className = "Sel${i}", inline = true, keepTemplate = false)
                    public static class Sel0 {
                        @io.quarkiverse.permuplate.PermuteMethod(
                                varName = "x", from = "${i > 1 ? i : i+1}", to = "${i}",
                                name = "extra")
                        @io.quarkiverse.permuplate.PermuteReturn(
                                className = "Sel${i}", when = "true")
                        public Object extraSentinel(
                                @io.quarkiverse.permuplate.PermuteDeclr(type = "String")
                                Object param) { return this; }

                        public Object regular() { return this; }
                    }
                }
                """;
        String src1 = generateInline(parentSource, "Sel0", "i", 1, 3, "Sel${i}", 1);
        assertThat(src1).doesNotContain("extra(");
        assertThat(src1).contains("regular()");

        String src2 = generateInline(parentSource, "Sel0", "i", 1, 3, "Sel${i}", 2);
        assertThat(src2).contains("Sel2 extra(String param)");
        assertThat(src2).contains("regular()");

        String src3 = generateInline(parentSource, "Sel0", "i", 1, 3, "Sel${i}", 3);
        assertThat(src3).contains("Sel3 extra(String param)");
    }

    @Test
    public void testPermuteMethodJBasedTypeExpansion() {
        // Template JConn0<T1> (generated set = {JConn1}) has one declared class-level type param T1.
        // The sentinel method uses a method-level type param <T2> so the template is valid Java.
        // T2 is NOT a class-level type param, so expandMethodTypesForJ treats it as the growing tip.
        // The parameter type JS1<T2> (a separate stub class) also uses T2, making overloads
        // distinguishable by erasure.
        //
        // @PermuteMethod(j=1..3):
        //   j=1: no-op (offset=0) → <T2> JConn2<T1, T2> connect(JS1<T2> src)
        //   j=2: tip grows by 1 → <T2, T3> JConn3<T1, T2, T3> connect(JS2<T2, T3> src)
        //   j=3: tip grows by 2 → <T2, T3, T4> JConn4<T1, T2, T3, T4> connect(JS3<T2, T3, T4> src)
        var source = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.JConn0",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        @Permute(varName="i", from="1", to="1", className="JConn${i}")
                        public class JConn0<T1> {
                            @PermuteMethod(varName="j", from="1", to="3")
                            public <T2> JConn2<T1, T2> connect(JS1<T2> src) { return null; }
                        }
                        """);

        // Stubs for JS1, JS2, JS3 (parameter types — separate from generated JConn family)
        // and JConn2, JConn3, JConn4 (return types)
        var js1 = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.JS1",
                "package io.permuplate.example; public class JS1<A> {}");
        var js2 = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.JS2",
                "package io.permuplate.example; public class JS2<A, B> {}");
        var js3 = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.JS3",
                "package io.permuplate.example; public class JS3<A, B, C> {}");
        var jconn2 = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.JConn2",
                "package io.permuplate.example; public class JConn2<A, B> {}");
        var jconn3 = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.JConn3",
                "package io.permuplate.example; public class JConn3<A, B, C> {}");
        var jconn4 = com.google.testing.compile.JavaFileObjects.forSourceString(
                "io.permuplate.example.JConn4",
                "package io.permuplate.example; public class JConn4<A, B, C, D> {}");

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source, js1, js2, js3, jconn2, jconn3, jconn4);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.JConn1").orElseThrow());
        // j=1: return type unchanged (no-op) — method-level <T2> kept, param JS1<T2>
        assertThat(src).contains("JConn2<T1, T2> connect(JS1<T2> src)");
        // j=2: tip expanded by 1 → JConn3<T1, T2, T3>, param JS2<T2, T3>
        assertThat(src).contains("JConn3<T1, T2, T3> connect(JS2<T2, T3> src)");
        // j=3: tip expanded by 2 → JConn4<T1, T2, T3, T4>, param JS3<T2, T3, T4>
        assertThat(src).contains("JConn4<T1, T2, T3, T4> connect(JS3<T2, T3, T4> src)");
    }

    @Test
    public void testPermuteBodyAccessesInnerMethodVariable() {
        // @PermuteBody on a @PermuteMethod template must evaluate the body with the
        // inner method variable (n) available, not just the outer permutation variable (i).
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Counter2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        import io.quarkiverse.permuplate.PermuteBody;
                        @Permute(varName="i", from="1", to="1", className="Counter${i}")
                        public class Counter2 {
                            @PermuteMethod(varName="n", from="2", to="3", name="count${n}")
                            @PermuteBody(body="{ return ${n}; }")
                            public int countTemplate() { return 0; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);
        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Counter1").orElseThrow());
        assertThat(src).contains("int count2()");
        assertThat(src).contains("return 2");
        assertThat(src).contains("int count3()");
        assertThat(src).contains("return 3");
        assertThat(src).doesNotContain("return 0");
    }
}
