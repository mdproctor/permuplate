package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

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

        PermuteConfig config = new PermuteConfig("i", "3", "4", "Child${i}",
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

        PermuteConfig config = new PermuteConfig("i", "2", "3", "Handler${i}",
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

        PermuteConfig config = new PermuteConfig("i", "3", "3", "Worker${i}",
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
    // S1 — @Permute(inline=true) on an interface template
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@link InlineGenerator} correctly handles a nested {@code interface}
     * template (not just {@code class}). Interface methods are abstract with no body
     * and no constructor — the generator must not treat them differently from class methods.
     */
    @Test
    public void testInlineGenerationWithInterfaceTemplate() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class RuleInterfaces {
                    public interface Condition1 {
                        boolean test(Object fact1);
                    }
                }
                """);

        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Condition1")).orElseThrow();

        PermuteConfig config = new PermuteConfig("i", "2", "3", "Condition${i}",
                new String[0], new PermuteVarConfig[0], true, false);

        CompilationUnit output = InlineGenerator.generate(cu, template, config,
                List.of(Map.of("i", 2), Map.of("i", 3)));
        String src = output.toString();

        // Generated interfaces present
        assertThat(src).contains("interface Condition2");
        assertThat(src).contains("interface Condition3");
        // Template removed (keepTemplate = false)
        assertThat(src).doesNotContain("interface Condition1");
        // Methods preserved
        assertThat(src).contains("boolean test(Object fact1)");
        // No @Permute residue
        assertThat(src).doesNotContain("@Permute");
    }

    // -------------------------------------------------------------------------
    // S1+S2+N1 — Two @Permute(inline=true) templates in the same parent class
    // -------------------------------------------------------------------------

    /**
     * Verifies that two independent {@code @Permute}-annotated nested interfaces inside
     * the same parent class are processed sequentially and merged correctly.
     * The second {@link InlineGenerator#generate} call uses the output of the first as
     * its parent CU — this simulates how {@code PermuteMojo} chains multiple templates.
     * <ul>
     * <li>S1: both templates are interfaces (not classes)</li>
     * <li>S2: two templates in one parent</li>
     * <li>N1: two independent permutation families ({@code Condition} and {@code Action})</li>
     * </ul>
     */
    @Test
    public void testTwoInterfaceTemplatesInSameParent() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class RuleInterfaces {
                    public interface Condition1 {
                        boolean test(Object fact1);
                    }
                    public interface Action1 {
                        void execute(Object obj1);
                    }
                }
                """);

        // ── First template: Condition1 → Condition2, Condition3 ───────────────
        ClassOrInterfaceDeclaration condition1 = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Condition1")).orElseThrow();

        PermuteConfig condConfig = new PermuteConfig("i", "2", "3", "Condition${i}",
                new String[0], new PermuteVarConfig[0], true, false);

        CompilationUnit afterConditions = InlineGenerator.generate(cu, condition1, condConfig,
                List.of(Map.of("i", 2), Map.of("i", 3)));

        // ── Second template: Action1 → Action2, Action3 ───────────────────────
        // Action1 is still present in afterConditions (it was not the processed template)
        ClassOrInterfaceDeclaration action1 = afterConditions.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Action1")).orElseThrow();

        PermuteConfig actionConfig = new PermuteConfig("i", "2", "3", "Action${i}",
                new String[0], new PermuteVarConfig[0], true, false);

        CompilationUnit finalOutput = InlineGenerator.generate(afterConditions, action1, actionConfig,
                List.of(Map.of("i", 2), Map.of("i", 3)));
        String src = finalOutput.toString();

        // Both Condition family generated
        assertThat(src).contains("interface Condition2");
        assertThat(src).contains("interface Condition3");
        // Both Action family generated
        assertThat(src).contains("interface Action2");
        assertThat(src).contains("interface Action3");
        // Both templates removed (keepTemplate = false)
        assertThat(src).doesNotContain("interface Condition1");
        assertThat(src).doesNotContain("interface Action1");
        // Parent class preserved
        assertThat(src).contains("class RuleInterfaces");
        // Methods preserved in each family
        assertThat(src).contains("boolean test(Object fact1)");
        assertThat(src).contains("void execute(Object obj1)");
        // No annotation residue
        assertThat(src).doesNotContain("@Permute");
    }

    // -------------------------------------------------------------------------
    // @PermuteFilter in inline mode — skip filtered combinations
    // -------------------------------------------------------------------------

    @Test
    public void testPermuteFilterSkipsValueInInlineMode() throws Exception {
        CompilationUnit cu = StaticJavaParser.parse("""
                package io.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteFilter;
                public class JoinBuilder {
                    @Permute(varName="i", from="3", to="5", className="Join${i}Second",
                             inline=true, keepTemplate=false)
                    @PermuteFilter("${i} != 4")
                    public static class Join0Second {}
                }
                """);

        ClassOrInterfaceDeclaration template = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("Join0Second")).orElseThrow();

        com.github.javaparser.ast.expr.AnnotationExpr permuteAnn = template.getAnnotationByName("Permute").orElseThrow();
        PermuteConfig config = AnnotationReader.readPermute(permuteAnn);
        List<Map<String, Object>> allCombinations = PermuteConfig.buildAllCombinations(config);

        CompilationUnit result = InlineGenerator.generate(cu, template, config, allCombinations);

        String output = result.toString();
        assertThat(output).contains("Join3Second");
        assertThat(output).contains("Join5Second");
        assertThat(output).doesNotContain("Join4Second");
    }

    // -------------------------------------------------------------------------
    // Record template in inline mode — @PermuteParam + @PermuteTypeParam on record
    // -------------------------------------------------------------------------

    @Test
    public void testRecordInlineModeWithPermuteParam() throws Exception {
        // inline=true record template generating Tuple3 and Tuple4 as nested siblings
        String parentSrc = "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "import io.quarkiverse.permuplate.PermuteParam;\n" +
                "import io.quarkiverse.permuplate.PermuteTypeParam;\n" +
                "public class Tuples {\n" +
                "    @Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Tuple${i}\",\n" +
                "             inline=true, keepTemplate=false)\n" +
                "    public static record Tuple2<\n" +
                "        @PermuteTypeParam(varName=\"k\", from=\"1\", to=\"${i}\",\n" +
                "                          name=\"${alpha(k)}\") A\n" +
                "    >(\n" +
                "        @PermuteParam(varName=\"j\", from=\"1\", to=\"${i}\",\n" +
                "                      type=\"${alpha(j)}\", name=\"${lower(j)}\")\n" +
                "        A a\n" +
                "    ) {}\n" +
                "}";

        com.github.javaparser.ast.CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(parentSrc);
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration parent = cu.getClassByName("Tuples").orElseThrow();
        // Find the nested record
        com.github.javaparser.ast.body.RecordDeclaration template = parent.getMembers().stream()
                .filter(m -> m instanceof com.github.javaparser.ast.body.RecordDeclaration)
                .map(m -> (com.github.javaparser.ast.body.RecordDeclaration) m)
                .findFirst().orElseThrow();

        io.quarkiverse.permuplate.maven.AnnotationReader reader = new io.quarkiverse.permuplate.maven.AnnotationReader();
        io.quarkiverse.permuplate.core.PermuteConfig config = reader.readPermuteConfig(template);
        java.util.List<java.util.Map<String, Object>> allCombinations = io.quarkiverse.permuplate.core.PermuteConfig
                .buildAllCombinations(config);

        com.github.javaparser.ast.CompilationUnit result = new io.quarkiverse.permuplate.maven.InlineGenerator()
                .generate(cu, template, config, allCombinations);

        String output = result.toString();
        assertThat(output).contains("Tuple3");
        assertThat(output).contains("A a");
        assertThat(output).contains("B b");
        assertThat(output).contains("C c");
        assertThat(output).contains("Tuple4");
        assertThat(output).contains("D d");
        assertThat(output).doesNotContain("Tuple2"); // keepTemplate=false
    }

    // -------------------------------------------------------------------------
    // End-to-end: EventSystem cohesive example — all composition capabilities
    // -------------------------------------------------------------------------

    /**
     * End-to-end: Event system cohesive example — all composition capabilities together.
     * Event${i} records → Event${i}Builder (Capability C), EventFilter${i} (Capability A).
     */
    @Test
    public void testEventSystemAllCapabilities() throws Exception {
        com.github.javaparser.StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(
                new java.io.File(
                        "/Users/mdproctor/claude/permuplate/permuplate-mvn-examples" +
                                "/src/main/permuplate/io/quarkiverse/permuplate/example/composition/EventSystem.java"));

        ClassOrInterfaceDeclaration parent = cu.getClassByName("EventSystem").orElseThrow();

        io.quarkiverse.permuplate.maven.AnnotationReader reader = new io.quarkiverse.permuplate.maven.AnnotationReader();

        // Find templates by their sentinel names
        TypeDeclaration<?> eventTemplate = findNestedType(parent, "Event2");
        TypeDeclaration<?> builderTemplate = findNestedType(parent, "Event3Builder");
        TypeDeclaration<?> filterTemplate = findNestedType(parent, "EventFilter3");

        // Pass 1: generate Event records (root family)
        io.quarkiverse.permuplate.core.PermuteConfig eventConfig = reader.readPermuteConfig(eventTemplate);
        List<Map<String, Object>> eventCombos = io.quarkiverse.permuplate.core.PermuteConfig.buildAllCombinations(eventConfig);
        CompilationUnit afterEvents = new io.quarkiverse.permuplate.maven.InlineGenerator().generate(cu, eventTemplate,
                eventConfig, eventCombos);

        // Pass 2: generate EventBuilders (Capability C — builder synthesis)
        io.quarkiverse.permuplate.core.PermuteConfig builderConfig = reader.readPermuteConfig(builderTemplate);
        List<Map<String, Object>> builderCombos = io.quarkiverse.permuplate.core.PermuteConfig
                .buildAllCombinations(builderConfig);
        CompilationUnit afterBuilders = new io.quarkiverse.permuplate.maven.InlineGenerator().generate(afterEvents,
                builderTemplate, builderConfig, builderCombos);

        // Pass 3: generate EventFilters (Capability A — type param inference)
        io.quarkiverse.permuplate.core.PermuteConfig filterConfig = reader.readPermuteConfig(filterTemplate);
        List<Map<String, Object>> filterCombos = io.quarkiverse.permuplate.core.PermuteConfig
                .buildAllCombinations(filterConfig);
        CompilationUnit afterFilters = new io.quarkiverse.permuplate.maven.InlineGenerator().generate(afterBuilders,
                filterTemplate, filterConfig, filterCombos);

        String output = afterFilters.toString();

        // All event records generated
        assertThat(output).contains("Event3");
        assertThat(output).contains("Event4");
        assertThat(output).contains("Event5");

        // Capability C: builders generated with build() and return this
        assertThat(output).contains("Event3Builder");
        assertThat(output).contains("Event4Builder");
        assertThat(output).contains("Event5Builder");
        assertThat(output).contains("build()");
        assertThat(output).contains("return this");

        // Capability A: EventFilter classes with type params inferred from Event${i}
        assertThat(output).contains("EventFilter3");
        assertThat(output).contains("EventFilter4");
        assertThat(output).contains("EventFilter5");

        // Template sentinels removed (keepTemplate=false)
        assertThat(output).doesNotContain("record Event2"); // root template gone
        assertThat(output).doesNotContain("class Event3Builder {}"); // empty template body gone

        // No permuplate loop annotation residue on declarations
        assertThat(output).doesNotContain("@Permute(varName");
    }

    // -------------------------------------------------------------------------
    // Top-level class inline templates (inline=true on a non-nested class)
    // -------------------------------------------------------------------------

    /**
     * Verifies that inline=true works on a top-level class template.
     * RuleBuilderTemplate (top-level) with @Permute(className="RuleBuilder",from="1",to="1",
     * inline=true, keepTemplate=false) should produce a CU containing only public class RuleBuilder.
     *
     * <p>
     * This was blocked by a guard in PermuteMojo and a structural assumption in
     * InlineGenerator.generate() that the template is always a nested class. Both are
     * now relaxed to support top-level templates.
     */
    @Test
    public void testTopLevelInlineTemplateRenamesClassAndDropsTemplate() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteDeclr;
                @Permute(varName = "i", from = "1", to = "1",
                         className = "RuleBuilder", inline = true, keepTemplate = false)
                public class RuleBuilderTemplate<DS> {
                    @PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
                    private Object a;
                }
                """);

        ClassOrInterfaceDeclaration template = cu.getClassByName("RuleBuilderTemplate").orElseThrow();
        PermuteConfig config = new PermuteConfig("i", "1", "1", "RuleBuilder",
                new String[0], new PermuteVarConfig[0], true, false);
        List<Map<String, Object>> combos = List.of(Map.of("i", 1));

        CompilationUnit output = InlineGenerator.generate(cu, template, config, combos);
        String src = output.toString();

        // Generated class exists at top level with correct name
        assertThat(output.getClassByName("RuleBuilder").isPresent()).isTrue();
        assertThat(src).contains("public class RuleBuilder");

        // Template class gone (keepTemplate=false)
        assertThat(output.getClassByName("RuleBuilderTemplate").isEmpty()).isTrue();
        assertThat(src).doesNotContain("RuleBuilderTemplate");

        // Transformation applied: @PermuteDeclr renamed field
        assertThat(src).contains("A a"); // alpha(1)=A, lower(1)=a
        assertThat(src).doesNotContain("@PermuteDeclr");
    }

    private static TypeDeclaration<?> findNestedType(
            ClassOrInterfaceDeclaration parent, String name) {
        return parent.getMembers().stream()
                .filter(m -> m instanceof TypeDeclaration<?>)
                .map(m -> (TypeDeclaration<?>) m)
                .filter(t -> t.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Type not found: " + name));
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
                    @Permute(varName = "i", from = "3", to = "5",
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
        assertThat(config.from).isEqualTo("3");
        assertThat(config.to).isEqualTo("5");
        assertThat(config.className).isEqualTo("Foo${i}");
        assertThat(config.inline).isTrue();
        assertThat(config.keepTemplate).isTrue();
    }

    // -------------------------------------------------------------------------
    // Sealed class permits expansion
    // -------------------------------------------------------------------------

    @Test
    public void testSealedPermitsExpandedForTemplate() throws Exception {
        // Parent CU has: sealed interface Expr permits ExprTemplate {}
        // Template generates Expr1..Expr3.
        // After generation, permits should be: Expr1, Expr2, Expr3 (not ExprTemplate).
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit parentCu = StaticJavaParser.parse("""
                package io.permuplate.test;
                public class Shape {
                    public sealed interface Expr permits ExprTemplate {}
                }
                """);

        CompilationUnit templateCu = StaticJavaParser.parse("""
                package io.permuplate.test;
                import io.quarkiverse.permuplate.Permute;
                public class Shape {
                    @Permute(varName="i", from="1", to="3", className="Expr${i}", inline=true)
                    static final class ExprTemplate implements Expr {
                        public int rank() { return 1; }
                    }
                }
                """);

        ClassOrInterfaceDeclaration templateClass = templateCu
                .findFirst(ClassOrInterfaceDeclaration.class,
                        c -> "ExprTemplate".equals(c.getNameAsString()))
                .orElseThrow();

        var ann = templateClass.getAnnotationByName("Permute").orElseThrow();
        PermuteConfig config = AnnotationReader.readPermute(ann);
        List<Map<String, Object>> combinations = PermuteConfig.buildAllCombinations(config);

        CompilationUnit result = InlineGenerator.generate(parentCu, templateClass, config, combinations);
        String src = result.toString();

        assertThat(src).contains("permits Expr1, Expr2, Expr3");
        assertThat(src).doesNotContain("ExprTemplate");
        assertThat(src).contains("class Expr1");
        assertThat(src).contains("class Expr2");
        assertThat(src).contains("class Expr3");
    }

    @Test
    public void testSealedPermitsExpandsAtCorrectPosition() throws Exception {
        // Verifies that when permits has multiple entries, the placeholder is replaced
        // at its exact position (not appended), preserving surrounding entries.
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit parentCu = StaticJavaParser.parse("""
                package io.permuplate.test;
                public class Multi {
                    public sealed interface Node permits Leaf, NodeTemplate, Branch {}
                }
                """);

        CompilationUnit templateCu = StaticJavaParser.parse("""
                package io.permuplate.test;
                import io.quarkiverse.permuplate.Permute;
                public class Multi {
                    @Permute(varName="i", from="1", to="2", className="Node${i}", inline=true)
                    static final class NodeTemplate implements Node {
                        public int id() { return 1; }
                    }
                }
                """);

        ClassOrInterfaceDeclaration templateClass = templateCu
                .findFirst(ClassOrInterfaceDeclaration.class,
                        c -> "NodeTemplate".equals(c.getNameAsString()))
                .orElseThrow();

        var ann = templateClass.getAnnotationByName("Permute").orElseThrow();
        PermuteConfig config = AnnotationReader.readPermute(ann);
        List<Map<String, Object>> combinations = PermuteConfig.buildAllCombinations(config);

        CompilationUnit result = InlineGenerator.generate(parentCu, templateClass, config, combinations);
        String src = result.toString();

        // NodeTemplate (at index 1) should be replaced by Node1, Node2 — Leaf and Branch preserved
        assertThat(src).contains("Leaf");
        assertThat(src).contains("Node1");
        assertThat(src).contains("Node2");
        assertThat(src).contains("Branch");
        assertThat(src).doesNotContain("NodeTemplate");

        // Position: Leaf before Node1/Node2, Branch after
        int leafIdx = src.indexOf("Leaf");
        int node1Idx = src.indexOf("Node1");
        int branchIdx = src.indexOf("Branch");
        assertThat(leafIdx).isLessThan(node1Idx);
        assertThat(node1Idx).isLessThan(branchIdx);
    }

    // -------------------------------------------------------------------------
    // Enum inline templates: @PermuteImport
    // -------------------------------------------------------------------------

    @Test
    public void testEnumInlineTemplateHonorsPermuteImport() throws Exception {
        // Regression: @PermuteImport was silently skipped for enum templates because
        // collectInlinePermuteImports() only accepted ClassOrInterfaceDeclaration.
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);

        CompilationUnit parentCu = StaticJavaParser.parse("""
                package io.permuplate.test;
                public class Container {
                }
                """);

        CompilationUnit templateCu = StaticJavaParser.parse("""
                package io.permuplate.test;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteImport;
                public class Container {
                    @Permute(varName="i", from="1", to="2", className="Level${i}", inline=true)
                    @PermuteImport("java.util.function.Supplier")
                    static enum LevelTemplate {
                        ONE;
                    }
                }
                """);

        com.github.javaparser.ast.body.EnumDeclaration templateEnum = templateCu
                .findFirst(com.github.javaparser.ast.body.EnumDeclaration.class,
                        e -> "LevelTemplate".equals(e.getNameAsString()))
                .orElseThrow();

        var ann = templateEnum.getAnnotationByName("Permute").orElseThrow();
        PermuteConfig config = AnnotationReader.readPermute(ann);
        List<Map<String, Object>> combinations = PermuteConfig.buildAllCombinations(config);

        CompilationUnit result = InlineGenerator.generate(parentCu, templateEnum, config, combinations);
        String src = result.toString();

        assertThat(src).contains("import java.util.function.Supplier;");
        assertThat(src).contains("enum Level1");
        assertThat(src).contains("enum Level2");
        assertThat(src).doesNotContain("@PermuteImport");
    }

    @Test
    public void testPermuteBodyInPermuteMethodUsesInnerVariable() throws Exception {
        // InlineGenerator path: @PermuteBody on a @PermuteMethod clone must see the
        // inner variable (n), not just the outer (i).
        CompilationUnit parentCu = StaticJavaParser.parse("""
                package io.permuplate.test;
                public class Host {
                }
                """);

        CompilationUnit templateCu = StaticJavaParser.parse("""
                package io.permuplate.test;
                import io.quarkiverse.permuplate.Permute;
                import io.quarkiverse.permuplate.PermuteMethod;
                import io.quarkiverse.permuplate.PermuteBody;
                public class Host {
                    @Permute(varName="i", from="1", to="1", className="Gauge${i}", inline=true)
                    static class GaugeTemplate {
                        @PermuteMethod(varName="n", from="2", to="3", name="level${n}")
                        @PermuteBody(body="{ return ${n}; }")
                        public int levelTemplate() { return 0; }
                    }
                }
                """);

        ClassOrInterfaceDeclaration templateClass = templateCu
                .findFirst(ClassOrInterfaceDeclaration.class,
                        c -> "GaugeTemplate".equals(c.getNameAsString()))
                .orElseThrow();

        var ann = templateClass.getAnnotationByName("Permute").orElseThrow();
        PermuteConfig config = AnnotationReader.readPermute(ann);
        List<Map<String, Object>> combinations = PermuteConfig.buildAllCombinations(config);

        CompilationUnit result = InlineGenerator.generate(parentCu, templateClass, config, combinations);
        String src = result.toString();

        assertThat(src).contains("int level2()");
        assertThat(src).contains("return 2");
        assertThat(src).contains("int level3()");
        assertThat(src).contains("return 3");
        assertThat(src).doesNotContain("return 0");
    }
}
