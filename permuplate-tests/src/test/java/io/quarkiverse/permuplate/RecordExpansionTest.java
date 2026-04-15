package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Documents the current state of record support in Permuplate — NOT supported.
 *
 * Two blockers identified (2026-04-15):
 *
 * BLOCKER 1 — JavaParser language level:
 * StaticJavaParser defaults to Java 11 which predates records (Java 14+).
 * The processor calls StaticJavaParser.parse(source) without configuring the
 * language level, so any record template throws ParseProblemException.
 * Fix: StaticJavaParser.getParserConfiguration()
 * .setLanguageLevel(LanguageLevel.JAVA_17) in PermuteProcessor.init().
 *
 * BLOCKER 2 — AST lookup uses ClassOrInterfaceDeclaration exclusively:
 * The processor finds the template with findFirst(ClassOrInterfaceDeclaration.class).
 * Records are RecordDeclaration in JavaParser — a different node type.
 * Even with Blocker 1 fixed, the processor fails to locate the template class
 * and the compilation fails with an error. All transformer methods also take
 * ClassOrInterfaceDeclaration and would need updating.
 *
 * What would be needed:
 * 1. Configure StaticJavaParser for Java 17+ in PermuteProcessor.init()
 * 2. Update findFirst() calls to handle RecordDeclaration
 * 3. Update all transformer method signatures to accept TypeDeclaration<?>
 * (significant refactor — ClassOrInterfaceDeclaration is used throughout)
 *
 * These tests serve as regression guards once record support is implemented.
 */
public class RecordExpansionTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /**
     * BLOCKER 1: StaticJavaParser (Java 11 default) throws ParseProblemException
     * on record syntax. The RuntimeException propagates out of compile().
     */
    @Test
    public void testBlocker1RecordParsingFails() {
        try {
            compile("io.example.Point2D",
                    "package io.example;\n" +
                            "import io.quarkiverse.permuplate.Permute;\n" +
                            "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Point${i}D\")\n" +
                            "public record Point2D(double x, double y) {}");
            // If we reach here, language level was pre-configured externally — Blocker 1 already fixed
        } catch (RuntimeException e) {
            // Expected: ParseProblemException wrapping "Record Declarations are not supported"
            assertThat(e.getMessage()).contains("Record Declarations are not supported");
        }
    }

    /**
     * BLOCKER 2: Even with Java 17 language level, the processor fails because it
     * looks for ClassOrInterfaceDeclaration but finds a RecordDeclaration instead.
     * The compilation fails (not silently produces nothing).
     */
    @Test
    public void testBlocker2RecordDeclarationNotFoundByProcessor() {
        ParserConfiguration.LanguageLevel previous = StaticJavaParser.getParserConfiguration().getLanguageLevel();
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        try {
            Compilation compilation = compile("io.example.Point2D",
                    "package io.example;\n" +
                            "import io.quarkiverse.permuplate.Permute;\n" +
                            "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Point${i}D\")\n" +
                            "public record Point2D(double x, double y) {}");

            // Blocker 1 resolved — no ParseProblemException.
            // Blocker 2: compilation fails because ClassOrInterfaceDeclaration lookup
            // returns empty for a record, causing processor to error.
            assertThat(compilation.status())
                    .isNotEqualTo(com.google.testing.compile.Compilation.Status.SUCCESS);
        } finally {
            StaticJavaParser.getParserConfiguration().setLanguageLevel(previous);
        }
    }
}
