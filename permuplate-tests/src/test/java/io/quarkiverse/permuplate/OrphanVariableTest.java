package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.example.Callable2;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for R2 (unmatched literal — substring-based), R3 (orphan variable),
 * and R4 (no anchor) validation rules applied by the transformers.
 *
 * <p>
 * R1 lives in {@link DegenerateInputTest}. Prefix-match tests (the old rule,
 * now a subset of R2) live in {@link PrefixValidationTest}.
 */
public class OrphanVariableTest {

    private static final String PKG = Callable2.class.getPackageName();
    private static final String PERMUTE_FQN = Permute.class.getName();
    private static final String PERMUTE_DECLR_FQN = PermuteDeclr.class.getName();
    private static final String PERMUTE_PARAM_FQN = PermuteParam.class.getName();

    private static Compilation compile(Class<?> anchor, String cls, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        anchor.getPackageName() + "." + cls, source));
    }

    // -------------------------------------------------------------------------
    // R2 — substring matching (multiple literals)
    // -------------------------------------------------------------------------

    @Test
    public void testR2_multipleLiterals_secondNotFound_isError() {
        // name="async${i}Cache" on field named "asyncDiskHandler2":
        // "async" found at pos 0, then "Cache" NOT found after it → error
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "async${i}Cache") Object asyncDiskHandler2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Cache");
    }

    @Test
    public void testR2_multipleLiterals_bothFound_noError() {
        // name="async${i}Handler" on field "asyncDiskHandler2" — "async" at start, "Handler" found later
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "async${i}Handler") Object asyncDiskHandler2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        // "async" found in "asyncDiskHandler2", "Handler" found after it → valid
        assertThat(compilation).succeeded();
    }

    // -------------------------------------------------------------------------
    // R3 — Orphan variable
    // -------------------------------------------------------------------------

    @Test
    public void testR3_orphanVariableAtStart_isError() {
        // name="${v1}c${i}" on field "c2": prefix before "c" in "c2" is "" → v1 orphan
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "${v1}c${i}") Object c2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("v1");
    }

    @Test
    public void testR3_notOrphan_nonEmptyPrefix_noError() {
        // name="${v1}c${i}" on field "myc2": prefix "my" before "c" is non-empty → no orphan.
        // v1 is defined via strings={"v1=my"} so JEXL evaluation succeeds at generation time.
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
                                 strings = {"v1=my"})
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "${v1}c${i}") Object myc2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).succeeded();
    }

    @Test
    public void testR3_r2ShortCircuits_onlyOneError() {
        // "${v1}Foo${v2}" — "Foo" not in field name "c2" → only R2 fires, not R3 for v1
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "${v1}Foo${v2}") Object c2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        // Should fail with R2 (unmatched literal "Foo"), not orphan for v1
        assertThat(compilation).hadErrorContaining("Foo");
    }

    // -------------------------------------------------------------------------
    // R4 — No anchor
    // -------------------------------------------------------------------------

    @Test
    public void testR4_pureVariables_isError() {
        // name="${v1}${v2}" — no static literal → no anchor
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "${v1}${v2}") Object c2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        // R4: no anchor error — the message says "no static literal"
        assertThat(compilation).hadErrorContaining("no static literal");
    }

    @Test
    public void testR4_stringConstantProducesAnchor_noError() {
        // type="${prefix}" with strings={"prefix=Object"} → expands to "Object" (pure literal),
        // literal "Object" found in "Object" (the field type) → no R4 error, no R2/R3 error → valid
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
                                 strings = {"prefix=Object"})
                        public class Foo2 {
                            private @PermuteDeclr(type = "${prefix}", name = "c${i}") Object c2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        // After expansion: type="${prefix}" with prefix=Object → "Object"
        // "Object" found in "Object" (field type) → no anchor error, no orphan → valid
        assertThat(compilation).succeeded();
    }
}
