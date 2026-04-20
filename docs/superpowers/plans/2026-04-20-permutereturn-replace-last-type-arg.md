# @PermuteReturn replaceLastTypeArgWith= Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development

**Goal:** New @PermuteReturn attribute replaceLastTypeArgWith="T". When set, generates return type as current generated class's type params with the LAST one replaced by the specified value. Eliminates the complex ternary typeArgs on type() in JoinBuilder.

**Architecture:** New attribute on @PermuteReturn. Implemented in applyPermuteReturn in both InlineGenerator and PermuteProcessor. Reads classDecl.getTypeParameters(), replaces last, builds return type string.

**Tech Stack:** Java 17, JavaParser 3.28.0.

**GitHub:** Epic #86, Issue #89

---

## Tasks

### Task 1: Add replaceLastTypeArgWith() to PermuteReturn.java

File: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteReturn.java`

```java
/**
 * When non-empty, the return type is the current generated class with all its
 * type parameters except the last, which is replaced by this value.
 *
 * <p>Useful for type-narrowing methods that return {@code this} but with the
 * last type parameter constrained to a specific type (e.g. narrowing
 * {@code Join3First<END, DS, A, B, C>} to {@code Join3First<END, DS, A, B, T>}).
 *
 * <p>The value is a literal type name (not a JEXL expression).
 * Mutually exclusive with {@code typeArgs}.
 */
String replaceLastTypeArgWith() default "";
```

### Task 2: Write failing tests

File: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/ReplaceLastTypeArgWithTest.java`

```java
@Test
public void testReplaceLastTypeArgWith() {
    // replaceLastTypeArgWith="X" generates return type with last type param replaced.
    var source = JavaFileObjects.forSourceString("io.ex.Narrow1",
        """
        package io.ex;
        import io.quarkiverse.permuplate.*;
        @Permute(varName="i", from="2", to="3", className="Narrow${i}")
        public class Narrow1<END,
                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="T${k}") T1> {
            @PermuteReturn(className="Narrow${i}", alwaysEmit=true, replaceLastTypeArgWith="X")
            public Object narrow() { return this; }
        }
        """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    // Narrow2<END, T1> → narrow() returns Narrow2<END, X>
    String src2 = sourceOf(c.generatedSourceFile("io.ex.Narrow2").orElseThrow());
    assertThat(src2).contains("Narrow2<END, X> narrow()");
    // Narrow3<END, T1, T2> → narrow() returns Narrow3<END, T1, X>
    String src3 = sourceOf(c.generatedSourceFile("io.ex.Narrow3").orElseThrow());
    assertThat(src3).contains("Narrow3<END, T1, X> narrow()");
}

@Test
public void testReplaceLastTypeArgWithTypeParamExpression() {
    // replaceLastTypeArgWith="T" with a method type param — most common use case.
    var source = JavaFileObjects.forSourceString("io.ex.TypeNarrow1",
        """
        package io.ex;
        import io.quarkiverse.permuplate.*;
        @Permute(varName="i", from="2", to="2", className="TypeNarrow${i}")
        public class TypeNarrow1<END,
                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="A${k}") A1> {
            @SuppressWarnings({"unchecked","varargs"})
            @PermuteReturn(className="TypeNarrow${i}", alwaysEmit=true, replaceLastTypeArgWith="T")
            public <T> Object narrow(T... cls) { return this; }
        }
        """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    String src = sourceOf(c.generatedSourceFile("io.ex.TypeNarrow2").orElseThrow());
    // TypeNarrow2<END, A1> → narrow() returns TypeNarrow2<END, T>
    assertThat(src).contains("TypeNarrow2<END, T> narrow(");
}

@Test
public void testReplaceLastTypeArgWithAndExplicitTypeArgsMutuallyExclusive() {
    // When both replaceLastTypeArgWith and typeArgs are set, typeArgs wins (explicit
    // always takes precedence) — or alternatively, emit a compile error. Choose one
    // and document the behaviour here.
    // Recommended: typeArgs wins; replaceLastTypeArgWith is silently ignored.
    var source = JavaFileObjects.forSourceString("io.ex.Both1",
        """
        package io.ex;
        import io.quarkiverse.permuplate.*;
        @Permute(varName="i", from="2", to="2", className="Both${i}")
        public class Both1<END,
                @PermuteTypeParam(varName="k", from="1", to="${i-1}", name="T${k}") T1> {
            @PermuteReturn(className="Both${i}", alwaysEmit=true,
                           replaceLastTypeArgWith="X", typeArgs="'END, EXPLICIT'")
            public Object both() { return this; }
        }
        """);
    Compilation c = Compiler.javac().withProcessors(new PermuteProcessor()).compile(source);
    assertThat(c).succeeded();
    String src = sourceOf(c.generatedSourceFile("io.ex.Both2").orElseThrow());
    // typeArgs wins
    assertThat(src).contains("Both2<END, EXPLICIT> both()");
}
```

