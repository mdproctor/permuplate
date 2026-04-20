package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteDefaultReturnTest {

    @Test
    public void testDefaultReturnAppliedToAllObjectMethods() {
        // Happy path: @PermuteDefaultReturn sets return type for all Object methods.
        var source = JavaFileObjects.forSourceString("io.ex.Chain1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @PermuteDefaultReturn(className = "Chain${i}", typeArgs = "")
                        @Permute(varName="i", from="2", to="3", className="Chain${i}")
                        public class Chain1 {
                            public Object step1() { return this; }
                            public Object step2() { return this; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Chain2").orElseThrow());
        assertThat(src2).contains("public Chain2 step1()");
        assertThat(src2).contains("public Chain2 step2()");
        assertThat(src2).doesNotContain("@PermuteDefaultReturn");
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Chain3").orElseThrow());
        assertThat(src3).contains("public Chain3 step1()");
    }

    @Test
    public void testExplicitPermuteReturnOverridesDefault() {
        // Explicit @PermuteReturn on a method takes precedence over the class default.
        var source = JavaFileObjects.forSourceString("io.ex.Builder1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @PermuteDefaultReturn(className = "Builder${i}", typeArgs = "")
                        @Permute(varName="i", from="2", to="2", className="Builder${i}")
                        public class Builder1 {
                            public Object step() { return this; }
                            @PermuteReturn(className="String", typeArgs="", alwaysEmit=true)
                            public Object name() { return "hello"; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.Builder2").orElseThrow());
        assertThat(src).contains("public Builder2 step()");
        assertThat(src).contains("public String name()");
    }

    @Test
    public void testNonObjectReturnTypeUnchanged() {
        // Methods with non-Object declared return types are never affected by the default.
        var source = JavaFileObjects.forSourceString("io.ex.Mixed1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @PermuteDefaultReturn(className = "Mixed${i}", typeArgs = "")
                        @Permute(varName="i", from="2", to="2", className="Mixed${i}")
                        public class Mixed1 {
                            public Object chain() { return this; }
                            public String label() { return "ok"; }
                            public int count()    { return 0; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src = sourceOf(c.generatedSourceFile("io.ex.Mixed2").orElseThrow());
        assertThat(src).contains("public Mixed2 chain()");
        assertThat(src).contains("public String label()");
        assertThat(src).contains("public int count()");
    }

    @Test
    public void testDefaultReturnWithTypeArgs() {
        // typeArgs= expression evaluated per permutation.
        var source = JavaFileObjects.forSourceString("io.ex.Typed1",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @PermuteDefaultReturn(className = "Typed${i}",
                                              typeArgs  = "'<END, ' + typeArgList(1, i-1, 'alpha') + '>'")
                        @Permute(varName="i", from="2", to="3", className="Typed${i}")
                        public class Typed1<END,
                                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="${alpha(k)}") A> {
                            public Object step() { return this; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        assertThat(c).succeeded();
        String src2 = sourceOf(c.generatedSourceFile("io.ex.Typed2").orElseThrow());
        assertThat(src2).contains("Typed2<END, A> step()");
        String src3 = sourceOf(c.generatedSourceFile("io.ex.Typed3").orElseThrow());
        assertThat(src3).contains("Typed3<END, A, B> step()");
    }
}
