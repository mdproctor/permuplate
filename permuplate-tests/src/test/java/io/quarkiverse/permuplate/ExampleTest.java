package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.Test;

import com.google.testing.compile.Compiler;

import io.quarkiverse.permuplate.example.AuditRecord2;
import io.quarkiverse.permuplate.example.BiCallable1x1;
import io.quarkiverse.permuplate.example.ProductFilter2;
import io.quarkiverse.permuplate.example.ValidationSuite;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests the real-world example templates in the example module.
 * Each test compiles a template, verifies the generated source shape, then loads
 * and exercises the generated classes at runtime to confirm they behave correctly.
 */
public class ExampleTest {

    // -------------------------------------------------------------------------
    // ProductFilter2 → ProductFilter3 .. ProductFilter7
    // Domain: e-commerce product search by N filter criteria
    // -------------------------------------------------------------------------

    /**
     * Each generated {@code ProductFilter{i}} class accepts {@code i-1} filter
     * criteria and evaluates every product in the catalogue against all of them.
     * Verified structurally (field name, for-each var, fixed-after param) and
     * behaviourally (callable receives all criteria + each product in order).
     */
    @Test
    public void testProductFilterSearchAppliesCriteriaToEachProduct() {
        var compilation = compileTemplate(ProductFilter2.class, 3, 7);
        assertThat(compilation).succeeded();

        // --- Structural: ProductFilter3 (minimum arity) ---
        var src3 = sourceOf(compilation.generatedSourceFile(
                generatedClassName(ProductFilter2.class, 3)).orElseThrow());
        assertThat(src3).contains("Callable3<A, B, C> matchFn3");
        assertThat(src3).doesNotContain("matchFn2");
        assertThat(src3).contains("A criterion1, B criterion2");
        assertThat(src3).contains("List<Object> matches");
        assertThat(src3).contains("for (C product3 : catalogue)");
        assertThat(src3).doesNotContain("product2");
        assertThat(src3).doesNotContain("@PermuteDeclr");
        assertThat(src3).doesNotContain("@PermuteParam");

        // --- Structural: ProductFilter7 (maximum arity, 6 criteria) ---
        var src7 = sourceOf(compilation.generatedSourceFile(
                generatedClassName(ProductFilter2.class, 7)).orElseThrow());
        assertThat(src7).contains("Callable7<"); // generic callable present
        assertThat(src7).contains("matchFn7");
        assertThat(src7).contains("for (G product7 : catalogue)");
        // All 6 generated criteria present in the method signature (typed A..F)
        IntStream.rangeClosed(1, 6).forEach(j -> assertThat(src7).contains("criterion" + j));

        // --- Behavioural: ProductFilter3 ---
        // search("Electronics", "under-1000", matches) with catalogue=["laptop"]
        // → matchFn3.call("Electronics", "under-1000", "laptop"), laptop added to matches
        var loader = classLoaderFor(compilation);
        var matches3 = new ArrayList<>();
        var fix3 = prepareFixture(loader,
                generatedClassName(ProductFilter2.class, 3), "catalogue", List.of("laptop"));
        fix3.invoke("search", "Electronics", "under-1000", matches3);
        assertThat(fix3.captured).containsExactly("Electronics", "under-1000", "laptop").inOrder();
        assertThat(matches3).containsExactly("laptop");

        // --- Behavioural: ProductFilter7 ---
        // 6 criteria, one catalogue item; Callable7 must receive all 7 args
        var matches7 = new ArrayList<>();
        var fix7 = prepareFixture(loader,
                generatedClassName(ProductFilter2.class, 7), "catalogue", List.of("camera"));
        fix7.invoke("search", "Photo", 2000.0, "Canon", 4.5, true, 3, matches7);
        assertThat(fix7.captured)
                .containsExactly("Photo", 2000.0, "Canon", 4.5, true, 3, "camera").inOrder();
        assertThat(matches7).containsExactly("camera");
    }

    // -------------------------------------------------------------------------
    // AuditRecord2 → AuditRecord3 .. AuditRecord6
    // Domain: compliance audit logging with fixed eventType/severity + N context fields
    // -------------------------------------------------------------------------

