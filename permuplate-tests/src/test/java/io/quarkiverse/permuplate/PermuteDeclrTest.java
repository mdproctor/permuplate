package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.core.EvaluationContext;
import io.quarkiverse.permuplate.core.PermuteDeclrTransformer;
import io.quarkiverse.permuplate.example.CtorDeclr2;
import io.quarkiverse.permuplate.example.DualForEach2;
import io.quarkiverse.permuplate.example.FieldDeclr2;
import io.quarkiverse.permuplate.example.ForEachDeclr2;
import io.quarkiverse.permuplate.example.GetterRename2;
import io.quarkiverse.permuplate.example.RichJoin2;
import io.quarkiverse.permuplate.example.TwoFieldDeclr2;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for {@code @PermuteDeclr}: declaration renaming and scope.
 * <p>
 * {@code @PermuteDeclr} can appear in three positions, each with a different scope:
 * <ul>
 * <li><b>Field</b> — rename propagates to the entire class body (all methods).</li>
 * <li><b>Constructor parameter</b> — rename propagates within the constructor body only.</li>
 * <li><b>For-each variable</b> — rename propagates only within the loop body.</li>
 * </ul>
 */
public class PermuteDeclrTest {

    // -------------------------------------------------------------------------
    // Field + for-each combined in a complex body: RichJoin3
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link RichJoin2} template, which annotates both the field
     * ({@code c2}) and the for-each variable ({@code o2}) with {@code @PermuteDeclr}.
     * Verifies that every usage site of both symbols is renamed — null checks,
     * skip branches, call sites, and printlns before, inside, and after the loop —
     * and that no stale references survive.
     */
    @Test
    public void testPermuteDeclrRenamesAllUsages() {
        var compilation = compileTemplate(RichJoin2.class, 3, 3);
        assertThat(compilation).succeeded();

        var src = sourceOf(compilation.generatedSourceFile(generatedClassName(RichJoin2.class, 3))
                .orElseThrow(() -> new AssertionError(generatedClassName(RichJoin2.class, 3) + ".java not generated")));

        // Field rename: c2 → c3 everywhere (now generic: Callable3<A, B, C>)
        assertThat(src).contains("Callable3<A, B, C> c3");
        assertThat(src).doesNotContain("c2");
        assertThat(src).contains("\"Processor: \" + c3"); // before loop
        assertThat(src).contains("c3 == null"); // null guard before loop
        assertThat(src).contains("\"Done with \" + c3"); // after loop

        // For-each variable rename: b (C type) everywhere inside loop
        assertThat(src).contains("for (C c : right)");
        assertThat(src).contains("c == null");
        assertThat(src).contains("skipped.add(c)");
        assertThat(src).contains("\"Processed: \" + c + \" with \"");

        // No stale for-each variable from template
        assertThat(src).doesNotContain("b == null");
        assertThat(src).doesNotContain("skipped.add(b)");

        // Typed params: A a, B b (from sentinel expansion); fixed String label preserved
        assertThat(src).contains("A a, B b");
        assertThat(src).contains("String label");
        assertThat(src).contains("\" with \" + label");
        assertThat(src).contains("count + \" items\"");
        assertThat(src).doesNotContain("@PermuteDeclr");
        assertThat(src).doesNotContain("@PermuteParam");

        // Behavioural: process(arg1, arg2, "label") with right=["R"]
        var loader = classLoaderFor(compilation);
        var fixture = prepareJoin(loader, generatedClassName(RichJoin2.class, 3), List.of("R"));
        setField(fixture.instance, "skipped", new ArrayList<>());
        fixture.invoke("process", "arg1", "arg2", "myLabel");
        assertThat(fixture.captured).containsExactly("arg1", "arg2", "R").inOrder();
    }

    // -------------------------------------------------------------------------
    // Field-only: rename propagates to all methods in the class body
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link FieldDeclr2} template, which annotates only the
     * field (no for-each annotation). Verifies that the rename propagates to every
     * usage site across all three methods: {@code describe()}, {@code isReady()},
     * and {@code execute()}.
     */
    @Test
    public void testFieldDeclrRenamesUsagesAcrossAllMethods() {
        var compilation = compileTemplate(FieldDeclr2.class, 3, 3);
        assertThat(compilation).succeeded();

        var src = sourceOf(compilation.generatedSourceFile(generatedClassName(FieldDeclr2.class, 3)).orElseThrow());
        assertThat(src).contains("Callable3<A, B, C> c3");
        assertThat(src).doesNotContain("c2");
        assertThat(src).contains("\"handler: \" + c3"); // describe()
        assertThat(src).contains("c3 == null"); // execute() null guard
        assertThat(src).contains("c3 + \" processing \""); // execute() body
        assertThat(src).doesNotContain("@PermuteDeclr");
        assertThat(src).doesNotContain("@Permute");

        // Behavioural: isReady() reads the c3 field — proves rename reached that method
        var loader = classLoaderFor(compilation);
        var instance = newInstance(loader, generatedClassName(FieldDeclr2.class, 3));
        var callableField = findCallableField(instance.getClass());
        var proxy = capturingProxy(loader, callableField.getType());

        setField(instance, callableField.getName(), proxy.proxy());
        assertThat(invokeMethod(instance, "isReady")).isEqualTo(true);

        setField(instance, callableField.getName(), null);
        assertThat(invokeMethod(instance, "isReady")).isEqualTo(false);
    }

    // -------------------------------------------------------------------------
    // For-each-only: rename propagates within the loop body only
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link ForEachDeclr2} template, which annotates only the
     * for-each variable (no field annotation). Verifies that every usage of the
     * loop variable within the loop body is renamed, that no stale {@code o2}
     * references survive anywhere, and that the null/non-null routing logic
     * behaves correctly at runtime.
     */
    @Test
    public void testForEachDeclrRenamesUsagesWithinLoopBody() {
        var compilation = compileTemplate(ForEachDeclr2.class, 3, 3);
        assertThat(compilation).succeeded();

        var src = sourceOf(compilation.generatedSourceFile(generatedClassName(ForEachDeclr2.class, 3)).orElseThrow());
        assertThat(src).contains("for (C c : items)");
        assertThat(src).contains("\"collected: \" + c");
        assertThat(src).doesNotContain("a2"); // template for-each var gone
        assertThat(src).doesNotContain("@PermuteDeclr");
        assertThat(src).doesNotContain("@Permute");

        // Behavioural: collect() routes non-nulls to results, nulls to skipped
        var loader = classLoaderFor(compilation);
        var instance = newInstance(loader, generatedClassName(ForEachDeclr2.class, 3));

        List<Object> results = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        setField(instance, "items", new ArrayList<>(Arrays.asList("item1", null, "item2")));
        setField(instance, "results", results);
        setField(instance, "skipped", skipped);

        invokeMethod(instance, "collect");

        assertThat(results).containsExactly("item1", "item2").inOrder();
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0)).isNull();
    }

    // -------------------------------------------------------------------------
    // Two annotated fields: primary and fallback renamed independently
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link TwoFieldDeclr2} template, which has TWO fields
     * each annotated with {@code @PermuteDeclr}. Verifies that both fields are
     * renamed to their respective generated names, that neither old name survives,
     * and that the renames propagate to every method that references each field.
     */
    @Test
    public void testTwoAnnotatedFieldsRenamedIndependently() {
        var compilation = compileTemplate(TwoFieldDeclr2.class, 3, 3);
        assertThat(compilation).succeeded();

        var src = sourceOf(compilation.generatedSourceFile(generatedClassName(TwoFieldDeclr2.class, 3)).orElseThrow());

        // Both fields renamed, neither old name survives (now generic)
        assertThat(src).contains("Callable3<A, B, C> primary3");
        assertThat(src).contains("Callable3<A, B, C> fallback3");
        assertThat(src).doesNotContain("primary2");
        assertThat(src).doesNotContain("fallback2");

        // Both names propagated to their respective methods
        assertThat(src).contains("return primary3 != null");
        assertThat(src).contains("return fallback3 != null");
        assertThat(src).contains("\"primary: \" + primary3"); // string opens the expression
        assertThat(src).contains("fallback: \" + fallback3"); // preceded by ", in the source
        assertThat(src).doesNotContain("@PermuteDeclr");

        // Behavioural: both fields can be set and read independently
        var loader = classLoaderFor(compilation);
        var instance = newInstance(loader, generatedClassName(TwoFieldDeclr2.class, 3));
        var clazz = instance.getClass();
        var primary = findField(clazz, "primary3");
        var fallback = findField(clazz, "fallback3");

        var proxy = capturingProxy(loader, primary.getType());
        setField(instance, primary.getName(), proxy.proxy());
        setField(instance, fallback.getName(), null);
        assertThat(invokeMethod(instance, "isPrimaryReady")).isEqualTo(true);
        assertThat(invokeMethod(instance, "isFallbackReady")).isEqualTo(false);

        setField(instance, primary.getName(), null);
        setField(instance, fallback.getName(), proxy.proxy());
        assertThat(invokeMethod(instance, "isPrimaryReady")).isEqualTo(false);
        assertThat(invokeMethod(instance, "isFallbackReady")).isEqualTo(true);
    }

    // -------------------------------------------------------------------------
    // Two for-each loops: each variable scoped to its own loop body;
    // also verifies anchor expansion at multiple call sites
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link DualForEach2} template, which has TWO for-each
     * loops each annotated with {@code @PermuteDeclr}. Verifies that each loop
     * variable is renamed within its own body only, and that the {@code @PermuteParam}
     * anchor is correctly expanded at both call sites.
     */
    @Test
    public void testMultipleForEachLoopsEachScopedIndependently() {
        var compilation = compileTemplate(DualForEach2.class, 3, 3);
        assertThat(compilation).succeeded();

        var src = sourceOf(compilation.generatedSourceFile(generatedClassName(DualForEach2.class, 3)).orElseThrow());

        // Both for-each loops renamed — distinct occurrences in source (now typed C)
        assertThat(src).contains("for (C c : first)");
        assertThat(src).contains("for (C c : second)");
        assertThat(src).doesNotContain("for (B b"); // template for-each var gone
        assertThat(src).doesNotContain("@PermuteDeclr");
        assertThat(src).doesNotContain("@PermuteParam");

        // Behavioural: anchor expanded at BOTH call sites; each loop's element captured
        var loader = classLoaderFor(compilation);
        var instance = newInstance(loader, generatedClassName(DualForEach2.class, 3));
        var callableField = findCallableField(instance.getClass());
        var capture = capturingProxy(loader, callableField.getType());
        setField(instance, callableField.getName(), capture.proxy());
        setField(instance, "first", new ArrayList<>(List.of("A")));
        setField(instance, "second", new ArrayList<>(List.of("B")));

        invokeMethod(instance, "process", "arg1", "arg2");

        // First loop: c3.call(arg1, arg2, A); second loop: c3.call(arg1, arg2, B)
        assertThat(capture.args()).containsExactly("arg1", "arg2", "A", "arg1", "arg2", "B").inOrder();
    }

    // -------------------------------------------------------------------------
    // Constructor parameter: rename propagates within the constructor body only
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link CtorDeclr2} template, which annotates a constructor
     * parameter with {@code @PermuteDeclr}. Verifies that the parameter type and name
     * are renamed, that every usage of the parameter inside the constructor body is
     * renamed, and that no {@code @PermuteDeclr} annotation survives in the output.
     */
    @Test
    public void testConstructorParamDeclrRenamesParamAndBody() {
        var compilation = compileTemplate(CtorDeclr2.class, 3, 3);
        assertThat(compilation).succeeded();

        var src = sourceOf(compilation.generatedSourceFile(generatedClassName(CtorDeclr2.class, 3)).orElseThrow());

        // Parameter type and name renamed (now generic)
        assertThat(src).contains("Callable3<A, B, C> c3");
        assertThat(src).doesNotContain("Callable2");

        // Usage inside the constructor body renamed
        assertThat(src).contains("\"arity=\" + c3");
        assertThat(src).doesNotContain("\"arity=\" + c2");

        // No stale annotations
        assertThat(src).doesNotContain("@PermuteDeclr");
        assertThat(src).doesNotContain("@Permute(");
    }

    // -------------------------------------------------------------------------
    // Method parameter @PermuteDeclr — G2a
    // -------------------------------------------------------------------------

    /**
     * @PermuteDeclr on a method parameter with name omitted — only the type changes,
     *               the original parameter name is preserved as-is.
     */
    @Test
    public void testMethodParamTypeOnlyNoRename() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.ChainStep2",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteDeclr;
                        @Permute(varName="i", from="3", to="4", className="ChainStep${i}")
                        public class ChainStep2 {
                            public Object join(@PermuteDeclr(type="String") Object src) {
                                return src;
                            }
                        }
                        """);

        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.ChainStep3")
                .orElseThrow());
        assertThat(src3).contains("Object join(String src)");
        assertThat(src3).doesNotContain("@PermuteDeclr");

        String src4 = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.ChainStep4")
                .orElseThrow());
        assertThat(src4).contains("Object join(String src)");
    }

    /**
     * @PermuteDeclr on a method parameter with name specified — both type and name change,
     *               and the new name is propagated throughout the method body.
     */
    @Test
    public void testMethodParamTypeAndNameWithBodyPropagation() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.RenameParam2",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteDeclr;
                        @Permute(varName="i", from="3", to="3", className="RenameParam${i}")
                        public class RenameParam2 {
                            public String process(
                                    @PermuteDeclr(type="Object", name="item${i}") Object item2) {
                                return item2.toString();
                            }
                        }
                        """);

        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.RenameParam3")
                .orElseThrow());
        assertThat(src).contains("Object item3");
        assertThat(src).contains("item3.toString()");
        assertThat(src).doesNotContain("item2");
        assertThat(src).doesNotContain("@PermuteDeclr");
    }

    /**
     * Multiple @PermuteDeclr on multiple parameters in the same method —
     * each parameter type is independently replaced, no name change.
     */
    @Test
    public void testMultipleMethodParamDeclr() {
        var source = JavaFileObjects.forSourceString(
                "io.quarkiverse.permuplate.example.MultiParam2",
                """
                        package io.quarkiverse.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteDeclr;
                        @Permute(varName="i", from="3", to="3", className="MultiParam${i}")
                        public class MultiParam2 {
                            public void process(
                                    @PermuteDeclr(type="String") Object paramA,
                                    @PermuteDeclr(type="Integer") Object paramB) {
                            }
                        }
                        """);

        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.quarkiverse.permuplate.example.MultiParam3")
                .orElseThrow());
        assertThat(src).contains("String paramA");
        assertThat(src).contains("Integer paramB");
        assertThat(src).doesNotContain("@PermuteDeclr");
    }

    // -------------------------------------------------------------------------
    // @PermuteDeclr.type with generic type expressions — AST correctness
    // -------------------------------------------------------------------------

    /**
     * Verifies that @PermuteDeclr(type="Consumer${i+1}<Context<DS>, ${typeArgList(2, i+1, 'alpha')}>")
     * produces a properly structured ClassOrInterfaceType in the AST — not a flat identifier
     * whose SimpleName happens to contain angle brackets.
     *
     * <p>
     * The text output (toString) of both approaches is identical, so this test checks
     * the AST structure directly: getName() should return "Consumer4" (the class name only),
     * and getTypeArguments() should be populated with [Context<DS>, B, C, D].
     *
     * <p>
     * This tests the transformer directly (not via compile-testing) because the bug
     * is not visible in generated source text — it only manifests in the AST node structure.
     */
    @Test
    public void testPermuteDeclrGenericTypeProducesProperAst() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Fn2 {
                    public void fn(
                        @io.quarkiverse.permuplate.PermuteDeclr(
                            type = "Consumer${i+1}<Context<DS>, ${typeArgList(2, i+1, 'alpha')}>")
                        Object fn3) {}
                }
                """);
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("Fn2").orElseThrow();
        EvaluationContext ctx = new EvaluationContext(Map.of("i", 3));

        PermuteDeclrTransformer.transform(classDecl, ctx, null);

        Parameter param = classDecl.getMethods().get(0).getParameters().get(0);
        ClassOrInterfaceType type = param.getType().asClassOrInterfaceType();

        // getName() must be the bare class name — not "Consumer4<Context<DS>, B, C, D>"
        assertThat(type.getNameAsString()).isEqualTo("Consumer4");

        // Type arguments must be in the proper AST slot, not embedded in the name
        assertThat(type.getTypeArguments().isPresent()).isTrue();
        assertThat(type.getTypeArguments().get()).hasSize(4); // Context<DS>, B, C, D
    }

    // -------------------------------------------------------------------------
    // @PermuteDeclr on ObjectCreationExpr (TYPE_USE target)
    // -------------------------------------------------------------------------

    /**
     * Verifies that @PermuteDeclr(type="Join${i+1}First") placed on a new expression
     * updates the constructor class name — enabling join() method body templates like:
     * return new @PermuteDeclr(type="Join${i+1}First") Join3First<>(end(), rule);
     */
    @Test
    public void testPermuteDeclrOnNewExpression() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Join2Second {
                    public Object join() {
                        return new @io.quarkiverse.permuplate.PermuteDeclr(type = "Join${i+1}First")
                                Join3First<>();
                    }
                }
                """);
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("Join2Second").orElseThrow();
        EvaluationContext ctx = new EvaluationContext(Map.of("i", 3));

        PermuteDeclrTransformer.transform(classDecl, ctx, null);

        // Find the ObjectCreationExpr in the method body
        com.github.javaparser.ast.expr.ObjectCreationExpr newExpr = classDecl
                .getMethods().get(0).getBody().orElseThrow()
                .findFirst(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                .orElseThrow();

        // Constructor type must be updated from Join3First to Join4First
        assertThat(newExpr.getType().getNameAsString()).isEqualTo("Join4First");
        // @PermuteDeclr annotation must be removed from the type
        assertThat(newExpr.getType().getAnnotations()).isEmpty();
    }

    /**
     * Verifies that @PermuteDeclr type expressions work with two @Permute-level variables
     * both present in the EvaluationContext. Note: @PermuteMethod inner variables (e.g. j)
     * are NOT available to @PermuteDeclr TYPE_USE in the method body because
     * PermuteDeclrTransformer runs in the outer context after PermuteMethodTransformer has
     * already consumed and removed the @PermuteMethod overloads.
     */
    @Test
    public void testPermuteDeclrOnNewExpressionWithTwoVariables() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Join1Second {
                    public Object joinBilinear() {
                        return new @io.quarkiverse.permuplate.PermuteDeclr(type = "Join${i+j}First")
                                Join1First<>();
                    }
                }
                """);
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("Join1Second").orElseThrow();
        EvaluationContext ctx = new EvaluationContext(Map.of("i", 1, "j", 2));

        PermuteDeclrTransformer.transform(classDecl, ctx, null);

        com.github.javaparser.ast.expr.ObjectCreationExpr newExpr = classDecl
                .getMethods().get(0).getBody().orElseThrow()
                .findFirst(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                .orElseThrow();

        // i=1, j=2 → Join${1+2}First = Join3First
        assertThat(newExpr.getType().getNameAsString()).isEqualTo("Join3First");
        assertThat(newExpr.getType().getAnnotations()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // @PermuteDeclr on method declaration — rename name and return type
    // -------------------------------------------------------------------------

    /**
     * @PermuteDeclr on a method declaration renames the method name and return type.
     *               GetterRename2<A> generates GetterRename3<A,B> and GetterRename4<A,B,C>, each
     *               adding one named getter and one named setter.
     */
    @Test
    public void testPermuteDeclrOnMethodRenamesNameAndReturnType() {
        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(templateSource(GetterRename2.class));
        assertThat(compilation).succeeded();

        var src3 = sourceOf(compilation.generatedSourceFile(
                generatedClassName(GetterRename2.class, 3)).orElseThrow());

        // Type params expanded
        assertThat(src3).contains("GetterRename3<A, B>");

        // Getter renamed: return type changed + method name changed
        assertThat(src3).contains("public B getB()");
        assertThat(src3).contains("return b;");

        // Setter renamed: method name changed, param type+name changed
        assertThat(src3).contains("public void setB(B b)");
        assertThat(src3).contains("this.b = b;");

        // No leftover annotation noise
        assertThat(src3).doesNotContain("@PermuteDeclr");

        var src4 = sourceOf(compilation.generatedSourceFile(
                generatedClassName(GetterRename2.class, 4)).orElseThrow());
        assertThat(src4).contains("GetterRename4<A, B, C>");
        assertThat(src4).contains("public C getC()");
        assertThat(src4).contains("public void setC(C c)");
    }

    /**
     * Verifies that @PermuteDeclr on a new expression inside a local variable assignment
     * works — the pattern used by path2()..path6() where the next JoinFirst is assigned
     * to a local variable before being passed to a PathN constructor.
     */
    @Test
    public void testPermuteDeclrOnNewExpressionInLocalVariable() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Join2Second {
                    public Object path2() {
                        Object nextJoin = new @io.quarkiverse.permuplate.PermuteDeclr(type = "Join${i+1}First")
                                Join3First<>(end(), rd);
                        return nextJoin;
                    }
                }
                """);
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("Join2Second").orElseThrow();
        EvaluationContext ctx = new EvaluationContext(Map.of("i", 3));

        PermuteDeclrTransformer.transform(classDecl, ctx, null);

        com.github.javaparser.ast.expr.ObjectCreationExpr newExpr = classDecl
                .getMethods().get(0).getBody().orElseThrow()
                .findFirst(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                .orElseThrow();

        // Join3First → Join4First at i=3
        assertThat(newExpr.getType().getNameAsString()).isEqualTo("Join4First");
        assertThat(newExpr.getType().getAnnotations()).isEmpty();
    }
}
