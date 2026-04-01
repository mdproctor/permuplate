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
 * Tests that the processor reports clear, actionable errors for degenerate
 * {@code @Permute} configurations rather than silently generating nothing or
 * crashing with an unhandled exception.
 * <p>
 * These tests use inline source strings — intentionally broken templates must
 * not live as real {@code .java} files in the source tree.
 * <p>
 * The template class name ({@code "Foo2"}) and generated name suffixes
 * ({@code "Foo3"}, {@code "FixedName"}) are unavoidably literal, since they
 * are made-up degenerate names with no real class to reference. The package and
 * annotation import, however, are derived from {@link Callable2} and
 * {@link Permute} so that a package rename is caught at compile time.
 */
public class DegenerateInputTest {

    /** Package shared by all degenerate template sources, derived from a real class. */
    private static final String PKG = Callable2.class.getPackageName();

    /** Fully-qualified {@code @Permute} annotation name for import statements. */
    private static final String PERMUTE_FQN = Permute.class.getName();

    /** Fully-qualified {@code @PermuteDeclr} annotation name for import statements. */
    private static final String PERMUTE_DECLR_FQN = PermuteDeclr.class.getName();

    /** Fully-qualified {@code @PermuteParam} annotation name for import statements. */
    private static final String PERMUTE_PARAM_FQN = PermuteParam.class.getName();

    /** Fully-qualified {@code @PermuteVar} annotation name for import statements. */
    private static final String PERMUTE_VAR_FQN = PermuteVar.class.getName();

