package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteImportTest {

    // Helper source for a class that the generated import will refer to.
    // The evaluated import "io.permuplate.helper.Helper${i}" must resolve at compile time,
    // so we include these classes as additional sources in the compilation.
    private static javax.tools.JavaFileObject helperSource(int n) {
        return JavaFileObjects.forSourceString(
                "io.permuplate.helper.Helper" + n,
                "package io.permuplate.helper; public class Helper" + n + " {}");
    }

    @Test
    public void testImportAddedPerPermutation() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Gen2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteImport;
                        @Permute(varName="i", from="3", to="4", className="Gen${i}")
                        @PermuteImport("io.permuplate.helper.Helper${i}")
                        public class Gen2 {}
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source, helperSource(3), helperSource(4));

        assertThat(compilation).succeeded();

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Gen3").orElseThrow());
        assertThat(src3).contains("import io.permuplate.helper.Helper3");
        assertThat(src3).doesNotContain("@PermuteImport");

        String src4 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Gen4").orElseThrow());
        assertThat(src4).contains("import io.permuplate.helper.Helper4");
    }

    @Test
    public void testMultipleImportsRepeatable() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Multi2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteImport;
                        @Permute(varName="i", from="3", to="3", className="Multi${i}")
                        @PermuteImport("io.permuplate.helper.A${i}")
                        @PermuteImport("io.permuplate.helper.B${i}")
                        public class Multi2 {}
                        """);

        // Define A3 and B3 as real classes so the generated import resolves
        var a3 = JavaFileObjects.forSourceString(
                "io.permuplate.helper.A3",
                "package io.permuplate.helper; public class A3 {}");
        var b3 = JavaFileObjects.forSourceString(
                "io.permuplate.helper.B3",
                "package io.permuplate.helper; public class B3 {}");

        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source, a3, b3);

        assertThat(compilation).succeeded();

        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Multi3").orElseThrow());
        assertThat(src).contains("import io.permuplate.helper.A3");
        assertThat(src).contains("import io.permuplate.helper.B3");
    }
}
