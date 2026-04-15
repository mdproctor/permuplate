package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class StringSetIterationTest {

    private static Compilation compile(String qualifiedName, String source) {
        return Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(JavaFileObjects.forSourceString(qualifiedName, source));
    }

    /**
     * The string variable T is available in JEXL expressions throughout the template.
     *
     * @PermuteDeclr(type="${T}") renames a field type using the string value.
     */
    @Test
    public void testStringVariableAvailableInJexlExpressions() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteDeclr;\n" +
                        "@Permute(varName=\"T\", values={\"Byte\",\"Short\"},\n" +
                        "         className=\"To${T}Function\")\n" +
                        "public class ToTypeFunction {\n" +
                        "    @PermuteDeclr(type=\"${T}\")\n" +
                        "    private Object value;\n" +
                        "}");

        assertThat(compilation).succeeded();
        String byteSrc = ProcessorTestSupport.sourceOf(
                compilation.generatedSourceFile("io.example.ToByteFunction").get());
        assertThat(byteSrc).contains("Byte value");

        String shortSrc = ProcessorTestSupport.sourceOf(
                compilation.generatedSourceFile("io.example.ToShortFunction").get());
        assertThat(shortSrc).contains("Short value");
    }

    /**
     * Specifying both values and from is a compile error.
     */
    @Test
    public void testValuesAndFromToMutuallyExclusiveError() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "@Permute(varName=\"T\", values={\"Byte\"}, from=\"1\", to=\"3\",\n" +
                        "         className=\"To${T}Function\")\n" +
                        "public interface ToTypeFunction {}");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("mutually exclusive");
    }

    /**
     * Empty values array is a compile error.
     */
    @Test
    public void testEmptyValuesArrayIsError() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "@Permute(varName=\"T\", values={}, className=\"To${T}Function\")\n" +
                        "public interface ToTypeFunction {}");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("values");
    }

    /**
     * String-set iteration works in inline (Maven plugin) mode.
     * @Permute(values={"Byte","Short"}, inline=true) generates ToByteFunction and
     * ToShortFunction as nested siblings inside the parent class.
     */
    @Test
    public void testStringSetInInlineMode() throws Exception {
        String parentSrc = "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteDeclr;\n" +
                "public class Converters {\n" +
                "    @Permute(varName=\"T\", values={\"Byte\",\"Short\"},\n" +
                "             className=\"To${T}Function\", inline=true, keepTemplate=false)\n" +
                "    public static class ToTypeFunction {\n" +
                "        @PermuteDeclr(type=\"${T}\")\n" +
                "        private Object value;\n" +
                "    }\n" +
                "}";

        com.github.javaparser.ast.CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(parentSrc);
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration parent = cu.getClassByName("Converters").orElseThrow();
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration template = parent.getMembers().stream()
                .filter(m -> m instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)
                .map(m -> (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) m)
                .findFirst().orElseThrow();

        io.quarkiverse.permuplate.maven.AnnotationReader reader = new io.quarkiverse.permuplate.maven.AnnotationReader();
        io.quarkiverse.permuplate.core.PermuteConfig config = reader.readPermuteConfig(template);
        java.util.List<java.util.Map<String, Object>> allCombinations = io.quarkiverse.permuplate.core.PermuteConfig
                .buildAllCombinations(config);

        com.github.javaparser.ast.CompilationUnit result = new io.quarkiverse.permuplate.maven.InlineGenerator()
                .generate(cu, template, config, allCombinations);

        String output = result.toString();
        assertThat(output).contains("ToByteFunction");
        assertThat(output).contains("ToShortFunction");
        assertThat(output).doesNotContain("ToTypeFunction"); // keepTemplate=false
        assertThat(output).contains("Byte value"); // @PermuteDeclr applied
        assertThat(output).contains("Short value");
    }

    /**
     * @PermuteVar cannot have both values and from/to — compile error.
     */
    @Test
    public void testExtraVarValuesAndFromToMutuallyExclusiveError() {
        Compilation compilation = compile("io.example.BiJoin2x1",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "import io.quarkiverse.permuplate.PermuteVar;\n" +
                        "@Permute(varName=\"i\", from=\"2\", to=\"3\",\n" +
                        "         className=\"BiJoin${i}x${k}\",\n" +
                        "         extraVars={@PermuteVar(varName=\"k\", values={\"A\",\"B\"}, from=\"1\")})\n" +
                        "public class BiJoin2x1 {}");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("mutually exclusive");
    }

    /**
     * Specifying neither values nor from/to is a compile error.
     */
    @Test
    public void testMissingBothValuesAndFromToIsError() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "@Permute(varName=\"T\", className=\"To${T}Function\")\n" +
                        "public interface ToTypeFunction {}");

        assertThat(compilation).failed();
        // Either "values" or "from" should appear in the error
        assertThat(compilation).hadErrorContaining("from");
    }

    /**
     * @Permute with values={"Byte","Short","Int"} and className="To${T}Function"
     *          generates ToByteFunction, ToShortFunction, ToIntFunction.
     *          Template class is ToTypeFunction (not in values set) to avoid name collision.
     *          Leading literal "To" is a prefix of "ToTypeFunction" — R1 passes.
     */
    @Test
    public void testStringSetGeneratesClassForEachValue() {
        Compilation compilation = compile("io.example.ToTypeFunction",
                "package io.example;\n" +
                        "import io.quarkiverse.permuplate.Permute;\n" +
                        "@Permute(varName=\"T\", values={\"Byte\",\"Short\",\"Int\"},\n" +
                        "         className=\"To${T}Function\")\n" +
                        "public interface ToTypeFunction {}");

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("io.example.ToByteFunction").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.ToShortFunction").isPresent()).isTrue();
        assertThat(compilation.generatedSourceFile("io.example.ToIntFunction").isPresent()).isTrue();
    }
}
