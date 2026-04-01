package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.Test;

import com.google.testing.compile.Compiler;

import io.quarkiverse.permuplate.example.Combo1x1;
import io.quarkiverse.permuplate.example.InterfaceLibrary;
import io.quarkiverse.permuplate.example.Join2;
import io.quarkiverse.permuplate.example.JoinLibrary;
import io.quarkiverse.permuplate.example.MultiArityJoin;
import io.quarkiverse.permuplate.example.PrefixedJoin2;
import io.quarkiverse.permuplate.example.WideJoin2;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for the {@code @Permute} annotation: class and interface generation across
 * a range, nested static class/interface promotion to top-level, double-digit arity
 * names, and cross-product generation via {@code extraVars}.
 * <p>
 * Each test exercises the full pipeline — field rename ({@code @PermuteDeclr}),
 * parameter expansion ({@code @PermuteParam}), and for-each rename
 * ({@code @PermuteDeclr}) — because they all co-operate in every generated class.
 * Tests for those annotations <em>in isolation</em> live in {@link PermuteDeclrTest}
 * and {@link PermuteParamTest}.
 */
public class PermuteTest {

    // -------------------------------------------------------------------------
    // Range of 4: Join3 .. Join6
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link Join2} top-level template and verifies all four
     * generated classes for correct field rename, param expansion, for-each
     * rename, call-site anchor expansion, and logic string preservation.
     */
    @Test
    public void testBasicJoinPermutation() {
        var compilation = compileTemplate(Join2.class, 3, 6);
        assertThat(compilation).succeeded();

        var loader = classLoaderFor(compilation);
        for (int i = 3; i <= 6; i++) {
            assertJoinN(compilation, loader, Join2.class, i);
        }

        // Logic strings preserved (not covered by assertJoinN)
        var src3 = sourceOf(compilation.generatedSourceFile(generatedClassName(Join2.class, 3)).orElseThrow());
        assertThat(src3).contains("\"before logic\"");
        assertThat(src3).contains("\"after logic\"");
    }

    // -------------------------------------------------------------------------
    // Nested static class → top-level, range of 3: FilterJoin3, 4, 5
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link JoinLibrary.FilterJoin2} nested static template and
     * verifies that all three generated classes are emitted as top-level types
     * (not nested inside {@code JoinLibrary}, no {@code static} modifier), with
     * correct field renames, call sites, and extra field preservation.
     */
    @Test
    public void testNestedClassGeneratesTopLevelClass() {
        var compilation = compileTemplate(JoinLibrary.FilterJoin2.class, 3, 5);
        assertThat(compilation).succeeded();

        var loader = classLoaderFor(compilation);

        for (int i = 3; i <= 5; i++) {
            final int fi = i;
            var className = generatedClassName(JoinLibrary.FilterJoin2.class, fi);
            var src = sourceOf(compilation.generatedSourceFile(className)
                    .orElseThrow(() -> new AssertionError(className + ".java was not generated")));

            assertThat(src).contains("public class FilterJoin" + fi);
            assertThat(src).doesNotContain("public static class FilterJoin" + fi);
            assertThat(src).doesNotContain("class JoinLibrary");
            assertThat(src).contains("Callable" + fi + " c" + fi);
            assertThat(src).contains("for (Object o" + fi + " : right)");
            assertThat(src).contains("String label");
            assertThat(src).doesNotContain("@PermuteDeclr");
            assertThat(src).doesNotContain("@PermuteParam");

            // Behavioural: run(arg1..arg{i-1}) with right=["R"]
            Object[] runArgs = IntStream.rangeClosed(1, fi - 1).mapToObj(j -> "arg" + j).toArray();
            var fixture = prepareJoin(loader, className, List.of("R"));
            fixture.invoke("run", runArgs);

            List<Object> expected = new ArrayList<>(Arrays.asList(runArgs));
            expected.add("R");
            assertThat(fixture.captured).containsExactlyElementsIn(expected).inOrder();
        }
    }

    // -------------------------------------------------------------------------
    // Nested static interface → top-level interface: Merger3, Merger4
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link InterfaceLibrary.Merger2} nested static interface
     * template and verifies that both generated types are emitted as top-level
     * interfaces — not classes, not nested — with the correct method signatures
     * and no leftover annotations.
     */
    @Test
    public void testNestedInterfaceGeneratesTopLevelInterface() {
        var compilation = compileTemplate(InterfaceLibrary.Merger2.class, 3, 4);
        assertThat(compilation).succeeded();

        for (int i = 3; i <= 4; i++) {
            final int fi = i;
            var className = generatedClassName(InterfaceLibrary.Merger2.class, fi);
            var src = sourceOf(compilation.generatedSourceFile(className)
                    .orElseThrow(() -> new AssertionError(className + ".java was not generated")));

            // Generated as a top-level interface, not a class, not nested
            assertThat(src).contains("public interface Merger" + fi);
            assertThat(src).doesNotContain("public class");
            assertThat(src).doesNotContain("static interface");
            assertThat(src).doesNotContain("class InterfaceLibrary");

            // Method signature correctly expanded: o1 .. o{i}
            var params = new StringBuilder("void merge(");
            for (int j = 1; j <= fi; j++) {
                if (j > 1)
                    params.append(", ");
                params.append("Object o").append(j);
            }
            assertThat(src).contains(params.append(")").toString());

            // No leftover annotations
            assertThat(src).doesNotContain("@Permute");
            assertThat(src).doesNotContain("@PermuteParam");
        }
    }

    // -------------------------------------------------------------------------
    // Method permutation: one class with all overloads — MultiArityJoins
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link MultiArityJoin} template, which places {@code @Permute}
     * on a method rather than a class. Verifies that a single class is generated
     * containing one overload per permutation value, with the correct parameter lists
     * and no leftover annotations.
     */
    @Test
    public void testMethodPermutationGeneratesOneClassWithAllOverloads() {
        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(templateSource(MultiArityJoin.class));
        assertThat(compilation).succeeded();

        String pkg = MultiArityJoin.class.getPackageName();
        var src = sourceOf(compilation.generatedSourceFile(pkg + ".MultiArityJoins")
                .orElseThrow(() -> new AssertionError("MultiArityJoins.java was not generated")));

        // One class, not multiple
        assertThat(src).contains("public class MultiArityJoins");

        // One overload per permutation value (i=2, 3, 4)
        assertThat(src).contains("void join(Object o1, Object o2)");
        assertThat(src).contains("void join(Object o1, Object o2, Object o3)");
        assertThat(src).contains("void join(Object o1, Object o2, Object o3, Object o4)");

        // Method body preserved in each overload
        assertThat(src).contains("System.out.println");

        // No leftover annotations
        assertThat(src).doesNotContain("@Permute");
        assertThat(src).doesNotContain("@PermuteParam");
    }

    // -------------------------------------------------------------------------
    // String variables in @Permute: PrefixedJoin2 → BufferedJoin3, BufferedJoin4
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link PrefixedJoin2} template, which uses a string
     * variable {@code prefix=Buffered} in {@code strings} alongside the integer
     * variable {@code i}. Verifies that the string variable is substituted in
     * {@code className} to produce correctly-named generated classes, and that
     * all other transformations ({@code @PermuteDeclr}, {@code @PermuteParam})
     * continue to work alongside it.
     */
    @Test
    public void testStringVariableInClassNameProducesCorrectlyNamedClasses() {
        var compilation = compileTemplate(PrefixedJoin2.class, 3, 4);
        assertThat(compilation).succeeded();

        String pkg = PrefixedJoin2.class.getPackageName();

        // String variable substituted: ${prefix}Join${i} with prefix=Buffered, i=3/4
        assertThat(compilation.generatedSourceFile(pkg + ".BufferedJoin3").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile(pkg + ".BufferedJoin4").isPresent()).isTrue();

        var src3 = sourceOf(compilation.generatedSourceFile(pkg + ".BufferedJoin3").orElseThrow());
        assertThat(src3).contains("public class BufferedJoin3");
        assertThat(src3).contains("Callable3 c3");
        assertThat(src3).contains("for (Object o3 : right)");
        assertThat(src3).contains("Object o1, Object o2");

        // No leftover variable expressions or annotations
        assertThat(src3).doesNotContain("${prefix}");
        assertThat(src3).doesNotContain("${i}");
        assertThat(src3).doesNotContain("@Permute");
        assertThat(src3).doesNotContain("@PermuteDeclr");
        assertThat(src3).doesNotContain("@PermuteParam");

        // Behavioural: generated class works correctly at runtime
        var loader = classLoaderFor(compilation);
        var fixture = prepareJoin(loader, pkg + ".BufferedJoin3", List.of("R"));
        fixture.invoke("left", "arg1", "arg2");
        assertThat(fixture.captured).containsExactly("arg1", "arg2", "R").inOrder();
    }

    // -------------------------------------------------------------------------
    // Range of 10 including double-digit arities: WideJoin3 .. WideJoin12
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link WideJoin2} template across a range of 10 and
     * verifies all generated classes, exercising double-digit class/field names
     * ({@code c10}, {@code c11}, {@code c12}) and argument lists up to 12
     * arguments. Ensures two-digit arity numbers do not corrupt name generation.
     */
    @Test
    public void testLargeRangeIncludingDoubleDigitArity() {
        var compilation = compileTemplate(WideJoin2.class, 3, 12);
        assertThat(compilation).succeeded();

        var loader = classLoaderFor(compilation);
        for (int i = 3; i <= 12; i++) {
            assertJoinN(compilation, loader, WideJoin2.class, i);
        }
    }

    // -------------------------------------------------------------------------
    // Cross-product extraVars: Combo2x2, Combo2x3, Combo3x2, Combo3x3
    // -------------------------------------------------------------------------

    /**
     * Compiles the real {@link Combo2x2} template, which uses two permutation
     * variables ({@code i} and {@code k}) via {@code extraVars} to generate the
     * cross-product: Combo2x2, Combo2x3, Combo3x2, Combo3x3. Verifies that each
     * class has the correct method signature (both sentinels expanded by their
     * respective variables) and collects the right arguments at runtime.
     */
    @Test
    public void testExtraVarsCrossProductGeneratesAllCombinations() {
        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(templateSource(Combo1x1.class));
        assertThat(compilation).succeeded();

        String pkg = Combo1x1.class.getPackageName();

        // All 4 combinations present
        assertThat(compilation.generatedSourceFile(pkg + ".Combo2x2").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile(pkg + ".Combo2x3").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile(pkg + ".Combo3x2").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile(pkg + ".Combo3x3").isPresent()).isTrue();

        var loader = classLoaderFor(compilation);

        // Combo2x2: i=2 → left1,left2  k=2 → right1,right2
        var src22 = sourceOf(compilation.generatedSourceFile(pkg + ".Combo2x2").orElseThrow());
        assertThat(src22).contains("void combine(Object left1, Object left2, Object right1, Object right2)");
        assertThat(src22).contains("Collections.addAll(results, left1, left2, right1, right2)");
        assertThat(src22).doesNotContain("@PermuteParam");
        var inst22 = newInstance(loader, pkg + ".Combo2x2");
        invokeMethod(inst22, "combine", "L1", "L2", "R1", "R2");
        assertThat(readResultsField(inst22)).containsExactly("L1", "L2", "R1", "R2").inOrder();

        // Combo2x3: i=2 → left1,left2  k=3 → right1,right2,right3
        var src23 = sourceOf(compilation.generatedSourceFile(pkg + ".Combo2x3").orElseThrow());
        assertThat(src23).contains(
                "void combine(Object left1, Object left2, Object right1, Object right2, Object right3)");
        var inst23 = newInstance(loader, pkg + ".Combo2x3");
        invokeMethod(inst23, "combine", "L1", "L2", "R1", "R2", "R3");
        assertThat(readResultsField(inst23)).containsExactly("L1", "L2", "R1", "R2", "R3").inOrder();

        // Combo3x2: i=3 → left1,left2,left3  k=2 → right1,right2
        var src32 = sourceOf(compilation.generatedSourceFile(pkg + ".Combo3x2").orElseThrow());
        assertThat(src32).contains(
                "void combine(Object left1, Object left2, Object left3, Object right1, Object right2)");
        var inst32 = newInstance(loader, pkg + ".Combo3x2");
        invokeMethod(inst32, "combine", "L1", "L2", "L3", "R1", "R2");
        assertThat(readResultsField(inst32)).containsExactly("L1", "L2", "L3", "R1", "R2").inOrder();

        // Combo3x3: i=3 → left1,left2,left3  k=3 → right1,right2,right3
        var src33 = sourceOf(compilation.generatedSourceFile(pkg + ".Combo3x3").orElseThrow());
        assertThat(src33).contains(
                "void combine(Object left1, Object left2, Object left3, Object right1, Object right2, Object right3)");
        var inst33 = newInstance(loader, pkg + ".Combo3x3");
        invokeMethod(inst33, "combine", "L1", "L2", "L3", "R1", "R2", "R3");
        assertThat(readResultsField(inst33)).containsExactly("L1", "L2", "L3", "R1", "R2", "R3").inOrder();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> readResultsField(Object instance) {
        try {
            return (java.util.List<Object>) instance.getClass().getField("results").get(instance);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