    /**
     * Compiles an inline source string with the annotation processor.
     * The qualified class name is derived from {@code packageAnchor} + the
     * plain {@code simpleClassName}; the caller supplies only the made-up name.
     */
    private static Compilation compile(Class<?> packageAnchor, String simpleClassName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        packageAnchor.getPackageName() + "." + simpleClassName, source));
    }

    // -------------------------------------------------------------------------
    // Invalid range: from > to
    // -------------------------------------------------------------------------

    /**
     * {@code @Permute(from=5, to=3)} is an empty range — no classes can be
     * generated. The processor must report an error rather than silently doing
     * nothing, which would leave callers confused about why expected types are
     * missing.
     */
    @Test
    public void testFromGreaterThanToIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 5, to = 3, className = "Foo${i}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("from=5");
        assertThat(compilation).hadErrorContaining("to=3");
    }

    // -------------------------------------------------------------------------
    // Equal boundary: from == to (valid — generates exactly one class)
    // -------------------------------------------------------------------------

    /**
     * {@code from == to} is the degenerate-but-valid case (range of exactly
     * one). The processor must not error; it generates precisely one class.
     */
    @Test
    public void testFromEqualToIsValid() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 3, className = "Foo${i}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile(PKG + ".Foo3").isPresent()).isTrue();
    }

    // -------------------------------------------------------------------------
    // className contains no permutation variable → duplicate class on each step
    // -------------------------------------------------------------------------

    /**
     * {@code className = "FixedName"} (no {@code ${i}}) causes the processor to
     * attempt writing the same generated file on every iteration. The second
     * attempt fails because the file already exists; the error must name the
     * duplicate class and hint that the {@code className} expression is missing
     * the permutation variable.
     */
    @Test
    public void testClassNameWithoutVariableProducesDuplicateError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "FixedName")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("FixedName");
        assertThat(compilation).hadErrorContaining("className");
    }

    // -------------------------------------------------------------------------
    // className literal prefix does not match the template class name
    // -------------------------------------------------------------------------

    /**
     * The static (non-variable) part of {@code className} must be a prefix of the
     * template class name, so that generated names remain recognisably related to
     * their source. {@code className = "Bar${i}"} on a class named {@code Foo2}
     * would silently produce unrelated {@code Bar3}, {@code Bar4} … classes — the
     * processor must reject this upfront rather than silently misleading the user.
     */
    @Test
    public void testClassNamePrefixMismatchIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Bar${i}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Bar");
        assertThat(compilation).hadErrorContaining("Foo2");
    }

    // -------------------------------------------------------------------------
    // Malformed JEXL expression in className
    // -------------------------------------------------------------------------

    /**
     * An invalid JEXL expression in {@code className} (here, a trailing operator
     * with no right-hand operand) must produce a clear error message that
     * identifies the problematic {@code className} attribute rather than
     * propagating a raw exception to the compiler.
     */
    @Test
    public void testInvalidJexlInClassNameIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 3, className = "Foo${i +}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("className");
        assertThat(compilation).hadErrorContaining("Foo${i +}");
    }

    // -------------------------------------------------------------------------
    // @PermuteDeclr string prefix constraints
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
    // @PermuteParam string prefix constraint
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
    // @Permute strings attribute — malformed entries
    // -------------------------------------------------------------------------

    /**
     * A {@code strings} entry without a {@code =} separator (e.g. {@code "badformat"})
     * cannot be parsed into a key-value pair. The processor must reject it immediately
     * with a clear error naming the problematic entry.
     */
    @Test
    public void testStringsMalformedEntryNoEqualsIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}",
                                 strings = {"badformat"})
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("badformat");
        assertThat(compilation).hadErrorContaining("key=value");
    }

    /**
     * A {@code strings} entry with an empty key (e.g. {@code "=value"}) is invalid —
     * an empty variable name cannot be referenced in {@code ${}} expressions. The
     * processor must report this with a clear error.
     */
    @Test
    public void testStringsEmptyKeyIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}",
                                 strings = {"=value"})
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("empty key");
    }

    /**
     * A {@code strings} key that duplicates {@code varName} would shadow the
     * integer loop variable. The processor must reject this conflict.
     */
    @Test
    public void testStringsKeyConflictingWithVarNameIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}",
                                 strings = {"i=conflict"})
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("conflicts");
        assertThat(compilation).hadErrorContaining("varName");
    }

    // -------------------------------------------------------------------------
    // Source-position precision — errors must carry IDE-navigable locations
    // -------------------------------------------------------------------------

    /**
     * Errors emitted via {@code AnnotationValue}-precision should have a positive
     * line number (not {@code Diagnostic.NOPOS}) so IDEs can navigate to the exact
     * offending attribute. Verified for the {@code from} attribute of a bad range.
     */
    @Test
    public void testFromGreaterThanToErrorHasSourcePosition() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 5, to = 3, className = "Foo${i}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation.errors().stream().anyMatch(d -> d.getLineNumber() > 0)).isTrue();
    }

    /**
     * The {@code className} prefix-mismatch error must carry a source position
     * pointing to the {@code className} attribute value, not an unlocated message.
     */
    @Test
    public void testClassNamePrefixMismatchErrorHasSourcePosition() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Bar${i}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation.errors().stream().anyMatch(d -> d.getLineNumber() > 0)).isTrue();
    }

    /**
     * Transformer-level errors (from {@code PermuteDeclrTransformer}) now receive
     * the annotated {@code TypeElement} as their location — at minimum file-level
     * precision. The line number must be positive.
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

    // -------------------------------------------------------------------------
    // Method-level @Permute degenerate cases
    // -------------------------------------------------------------------------

    /**
     * {@code @Permute(from=5, to=3)} on a method is the same invalid range as on
     * a class — the processor must report a clear error identifying the bad range
     * rather than silently generating nothing or crashing.
     */
    @Test
    public void testMethodFromGreaterThanToIsError() {
        var compilation = compile(Callable2.class, "Foo",
                """
                        package %s;
                        import %s;
                        public class Foo {
                            @Permute(varName = "i", from = 5, to = 3, className = "FooUtils")
                            public void process(Object o1) {}
                        }
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("from=5");
        assertThat(compilation).hadErrorContaining("to=3");
        assertThat(compilation.errors().stream().anyMatch(d -> d.getLineNumber() > 0)).isTrue();
    }

    /**
     * An invalid JEXL expression in {@code className} on a method-level
     * {@code @Permute} must produce a clear error naming the problematic
     * {@code className} attribute.
     */
    @Test
    public void testMethodInvalidJexlInClassNameIsError() {
        var compilation = compile(Callable2.class, "Foo",
                """
                        package %s;
                        import %s;
                        public class Foo {
                            @Permute(varName = "i", from = 2, to = 4, className = "Foo${i +}")
                            public void process(Object o1) {}
                        }
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("className");
        assertThat(compilation).hadErrorContaining("Foo${i +}");
    }

    // -------------------------------------------------------------------------
    // @Permute extraVars validation
    // -------------------------------------------------------------------------

    /**
     * A {@code @PermuteVar} with {@code from > to} is an invalid range. The processor
     * must report a clear error naming the variable and its bounds.
     */
    @Test
    public void testExtraVarFromGreaterThanToIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}",
                                 extraVars = { @PermuteVar(varName = "k", from = 5, to = 3) })
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("k");
        assertThat(compilation).hadErrorContaining("from=5");
        assertThat(compilation).hadErrorContaining("to=3");
    }

    /**
     * An {@code extraVars} variable name that duplicates the primary {@code varName}
     * would create an ambiguous binding. The processor must reject this.
     */
    @Test
    public void testExtraVarConflictsWithVarNameIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}",
                                 extraVars = { @PermuteVar(varName = "i", from = 2, to = 4) })
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("conflicts");
    }

    /**
     * Two {@code extraVars} entries with the same variable name would create an
     * ambiguous binding. The processor must reject this.
     */
    @Test
    public void testExtraVarConflictsWithAnotherExtraVarIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}",
                                 extraVars = { @PermuteVar(varName = "k", from = 2, to = 4),
                                               @PermuteVar(varName = "k", from = 2, to = 4) })
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("conflicts");
    }

    /**
     * A {@code strings} key that duplicates an {@code extraVars} variable name would
     * shadow the loop variable. The processor must reject this.
     */
    @Test
    public void testStringsKeyConflictsWithExtraVarIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = 3, to = 5, className = "Foo${i}",
                                 strings = { "k=fixed" },
                                 extraVars = { @PermuteVar(varName = "k", from = 2, to = 4) })
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("conflicts");
    }
}
