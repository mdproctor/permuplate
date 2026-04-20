package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteReturnReplaceLastTest {

    @Test
    public void testReplaceLastTypeArgWithReplacesLastParam() {
        // replaceLastTypeArgWith="END" replaces the last type param with an existing class param.
        // Uses a type param already on the class so the generated code compiles cleanly.
        var source = JavaFileObjects.forSourceString("io.ex.Narrow1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="3", className="Narrow${i}")
                        public class Narrow1<END,
                                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="T${k}") T1> {
                            @PermuteReturn(className="Narrow${i}", alwaysEmit=true, replaceLastTypeArgWith="END")
                            public Object narrow() { return null; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        // Narrow2<END, T1> → narrow() returns Narrow2<END, END>
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Narrow2").orElseThrow());
        assertThat(src2).contains("Narrow2<END, END> narrow()");
        // Narrow3<END, T1, T2> → narrow() returns Narrow3<END, T1, END>
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Narrow3").orElseThrow());
        assertThat(src3).contains("Narrow3<END, T1, END> narrow()");
    }

    @Test
    public void testReplaceLastTypeArgWithMethodTypeParam() {
        // The replacement value "T" is the method's own type parameter — most common use.
        // Body returns null so the generated code compiles regardless of the return type.
        var source = JavaFileObjects.forSourceString("io.ex.TypeNarrow1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="2", to="2", className="TypeNarrow${i}")
                        public class TypeNarrow1<END,
                                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="A${k}") A1> {
                            @SuppressWarnings({"unchecked","varargs"})
                            @PermuteReturn(className="TypeNarrow${i}", alwaysEmit=true, replaceLastTypeArgWith="T")
                            public <T> Object narrow(T... cls) { return null; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.TypeNarrow2").orElseThrow());
        // TypeNarrow2<END, A1> → narrow() returns TypeNarrow2<END, T>
        assertThat(src).contains("TypeNarrow2<END, T> narrow(");
    }
}