### Task 3: Implement in InlineGenerator

File: `permuplate-maven-plugin/src/main/java/io/quarkiverse/permuplate/maven/InlineGenerator.java`

In `applyPermuteReturn()`, after reading `cfg.typeArgs()`, before the existing typeArgs-based branch, add check for `replaceLastTypeArgWith`. The check order must be: explicit `typeArgs` first, then `replaceLastTypeArgWith`, then alpha inference (Plan B), then boundary check.

```java
// replaceLastTypeArgWith — build typeArgs by replacing last class type param
String effectiveTypeArgs = cfg.typeArgs();
if ((effectiveTypeArgs == null || effectiveTypeArgs.isEmpty())
        && cfg.replaceLastTypeArgWith() != null
        && !cfg.replaceLastTypeArgWith().isEmpty()) {
    java.util.List<String> params = new java.util.ArrayList<>();
    if (classDecl instanceof com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<?> nwtp) {
        nwtp.getTypeParameters().forEach(tp -> params.add(tp.getNameAsString()));
    }
    String replacement = cfg.replaceLastTypeArgWith();
    if (params.isEmpty()) {
        effectiveTypeArgs = "'" + replacement + "'";
    } else {
        params.set(params.size() - 1, replacement);
        effectiveTypeArgs = "'" + String.join(", ", params) + "'";
    }
}
// Then use effectiveTypeArgs wherever typeArgs was previously used
```

Add to `PermuteReturnConfig` record:
```java
String replaceLastTypeArgWith
```

Read in `readPermuteReturn()` from annotation attribute (default `""`):
```java
String replaceLastTypeArgWith = readString(normal, "replaceLastTypeArgWith", "");
```

### Task 4: Implement in PermuteProcessor (APT)

File: `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/PermuteProcessor.java`

Same logic in `applyPermuteReturn()`. Read `replaceLastTypeArgWith` attribute via the existing annotation attribute reading pattern. Mirror the `effectiveTypeArgs` derivation above.

### Task 5: Apply to JoinBuilder type() method

File: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/JoinBuilder.java`

Change `type()` from:
```java
@PermuteReturn(className = "Join${i}First",
               typeArgs = "'END, DS, ' + typeArgList(1, i-1, 'alpha') + (i > 1 ? ', ' : '') + 'T'",
               alwaysEmit = true)
public <T> Object type(Class<T>... cls) { return cast(this); }
```
to:
```java
@PermuteReturn(className = "Join${i}First", alwaysEmit = true, replaceLastTypeArgWith = "T")
public <T> Object type(Class<T>... cls) { return cast(this); }
```

Build and verify:
```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -am -q 2>&1 | tail -5
```

### Task 6: Update CLAUDE.md and commit (closes #89)

Add to the "Key non-obvious decisions" table in `CLAUDE.md`:

```
| @PermuteReturn replaceLastTypeArgWith | New attribute: generates return type as current generated class with its type params minus the last, which is replaced by the literal value. Mutually exclusive with typeArgs — explicit typeArgs wins if both are set. Implemented in both InlineGenerator and PermuteProcessor by reading classDecl.getTypeParameters(), replacing last, joining with ", ". Eliminates the ternary typeArgList expression on type() in JoinBuilder. |
```

Stage only the files changed for this issue. Stop for review before committing.
