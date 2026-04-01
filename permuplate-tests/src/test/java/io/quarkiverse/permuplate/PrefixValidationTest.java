package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import org.junit.Ignore;
import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.example.Callable2;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests that the processor enforces the string-literal prefix rule across all
 * annotations: the static (non-{@code ${...}}) part of a {@code type} or
 * {@code name} template must be a prefix of the actual declaration's type or
 * name. A mismatch indicates the template string was not updated after an IDE
 * rename and is caught as a compile error before any classes are generated.
 *
 * <p>
 * The rule applies to:
 * <ul>
 * <li>{@code @PermuteDeclr} on fields — both {@code type} and {@code name}</li>
 * <li>{@code @PermuteDeclr} on for-each variables — both {@code type} and {@code name}</li>
 * <li>{@code @PermuteDeclr} on constructor parameters — both {@code type} and {@code name}</li>
 * <li>{@code @PermuteParam} — {@code name} only ({@code type} is the generated
 * parameter type, not the sentinel's placeholder type, so a mismatch is not
 * necessarily a mistake)</li>
 * </ul>
 *
 * <p>
 * Coverage matrix — each placement × {type error, name error, valid pass}:
 * <table>
 * <tr>
 * <th>Placement</th>
 * <th>type mismatch</th>
 * <th>name mismatch</th>
 * <th>valid (no error)</th>
 * </tr>
 * <tr>
 * <td>field</td>
 * <td>✓</td>
 * <td>✓</td>
 * <td>✓</td>
 * </tr>
 * <tr>
 * <td>for-each variable</td>
 * <td>✓</td>
 * <td>✓</td>
 * <td>✓</td>
 * </tr>
 * <tr>
 * <td>constructor param</td>
 * <td>✓</td>
 * <td>✓</td>
 * <td>✓</td>
 * </tr>
 * <tr>
 * <td>@PermuteParam</td>
 * <td>n/a</td>
 * <td>✓</td>
 * <td>✓</td>
 * </tr>
 * </table>
 */
public class PrefixValidationTest {

    private static final String PKG = Callable2.class.getPackageName();
    private static final String PERMUTE_FQN = Permute.class.getName();
    private static final String PERMUTE_DECLR_FQN = PermuteDeclr.class.getName();
    private static final String PERMUTE_PARAM_FQN = PermuteParam.class.getName();

