package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.*;

import java.util.stream.IntStream;

import org.junit.Test;

import com.google.testing.compile.Compiler;

import io.quarkiverse.permuplate.example.Callable1;
import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Dog-fooding test: permuplate processes its own annotation templates to generate
 * the multi-arity {@code Callable} interfaces it depends on internally.
 * <p>
 * {@link Callable1} is annotated with {@code @Permute} and {@code @PermuteParam}
 * to generate {@code Callable2} through {@code Callable10} — the same interfaces
 * used as field types in every Join-pattern template. This test verifies that the
 * library can successfully describe and generate its own foundational types.
 */
public class DogFoodingTest {

    @Test
    public void testCallable1GeneratesCallable2Through10() {
        // Callable1 only uses Object, so no support sources are needed;
        // the processor expands @PermuteParam directly in the interface method.
        var compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(templateSource(Callable1.class));
        assertThat(compilation).succeeded();

        var loader = classLoaderFor(compilation);

        for (int i = 2; i <= 10; i++) {
            var className = generatedClassName(Callable1.class, i);

            // Structural: correct interface shape and parameter list
            var src = sourceOf(compilation.generatedSourceFile(className)
                    .orElseThrow(() -> new AssertionError(className + " was not generated")));
            assertThat(src).contains("public interface Callable" + i);
            assertThat(src).doesNotContain("public class");
            var params = new StringBuilder("void call(");
            for (int j = 1; j <= i; j++) {
                if (j > 1)
                    params.append(", ");
                params.append("Object o").append(j);
            }
            assertThat(src).contains(params.append(")").toString());
            assertThat(src).doesNotContain("@Permute");
            assertThat(src).doesNotContain("@PermuteParam");

            // Behavioural: call with i args, assert all received in order
            Object[] args = IntStream.rangeClosed(1, i).mapToObj(j -> "arg" + j).toArray();
            assertThat(invokeCallable(loader, className, args)).containsExactly(args).inOrder();
        }
    }
}
