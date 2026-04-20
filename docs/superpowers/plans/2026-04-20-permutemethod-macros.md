# @PermuteMethod macros= Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development

**Goal:** Add String[] macros() default {} to @PermuteMethod. Macros are evaluated with the inner method variable (j/n) in scope, enabling path${n}() typeArgs to be decomposed into named pieces.

**Architecture:** @PermuteMethod annotation gains macros= attribute. In applyPermuteMethod(), after creating innerCtx, evaluate each macro and add to a new extended context. Maven plugin and APT both updated.

**Tech Stack:** Java 17, JavaParser 3.28.0.

**GitHub:** Epic #86, Issue #88

---

## Tasks

### Task 1: Add macros() to PermuteMethod.java

File: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteMethod.java`

```java
/**
 * Named JEXL expression macros, evaluated with the inner method variable in scope.
 * Format: {@code "name=jexlExpression"}. Evaluated after the inner loop variable
 * is bound, enabling expressions like {@code "tail=typeArgList(i,i+n-1,'alpha')"}.
 * Available as {@code ${name}} in all JEXL expressions on this method (typeArgs, etc.).
 */
String[] macros() default {};
```

### Task 2: Write failing tests

File: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteMethodMacrosTest.java`

```java
@Test
public void testPermuteMethodMacroAvailableInTypeArgs() {
    // macros= on @PermuteMethod evaluated with inner variable (j) in scope.
    var source = JavaFileObjects.forSourceString("io.ex.Multi1",
        """
        package io.ex;
        import io.quarkiverse.permuplate.*;
        @Permute(varName="i", from="1", to="1", className="Multi${i}")
        public class Multi1 {
            @PermuteMethod(varName="n", from="2", to="3", name="level${n}",
                           macros={"doubled=n*2"})
            @PermuteReturn(className="Multi${i}", typeArgs="'<' + doubled + '>'",
                           alwaysEmit=true)
            public Object levelTemplate() { return this; }
        }
        """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    String src = sourceOf(c.generatedSourceFile("io.ex.Multi1").orElseThrow());
    assertThat(src).contains("Multi1<4> level2()");  // n=2, doubled=4
    assertThat(src).contains("Multi1<6> level3()");  // n=3, doubled=6
}

@Test
public void testPermuteMethodMacroChaining() {
    // Later macros can reference earlier macros within @PermuteMethod.
    var source = JavaFileObjects.forSourceString("io.ex.Chain1",
        """
        package io.ex;
        import io.quarkiverse.permuplate.*;
        @Permute(varName="i", from="1", to="1", className="Chain${i}")
        public class Chain1 {
            @PermuteMethod(varName="n", from="2", to="2", name="op${n}",
                           macros={"base=n+1", "triple=base*3"})
            @PermuteReturn(className="Chain${i}", typeArgs="'<' + triple + '>'",
                           alwaysEmit=true)
            public Object opTemplate() { return this; }
        }
        """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    String src = sourceOf(c.generatedSourceFile("io.ex.Chain1").orElseThrow());
    // n=2, base=3, triple=9
    assertThat(src).contains("Chain1<9> op2()");
}
```

### Task 3: Implement in AnnotationReader / PermuteMethodConfig

File: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/AnnotationReader.java` (or wherever `PermuteMethodConfig` is defined)

In `readPermuteMethod()`, read macros array alongside existing attributes:
```java
String[] methodMacros = readStringArray(normal, "macros");
```

Add to `PermuteMethodConfig` record:
```java
String[] macros
```

### Task 4: Implement in applyPermuteMethod (InlineGenerator)

File: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

After `EvaluationContext innerCtx = ctx.withVariable(pmCfg.varName(), j)`, add macro evaluation:

```java
// Apply @PermuteMethod macros — evaluated with inner variable in scope
if (pmCfg.macros() != null && pmCfg.macros().length > 0) {
    for (String macro : pmCfg.macros()) {
        int eq = macro.indexOf('=');
        if (eq < 0) continue;
        String name = macro.substring(0, eq).trim();
        String expr = macro.substring(eq + 1).trim();
        try {
            String value = innerCtx.evaluate("${" + expr + "}");
            innerCtx = innerCtx.withVariable(name, value);
        } catch (Exception ignored) {}
    }
}
```

Note: `EvaluationContext.withVariable` accepts `(String, int)` and `(String, String)`. Use the String version since macro results are strings. Chaining `withVariable` calls accumulates all macro bindings sequentially, so later macros can reference earlier ones.

### Task 5: Implement in applyPermuteMethodApt (PermuteProcessor)

File: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

Same macro evaluation after `innerCtx = ctx.withVariable(varName, j)`.

Read macros from the JavaParser annotation directly (no AnnotationReader indirection in the APT path):

```java
java.util.List<String> methodMacros = new java.util.ArrayList<>();
for (com.github.javaparser.ast.expr.MemberValuePair pair : pmAnn.getPairs()) {
    if (pair.getNameAsString().equals("macros")) {
        com.github.javaparser.ast.expr.Expression val = pair.getValue();
        if (val instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr) {
            arr.getValues().forEach(e -> methodMacros.add(e.asStringLiteralExpr().asString()));
        }
    }
}
// Apply macros to innerCtx
for (String macro : methodMacros) {
    int eq = macro.indexOf('=');
    if (eq < 0) continue;
    String name = macro.substring(0, eq).trim();
    String expr = macro.substring(eq + 1).trim();
    try {
        String value = innerCtx.evaluate("${" + expr + "}");
        innerCtx = innerCtx.withVariable(name, value);
    } catch (Exception ignored) {}
}
```

### Task 6: Apply to JoinBuilder path method

File: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

Add macros to the `path${n}` `@PermuteMethod`. The `alpha` variable (typeArgList(1,i,'alpha')) is already in scope from the outer `@Permute macros=` (added in Plan A+DSL / issue #90). Macros here cover the inner-n-dependent expressions:

```java
@PermuteMethod(varName = "n", from = "2", to = "6", name = "path${n}",
               macros = {"tail=typeArgList(i,i+n-1,'alpha')",
                         "prev=typeArgList(i,i+n-2,'alpha')"})
@PermuteReturn(
        className = "RuleOOPathBuilder.Path${n}",
        typeArgs = "'Join'+(i+1)+'First<END, DS, '+alpha+', BaseTuple.Tuple'+n+'<'+tail+'>>, BaseTuple.Tuple'+(n-1)+'<'+prev+'>, '+tail",
        when = "i < 6")
```

Build and verify all DSL tests still pass:
```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

### Task 7: Update CLAUDE.md and commit (closes #88)

Add to the "Key non-obvious decisions" table in `CLAUDE.md`:

```
| @PermuteMethod macros= | String[] macros() on @PermuteMethod. Format: "name=jexlExpr". Evaluated sequentially after the inner loop variable is bound — later macros can reference earlier ones. Result added to innerCtx via withVariable(name, stringValue). Available in all JEXL expressions on the method (typeArgs, name, @PermuteReturn, etc.). Both InlineGenerator and APT paths implement the same evaluation loop. |
```

Stage only the files changed for this issue. Stop for review before committing.
