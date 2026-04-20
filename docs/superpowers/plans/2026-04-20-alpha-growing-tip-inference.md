# Alpha Growing-Tip Inference for @PermuteReturn Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development

**Goal:** When @PermuteReturn has className but no typeArgs, AND the method has a single-value @PermuteTypeParam with alpha naming, infer typeArgs as the current generated class's type parameters + the new alpha letter. Eliminates the typeArgs on join() in JoinBuilder.

**Architecture:** Extend applyPermuteReturn in both InlineGenerator and PermuteProcessor. After evaluating className (and finding typeArgs empty), check whether the method has a qualifying @PermuteTypeParam annotation. If so, build typeArgs from classDecl.getTypeParameters() + the single alpha letter.

**Tech Stack:** Java 17, JavaParser 3.28.0, APT, Maven plugin.

**GitHub:** Epic #86, Issue #87

---

## What qualifies for inference

A method is eligible for alpha growing-tip inference when ALL of these hold:

1. Has `@PermuteReturn(className=...)` with `typeArgs=""` (empty, not specified)
2. Has exactly ONE `@PermuteTypeParam` annotation with `from == to` (single value) and `name` matching pattern `${alpha(...)}` (contains "alpha")
3. The `@PermuteReturn.alwaysEmit` is either true or the class is in the generated set

**Inference formula:**

- Read `classDecl.getTypeParameters()` — these are the ALREADY EXPANDED type params of the current generated class (e.g. `[END, DS, A, B]` for Join2First at i=2)
- The new letter = evaluate the @PermuteTypeParam's `name` template in `innerCtx`
- typeArgs = all current type params joined by ", " + ", " + new letter

**Example:** Join2First has type params `<END, DS, A, B>`. Method has `@PermuteTypeParam(name="${alpha(m)}", from="${i+1}", to="${i+1}")`. At i=2, alpha(3)=C. Inferred typeArgs = `"END, DS, A, B, C"`. Return type becomes `Join3First<END, DS, A, B, C>`.

---

## Tasks

### Task 1: Write failing tests

