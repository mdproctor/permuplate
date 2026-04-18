package io.quarkiverse.permuplate.testing;

import java.io.IOException;
import java.util.stream.Collectors;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;

/**
 * Static entry point for fluent assertions over Permuplate-generated sources.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * PermuplateAssertions.assertGenerated(compilation, "io.example.Join3")
 *         .hasField("Callable3 c3")
 *         .hasMethod("join(")
 *         .hasCase(3)
 *         .doesNotContain("@PermuteCase");
 * </pre>
 */
public final class PermuplateAssertions {

    private PermuplateAssertions() {
    }

    /**
     * Entry point. Extracts the generated source for {@code className} from the compilation
     * result and returns a fluent assertion object.
     *
     * @param compilation the result of a Permuplate-annotated compilation
     * @param className the fully-qualified name of the generated class to assert on
     * @throws AssertionError if no generated source file exists for {@code className}
     */
    public static GeneratedClassAssert assertGenerated(Compilation compilation, String className) {
        JavaFileObject file = compilation.generatedSourceFile(className)
                .orElseThrow(() -> new AssertionError(
                        "No generated source file found for class: " + className
                                + "\nGenerated files: " + compilation.generatedSourceFiles()
                                        .stream().map(f -> f.getName()).collect(Collectors.joining(", "))));
        String source = sourceOf(file);
        return new GeneratedClassAssert(className, source);
    }

    private static String sourceOf(JavaFileObject file) {
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionError("Failed to read generated source for " + file.getName(), e);
        }
    }
}
