package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import java.util.Optional;

import javax.tools.JavaFileObject;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Tests for {@code macros=} attribute on {@code @PermuteMethod}.
 *
 * <p>
 * These tests verify that macros are evaluated with the inner variable in scope and
 * that later macros can reference earlier ones. The generated source is checked
 * directly (via reflection into the compilation result) rather than requiring
 * full compilation success, since the type arguments use alpha placeholders
 * ({@code A}, {@code B}) that are not declared as type parameters on the generated
 * classes.
 */
public class PermuteMethodMacrosTest {

    /** Reads a generated source file from a compilation, even if compilation failed. */
    @SuppressWarnings("unchecked")
    private static String generatedSource(Compilation c, String fqn) {
        try {
            java.lang.reflect.Field f = c.getClass().getDeclaredField("generatedFiles");
            f.setAccessible(true);
            ImmutableList<JavaFileObject> files = (ImmutableList<JavaFileObject>) f.get(c);
            Optional<JavaFileObject> match = files.stream()
                    .filter(file -> file.getName().contains(fqn.replace('.', '/') + ".java"))
                    .findFirst();
            return match.map(ProcessorTestSupport::sourceOf)
                    .orElseThrow(() -> new AssertionError(fqn + " was not generated"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testPermuteMethodMacroEvaluatedWithInnerVariable() {
        // Verify that macros= are evaluated with the inner variable (n) in scope.
        // tail = typeArgList(1, n, 'alpha') produces "A" for n=1 and "A, B" for n=2.
        // The generated source text is checked directly — the types are valid Java
        // type argument text even though they are not declared on the outer class.
        // Template is Mm0 (not in generated set Mm1..Mm2).
        var source = JavaFileObjects.forSourceString("io.ex.Mm0",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="1", to="2", className="Mm${i}",
                                 strings={"maxN=2"})
                        public class Mm0 {
                            @PermuteMethod(varName="n", from="1", to="${maxN - i}",
                                           name="level${n}",
                                           macros={"tail=typeArgList(1,n,'alpha')"})
                            @PermuteReturn(className="Mm${i+n}", typeArgs="tail",
                                           alwaysEmit=true)
                            public Object levelTemplate() { return null; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        // Mm1 (i=1): n goes 1..1 → level1() returns Mm2<A>
        String src1 = generatedSource(c, "io.ex.Mm1");
        assertThat(src1).contains("Mm2<A> level1()");
    }

    @Test
    public void testPermuteMethodMacroChaining() {
        // Verify later macros can reference earlier ones within the same @PermuteMethod.
        // tip = typeArgList(1,n,'alpha'), prev = typeArgList(1,n-1,'alpha').
        // For n=2: tip="A, B", prev="A" → typeArgs = "tip + ',' + prev" = "A, B,A".
        // Template is Mc0 (not in generated set Mc1).
        var source = JavaFileObjects.forSourceString("io.ex.Mc0",
                """
                        package io.ex;
                        import io.quarkiverse.permuplate.*;
                        @Permute(varName="i", from="1", to="1", className="Mc${i}")
                        public class Mc0 {
                            @PermuteMethod(varName="n", from="2", to="2", name="op${n}",
                                           macros={"tip=typeArgList(1,n,'alpha')",
                                                   "prev=typeArgList(1,n-1,'alpha')"})
                            @PermuteReturn(className="Mc${i}", typeArgs="tip + ',' + prev",
                                           alwaysEmit=true)
                            public Object opTemplate() { return null; }
                        }
                        """);
        Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
        // n=2: tip="A, B", prev="A" → typeArgs="A, B,A" → return type Mc1<A, B, A>
        // (JavaParser normalises spacing around commas in the type arg list)
        String src1 = generatedSource(c, "io.ex.Mc1");
        assertThat(src1).contains("op2()");
        assertThat(src1).contains("Mc1<A, B, A>");
    }
}
