package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import io.quarkiverse.permuplate.example.CtorDeclr2;
import io.quarkiverse.permuplate.example.DualForEach2;
import io.quarkiverse.permuplate.example.FieldDeclr2;
import io.quarkiverse.permuplate.example.ForEachDeclr2;
import io.quarkiverse.permuplate.example.RichJoin2;
import io.quarkiverse.permuplate.example.TwoFieldDeclr2;

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

        // Field rename: c2 → c3 everywhere
        assertThat(src).contains("Callable3 c3");
        assertThat(src).doesNotContain("c2");
        assertThat(src).contains("\"Processor: \" + c3"); // before loop
        assertThat(src).contains("c3 == null"); // null guard before loop
        assertThat(src).contains("\"Done with \" + c3"); // after loop

        // For-each variable rename: o2 → o3 everywhere inside loop
        assertThat(src).contains("for (Object o3 : right)");
        assertThat(src).contains("o3 == null");
        assertThat(src).contains("skipped.add(o3)");
        assertThat(src).contains("\"Processed: \" + o3 + \" with \"");

        // No stale o2 in loop body (generated param "Object o2" is fine)
        assertThat(src).contains("Object o2");
        assertThat(src).doesNotContain("o2 == null");
        assertThat(src).doesNotContain("skipped.add(o2)");
        assertThat(src).doesNotContain("\"Processed: \" + o2");

        // Fixed "String label" param preserved after sentinel expansion
        assertThat(src).contains("Object o1, Object o2");
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
        assertThat(src).contains("Callable3 c3");
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
        assertThat(src).contains("for (Object o3 : items)");
        assertThat(src).contains("\"collected: \" + o3");
        assertThat(src).doesNotContain("o2");
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

        // Both fields renamed, neither old name survives
        assertThat(src).contains("Callable3 primary3");
        assertThat(src).contains("Callable3 fallback3");
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

        // Both for-each loops renamed — distinct occurrences in source
        assertThat(src).contains("for (Object o3 : first)");
        assertThat(src).contains("for (Object o3 : second)");
        assertThat(src).doesNotContain("for (Object o2");
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

        // Parameter type and name renamed
        assertThat(src).contains("Callable3 c3");
        assertThat(src).doesNotContain("Callable2 c2");

        // Usage inside the constructor body renamed
        assertThat(src).contains("\"arity=\" + c3");
        assertThat(src).doesNotContain("\"arity=\" + c2");

        // No stale annotations
        assertThat(src).doesNotContain("@PermuteDeclr");
        assertThat(src).doesNotContain("@Permute(");
    }
}