    private static Compilation compile(Class<?> packageAnchor, String simpleClassName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        packageAnchor.getPackageName() + "." + simpleClassName, source));
    }

    // -------------------------------------------------------------------------
    // Adjacent variables — collective region behaviour
    // -------------------------------------------------------------------------

    /**
     * Adjacent variables before a static literal (e.g. {@code "${v1}${v2}Element${v3}"})
     * are treated as a <em>collective unit</em> covering the prefix region. When the
     * collective prefix region is <strong>non-empty</strong>, neither variable is an orphan.
     * The individual split between them is irrelevant and is not validated — at generate
     * time {@code v1+v2} concatenates to produce whatever text fills the slot.
     *
     * <p>
     * This is intentional and accepted behaviour. No error must be reported.
     *
     * <p>
     * <strong>Ignored until Sub-project 1:</strong> this test requires the full
     * substring-based matching algorithm ({@code "Element"} must be found as a
     * substring of {@code "myElement2"}, not just a prefix). The current validator
     * uses prefix-only matching so it incorrectly rejects this as an error. Un-ignore
     * after implementing {@code permuplate-ide-support} and wiring it into the processor.
     */
    @Ignore("Requires Sub-project 1 substring algorithm — current validator uses prefix-only matching")
    @Test
    public void testAdjacentVariablesOnNonEmptyPrefixAreNotOrphan() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        import java.util.List;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private List<Object> myElements;
                            public void go() {
                                for (@PermuteDeclr(type = "Object",
                                                   name = "${v1}${v2}Element${v3}") Object myElement2 : myElements) {}
                            }
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        // ${v1} and ${v2} together cover prefix "my" in "myElement2" — non-empty.
        // Neither is orphan. The individual split between them is not our concern.
        assertThat(compilation).succeeded();
    }

    /**
     * Adjacent variables where the collective prefix region is <strong>empty</strong>:
     * both are orphan. Contrasts with
     * {@link #testAdjacentVariablesOnNonEmptyPrefixAreNotOrphan()} — the rule applies
     * to the region as a whole, not per-variable.
     *
     * <p>
     * {@code "${v1}${v2}c${v3}"} on field {@code c2}: literal {@code "c"} is at position
     * 0, so the prefix region before it is empty. Both {@code ${v1}} and {@code ${v2}}
     * collectively cover nothing — both are orphan.
     *
     * <p>
     * <strong>Ignored until Sub-project 1:</strong> the orphan variable rule is not yet
     * implemented in the processor. Currently {@code "${v1}${v2}c${v3}"} passes prefix
     * validation (static prefix {@code "c"} is a prefix of {@code "c2"}) but then fails
     * at generation time with a JEXL undefined-variable exception because {@code v1},
     * {@code v2}, and {@code v3} are not declared as loop or string variables.
     * Un-ignore after implementing the orphan-variable rule in Sub-project 1.
     */
    @Ignore("Requires Sub-project 1 orphan-variable rule — not yet implemented in processor")
    @Test
    public void testAdjacentVariablesOnEmptyPrefixAreBothOrphan() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object",
                                                   name = "${v1}${v2}c${v3}") Object c2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        // ${v1} and ${v2} collectively cover the prefix before "c" in "c2", which is "".
        // Both are orphan — at least one orphan error must be reported.
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("c2");
    }

    // -------------------------------------------------------------------------
    // @PermuteDeclr on fields
    // -------------------------------------------------------------------------

    /**
     * The static part of {@code @PermuteDeclr type} must be a prefix of the actual
     * field type. {@code type = "Bar${i}"} on a field of type {@code Object} would
     * silently generate code that references a non-existent {@code Bar3} type; the
     * processor must reject this upfront.
     */
    @Test
    public void testPermuteDeclrFieldTypePrefixMismatchIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Bar${i}", name = "c${i}") Object c2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Bar");
        assertThat(compilation).hadErrorContaining("Object");
    }

    /**
     * The static part of {@code @PermuteDeclr name} must be a prefix of the actual
     * field name. {@code name = "x${i}"} on a field named {@code c2} would rename
     * usages that don't exist under that prefix; the processor must reject this upfront.
     */
    @Test
    public void testPermuteDeclrFieldNamePrefixMismatchIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "x${i}") Object c2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("x");
        assertThat(compilation).hadErrorContaining("c2");
    }

    // -------------------------------------------------------------------------
    // @PermuteDeclr on for-each variables
    // -------------------------------------------------------------------------

    /**
     * The static part of {@code @PermuteDeclr name} on a for-each variable must be a
     * prefix of the actual loop variable name. {@code name = "x${i}"} on a variable
     * named {@code o2} is almost certainly a mistake; the processor must reject it.
     */
    @Test
    public void testPermuteDeclrForEachNamePrefixMismatchIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        import java.util.List;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private List<Object> right;
                            public void go() {
                                for (@PermuteDeclr(type = "Object", name = "x${i}") Object o2 : right) {}
                            }
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("x");
        assertThat(compilation).hadErrorContaining("o2");
    }

    // -------------------------------------------------------------------------
    // @PermuteDeclr on constructor parameters
    // -------------------------------------------------------------------------

    /**
     * The static part of {@code @PermuteDeclr name} on a constructor parameter must
     * be a prefix of the actual parameter name. {@code name = "x${i}"} on a parameter
     * named {@code c2} would produce a constructor whose parameter name is unrelated
     * to the template; the processor must reject this upfront.
     */
    @Test
    public void testPermuteDeclrConstructorParamNamePrefixMismatchIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            public Foo2(@PermuteDeclr(type = "Object", name = "x${i}") Object c2) {}
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("x");
        assertThat(compilation).hadErrorContaining("c2");
    }

    // -------------------------------------------------------------------------
    // @PermuteParam
    // -------------------------------------------------------------------------

    /**
     * The static part of {@code @PermuteParam name} must be a prefix of the sentinel
     * parameter name. {@code name = "x${j}"} on a parameter named {@code o1} means
     * the generated names ({@code x1}, {@code x2}, …) won't match any existing
     * call-site arguments; the processor must reject this upfront.
     */
    @Test
    public void testPermuteParamNamePrefixMismatchIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            public void go(
                                    @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "x${j}") Object o1) {}
                        }
                        """
                        .formatted(PKG, PERMUTE_FQN, PERMUTE_PARAM_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("x");
        assertThat(compilation).hadErrorContaining("o1");
    }

    /**
     * The static part of {@code @PermuteDeclr type} on a for-each variable must be a
     * prefix of the actual loop variable type. {@code type = "Bar${i}"} on a variable
     * of type {@code Object} is clearly wrong; the processor must reject it upfront.
     * (Previously only the {@code name} was tested for for-each variables.)
     */
    @Test
    public void testPermuteDeclrForEachTypePrefixMismatchIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        import java.util.List;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private List<Object> right;
                            public void go() {
                                for (@PermuteDeclr(type = "Bar${i}", name = "o${i}") Object o2 : right) {}
                            }
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Bar");
        assertThat(compilation).hadErrorContaining("Object");
    }

    /**
     * The static part of {@code @PermuteDeclr type} on a constructor parameter must
     * be a prefix of the actual parameter type. Previously only the {@code name}
     * attribute was tested for constructor parameters.
     */
    @Test
    public void testPermuteDeclrConstructorParamTypePrefixMismatchIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            public Foo2(@PermuteDeclr(type = "Bar${i}", name = "c${i}") Object c2) {}
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Bar");
        assertThat(compilation).hadErrorContaining("Object");
    }

    // -------------------------------------------------------------------------
    // Valid strings — must NOT produce errors
    // -------------------------------------------------------------------------

    /**
     * A correctly written field {@code @PermuteDeclr} must compile without errors.
     * Uses {@code Object} as the type so the generated files don't reference
     * missing external types.
     */
    @Test
    public void testPermuteDeclrFieldWithValidStringsDoesNotError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "item${i}") Object item2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).succeeded();
    }

    /**
     * A correctly written for-each {@code @PermuteDeclr} — the type is the static
     * {@code "Object"} (unchanged across permutations) and the name prefix matches —
     * must compile without errors. Verifies that a type string with no variable
     * ({@code "Object"}) is valid when the type genuinely does not change.
     */
    @Test
    public void testPermuteDeclrForEachWithValidStringsDoesNotError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        import java.util.List;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private List<Object> right;
                            public void go() {
                                for (@PermuteDeclr(type = "Object", name = "o${i}") Object o2 : right) {}
                            }
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).succeeded();
    }

    /**
     * A correctly written constructor parameter {@code @PermuteDeclr} must
     * compile without errors.
     */
    @Test
    public void testPermuteDeclrConstructorParamWithValidStringsDoesNotError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            public Foo2(@PermuteDeclr(type = "Object", name = "arg${i}") Object arg2) {}
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).succeeded();
    }

    /**
     * A correctly written {@code @PermuteParam} — the {@code name} prefix matches
     * the sentinel parameter name — must compile without errors.
     */
    @Test
    public void testPermuteParamWithValidNameDoesNotError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            public void go(
                                    @PermuteParam(varName = "j", from = "1", to = "${i-1}",
                                                  type = "Object", name = "o${j}") Object o1) {}
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_PARAM_FQN));

        assertThat(compilation).succeeded();
    }

    /**
     * Two separate {@code @PermuteDeclr} annotations in the same class — one on a
     * field and one on a for-each variable — must each be validated independently.
     * Both are valid; no error must be reported for either.
     */
    @Test
    public void testMultiplePermuteDeclrAnnotationsInSameClassAllValidDoesNotError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        import java.util.List;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "item${i}") Object item2;
                            private List<Object> elements;
                            public void go() {
                                for (@PermuteDeclr(type = "Object", name = "elem${i}") Object elem2 : elements) {}
                            }
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).succeeded();
    }

    /**
     * When one {@code @PermuteDeclr} is valid and a second one in the same class
     * is invalid, only the invalid annotation produces an error — the valid one
     * must not be incorrectly flagged.
     */
    @Test
    public void testOneValidOneInvalidPermuteDeclrReportsErrorOnlyForInvalid() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        import java.util.List;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Object", name = "item${i}") Object item2;
                            private List<Object> elements;
                            public void go() {
                                for (@PermuteDeclr(type = "Wrong${i}", name = "elem${i}") Object elem2 : elements) {}
                            }
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Wrong");
        assertThat(compilation).hadErrorContaining("Object");
    }

    // -------------------------------------------------------------------------
    // Source-position precision for prefix errors
    // -------------------------------------------------------------------------

    /**
     * Transformer-level prefix errors (from {@code PermuteDeclrTransformer}) receive
     * the annotated {@code TypeElement} as their location — at minimum file-level
     * precision. The line number must be positive so IDEs can navigate to the error.
     */
    @Test
    public void testPermuteDeclrPrefixMismatchErrorHasSourcePosition() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}")
                        public class Foo2 {
                            private @PermuteDeclr(type = "Bar${i}", name = "c${i}") Object c2;
                        }
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_DECLR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation.errors().stream().anyMatch(d -> d.getLineNumber() > 0)).isTrue();
    }
}
