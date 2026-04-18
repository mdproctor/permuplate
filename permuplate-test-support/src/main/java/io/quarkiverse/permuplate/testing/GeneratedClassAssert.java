package io.quarkiverse.permuplate.testing;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Fluent assertions over a single generated class's source text.
 *
 * <p>
 * Obtain via {@link PermuplateAssertions#assertGenerated(com.google.testing.compile.Compilation, String)}.
 */
public final class GeneratedClassAssert {

    private final String className;
    private final String source;

    GeneratedClassAssert(String className, String source) {
        this.className = className;
        this.source = source;
    }

    /** Asserts the generated source contains {@code snippet}. */
    public GeneratedClassAssert contains(String snippet) {
        assertWithMessage("generated class %s", className)
                .that(source).contains(snippet);
        return this;
    }

    /** Asserts the generated source does NOT contain {@code snippet}. */
    public GeneratedClassAssert doesNotContain(String snippet) {
        assertWithMessage("generated class %s", className)
                .that(source).doesNotContain(snippet);
        return this;
    }

    /**
     * Asserts the generated source contains a field declaration matching {@code typeAndName}.
     * Example: {@code hasField("Callable3 c3")} checks for {@code "Callable3 c3"}.
     */
    public GeneratedClassAssert hasField(String typeAndName) {
        assertWithMessage("generated class %s should have field [%s]", className, typeAndName)
                .that(source).contains(typeAndName);
        return this;
    }

    /**
     * Asserts the generated source contains a method whose signature starts with {@code methodPrefix}.
     * Example: {@code hasMethod("join(")} checks for any method named {@code join}.
     */
    public GeneratedClassAssert hasMethod(String methodPrefix) {
        assertWithMessage("generated class %s should have method starting with [%s]", className, methodPrefix)
                .that(source).contains(methodPrefix);
        return this;
    }

    /**
     * Asserts the generated source contains a switch case for integer {@code label}.
     * Example: {@code hasCase(3)} checks for {@code "case 3:"}.
     */
    public GeneratedClassAssert hasCase(int label) {
        String caseLabel = "case " + label + ":";
        assertWithMessage("generated class %s should have [%s]", className, caseLabel)
                .that(source).contains(caseLabel);
        return this;
    }

    /**
     * Asserts the generated source contains an import for {@code fqn}.
     * Example: {@code hasImport("java.util.List")} checks for {@code "import java.util.List;"}.
     */
    public GeneratedClassAssert hasImport(String fqn) {
        String importStmt = "import " + fqn + ";";
        assertWithMessage("generated class %s should have [%s]", className, importStmt)
                .that(source).contains(importStmt);
        return this;
    }

    /**
     * Asserts that no Permuplate annotation appears in the generated source.
     * Checks for common annotation simple names.
     */
    public GeneratedClassAssert hasNoPermuplateAnnotations() {
        for (String ann : new String[] {
                "@Permute", "@PermuteAnnotation", "@PermuteCase", "@PermuteConst",
                "@PermuteDeclr", "@PermuteDelegate", "@PermuteExtends", "@PermuteFilter",
                "@PermuteImport", "@PermuteMethod", "@PermuteParam", "@PermuteReturn",
                "@PermuteSource", "@PermuteStatements", "@PermuteThrows",
                "@PermuteTypeParam", "@PermuteValue", "@PermuteVar" }) {
            assertWithMessage("generated class %s should not contain [%s]", className, ann)
                    .that(source).doesNotContain(ann);
        }
        return this;
    }

    /** Returns the raw generated source text for custom assertions. */
    public String source() {
        return source;
    }
}
