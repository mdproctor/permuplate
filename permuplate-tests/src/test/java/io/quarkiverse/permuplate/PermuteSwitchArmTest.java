package io.quarkiverse.permuplate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.quarkiverse.permuplate.ProcessorTestSupport.sourceOf;

import org.junit.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;

import io.quarkiverse.permuplate.processor.PermuteProcessor;

public class PermuteSwitchArmTest {

    @Test
    public void testArmsInsertedBeforeDefault() {
        // i=2 → inner loop k=1..1, one arm inserted: case Long n1.
        // case Integer x (seed), case Long n1 (inserted), default are disjoint — no pattern conflicts.
        // Template "DispatcherBase" generates "Dispatcher2".
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.DispatcherBase",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="2", to="2", className="Dispatcher${i}")
                        public class DispatcherBase {
                            @PermuteSwitchArm(varName="k", from="1", to="${i-1}",
                                             pattern="Long n${k}",
                                             body="yield n${k}.intValue();")
                            public int dispatch(Number n) {
                                return switch (n) {
                                    case Integer x -> x;
                                    default -> throw new IllegalArgumentException();
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Dispatcher2").orElseThrow());

        assertThat(src).contains("case Integer x");
        assertThat(src).contains("case Long n1");
        assertThat(src).contains("yield n1.intValue()");
        assertThat(src).doesNotContain("@PermuteSwitchArm");
    }

    @Test
    public void testEmptyRangeInsertsNoArms() {
        // from="3" > to="${i}" (=2): empty range — no arms inserted.
        // Template "SoloBase" generates "Solo2" (no name collision).
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.SoloBase",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="2", to="2", className="Solo${i}")
                        public class SoloBase {
                            @PermuteSwitchArm(varName="k", from="3", to="${i}",
                                             pattern="Object o${k}",
                                             body="yield 0;")
                            public int get(Object o) {
                                return switch (o) {
                                    case String s -> s.length();
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
                .generatedSourceFile("io.permuplate.example.Solo2").orElseThrow());

        assertThat(src).contains("case String s");
        assertThat(src).doesNotContain("case Object o");
        assertThat(src).doesNotContain("@PermuteSwitchArm");
    }

    @Test
    public void testGuardConditionGeneratedCorrectly() {
        // Guard references the pattern variable (n${k}) so it is not a constant expression —
        // javac can't reduce it at compile time and won't warn/error on it.
        // Template "GuardedBase" generates "Guarded2" with i=2, k=1..1 (one guarded arm).
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.GuardedBase",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="2", to="2", className="Guarded${i}")
                        public class GuardedBase {
                            @PermuteSwitchArm(varName="k", from="1", to="${i-1}",
                                             pattern="Integer n${k}",
                                             when="n${k} > 0",
                                             body="yield n${k} * 2;")
                            public int doublePositive(Object o) {
                                return switch (o) {
                                    case Long l -> (int)(long)l;
                                    default -> 0;
                                };
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Guarded2").orElseThrow());

        assertThat(src).contains("case Integer n1");
        assertThat(src).contains("when n1 > 0");
        assertThat(src).doesNotContain("@PermuteSwitchArm");
    }

    @Test
    public void testBodyAsBlock() {
        // i=2 → k=1..1 (one arm with block body). String s1 and Integer n don't overlap.
        // Template "LoggerBase" generates "Logger2".
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.LoggerBase",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="2", to="2", className="Logger${i}")
                        public class LoggerBase {
                            @PermuteSwitchArm(varName="k", from="1", to="${i-1}",
                                             pattern="String s${k}",
                                             body="{ System.out.println(s${k}); yield s${k}.length(); }")
                            public int process(Object o) {
                                return switch (o) {
                                    case Integer n -> n;
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
                .generatedSourceFile("io.permuplate.example.Logger2").orElseThrow());
        assertThat(src).contains("case String s1");
        assertThat(src).contains("System.out.println(s1)");
        assertThat(src).contains("yield s1.length()");
    }

    @Test
    public void testStandaloneSwitchStatement() {
        // Tests the SwitchStmt path in findSwitchEntries (not SwitchExpr).
        // A void method with a standalone switch statement (no return).
        // i=2 → k=1..1: one arm inserted (String s1), no dominated-label conflict.
        // Also validates Fix 1: body without trailing semicolon is auto-corrected.
        var source = JavaFileObjects.forSourceString(
                "io.permuplate.example.PrinterBase",
                """
                        package io.permuplate.example;
                        import io.quarkiverse.permuplate.Permute;
                        import io.quarkiverse.permuplate.PermuteSwitchArm;
                        @Permute(varName="i", from="2", to="2", className="Printer${i}")
                        public class PrinterBase {
                            @PermuteSwitchArm(varName="k", from="1", to="${i-1}",
                                             pattern="String s${k}",
                                             body="System.out.println(s${k})")
                            public void print(Object o) {
                                switch (o) {
                                    case Integer n -> System.out.println(n);
                                    default -> {}
                                }
                            }
                        }
                        """);
        Compilation compilation = Compiler.javac()
                .withProcessors(new PermuteProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        String src = sourceOf(compilation
                .generatedSourceFile("io.permuplate.example.Printer2").orElseThrow());

        assertThat(src).contains("case Integer n");
        assertThat(src).contains("case String s1");
        assertThat(src).contains("System.out.println(s1)");
        assertThat(src).doesNotContain("@PermuteSwitchArm");
    }
}
