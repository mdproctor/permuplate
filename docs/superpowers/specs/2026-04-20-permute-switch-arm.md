# @PermuteSwitchArm Design

## Goal

Enable `@PermuteCase`-style expansion for Java 21+ arrow-switch pattern matching arms. The existing `@PermuteCase` targets colon-switch (`case N: ... break;`). Pattern matching requires arrow-switch (`case Type var -> expr`). These are distinct constructs — a separate annotation avoids overloading `@PermuteCase`.

## Annotation

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteSwitchArm {
    /** Inner loop variable name (e.g. {@code "k"}). */
    String varName();

    /** Inclusive lower bound (JEXL expression, e.g. {@code "1"}). */
    String from();

    /**
     * Inclusive upper bound (JEXL expression, e.g. {@code "${i}"}). Empty range = no arms.
     */
    String to();

    /**
     * JEXL template for the type pattern (e.g. {@code "Shape${k} s"}).
     * Evaluated per inner loop value. Produces the left side of {@code case <pattern> ->}.
     */
    String pattern();

    /**
     * JEXL template for the arm body — the right side of {@code ->}.
     * May be an expression ({@code "s.area()"}) or a block ({@code "{ yield s.area(); }"}).
     */
    String body();

    /**
     * Optional JEXL guard expression (e.g. {@code "${k} > 0"}).
     * When non-empty, generates {@code case <pattern> when <guard> -> <body>}.
     * Evaluated per inner loop value.
     */
    String when() default "";
}
```

## Template pattern

```java
// Template: generates arm for each Shape subtype
@PermuteSwitchArm(varName="k", from="1", to="${i}",
                  pattern="Shape${k} s",
                  body="yield s.area();")
public double area(Shape shape) {
    return switch (shape) {
        case Circle c -> c.radius() * c.radius() * Math.PI;  // seed arm — preserved
        default -> throw new IllegalArgumentException(shape.toString());
    };
}
// Generated for i=3: arms for Shape1 s, Shape2 s, Shape3 s inserted before default
```

With guard:
```java
@PermuteSwitchArm(varName="k", from="1", to="${i}",
                  pattern="Shape${k} s",
                  when="${k} > 1",
                  body="yield s.area();")
```
Generates `case Shape2 s when 2 > 1 -> yield s.area();` etc. (guard evaluated per k).

## Transformer: `PermuteSwitchArmTransformer`

**Location:** `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteSwitchArmTransformer.java`

**Algorithm:**

1. Find all `MethodDeclaration` nodes carrying `@PermuteSwitchArm`
2. Locate the first `SwitchExpr` or `SwitchStmt` in the method body (arrow form detected by entry type)
3. Evaluate `from`, `to` bounds (skip on error)
4. For each `k` in `[fromVal, toVal]`:
   - Evaluate `pattern`, `body`, `when` templates with `innerCtx`
   - Construct the arm source text: `"case <pattern>[when <guard>] -> <body>"`
   - Parse via `StaticJavaParser` with language level temporarily set to `JAVA_21`
   - Specifically: `StaticJavaParser.parseStatement("switch(__x__){" + armText + " default -> null; }")` then extract the first entry
   - Insert arm before the `default` entry (same `findDefaultCaseIndex` logic as `PermuteCaseTransformer`)
5. Strip `@PermuteSwitchArm` annotation

**Language level:** Set `StaticJavaParser.getParserConfiguration().setLanguageLevel(JAVA_21)` before parsing the synthetic switch, then restore. This is safe because the transformer runs after the template has already been parsed.

**Body parsing:** If `body` starts with `{` and ends with `}`, it is a block body. Otherwise it is an expression body. Both are valid on the right-hand side of `->`. Wrap in a `switch(__x__){ case <pattern> -> <body> default -> null; }` to parse, then extract the first `SwitchEntry`.

## Pipeline registration

Registered immediately after `PermuteCaseTransformer` in both:
- `InlineGenerator.java` (COID and enum branches)
- `PermuteProcessor.java` (COID/interface branch)

**Import:** `import io.quarkiverse.permuplate.core.PermuteSwitchArmTransformer;`

Added to `stripPermuteAnnotations` in `InlineGenerator` alongside other annotation names.

## IntelliJ plugin integration

### Rename propagation

Add `"io.quarkiverse.permuplate.PermuteSwitchArm"` to `ALL_ANNOTATION_FQNS` in:
- `AnnotationStringRenameProcessor.java`
- `PermuteMethodNavigator.java`

The `AnnotationStringAlgorithm` already handles any string attribute containing class name literals — renaming `Shape3` updates `pattern = "Shape${k} s"` where the literal `Shape` prefix matches.

`body` and `when` may contain class references too (e.g. `"yield new Shape${k}()"`) — including them in rename propagation is correct. The algorithm only updates where the old literal actually appears.

### Inspection guard

Any `LocalInspectionTool` that checks for Permuplate annotations must add the simple-name fallback:
```java
|| fqn.equals("PermuteSwitchArm")
```
alongside the existing `endsWith(".PermuteSwitchArm")` check (see CLAUDE.md: `PsiAnnotation.getQualifiedName()` simple-name fallback).

## CLAUDE.md additions

Add to the annotations table:
```
| `@PermuteSwitchArm` | method | Generate Java 21+ arrow-switch pattern arms per permutation; pattern/body/when are JEXL templates |
```

Add to key decisions table:
```
| `@PermuteSwitchArm` body parsing | Constructs a synthetic `switch(__x__){ case <pattern> -> <body> default -> null; }` with JAVA_21 language level and extracts the first SwitchEntry. Avoids direct AST construction of PatternExpr — simpler and more robust as Java 21 AST evolves. |
```

## Tests

### Compilation test (`PermuteSwitchArmTest.java`, new file)

1. **Basic arm expansion:** template generates correct arms for each k value
2. **Guard condition:** `when="${k} > 1"` produces `when k > 1` guard in output
3. **Empty range:** `from="3" to="2"` inserts no arms, seed arm and default preserved
4. **Body as block:** `body="{ System.out.println(s); yield s.area(); }"` parses correctly
5. **Annotation removed:** `doesNotContain("@PermuteSwitchArm")` in all generated classes

### Plugin test (Gradle test suite)

Add a test verifying that renaming a class referenced in `@PermuteSwitchArm(pattern="OldShape${k} s")` propagates to `"NewShape${k} s"`.

## Files

| Action | Path |
|---|---|
| Create | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteSwitchArm.java` |
| Create | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteSwitchArmTransformer.java` |
| Modify | `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java` |
| Modify | `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java` |
| Modify | `permuplate-intellij-plugin/src/main/java/.../rename/AnnotationStringRenameProcessor.java` |
| Modify | `permuplate-intellij-plugin/src/main/java/.../navigation/PermuteMethodNavigator.java` |
| Modify | `permuplate-intellij-plugin/src/main/java/.../inspection/*.java` (any that list annotations) |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteSwitchArmTest.java` |
| Modify | `permuplate-intellij-plugin/src/test/java/.../rename/AnnotationStringRenameProcessorTest.java` |
| Modify | `README.md` |
| Modify | `CLAUDE.md` |
