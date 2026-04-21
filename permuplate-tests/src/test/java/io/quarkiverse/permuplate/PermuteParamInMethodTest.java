package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for {@code @PermuteParam} inside a {@code @PermuteMethod} clone.
 *
 * <p>
 * When {@code @PermuteParam(to="${m}")} appears on a parameter of a {@code @PermuteMethod}
 * method, the parameter list must expand with the inner variable {@code m} in scope, AND call
 * sites in the method body that reference the anchor parameter must be rewritten to include the
 * full expanded list.
 */
public class PermuteParamInMethodTest {

    /**
     * A {@code @PermuteMethod(varName="m", from="2", to="3")} method carries a
     * {@code @PermuteParam(to="${m}")} sentinel. For each clone:
     * <ul>
     * <li>m=2: parameter list is {@code (String v1, String v2, String extra)};
     * body call site becomes {@code java.util.List.of(v1, v2)}</li>
     * <li>m=3: parameter list is {@code (String v1, String v2, String v3, String extra)};
     * body call site becomes {@code java.util.List.of(v1, v2, v3)}</li>
     * </ul>
     */
    @Test
    public void testPermuteParamExpandsInsidePermuteMethod() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.test.Filter0",
                """
                        package io.permuplate.test;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="1", to="2", className="Filter${i}")
                        public class Filter0 {
                            @PermuteMethod(varName="m", from="2", to="3", name="log")
                            public void logVar(
                                @PermuteParam(varName="k", from="1", to="${m}",
                                              type="String", name="v${k}") String v1,
                                String extra) {
                                java.util.List.of(v1);
                            }
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Filter1 and Filter2 should each have two overloads: log(...m=2...) and log(...m=3...)
        for (int i = 1; i <= 2; i++) {
            String src = sourceOf(compilation
                    .generatedSourceFile("io.permuplate.test.Filter" + i).orElseThrow());

            // m=2 overload: two String params before extra, body uses v1, v2
            assertThat(src).contains("String v1, String v2, String extra");
            assertThat(src).contains("List.of(v1, v2)");

            // m=3 overload: three String params before extra, body uses v1, v2, v3
            assertThat(src).contains("String v1, String v2, String v3, String extra");
            assertThat(src).contains("List.of(v1, v2, v3)");

            // No leftover annotations
            assertThat(src).doesNotContain("@PermuteParam");
            assertThat(src).doesNotContain("@PermuteMethod");
        }
    }
}
