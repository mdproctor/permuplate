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
 * Tests that the APT annotation processor correctly guards against the use of
 * {@code @Permute(inline = true)} and related attributes.
 *
 * <p>
 * Inline generation — writing permuted classes as nested siblings inside an
 * augmented parent — requires {@code permuplate-maven-plugin}, which runs
 * before javac and can rewrite existing source files. The APT processor is
 * fundamentally incapable of this (it can only create new files), so it must
 * reject {@code inline = true} with a clear, actionable error message that
 * directs users to the Maven plugin.
 *
 * <p>
 * Additionally, {@code keepTemplate = true} has no effect without
 * {@code inline = true}; the processor warns rather than errors to avoid
 * breaking builds that may have set it by mistake.
 */
public class AptInlineGuardTest {

    private static final String PKG = Callable2.class.getPackageName();
    private static final String PERMUTE_FQN = Permute.class.getName();

    private static Compilation compile(Class<?> packageAnchor, String simpleClassName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(
                        packageAnchor.getPackageName() + "." + simpleClassName, source));
    }

    /**
     * {@code @Permute(inline = true)} on a nested static class is rejected by the APT
     * with a message naming both the problem ({@code inline}) and the solution
     * ({@code permuplate-maven-plugin}).
     */
    @Test
    public void testInlineTrueOnAptIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        public class Foo2 {
                            @Permute(varName = "i", from = "3", to = "5",
                                     className = "Foo${i}", inline = true)
                            public static class FooNested {}
                        }
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("inline");
        assertThat(compilation).hadErrorContaining("permuplate-maven-plugin");
    }

    /**
     * {@code @Permute(inline = true)} on a top-level class is a configuration error
     * regardless of which tool processes it — there is no parent class to inline into.
     * The APT must report this with a message mentioning both {@code inline} and
     * {@code nested}.
     */
    @Test
    public void testInlineTrueOnTopLevelClassIsError() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5",
                                 className = "Foo${i}", inline = true)
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("inline");
        assertThat(compilation).hadErrorContaining("nested");
    }

    /**
     * {@code keepTemplate = true} without {@code inline = true} is meaningless —
     * there is no template class to retain in top-level generation. The processor
     * must warn rather than error so existing builds are not broken, but the
     * warning message must name {@code keepTemplate} so users can find and fix it.
     */
    @Test
    public void testKeepTemplateTrueWithInlineFalseIsWarning() {
        var compilation = compile(Callable2.class, "Foo2",
                """
                        package %s;
                        import %s;
                        @Permute(varName = "i", from = "3", to = "5",
                                 className = "Foo${i}", keepTemplate = true)
                        public class Foo2 {}
                        """.formatted(PKG, PERMUTE_FQN));

        // keepTemplate without inline should still succeed (just a warning)
        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("keepTemplate");
    }
}
