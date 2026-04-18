package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class EnumGenerationTest {

    @Test
    public void testBasicEnumRename() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Color1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        @Permute(varName="i", from="2", to="3", className="Color${i}")
                        public enum Color1 {
                            RED, GREEN, BLUE;
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Color2").orElseThrow());
        assertThat(src2).contains("enum Color2");
        assertThat(src2).contains("RED");
        assertThat(src2).contains("GREEN");
        assertThat(src2).contains("BLUE");
        assertThat(src2).doesNotContain("@Permute");

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Color3").orElseThrow());
        assertThat(src3).contains("enum Color3");
    }

    @Test
    public void testEnumConstExpansion() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Priority1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteEnumConst;
                        @Permute(varName="i", from="2", to="3", className="Priority${i}")
                        public enum Priority1 {
                            LOW,
                            MED,
                            @PermuteEnumConst(varName="k", from="3", to="${i}", name="LEVEL${k}")
                            HIGH_PLACEHOLDER;
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // Priority2: from=3 to=2 → empty range → sentinel removed, no extra constants
        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Priority2").orElseThrow());
        assertThat(src2).contains("LOW");
        assertThat(src2).contains("MED");
        assertThat(src2).doesNotContain("HIGH_PLACEHOLDER");
        assertThat(src2).doesNotContain("LEVEL");
        assertThat(src2).doesNotContain("@PermuteEnumConst");

        // Priority3: from=3 to=3 → LEVEL3
        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Priority3").orElseThrow());
        assertThat(src3).contains("LOW");
        assertThat(src3).contains("MED");
        assertThat(src3).contains("LEVEL3");
        assertThat(src3).doesNotContain("HIGH_PLACEHOLDER");
        assertThat(src3).doesNotContain("@PermuteEnumConst");
    }

    @Test
    public void testEnumConstExpansionWithArgs() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Status1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteEnumConst;
                        @Permute(varName="i", from="2", to="2", className="Status${i}")
                        public enum Status1 {
                            FIRST(1),
                            @PermuteEnumConst(varName="k", from="2", to="${i}", name="ITEM${k}", args="${k}")
                            PLACEHOLDER(99);
                            private final int code;
                            Status1(int code) { this.code = code; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Status2").orElseThrow());
        assertThat(src).contains("FIRST(1)");
        assertThat(src).contains("ITEM2(2)");
        assertThat(src).doesNotContain("PLACEHOLDER");
    }
}