File: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/AlphaGrowingTipInferenceTest.java`

```java
@Test
public void testAlphaGrowingTipInferenceFillsTypeArgs() {
    // When @PermuteReturn has no typeArgs AND method has single-value @PermuteTypeParam
    // with alpha naming, typeArgs is inferred from current class params + new alpha letter.
    var source = JavaFileObjects.forSourceString("io.ex.Step1",
        """
        package io.ex;
        import io.quarkiverse.permuplate.*;
        @Permute(varName="i", from="2", to="3", className="Step${i}")
        public class Step1<END, @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="${alpha(k)}") A> {
            // @PermuteReturn with NO typeArgs — should be inferred
            @PermuteTypeParam(varName="m", from="${i}", to="${i}", name="${alpha(m)}")
            @PermuteReturn(className="Step${i+1}")
            public <B> Object advance(java.util.function.Function<String, B> fn) {
                return null;
            }
        }
        """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    String src2 = sourceOf(c.generatedSourceFile("io.ex.Step2").orElseThrow());
    // Step2<END, A>. advance() should return Step3<END, A, B>
    assertThat(src2).contains("Step3<END, A, B> advance(");
    String src3 = sourceOf(c.generatedSourceFile("io.ex.Step3").orElseThrow());
    // Step3<END, A, B>. advance() should return Step4<END, A, B, C>
    assertThat(src3).contains("Step4<END, A, B, C> advance(");
}

@Test
public void testExplicitTypeArgsNotOverriddenByInference() {
    // When @PermuteReturn has explicit typeArgs, inference does NOT fire.
    var source = JavaFileObjects.forSourceString("io.ex.Explicit1",
        """
        package io.ex;
        import io.quarkiverse.permuplate.*;
        @Permute(varName="i", from="2", to="2", className="Explicit${i}")
        public class Explicit1<END, @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="${alpha(k)}") A> {
            @PermuteTypeParam(varName="m", from="${i}", to="${i}", name="${alpha(m)}")
            @PermuteReturn(className="Explicit${i+1}", typeArgs="'CUSTOM'")
            public <B> Object advance(Object fn) { return null; }
        }
        """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    String src = sourceOf(c.generatedSourceFile("io.ex.Explicit2").orElseThrow());
    // typeArgs was explicit "CUSTOM", so return type is Explicit3<CUSTOM>
    assertThat(src).contains("Explicit3<CUSTOM> advance(");
}

@Test
public void testBoundaryOmissionStillWorksWithInference() {
    // Boundary omission (className not in generated set) still removes the method.
    var source = JavaFileObjects.forSourceString("io.ex.Bound1",
        """
        package io.ex;
        import io.quarkiverse.permuplate.*;
        @Permute(varName="i", from="2", to="3", className="Bound${i}")
        public class Bound1<END, @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="${alpha(k)}") A> {
            @PermuteTypeParam(varName="m", from="${i}", to="${i}", name="${alpha(m)}")
            @PermuteReturn(className="Bound${i+1}")  // Bound4 not generated — omit from Bound3
            public <B> Object advance(Object fn) { return null; }
        }
        """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    String src2 = sourceOf(c.generatedSourceFile("io.ex.Bound2").orElseThrow());
    assertThat(src2).contains("Bound3");  // Bound2 has advance() returning Bound3
    String src3 = sourceOf(c.generatedSourceFile("io.ex.Bound3").orElseThrow());
    assertThat(src3).doesNotContain("advance(");  // Bound4 not generated → omitted
}
```

### Task 2: Implement in InlineGenerator

File: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

In `applyPermuteReturn(ClassOrInterfaceDeclaration classDecl, EvaluationContext ctx, Set<String> allGeneratedNames)`, after reading the annotation's `typeArgs` attribute, if `typeArgs` is empty, check for alpha growing-tip inference:

```java
// Alpha growing-tip inference: if typeArgs is empty, check for single-value
// @PermuteTypeParam with alpha naming on the same method.
if ((cfg.typeArgs() == null || cfg.typeArgs().isEmpty()) && method != null) {
    String inferredTypeArgs = inferAlphaTypeArgs(method, classDecl, ctx);
    if (inferredTypeArgs != null) {
        // Use inferred typeArgs in place of the empty one
        // ... set return type using inferredTypeArgs
    }
}
```

Add helper method to InlineGenerator:

```java
private static String inferAlphaTypeArgs(MethodDeclaration method,
        TypeDeclaration<?> classDecl, EvaluationContext ctx) {
    // Find a @PermuteTypeParam on the method with from==to and name containing "alpha"
    for (AnnotationExpr ann : method.getAnnotations()) {
        if (!ann.getNameAsString().equals("PermuteTypeParam")
            && !ann.getNameAsString().equals("io.quarkiverse.permuplate.PermuteTypeParam"))
            continue;
        if (!(ann instanceof NormalAnnotationExpr normal)) continue;
        String fromStr = null, toStr = null, nameTemplate = null;
        for (com.github.javaparser.ast.expr.MemberValuePair p : normal.getPairs()) {
            switch (p.getNameAsString()) {
                case "from" -> fromStr = p.getValue().asStringLiteralExpr().asString();
                case "to"   -> toStr   = p.getValue().asStringLiteralExpr().asString();
                case "name" -> nameTemplate = p.getValue().asStringLiteralExpr().asString();
            }
        }
        if (fromStr == null || toStr == null || nameTemplate == null) continue;
        if (!nameTemplate.contains("alpha")) continue;
        // Check from==to (single value)
        try {
            int from = ctx.evaluateInt(fromStr);
            int to   = ctx.evaluateInt(toStr);
            if (from != to) continue;
            // Evaluate the new alpha letter
            EvaluationContext innerCtx = ctx.withVariable(
                getVarNameFromTypeParamAnn(normal), from);
            String newLetter = innerCtx.evaluate(nameTemplate);
            // Build typeArgs: all current class type params + ", " + newLetter
            String currentParams = classDecl.getTypeParameters().stream()
                    .map(tp -> tp.getNameAsString())
                    .collect(java.util.stream.Collectors.joining(", "));
            return currentParams.isEmpty()
                    ? newLetter
                    : currentParams + ", " + newLetter;
        } catch (Exception ignored) {}
    }
    return null; // no inference
}

private static String getVarNameFromTypeParamAnn(NormalAnnotationExpr ann) {
    for (com.github.javaparser.ast.expr.MemberValuePair p : ann.getPairs()) {
        if (p.getNameAsString().equals("varName"))
            return p.getValue().asStringLiteralExpr().asString();
    }
    return "_";
}
```

### Task 3: Implement in PermuteProcessor (APT path)

File: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

Same logic in `applyPermuteReturn()`. The `classDecl` is the generated class TypeDeclaration. Mirror the `inferAlphaTypeArgs` and `getVarNameFromTypeParamAnn` helpers in `PermuteProcessor` (or extract to a shared utility in `permuplate-core` if both callers are in scope).

### Task 4: Apply to JoinBuilder — remove typeArgs from join()

File: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

Change `join()` from:
```java
@PermuteReturn(className = "Join${i+1}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i+1, 'alpha')")
```
to:
```java
@PermuteReturn(className = "Join${i+1}First")  // typeArgs inferred from @PermuteTypeParam
```

Build and verify:
```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

### Task 5: Update CLAUDE.md and commit (closes #87)

Add to the "Key non-obvious decisions" table in `CLAUDE.md`:

```
| Alpha growing-tip inference | When @PermuteReturn has className but no typeArgs AND the method has a single-value @PermuteTypeParam with name containing "alpha", typeArgs are inferred as current class type params + the new alpha letter. Implemented in inferAlphaTypeArgs() in both InlineGenerator and PermuteProcessor. Explicit typeArgs always take precedence. |
```

Stage only the files changed for this issue. Stop for review before committing.
