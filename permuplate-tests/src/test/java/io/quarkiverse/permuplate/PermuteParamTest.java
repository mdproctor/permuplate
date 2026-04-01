package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.google.testing.compile.Compiler;

import io.quarkiverse.permuplate.example.ContextJoin2;
import io.quarkiverse.permuplate.example.DualParam2;
import io.quarkiverse.permuplate.example.MultiFixed2;
import io.quarkiverse.permuplate.example.TwoMethodParam2;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for {@code @PermuteParam}: sentinel parameter expansion, fixed parameter
 * preservation, call-site anchor rewriting, and multiple sentinels in one method.
 */
public class PermuteParamTest {

    // -------------------------------------------------------------------------
    // Fixed params before/after sentinel, range of 2: ContextJoin3, ContextJoin4
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link ContextJoin2} template and verifies that the fixed
     * params ({@code String ctx} before the sentinel, {@code List<Object> results}
     * after it) are preserved in position for both generated arities, and that
     * the for-each rename and {@code results.add} propagate correctly.
     */
    @Test
    public void testFixedParamsBeforeAndAfterPermuteParam() {
        var compilation = compileTemplate(ContextJoin2.class, 3, 4);
        assertThat(compilation).succeeded();

        var loader = classLoaderFor(compilation);

        // ContextJoin3: sentinel expands to (o1, o2), for-each → o3
        var src3 = sourceOf(compilation.generatedSourceFile(generatedClassName(ContextJoin2.class, 3)).orElseThrow());
        assertThat(src3).contains("Callable3 c3");
        assertThat(src3).contains("String ctx");
        assertThat(src3).contains("Object o1, Object o2");
        assertThat(src3).contains("List<Object> results");
        assertThat(src3.indexOf("String ctx")).isLessThan(src3.indexOf("Object o1"));
        assertThat(src3.indexOf("Object o1")).isLessThan(src3.indexOf("List<Object> results"));
        assertThat(src3).contains("for (Object o3 : right)");
        assertThat(src3).doesNotContain("for (Object o2");
        assertThat(src3).doesNotContain("@PermuteDeclr");
        assertThat(src3).doesNotContain("@PermuteParam");

        // Behavioural ContextJoin3: callable captures (arg1, arg2, R); results gets R
        List<Object> results3 = new ArrayList<>();
        var fix3 = prepareJoin(loader, generatedClassName(ContextJoin2.class, 3), List.of("R"));
        fix3.invoke("join", "ctx", "arg1", "arg2", results3);
        assertThat(fix3.captured).containsExactly("arg1", "arg2", "R").inOrder();
        assertThat(results3).containsExactly("R");

        // ContextJoin4: sentinel expands to (o1, o2, o3), for-each → o4
        var src4 = sourceOf(compilation.generatedSourceFile(generatedClassName(ContextJoin2.class, 4)).orElseThrow());
        assertThat(src4).contains("Callable4 c4");
        assertThat(src4).contains("String ctx");
        assertThat(src4).contains("Object o1, Object o2, Object o3");
        assertThat(src4).contains("List<Object> results");
        assertThat(src4.indexOf("String ctx")).isLessThan(src4.indexOf("Object o1"));
        assertThat(src4.indexOf("Object o1")).isLessThan(src4.indexOf("List<Object> results"));
        assertThat(src4).contains("for (Object o4 : right)");
        assertThat(src4).doesNotContain("for (Object o2");
        assertThat(src4).doesNotContain("@PermuteDeclr");
        assertThat(src4).doesNotContain("@PermuteParam");

        // Behavioural ContextJoin4: callable captures (arg1, arg2, arg3, R); results gets R
        List<Object> results4 = new ArrayList<>();
        var fix4 = prepareJoin(loader, generatedClassName(ContextJoin2.class, 4), List.of("R"));
        fix4.invoke("join", "ctx", "arg1", "arg2", "arg3", results4);
        assertThat(fix4.captured).containsExactly("arg1", "arg2", "arg3", "R").inOrder();
        assertThat(results4).containsExactly("R");
    }

    // -------------------------------------------------------------------------
    // Two fixed params before + two fixed params after the sentinel
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link MultiFixed2} template, which has TWO fixed params
     * before the sentinel ({@code String tag, String source}) and TWO fixed params
     * after it ({@code List<Object> results, String label}). Verifies that all four
     * fixed params are preserved in position for both arities and that only the
     * sentinel is replaced.
     */
    @Test
    public void testMultipleFixedParamsAroundSentinel() {
        var compilation = compileTemplate(MultiFixed2.class, 3, 4);
        assertThat(compilation).succeeded();

        var loader = classLoaderFor(compilation);

        // MultiFixed3: tag, source BEFORE; o1, o2 expanded; results, label AFTER
        var src3 = sourceOf(compilation.generatedSourceFile(generatedClassName(MultiFixed2.class, 3)).orElseThrow());
        assertThat(src3).contains("String tag");
        assertThat(src3).contains("String source");
        assertThat(src3).contains("Object o1, Object o2");
        assertThat(src3).contains("List<Object> results");
        assertThat(src3).contains("String label");
        // Positional ordering
        assertThat(src3.indexOf("String tag")).isLessThan(src3.indexOf("String source"));
        assertThat(src3.indexOf("String source")).isLessThan(src3.indexOf("Object o1"));
        assertThat(src3.indexOf("Object o1")).isLessThan(src3.indexOf("List<Object> results"));
        assertThat(src3.indexOf("List<Object> results")).isLessThan(src3.indexOf("String label"));
        assertThat(src3).doesNotContain("@PermuteParam");

        // Behavioural MultiFixed3: callable captures (arg1, arg2, R); results gets R
        List<Object> res3 = new ArrayList<>();
        var fix3 = prepareJoin(loader, generatedClassName(MultiFixed2.class, 3), List.of("R"));
        fix3.invoke("process", "myTag", "mySource", "arg1", "arg2", res3, "myLabel");
        assertThat(fix3.captured).containsExactly("arg1", "arg2", "R").inOrder();
        assertThat(res3).containsExactly("R");

        // MultiFixed4: three expanded params; same fixed params in same positions
        var src4 = sourceOf(compilation.generatedSourceFile(generatedClassName(MultiFixed2.class, 4)).orElseThrow());
        assertThat(src4).contains("Object o1, Object o2, Object o3");
        assertThat(src4.indexOf("String source")).isLessThan(src4.indexOf("Object o1"));
        assertThat(src4.indexOf("Object o1")).isLessThan(src4.indexOf("List<Object> results"));

        // Behavioural MultiFixed4
        List<Object> res4 = new ArrayList<>();
        var fix4 = prepareJoin(loader, generatedClassName(MultiFixed2.class, 4), List.of("R"));
        fix4.invoke("process", "myTag", "mySource", "arg1", "arg2", "arg3", res4, "myLabel");
        assertThat(fix4.captured).containsExactly("arg1", "arg2", "arg3", "R").inOrder();
        assertThat(res4).containsExactly("R");
    }

    // -------------------------------------------------------------------------
    // Two @PermuteParam sentinels in the same method: DualParam3, DualParam4
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link DualParam2} template, which has TWO {@code @PermuteParam}
     * sentinels in the same method ({@code left1} expanding the left sequence and
     * {@code right1} expanding the right sequence). Verifies that both sentinels
     * are expanded independently in declaration order, that both anchors are correctly
     * rewritten at the shared call site, and that the generated method behaves correctly
     * at runtime.
     */
    @Test
    public void testTwoSentinelsInSameMethod() {
        // DualParam2 uses Collections.addAll (varargs) so no Callable support sources needed
        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(templateSource(DualParam2.class));
        assertThat(compilation).succeeded();

        var loader = classLoaderFor(compilation);

        // --- DualParam3: each sentinel expands to 2 params (from=1, to=i-1=2) ---
        var src3 = sourceOf(compilation.generatedSourceFile(generatedClassName(DualParam2.class, 3)).orElseThrow());

        // Both sentinels expanded in declaration order
        assertThat(src3).contains("void merge(Object left1, Object left2, Object right1, Object right2)");

        // Both anchors expanded at the shared Collections.addAll call site
        assertThat(src3).contains("Collections.addAll(merged, left1, left2, right1, right2)");
        assertThat(src3).doesNotContain("@PermuteParam");

        // Behavioural: merged list receives all 4 args in left-then-right order
        var instance3 = newInstance(loader, generatedClassName(DualParam2.class, 3));
        invokeMethod(instance3, "merge", "L1", "L2", "R1", "R2");
        assertThat(readMergedField(instance3)).containsExactly("L1", "L2", "R1", "R2").inOrder();

        // --- DualParam4: each sentinel expands to 3 params (from=1, to=i-1=3) ---
        var src4 = sourceOf(compilation.generatedSourceFile(generatedClassName(DualParam2.class, 4)).orElseThrow());
        assertThat(src4).contains(
                "void merge(Object left1, Object left2, Object left3, Object right1, Object right2, Object right3)");
        assertThat(src4).contains("Collections.addAll(merged, left1, left2, left3, right1, right2, right3)");

        // Behavioural
        var instance4 = newInstance(loader, generatedClassName(DualParam2.class, 4));
        invokeMethod(instance4, "merge", "L1", "L2", "L3", "R1", "R2", "R3");
        assertThat(readMergedField(instance4)).containsExactly("L1", "L2", "L3", "R1", "R2", "R3").inOrder();
    }

    // -------------------------------------------------------------------------
    // @PermuteParam in two separate methods of the same class
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link TwoMethodParam2} template, which has {@code @PermuteParam}
     * in TWO separate methods ({@code processLeft} with sentinel {@code o1} and
     * {@code processRight} with sentinel {@code a1}). Verifies that both methods are
     * expanded independently and that each anchor only affects its own method's call sites.
     */
    @Test
    public void testSentinelExpansionInMultipleMethods() {
        var compilation = compileTemplate(TwoMethodParam2.class, 3, 3);
        assertThat(compilation).succeeded();

        var src = sourceOf(compilation.generatedSourceFile(generatedClassName(TwoMethodParam2.class, 3)).orElseThrow());

        // processLeft expanded: sentinel o1 → (o1, o2); call uses o1, o2, o3
        assertThat(src).contains("void processLeft(Object o1, Object o2)");
        // processRight expanded: sentinel a1 → (a1, a2); call uses a1, a2, o3
        assertThat(src).contains("void processRight(Object a1, Object a2)");
        assertThat(src).doesNotContain("@PermuteParam");

        // Behavioural: each method's anchor is expanded independently
        var loader = classLoaderFor(compilation);

        // processLeft: o1 anchor → callable receives (o1, o2, rightElem)
        var fixLeft = prepareJoin(loader, generatedClassName(TwoMethodParam2.class, 3), List.of("R"));
        fixLeft.invoke("processLeft", "left1", "left2");
        assertThat(fixLeft.captured).containsExactly("left1", "left2", "R").inOrder();

        // processRight: a1 anchor → callable receives (a1, a2, rightElem)
        var fixRight = prepareJoin(loader, generatedClassName(TwoMethodParam2.class, 3), List.of("R"));
        fixRight.invoke("processRight", "right1", "right2");
        assertThat(fixRight.captured).containsExactly("right1", "right2", "R").inOrder();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Reads the public {@code merged} field from a generated DualParam instance. */
    @SuppressWarnings("unchecked")
    private static java.util.List<Object> readMergedField(Object instance) {
        try {
            return (java.util.List<Object>) instance.getClass().getField("merged").get(instance);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