    /**
     * Each {@code AuditRecord{i}} fans one audit event out to every registered
     * sink, carrying the N-1 contextual fields. The fixed {@code eventType} (before
     * the permuted sequence) and {@code severity} (after) appear in every generated
     * method signature but are NOT forwarded to the callable — only the context
     * fields and sink are.
     */
    @Test
    public void testAuditRecordFansContextFieldsOutToEachSink() {
        var compilation = compileTemplate(AuditRecord2.class, 3, 6);
        assertThat(compilation).succeeded();

        // --- Structural: AuditRecord3 ---
        var src3 = sourceOf(compilation.generatedSourceFile(
                generatedClassName(AuditRecord2.class, 3)).orElseThrow());
        assertThat(src3).contains("Callable3<A, B, C> writer3");
        assertThat(src3).doesNotContain("writer2");
        // Fixed params present in method signature
        assertThat(src3).contains("String eventType");
        assertThat(src3).contains("String severity");
        // Permuted context fields (now typed A, B)
        assertThat(src3).contains("A context1, B context2");
        assertThat(src3).contains("for (C sink3 : sinks)");
        // eventType must appear before the context params (fixed before sentinel)
        assertThat(src3.indexOf("String eventType")).isLessThan(src3.indexOf("A context1"));
        // severity must appear after the context params (fixed after sentinel)
        assertThat(src3.indexOf("A context1")).isLessThan(src3.indexOf("String severity"));
        assertThat(src3).doesNotContain("@PermuteDeclr");
        assertThat(src3).doesNotContain("@PermuteParam");

        // --- Structural: AuditRecord6 (5 context fields) ---
        var src6 = sourceOf(compilation.generatedSourceFile(
                generatedClassName(AuditRecord2.class, 6)).orElseThrow());
        assertThat(src6).contains("Callable6<"); // generic callable present
        assertThat(src6).contains("writer6");
        assertThat(src6).contains("for (F sink6 : sinks)");
        IntStream.rangeClosed(1, 5).forEach(j -> assertThat(src6).contains("context" + j));

        // --- Behavioural: AuditRecord3 with two sinks ---
        // record("USER_LOGIN", "tenant-1", "user-42", "WARN")
        // → writer3.call("tenant-1", "user-42", sinkA) for each sink
        var loader = classLoaderFor(compilation);
        var fix3 = prepareFixture(loader,
                generatedClassName(AuditRecord2.class, 3), "sinks", List.of("db", "siem"));
        fix3.invoke("record", "USER_LOGIN", "tenant-1", "user-42", "WARN");
        // Two sinks → two calls, each with the same 2 context fields + one sink
        assertThat(fix3.captured)
                .containsExactly("tenant-1", "user-42", "db", "tenant-1", "user-42", "siem")
                .inOrder();

        // --- Behavioural: AuditRecord6 — 5 context fields reach the callable ---
        var fix6 = prepareFixture(loader,
                generatedClassName(AuditRecord2.class, 6), "sinks", List.of("archive"));
        fix6.invoke("record", "RECORD_DELETED",
                "tenant-1", "user-42", "order-99", "4242", "10.0.0.1", "CRITICAL");
        assertThat(fix6.captured)
                .containsExactly("tenant-1", "user-42", "order-99", "4242", "10.0.0.1", "archive")
                .inOrder();
    }

    // -------------------------------------------------------------------------
    // ValidationSuite.FieldValidator2 → FieldValidator3 .. FieldValidator6
    // Domain: form validation with N fields; nested class becomes top-level
    // -------------------------------------------------------------------------

