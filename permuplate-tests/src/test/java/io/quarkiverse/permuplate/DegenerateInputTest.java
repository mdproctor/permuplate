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
 * Tests that the processor reports clear, actionable errors for invalid
 * {@code @Permute} configurations — covering range validation, {@code className}
 * format, {@code strings} and {@code extraVars} attribute validation, method-level
 * errors, and source-position precision.
 *
 * <p>
 * String-literal prefix rules ({@code @PermuteDeclr}, {@code @PermuteParam}) are
 * tested in {@link PrefixValidationTest}. APT-specific rejection of {@code inline}
 * and {@code keepTemplate} is tested in {@link AptInlineGuardTest}.
 *
 * <p>
 * All tests use inline source strings — intentionally broken templates must not
 * live as real {@code .java} files in the source tree.
 */
public class DegenerateInputTest {

    /** Package shared by all degenerate template sources, derived from a real class. */
    private static final String PKG = Callable2.class.getPackageName();

    /** Fully-qualified {@code @Permute} annotation name for import statements. */
    private static final String PERMUTE_FQN = Permute.class.getName();

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
     * {@code @Permute(from="5", to="3")} is an empty range — no classes can be
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
                        @Permute(varName = "i", from = "5", to = "3", className = "Foo${i}")
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
                        @Permute(varName = "i", from = "3", to = "3", className = "Foo${i}")
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
                        @Permute(varName = "i", from = "3", to = "5", className = "FixedName")
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Bar${i}")
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
                        @Permute(varName = "i", from = "3", to = "3", className = "Foo${i +}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("className");
        assertThat(compilation).hadErrorContaining("Foo${i +}");
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
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
                        @Permute(varName = "i", from = "5", to = "3", className = "Foo${i}")
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Bar${i}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation.errors().stream().anyMatch(d -> d.getLineNumber() > 0)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Method-level @Permute degenerate cases
    // -------------------------------------------------------------------------

    /**
     * {@code @Permute(from="5", to="3")} on a method is the same invalid range as on
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
                            @Permute(varName = "i", from = "5", to = "3", className = "FooUtils")
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
                            @Permute(varName = "i", from = "2", to = "4", className = "Foo${i +}")
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
                                 extraVars = { @PermuteVar(varName = "k", from = "5", to = "3") })
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
                                 extraVars = { @PermuteVar(varName = "i", from = "2", to = "4") })
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
                                 extraVars = { @PermuteVar(varName = "k", from = "2", to = "4"),
                                               @PermuteVar(varName = "k", from = "2", to = "4") })
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
                        @Permute(varName = "i", from = "3", to = "5", className = "Foo${i}",
                                 strings = { "k=fixed" },
                                 extraVars = { @PermuteVar(varName = "k", from = "2", to = "4") })
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("conflicts");
    }

    // -------------------------------------------------------------------------
    // R1 — @Permute.className has no variable (compile error regardless of range)
    // -------------------------------------------------------------------------

    /**
     * {@code className = "FixedName"} with {@code from="3", to=5} produces a duplicate
     * class error via the Filer today, but R1 should catch it first with a clearer message.
     */
    @Test
    public void testClassNameNoVariableWithRangeIsR1Error() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5", className = "FixedName")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("FixedName");
        assertThat(compilation).hadErrorContaining("no variables");
    }

