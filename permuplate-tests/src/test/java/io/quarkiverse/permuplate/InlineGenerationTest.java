package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import io.quarkiverse.permuplate.core.PermuteConfig;
import io.quarkiverse.permuplate.core.PermuteVarConfig;
import io.quarkiverse.permuplate.maven.AnnotationReader;
import io.quarkiverse.permuplate.maven.InlineGenerator;

/**
 * Tests for {@link InlineGenerator} and {@link AnnotationReader}.
 * Uses JavaParser directly — no compile-testing framework needed since we are
 * testing AST transformation, not javac annotation processing.
 */
public class InlineGenerationTest {

    // -------------------------------------------------------------------------
    // InlineGenerator: keepTemplate = false removes the template class
    // -------------------------------------------------------------------------

    @Test
    public void testInlineGenerationRemovesTemplateByDefault() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class Parent {
                    public static class Child2 {
                        public void handle(Object o1) {}
                    }
                }
                """);

        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Child2")).orElseThrow();

        PermuteConfig config = new PermuteConfig("i", 3, 4, "Child${i}",
                new String[0], new PermuteVarConfig[0], true, false);

        List<Map<String, Object>> combos = List.of(Map.of("i", 3), Map.of("i", 4));

        CompilationUnit output = InlineGenerator.generate(cu, template, config, combos);
        String src = output.toString();

        // Template class removed (keepTemplate = false)
        assertThat(src).doesNotContain("class Child2");

        // Generated classes present
        assertThat(src).contains("class Child3");
        assertThat(src).contains("class Child4");

        // Parent class preserved
        assertThat(src).contains("class Parent");

        // No @Permute annotations left
        assertThat(src).doesNotContain("@Permute");
    }

    // -------------------------------------------------------------------------
    // InlineGenerator: keepTemplate = true retains the template
    // -------------------------------------------------------------------------

    @Test
    public void testInlineGenerationKeepsTemplateWhenRequested() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class Handlers {
                    public static class Handler1 {
                        public void handle(Object arg1) {}
                    }
                }
                """);

        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Handler1")).orElseThrow();

        PermuteConfig config = new PermuteConfig("i", 2, 3, "Handler${i}",
                new String[0], new PermuteVarConfig[0], true, true);

        List<Map<String, Object>> combos = List.of(Map.of("i", 2), Map.of("i", 3));

        CompilationUnit output = InlineGenerator.generate(cu, template, config, combos);
        String src = output.toString();

        // Template retained
        assertThat(src).contains("class Handler1");

        // Generated classes added
        assertThat(src).contains("class Handler2");
        assertThat(src).contains("class Handler3");

        // Parent preserved
        assertThat(src).contains("class Handlers");
    }

    // -------------------------------------------------------------------------
    // InlineGenerator: parent content (fields, methods, other nested) preserved
    // -------------------------------------------------------------------------

    @Test
    public void testParentContentPreservedDuringInlineGeneration() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class Registry {
                    private static final String VERSION = "1.0";
                    public static String version() { return VERSION; }

                    public static class Worker2 {
                        public void work(Object o1) {}
                    }

                    public static class Helper {
                        public void help() {}
                    }
                }
                """);

        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Worker2")).orElseThrow();

        PermuteConfig config = new PermuteConfig("i", 3, 3, "Worker${i}",
                new String[0], new PermuteVarConfig[0], true, false);

        CompilationUnit output = InlineGenerator.generate(
                cu, template, config, List.of(Map.of("i", 3)));
        String src = output.toString();

        // Static field and method preserved
        assertThat(src).contains("VERSION");
        assertThat(src).contains("version()");

        // Unrelated nested class preserved
        assertThat(src).contains("class Helper");

        // Template removed
        assertThat(src).doesNotContain("class Worker2");

        // Generated added
        assertThat(src).contains("class Worker3");

        // No @Permute annotations left
        assertThat(src).doesNotContain("@Permute");
    }

    // -------------------------------------------------------------------------
    // AnnotationReader: reads @Permute values from JavaParser AST
    // -------------------------------------------------------------------------

    @Test
    public void testAnnotationReaderParsesBasicPermute() throws Exception {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                import io.quarkiverse.permuplate.Permute;
                public class Outer {
                    @Permute(varName = "i", from = 3, to = 5,
                             className = "Foo${i}", inline = true, keepTemplate = true)
                    public static class Foo2 {}
                }
                """);

        var ann = cu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Foo2"))
                .orElseThrow()
                .getAnnotationByName("Permute").orElseThrow();

        PermuteConfig config = AnnotationReader.readPermute(ann);

        assertThat(config.varName).isEqualTo("i");
        assertThat(config.from).isEqualTo(3);
        assertThat(config.to).isEqualTo(5);
        assertThat(config.className).isEqualTo("Foo${i}");
        assertThat(config.inline).isTrue();
        assertThat(config.keepTemplate).isTrue();
    }
}
