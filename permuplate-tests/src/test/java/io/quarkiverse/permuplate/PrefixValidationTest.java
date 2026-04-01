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