    /**
     * {@code className = "FixedName"} with {@code from="3", to=3}: only one iteration,
     * so the Filer never fires a duplicate error. R1 must catch this case.
     */
    @Test
    public void testClassNameNoVariableWithFromEqualsToIsR1Error() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "3", className = "FixedName")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("FixedName");
        assertThat(compilation).hadErrorContaining("no variables");
    }

    /**
     * A {@code className} with a variable does NOT trigger R1.
     */
    @Test
    public void testClassNameWithVariableDoesNotTriggerR1() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "3", className = "Foo${i}")
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).succeeded();
    }

    // -------------------------------------------------------------------------
    // R1b — extraVars variable absent from className (produces duplicates)
    // -------------------------------------------------------------------------

    /**
     * {@code extraVars = {@PermuteVar(varName="k", ...)}} but {@code ${k}} never
     * appears in {@code className} — every value of k generates the same class name.
     */
    @Test
    public void testExtraVarsMissingFromClassNameIsR1bError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "2", to = "3", className = "Foo${i}",
                                 extraVars = { @PermuteVar(varName = "k", from = "2", to = "3") })
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("k");
        assertThat(compilation).hadErrorContaining("className");
    }

    // -------------------------------------------------------------------------
    // @PermuteImport — bad JEXL expression must be a compile error
    // -------------------------------------------------------------------------

    /**
     * A {@code @PermuteImport} expression that fails to evaluate (e.g., referencing
     * an undefined variable) must surface as a compiler error, not be silently ignored.
     */
    @Test
    public void testBadPermuteImportExpressionIsError() {
        var compilation = compile(Callable2.class, "ImportFail2",
                """
                        package %s;
                        import %s;
                        import io.quarkiverse.permuplate.PermuteImport;
                        @Permute(varName = "i", from = "2", to = "2", className = "ImportFail${i}")
                        @PermuteImport("${undefinedVar}.SomeClass")
                        public class ImportFail2 {}
                        """.formatted(PKG, PERMUTE_FQN));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("PermuteImport");
    }

    // -------------------------------------------------------------------------
    // JEXL expression evaluation failures
    // -------------------------------------------------------------------------

    /**
     * A {@code @PermuteMethod} {@code name} expression that references an undefined variable
     * must surface as a compiler error rather than being silently ignored.
     */
    @Test
    public void testBadPermuteMethodNameExpressionIsError() {
        var compilation = compile(Callable2.class, "BadMethodName2",
                """
                        package %s;
                        import %s;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        @Permute(varName = "i", from = "1", to = "1", className = "BadMethodName${i}")
                        public class BadMethodName2 {
                            @PermuteMethod(varName = "j", from = "1", to = "2",
                                           name = "${undefinedNameVar}Method${j}")
                            public void templateMethod() {}
                        }
                        """.formatted(PKG, PERMUTE_FQN));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("PermuteMethod");
        assertThat(compilation).hadErrorContaining("name");
    }

    /**
     * A {@code @PermuteStatements} {@code from} expression that references an undefined variable
     * must surface as a compiler error rather than being silently ignored.
     */
    @Test
    public void testBadPermuteStatementsBoundExpressionIsError() {
        var compilation = compile(Callable2.class, "BadStmts2",
                """
                        package %s;
                        import %s;
                        import io.quarkiverse.permuplate.PermuteStatements;
                        @Permute(varName = "i", from = "2", to = "2", className = "BadStmts${i}")
                        public class BadStmts2 {
                            @PermuteStatements(varName = "k", from = "${undefinedBoundVar}",
                                               to = "${i}", position = "first",
                                               body = "System.out.println(${k});")
                            public void init() {}
                        }
                        """.formatted(PKG, PERMUTE_FQN));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("PermuteStatements");
        assertThat(compilation).hadErrorContaining("from");
    }

    // -------------------------------------------------------------------------
    // @PermuteSwitchArm: requires Java 21+ source level
    // -------------------------------------------------------------------------

    @Test
    public void testPermuteSwitchArmBelowJava21EmitsError() {
        // When compiled with -source 17, @PermuteSwitchArm on a method must emit
        // a clear error pointing at the annotated class (not a confusing javac
        // error on the generated file).
        //
        // Template uses arrow switch on int — valid from Java 14, so javac accepts
        // the source. The processor must check the source level and report the error.
        Compilation compilation = Compiler.javac()
                .withOptions("-source", "17")
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        PKG + ".SwitchArmSource2",
                        """
                                package %s;
                                import %s;
                                import io.quarkiverse.permuplate.PermuteSwitchArm;
                                @Permute(varName = "i", from = "3", to = "3", className = "SwitchArmSource${i}")
                                public class SwitchArmSource2 {
                                    @PermuteSwitchArm(varName = "k", from = "1", to = "1",
                                                     pattern = "Integer n",
                                                     body = "yield n;")
                                    public int dispatch(Object x) {
                                        return switch (x) {
                                            default -> -1;
                                        };
                                    }
                                }
                                """.formatted(PKG, PERMUTE_FQN)));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@PermuteSwitchArm");
        assertThat(compilation).hadErrorContaining("21");
    }

    @Test
    public void testPermuteSwitchArmAtJava21DoesNotError() {
        // Happy path: without -source 17 override (default is 21+ in this project),
        // @PermuteSwitchArm compiles without the source-level error.
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        PKG + ".SwitchArmOk2",
                        """
                                package %s;
                                import %s;
                                import io.quarkiverse.permuplate.PermuteSwitchArm;
                                @Permute(varName = "i", from = "3", to = "3", className = "SwitchArmOk${i}")
                                public class SwitchArmOk2 {
                                    @PermuteSwitchArm(varName = "k", from = "1", to = "1",
                                                     pattern = "Integer n",
                                                     body = "yield n;")
                                    public int dispatch(Object x) {
                                        return switch (x) {
                                            default -> -1;
                                        };
                                    }
                                }
                                """.formatted(PKG, PERMUTE_FQN)));

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile(PKG + ".SwitchArmOk3").isPresent()).isTrue();
    }

    /**
     * When all declared variables appear in {@code className}, no R1b error.
     */
    @Test
    public void testExtraVarsPresentInClassNameDoesNotTriggerR1b() {
        var compilation = compile(Callable2.class, "Foo2x",
                """
                        package %s;
                        import %s;
                        import %s;
                        @Permute(varName = "i", from = "2", to = "3", className = "Foo${i}x${k}",
                                 extraVars = { @PermuteVar(varName = "k", from = "2", to = "3") })
                        public class Foo2x {}
                        """.formatted(PKG, PERMUTE_FQN, PERMUTE_VAR_FQN));

        assertThat(compilation).succeeded();
    }

}
