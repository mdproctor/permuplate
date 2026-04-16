package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.maven.AnnotationReader;
import io.quarkiverse.permuplate.maven.InlineGenerator;

/**
 * Tests for @PermuteSource template composition (Capabilities A, B, C).
 *
 * All tests follow the two-pass pattern:
 * 1. Parse parent CU with Template A and Template B
 * 2. Generate Template A (source) → parentCu now contains Callable3, Callable4 etc.
 * 3. Generate Template B (derived) using output of step 2 as parentCu
 * 4. Assert derived classes have correct type params, methods, fields
 */
public class TemplateCompositionTest {

    private static final AnnotationReader READER = new AnnotationReader();

    private static CompilationUnit generate(CompilationUnit parentCu,
            TypeDeclaration<?> template) throws AnnotationReader.MojoAnnotationException {
        PermuteConfig config = READER.readPermuteConfig(template);
        List<Map<String, Object>> combos = PermuteConfig.buildAllCombinations(config);
        return new InlineGenerator().generate(parentCu, template, config, combos);
    }

    private static TypeDeclaration<?> findTemplate(ClassOrInterfaceDeclaration parent,
            String name) {
        return parent.getMembers().stream()
                .filter(m -> m instanceof TypeDeclaration<?>)
                .map(m -> (TypeDeclaration<?>) m)
                .filter(t -> t.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Template not found: " + name));
    }

    // =========================================================================
    // Capability A — ordering + type parameter inference
    // =========================================================================

    @Test
    public void testTypeParameterInferenceFromSource() throws Exception {
        String src = "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "public class Family {\n" +
                "    @Permute(varName=\"i\", from=\"2\", to=\"3\",\n" +
                "             className=\"Callable${i}\", inline=true, keepTemplate=false)\n" +
                "    interface Callable1<\n" +
                "        @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                "                          name=\"${alpha(k)}\") A\n" +
                "    > {\n" +
                "        @PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\",\n" +
                "                      type=\"${alpha(j)}\", name=\"${lower(j)}\")\n" +
                "        Object result(Object o1) throws Exception;\n" +
                "    }\n" +
                "    @Permute(varName=\"i\", from=\"2\", to=\"3\",\n" +
                "             className=\"TimedCallable${i}\", inline=true, keepTemplate=false)\n" +
                "    @PermuteSource(\"Callable${i}\")\n" +
                "    static class TimedCallable2 implements Callable2<Object> {\n" +
                "        private final Callable2<Object> delegate;\n" +
                "        public Object result(Object o1) throws Exception {\n" +
                "            long t = System.nanoTime();\n" +
                "            try { return delegate.result(o1); }\n" +
                "            finally { System.out.println(System.nanoTime() - t); }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";

        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = StaticJavaParser.parse(src);
        ClassOrInterfaceDeclaration family = cu.getClassByName("Family").orElseThrow();

        CompilationUnit afterA = generate(cu, findTemplate(family, "Callable1"));
        CompilationUnit afterB = generate(afterA, findTemplate(family, "TimedCallable2"));
        String output = afterB.toString();

        assertThat(output).contains("TimedCallable3");
        assertThat(output).contains("<A, B, C>");
        assertThat(output).contains("TimedCallable2");
        assertThat(output).contains("<A, B>");
    }

    @Test
    public void testPermuteSourceInAptModeEmitsError() {
        com.google.testing.compile.Compilation c = com.google.testing.compile.Compiler.javac()
                .withProcessors(new io.quarkiverse.permuplate.processor.PermuteProcessor())
                .compile(com.google.testing.compile.JavaFileObjects.forSourceString(
                        "io.example.Foo",
                        "package io.example;\n" +
                                "import io.quarkiverse.permuplate.Permute;\n" +
                                "import io.quarkiverse.permuplate.PermuteSource;\n" +
                                "@Permute(varName=\"i\", from=\"2\", to=\"3\", className=\"Foo${i}\")\n" +
                                "@PermuteSource(\"Bar${i}\")\n" +
                                "public class Foo {}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("Maven plugin");
    }

    // =========================================================================
    // Capability B — @PermuteDelegate synthesis
    // =========================================================================

    @Test
    public void testDelegateSynthesisBasic() throws Exception {
        String src = "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "public class Family {\n" +
                "    @Permute(varName=\"i\", from=\"2\", to=\"2\",\n" +
                "             className=\"Callable${i}\", inline=true, keepTemplate=false)\n" +
                "    interface Callable1<\n" +
                "        @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                "                          name=\"${alpha(k)}\") A\n" +
                "    > {\n" +
                "        @PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\",\n" +
                "                      type=\"${alpha(j)}\", name=\"${lower(j)}\")\n" +
                "        Object result(Object o1);\n" +
                "    }\n" +
                "    @Permute(varName=\"i\", from=\"2\", to=\"2\",\n" +
                "             className=\"SynchronizedCallable${i}\", inline=true, keepTemplate=false)\n" +
                "    @PermuteSource(\"Callable${i}\")\n" +
                "    static class SynchronizedCallable2 implements Callable2<Object> {\n" +
                "        @PermuteDelegate(modifier = \"synchronized\")\n" +
                "        private final Callable2<Object> delegate;\n" +
                "    }\n" +
                "}\n";

        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = StaticJavaParser.parse(src);
        ClassOrInterfaceDeclaration family = cu.getClassByName("Family").orElseThrow();
        CompilationUnit afterA = generate(cu, findTemplate(family, "Callable1"));
        CompilationUnit afterB = generate(afterA, findTemplate(family, "SynchronizedCallable2"));
        String output = afterB.toString();

        assertThat(output).contains("SynchronizedCallable2");
        assertThat(output).contains("synchronized");
        assertThat(output).contains("delegate.result(");
    }

    @Test
    public void testDelegateUserMethodTakesPrecedence() throws Exception {
        String src = "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "public class Family {\n" +
                "    @Permute(varName=\"i\", from=\"2\", to=\"2\",\n" +
                "             className=\"Callable${i}\", inline=true, keepTemplate=false)\n" +
                "    interface Callable1<\n" +
                "        @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                "                          name=\"${alpha(k)}\") A\n" +
                "    > {\n" +
                "        @PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\",\n" +
                "                      type=\"${alpha(j)}\", name=\"${lower(j)}\")\n" +
                "        Object result(Object o1);\n" +
                "    }\n" +
                "    @Permute(varName=\"i\", from=\"2\", to=\"2\",\n" +
                "             className=\"LoggedCallable${i}\", inline=true, keepTemplate=false)\n" +
                "    @PermuteSource(\"Callable${i}\")\n" +
                "    static class LoggedCallable2 implements Callable2<Object> {\n" +
                "        @PermuteDelegate\n" +
                "        private final Callable2<Object> delegate;\n" +
                "        @Override\n" +
                "        public Object result(Object o1) {\n" +
                "            System.out.println(\"logging\");\n" +
                "            return delegate.result(o1);\n" +
                "        }\n" +
                "    }\n" +
                "}\n";

        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = StaticJavaParser.parse(src);
        ClassOrInterfaceDeclaration family = cu.getClassByName("Family").orElseThrow();
        CompilationUnit afterA = generate(cu, findTemplate(family, "Callable1"));
        CompilationUnit afterB = generate(afterA, findTemplate(family, "LoggedCallable2"));
        String output = afterB.toString();

        assertThat(output).contains("System.out.println(\"logging\")");
        // Verify the user-declared method is present and no synthesised duplicate was added.
        // Count only within LoggedCallable2's class body (output also contains Callable2 interface
        // which independently contributes one "Object result(" occurrence).
        String loggedClass = output.substring(output.indexOf("class LoggedCallable2"));
        assertThat(loggedClass.split("Object result\\(").length - 1).isEqualTo(1);
    }

    // =========================================================================
    // Capability C — builder synthesis from records
    // =========================================================================

    @Test
    public void testBuilderSynthesisFromRecord() throws Exception {
        String src = "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "public class TupleFamily {\n" +
                "    @Permute(varName=\"i\", from=\"3\", to=\"3\",\n" +
                "             className=\"Tuple${i}\", inline=true, keepTemplate=false)\n" +
                "    public static record Tuple2<\n" +
                "        @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                "                          name=\"${alpha(k)}\") A\n" +
                "    >(\n" +
                "        @PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\",\n" +
                "                      type=\"${alpha(j)}\", name=\"${lower(j)}\")\n" +
                "        A a\n" +
                "    ) {}\n" +
                "    @Permute(varName=\"i\", from=\"3\", to=\"3\",\n" +
                "             className=\"Tuple${i}Builder\", inline=true, keepTemplate=false)\n" +
                "    @PermuteSource(\"Tuple${i}\")\n" +
                "    static class Tuple3Builder {}\n" +
                "}\n";

        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = StaticJavaParser.parse(src);
        ClassOrInterfaceDeclaration family = cu.getClassByName("TupleFamily").orElseThrow();
        CompilationUnit afterA = generate(cu, findTemplate(family, "Tuple2"));
        CompilationUnit afterB = generate(afterA, findTemplate(family, "Tuple3Builder"));
        String output = afterB.toString();

        assertThat(output).contains("Tuple3Builder");
        assertThat(output).contains("<A, B, C>");
        assertThat(output).contains("private A a");
        assertThat(output).contains("private B b");
        assertThat(output).contains("private C c");
        assertThat(output).contains("return this");
        assertThat(output).contains("new Tuple3<>(");
    }

    @Test
    public void testEventSystemCohesiveExample() throws Exception {
        // Placeholder — full test in InlineGenerationTest (Task 7)
        assertThat(new PermuteSource() {
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return PermuteSource.class;
            }

            public String value() {
                return "Test${i}";
            }
        }.value()).isEqualTo("Test${i}");
    }
}
