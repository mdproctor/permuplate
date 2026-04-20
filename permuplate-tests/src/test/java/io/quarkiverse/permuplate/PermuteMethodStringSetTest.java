package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteMethodStringSetTest {

    @Test
    public void testStringValuesGenerateOneOverloadPerValue() {
        // Happy path: values={"Sync","Async"} generates two overloads per arity.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Proto2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from="1", to="1", className="Proto${i}")
                        public class Proto2 {
                            @PermuteMethod(varName="T", values={"Sync","Async"},
                                           name="${capitalize(T)}Run")
                            @PermuteReturn(className="Proto${i}", typeArgs="", alwaysEmit=true)
                            public Object runTemplate() { return this; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Proto1").orElseThrow());

        assertThat(src).contains("SyncRun()");
        assertThat(src).contains("AsyncRun()");
        assertThat(src).doesNotContain("runTemplate");
        assertThat(src).doesNotContain("@PermuteMethod");
    }

    @Test
    public void testStringVariableAvailableInMethodBody() {
        // The string variable T must be available in @PermuteBody body templates.
        // Return type is String so the body can return "${T}" without type mismatch.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Mode2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        import io.quarkiverse.permuplate.PermuteBody;
                        @Permute(varName="i", from="1", to="1", className="Mode${i}")
                        public class Mode2 {
                            @PermuteMethod(varName="T", values={"fast","slow"},
                                           name="${T}Mode")
                            @PermuteBody(body="{ return \\"${T}\\"; }")
                            public String modeTemplate() { return ""; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Mode1").orElseThrow());

        assertThat(src).contains("fastMode()");
        assertThat(src).contains("slowMode()");
        assertThat(src).contains("\"fast\"");
        assertThat(src).contains("\"slow\"");
    }

    @Test
    public void testCapitalizeWorksWithStringVariable() {
        // capitalize(T) must work with a string variable from values={}.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Builder2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from="1", to="1", className="Builder${i}")
                        public class Builder2 {
                            @PermuteMethod(varName="kind", values={"widget","gadget"},
                                           name="build${capitalize(kind)}")
                            @PermuteReturn(className="Builder${i}", typeArgs="", alwaysEmit=true)
                            public Object buildTemplate() { return this; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Builder1").orElseThrow());
        assertThat(src).contains("buildWidget()");
        assertThat(src).contains("buildGadget()");
    }

    @Test
    public void testOuterStringSetWithInnerStringSetValues() {
        // Regression: when outer @Permute uses values={} (string-set mode),
        // @PermuteMethod with values={} must not throw ClassCastException
        // from casting the outer string variable to Number.
        // Template class "Widget1" contains the literal "Widget" from className="${style}Widget",
        // satisfying R2 validation. Generated classes "BoldWidget"/"LightWidget" don't collide.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Widget1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="style", values={"Bold","Light"}, className="${style}Widget")
                        public class Widget1 {
                            @PermuteMethod(varName="T", values={"Sync","Async"},
                                           name="${T}Run")
                            @PermuteReturn(className="${style}Widget", typeArgs="", alwaysEmit=true)
                            public Object runTemplate() { return this; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String boldSrc = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.BoldWidget").orElseThrow());
        assertThat(boldSrc).contains("SyncRun()");
        assertThat(boldSrc).contains("AsyncRun()");

        String lightSrc = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.LightWidget").orElseThrow());
        assertThat(lightSrc).contains("SyncRun()");
        assertThat(lightSrc).contains("AsyncRun()");
    }

    @Test
    public void testEmptyValuesUsesIntegerRange() {
        // Regression: values={} (default) must still use from/to integer range.
        // Template is named Legacy1 (outside the from=2..to=3 range) to avoid collision.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Legacy1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteMethod;
                        import io.quarkiverse.permuplate.PermuteReturn;
                        @Permute(varName="i", from="2", to="3", className="Legacy${i}")
                        public class Legacy1 {
                            @PermuteMethod(varName="j", from="1", to="${i-1}", name="op${j}")
                            @PermuteReturn(className="Legacy${i}", typeArgs="", alwaysEmit=true)
                            public Object opTemplate() { return this; }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src2 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Legacy2").orElseThrow());
        assertThat(src2).contains("op1()");
        assertThat(src2).doesNotContain("op2()");

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Legacy3").orElseThrow());
        assertThat(src3).contains("op1()");
        assertThat(src3).contains("op2()");
    }
}