    /**
     * {@code ValidationSuite.FieldValidator2} is a <em>nested</em> static class;
     * permuplate promotes each generated variant to a top-level class.
     * Each {@code FieldValidator{i}} applies every shared rule to the N-1 supplied
     * field values, collecting violations via the {@code errors} fixed-after parameter.
     */
    @Test
    public void testFieldValidatorGeneratesTopLevelClassThatAppliesRulesToAllFields() {
        var compilation = compileTemplate(ValidationSuite.FieldValidator2.class, 3, 6);
        assertThat(compilation).succeeded();

        // --- Structural: FieldValidator3 is top-level, not nested ---
        var src3 = sourceOf(compilation.generatedSourceFile(
                generatedClassName(ValidationSuite.FieldValidator2.class, 3)).orElseThrow());
        assertThat(src3).contains("public class FieldValidator3");
        assertThat(src3).doesNotContain("public static class FieldValidator3");
        assertThat(src3).doesNotContain("class ValidationSuite");
        assertThat(src3).contains("Callable3<A, B, C> ruleFn3"); // field renamed, now generic
        assertThat(src3).contains("A field1, B field2");
        assertThat(src3).contains("List<String> errors");
        assertThat(src3).contains("for (C rule3 : rules)");
        assertThat(src3).doesNotContain("@PermuteDeclr");
        assertThat(src3).doesNotContain("@PermuteParam");

        // --- Structural: FieldValidator6 (5 field params) ---
        var src6 = sourceOf(compilation.generatedSourceFile(
                generatedClassName(ValidationSuite.FieldValidator2.class, 6)).orElseThrow());
        assertThat(src6).contains("public class FieldValidator6");
        assertThat(src6).doesNotContain("class ValidationSuite");
        assertThat(src6).contains("Callable6<"); // generic callable present
        assertThat(src6).contains("ruleFn6");
        IntStream.rangeClosed(1, 5).forEach(j -> assertThat(src6).contains("field" + j));

        // --- Behavioural: FieldValidator3 with two rules ---
        // validate("alice", "alice@example.com", errors) with rules=["notBlank","validEmail"]
        // → ruleFn3.call("alice", "alice@example.com", "notBlank")
        //   ruleFn3.call("alice", "alice@example.com", "validEmail")
        var loader = classLoaderFor(compilation);
        var errors = new ArrayList<String>();
        var fix3 = prepareFixture(loader,
                generatedClassName(ValidationSuite.FieldValidator2.class, 3),
                "rules", List.of("notBlank", "validEmail"));
        setField(fix3.instance, "formId", "login-form");
        fix3.invoke("validate", "alice", "alice@example.com", errors);
        assertThat(fix3.captured)
                .containsExactly(
                        "alice", "alice@example.com", "notBlank",
                        "alice", "alice@example.com", "validEmail")
                .inOrder();

        // --- Behavioural: FieldValidator6 — all 5 fields reach every rule ---
        var fix6 = prepareFixture(loader,
                generatedClassName(ValidationSuite.FieldValidator2.class, 6),
                "rules", List.of("required"));
        setField(fix6.instance, "formId", "checkout-form");
        fix6.invoke("validate", "Alice Smith", "alice@example.com",
                "123 Main St", "Springfield", "90210", new ArrayList<String>());
        assertThat(fix6.captured)
                .containsExactly(
                        "Alice Smith", "alice@example.com", "123 Main St",
                        "Springfield", "90210", "required")
                .inOrder();
    }

    // -------------------------------------------------------------------------
    // BiCallable1x1 → BiCallable2x2 through BiCallable4x4 (cross-product)
    // -------------------------------------------------------------------------

    /**
     * Compiles {@link BiCallable1x1}, which uses two permutation variables ({@code i}
     * and {@code k}) via {@code extraVars} to generate 9 two-sided functional
     * interfaces — {@code BiCallable2x2} through {@code BiCallable4x4}. Verifies that
     * every generated interface has the correct {@code call} signature (left group then
     * right group) and can be invoked behaviourally via a capturing proxy.
     */
    @Test
    public void testBiCallableGeneratesNineInterfacesViaCrossProduct() {
        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(templateSource(BiCallable1x1.class));
        assertThat(compilation).succeeded();

        var loader = classLoaderFor(compilation);
        String pkg = BiCallable1x1.class.getPackageName();

        // All 9 combinations generated
        for (int i = 2; i <= 4; i++) {
            for (int k = 2; k <= 4; k++) {
                String name = pkg + ".BiCallable" + i + "x" + k;
                var src = sourceOf(compilation.generatedSourceFile(name)
                        .orElseThrow(() -> new AssertionError(name + " not generated")));

                // Generated as interface with correct type parameters
                var typeDecl = new StringBuilder("BiCallable").append(i).append("x").append(k).append("<");
                for (int j = 1; j <= i; j++) {
                    if (j > 1)
                        typeDecl.append(", ");
                    typeDecl.append("T").append(j);
                }
                for (int m = 1; m <= k; m++) {
                    typeDecl.append(", U").append(m);
                }
                assertThat(src).contains("public interface " + typeDecl.append(">"));
                assertThat(src).doesNotContain("public class");

                // Correct signature: i typed left params then k typed right params
                var params = new StringBuilder("void call(");
                for (int j = 1; j <= i; j++) {
                    if (j > 1)
                        params.append(", ");
                    params.append("T").append(j).append(" left").append(j);
                }
                for (int m = 1; m <= k; m++) {
                    params.append(", U").append(m).append(" right").append(m);
                }
                assertThat(src).contains(params.append(")").toString());

                assertThat(src).doesNotContain("@Permute");
                assertThat(src).doesNotContain("@PermuteParam");

                // Behavioural: all args received in order via proxy
                Object[] args = new Object[i + k];
                for (int j = 0; j < i; j++)
                    args[j] = "L" + (j + 1);
                for (int m = 0; m < k; m++)
                    args[i + m] = "R" + (m + 1);
                assertThat(invokeCallable(loader, name, args)).containsExactly(args).inOrder();
            }
        }
    }
}
