package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

/**
 * TDD tests for @PermuteCase arrow-switch support.
 *
 * Tests: unit (arrow detection, block body, auto-semicolon, string literal),
 * integration (SwitchExpr, SwitchStmt), correctness (empty range, colon regression),
 * happy path (E2E generation).
 */
public class PermuteCaseArrowTest {

    @Test
    public void testArrowSwitchStatementInsertsArrowEntries() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Stmt2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="Stmt${i}")
                        public class Stmt2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="doWork(${k});")
                            public void dispatch(int x) {
                                switch (x) {
                                    case 0 -> doWork(0);
                                    default -> {}
                                }
                            }
                            private void doWork(int n) {}
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Stmt3").orElseThrow());

        assertThat(src).contains("case 1 ->");
        assertThat(src).contains("case 2 ->");
        assertThat(src).doesNotContain("case 1:");
        assertThat(src).doesNotContain("case 2:");
        assertThat(src).contains("case 0 ->");
        assertThat(src).doesNotContain("@PermuteCase");
    }

    @Test
    public void testArrowSwitchExpressionInsertsArrowEntries() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.Expr2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="Expr${i}")
                        public class Expr2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="yield ${k};")
                            public int select(int x) {
                                return switch (x) {
                                    case 0 -> 0;
                                    default -> -1;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Expr3").orElseThrow());

        assertThat(src).contains("case 1 ->");
        assertThat(src).contains("case 2 ->");
        assertThat(src).contains("yield 1");
        assertThat(src).contains("yield 2");
        assertThat(src).doesNotContain("@PermuteCase");
    }

    @Test
    public void testEmptyRangeArrowSwitchInsertsNoArms() {
        // Template EmptyArrow1 generates EmptyArrow2 (from=2,to=2 → i=2).
        // @PermuteCase from=3 to=${i}=2 → empty range: no arms inserted, annotation stripped.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.EmptyArrow1",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="2", to="2", className="EmptyArrow${i}")
                        public class EmptyArrow1 {
                            @PermuteCase(varName="k", from="3", to="${i}",
                                         index="${k}", body="yield ${k};")
                            public int get(int x) {
                                return switch (x) {
                                    case 0 -> 0;
                                    default -> -1;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.EmptyArrow2").orElseThrow());

        assertThat(src).contains("case 0 ->");
        assertThat(src).doesNotContain("case 3");
        assertThat(src).doesNotContain("@PermuteCase");
    }

    @Test
    public void testArrowBodyWithoutSemicolonAutoAppended() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.AutoSemi2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="AutoSemi${i}")
                        public class AutoSemi2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="yield ${k}")
                            public int compute(int x) {
                                return switch (x) {
                                    case 0 -> 0;
                                    default -> -1;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.AutoSemi3").orElseThrow());
        assertThat(src).contains("yield 1");
        assertThat(src).contains("yield 2");
    }

    @Test
    public void testArrowBodyAsBlock() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.BlockArm2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="BlockArm${i}")
                        public class BlockArm2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}",
                                         body="{ System.out.println(${k}); yield ${k}; }")
                            public int handle(int x) {
                                return switch (x) {
                                    case 0 -> { System.out.println(0); yield 0; }
                                    default -> -1;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.BlockArm3").orElseThrow());
        assertThat(src).contains("System.out.println(1)");
        assertThat(src).contains("System.out.println(2)");
        assertThat(src).contains("yield 1");
        assertThat(src).contains("yield 2");
    }

    @Test
    public void testArrowBodyWithStringLiteral() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.StringArrow2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="StringArrow${i}")
                        public class StringArrow2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="yield \\"item-${k}\\";")
                            public String label(int x) {
                                return switch (x) {
                                    case 0 -> "item-0";
                                    default -> "unknown";
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.StringArrow3").orElseThrow());
        assertThat(src).contains("\"item-1\"");
        assertThat(src).contains("\"item-2\"");
    }

    @Test
    public void testColonSwitchUnchanged() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.ColonReg2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="3", className="ColonReg${i}")
                        public class ColonReg2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="return ${k};")
                            public int dispatch(int x) {
                                switch (x) {
                                    case 0: return 0;
                                    default: throw new IllegalArgumentException();
                                }
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.ColonReg3").orElseThrow());

        assertThat(src).contains("case 1:");
        assertThat(src).contains("case 2:");
        assertThat(src).doesNotContain("case 1 ->");
        assertThat(src).doesNotContain("case 2 ->");
    }

    @Test
    public void testArrowSwitchExpressionEndToEnd() {
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.ArrowE2E2",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteCase;
                        @Permute(varName="i", from="3", to="5", className="ArrowE2E${i}")
                        public class ArrowE2E2 {
                            @PermuteCase(varName="k", from="1", to="${i-1}",
                                         index="${k}", body="yield ${k * 10};")
                            public int compute(int x) {
                                return switch (x) {
                                    case 0 -> 0;
                                    default -> -1;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        String src3 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.ArrowE2E3").orElseThrow());
        assertThat(src3).contains("case 1 ->");
        assertThat(src3).contains("case 2 ->");
        assertThat(src3).doesNotContain("case 3 ->");

        String src4 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.ArrowE2E4").orElseThrow());
        assertThat(src4).contains("case 1 ->");
        assertThat(src4).contains("case 2 ->");
        assertThat(src4).contains("case 3 ->");

        String src5 = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.ArrowE2E5").orElseThrow());
        assertThat(src5).contains("case 4 ->");
        assertThat(src5).contains("yield 40");
    }
}
