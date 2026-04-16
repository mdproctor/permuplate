package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * Shared infrastructure for {@link ProcessorTest}: compilation helpers,
 * reflection utilities, and the capturing-proxy machinery used for
 * behavioural verification of generated classes.
 */
class ProcessorTestSupport {

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * A null-returning {@link Proxy} paired with the {@link List} into which every
     * invocation's arguments are recorded. Create via {@link #capturingProxy}.
     */
    record Capture(Object proxy, List<Object> args) {
    }

    /**
     * A generated Join-pattern instance with a capturing Callable proxy already
     * installed and the {@code right} list pre-populated. Call {@link #invoke} to
     * drive the join method, then inspect {@link #captured} for what the callable
     * received.
     */
    static final class JoinFixture {
        final Object instance;
        final List<Object> captured;

        JoinFixture(Object instance, List<Object> captured) {
            this.instance = instance;
            this.captured = captured;
        }

        Object invoke(String methodName, Object... args) {
            return invokeMethod(instance, methodName, args);
        }
    }

    // =========================================================================
    // Compilation helpers
    // =========================================================================

    static String sourceOf(JavaFileObject file) {
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a real Java template class from {@code src/test/java/} and returns it
     * as a {@link JavaFileObject}. File path and binary name are derived entirely
     * from the {@link Class} object. Nested classes are handled by walking up to
     * the top-level enclosing class, so callers can pass the actual template class
     * (e.g. {@code JoinLibrary.FilterJoin2.class}).
     */
    static JavaFileObject templateSource(Class<?> clazz) {
        Class<?> topLevel = clazz;
        while (topLevel.getEnclosingClass() != null) {
            topLevel = topLevel.getEnclosingClass();
        }
        Path base = Paths.get("src", "test", "java");
        for (String segment : topLevel.getPackageName().split("\\.")) {
            base = base.resolve(segment);
        }
        try {
            return JavaFileObjects.forSourceString(topLevel.getName(),
                    Files.readString(base.resolve(topLevel.getSimpleName() + ".java")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Generates a {@code Callable{n}} source in the same package as the template class. */
    static JavaFileObject callableSource(Class<?> templateClass, int n) {
        String pkg = templateClass.getPackageName();
        StringBuilder typeParams = new StringBuilder();
        StringBuilder params = new StringBuilder();
        for (int j = 1; j <= n; j++) {
            char letter = (char) ('A' + j - 1);
            if (j > 1) {
                typeParams.append(", ");
                params.append(", ");
            }
            typeParams.append(letter);
            params.append(letter).append(" ").append((char) ('a' + j - 1));
        }
        return JavaFileObjects.forSourceString(
                pkg + ".Callable" + n,
                """
                        package %s;
                        public interface Callable%d<%s> {
                            void call(%s);
                        }
                        """.formatted(pkg, n, typeParams, params));
    }

    /** Compiles a real template class with the processor and the necessary Callable support sources. */
    static Compilation compileTemplate(Class<?> templateClass, int from, int to) {
        List<JavaFileObject> all = new ArrayList<>();
        all.add(templateSource(templateClass));
        IntStream.rangeClosed(from, to)
                .mapToObj(n -> callableSource(templateClass, n))
                .forEach(all::add);
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(all);
    }

    /**
     * Extracts all compiled class files from a {@link Compilation} and returns a
     * {@link ClassLoader} that can load them, enabling behavioural verification.
     * The parent is the test classloader so standard types resolve normally.
     */
    static ClassLoader classLoaderFor(Compilation compilation) {
        Map<String, byte[]> classBytes = new HashMap<>();
        for (JavaFileObject file : compilation.generatedFiles()) {
            if (file.getKind() != JavaFileObject.Kind.CLASS)
                continue;
            String className = file.getName()
                    .replaceAll("^.*?CLASS_OUTPUT.", "")
                    .replace('/', '.')
                    .replaceAll("\\.class$", "");
            try (InputStream in = file.openInputStream()) {
                classBytes.put(className, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new ClassLoader(ProcessorTestSupport.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classBytes.get(name);
                if (bytes == null)
                    throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    // =========================================================================
    // Reflection utilities
    // =========================================================================

    /** Loads a class from {@code loader} and creates a new no-arg instance. */
    static Object newInstance(ClassLoader loader, String className) {
        try {
            return loader.loadClass(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Sets a private field by name on {@code instance}. */
    static void setField(Object instance, String fieldName, Object value) {
        try {
            Field f = instance.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(instance, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the named declared field of {@code clazz} with accessibility set.
     * Useful when the field name is known (e.g. derived from the arity).
     */
    static Field findField(Class<?> clazz, String name) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the declared field whose type simple-name starts with {@code "Callable"},
     * with accessibility already set. This field holds the permuted callable in every
     * generated Join-pattern class.
     */
    static Field findCallableField(Class<?> clazz) {
        Field f = Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> field.getType().getSimpleName().startsWith("Callable"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Callable field in " + clazz.getSimpleName()));
        f.setAccessible(true);
        return f;
    }

    /** Finds {@code methodName} on {@code instance}'s class and invokes it with {@code args}. */
    static Object invokeMethod(Object instance, String methodName, Object... args) {
        try {
            Method m = Arrays.stream(instance.getClass().getMethods())
                    .filter(x -> x.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No method: " + methodName));
            return m.invoke(instance, (Object[]) args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // Capturing proxy
    // =========================================================================

    /**
     * Creates a {@link Proxy} implementing {@code iface} that records all arguments
     * of every invocation into {@link Capture#args} and returns {@code null}.
     */
    static Capture capturingProxy(ClassLoader loader, Class<?> iface) {
        List<Object> args = new ArrayList<>();
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { iface },
                (p, m, a) -> {
                    if (a != null)
                        Collections.addAll(args, a);
                    return null;
                });
        return new Capture(proxy, args);
    }

    // =========================================================================
    // Join-pattern fixture
    // =========================================================================

    /**
     * General fixture builder: loads {@code className}, instantiates it, installs a
     * capturing proxy in the {@code Callable{i}} field, and sets the named iterable
     * field to {@code items}. Use this when the template's iterable field has a
     * domain-specific name (e.g. {@code "catalogue"}, {@code "sinks"}, {@code "rules"})
     * rather than the generic {@code "right"}.
     */
    static JoinFixture prepareFixture(ClassLoader loader, String className,
            String iterableFieldName, List<Object> items) {
        try {
            Object instance = newInstance(loader, className);
            Field callableField = findCallableField(instance.getClass());
            var capture = capturingProxy(loader, callableField.getType());
            callableField.set(instance, capture.proxy());
            setField(instance, iterableFieldName, items);
            return new JoinFixture(instance, capture.args());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convenience wrapper for templates whose iterable field is named {@code "right"}.
     */
    static JoinFixture prepareJoin(ClassLoader loader, String className, List<Object> right) {
        return prepareFixture(loader, className, "right", right);
    }

    /**
     * Loads the named Callable interface from {@code loader}, creates a capturing
     * proxy, invokes {@code call} with {@code args}, and returns what was received.
     * An arity mismatch causes {@link Method#invoke} to throw immediately.
     */
    static List<Object> invokeCallable(ClassLoader loader, String className, Object... args) {
        try {
            Class<?> clazz = loader.loadClass(className);
            var capture = capturingProxy(loader, clazz);
            Arrays.stream(clazz.getMethods())
                    .filter(m -> m.getName().equals("call"))
                    .findFirst().orElseThrow()
                    .invoke(capture.proxy(), (Object[]) args);
            return capture.args();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // Structural + behavioural assertion helper
    // =========================================================================

    /**
     * Returns the FQN of the class generated from {@code templateClass} at arity
     * {@code i}. Template classes end in a digit; that digit is replaced with {@code i}.
     */
    static String generatedClassName(Class<?> templateClass, int i) {
        String simple = templateClass.getSimpleName();
        if (!Character.isDigit(simple.charAt(simple.length() - 1)))
            throw new IllegalArgumentException("Expected a template class ending in a digit, got: " + simple);
        return templateClass.getPackageName() + "." + simple.substring(0, simple.length() - 1) + i;
    }

    /**
     * Structurally verifies the generated Join{i} source (field, for-each, no leftover
     * annotations), then behaviourally calls its {@code left} method with {@code (i-1)}
     * string arguments and a single {@code "R"} right-side element, asserting all were
     * captured by the callable in order.
     *
     * <p>
     * The call-site string (e.g. {@code c3.call(a, b, c)}) is intentionally absent
     * from the structural checks — the behavioural assertion makes it redundant.
     */
    static void assertJoinN(Compilation compilation, ClassLoader loader,
            Class<?> templateClass, int i) {
        String className = generatedClassName(templateClass, i);
        String src = sourceOf(compilation.generatedSourceFile(className)
                .orElseThrow(() -> new AssertionError(className + ".java was not generated")));

        // For Join3 (i=3): type param C (A+2), var name c (a+2)
        char typeChar = (char) ('A' + i - 1);
        char varChar = (char) ('a' + i - 1);

        assertThat(src).contains("Callable" + i + "<"); // generic callable present
        assertThat(src).contains(" c" + i); // field renamed (c2→c3 etc.)
        assertThat(src).doesNotContain("Callable2");
        assertThat(src).doesNotContain("c2;");
        assertThat(src).contains("for (" + typeChar + " " + varChar + " : right)");
        assertThat(src).doesNotContain("@PermuteDeclr");
        assertThat(src).doesNotContain("@PermuteParam");
        assertThat(src).doesNotContain("@Permute");

        // Behavioral: invoke left(arg1..arg{i-1}) with right=[lastArg]; callable should capture all
        Object[] leftArgs = IntStream.rangeClosed(1, i - 1).mapToObj(j -> "arg" + j).toArray();
        var fixture = prepareJoin(loader, className, List.of("R"));
        fixture.invoke("left", leftArgs);

        List<Object> expected = new ArrayList<>(Arrays.asList(leftArgs));
        expected.add("R");
        assertThat(fixture.captured).containsExactlyElementsIn(expected).inOrder();
    }
}
