# Method Rename + BaseTuple Full Template Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `ElementType.METHOD` support to `@PermuteDeclr` (rename method name + return type per permutation), then use it to fully template `BaseTuple`'s inner `Tuple1..6` classes, eliminating 5 hand-written arity variants.

**Architecture:** Three-phase: (1) extend the annotation and transformer, (2) fix `renameAllUsages` to cover `FieldAccessExpr` (needed for `this.field = x` patterns in setters), (3) restructure `BaseTuple` and create an inline template. No new annotations; no changes to the Maven plugin. All new behaviour is in `PermuteDeclrTransformer` and the mvn-examples source tree.

**Tech Stack:** Java 17, JavaParser AST (already used), Google compile-testing (already used), Permuplate Maven plugin (inline templates in `src/main/permuplate/`).

---

## Context — what this builds on

The delegation-to-super refactoring of `BaseTuple` is already done (each `Tuple2..6.get/set` now handles only its own index, delegates to `super`). That makes the template feasible. What blocks full templating today:

1. `@PermuteDeclr` has no `ElementType.METHOD` target — can't rename `getA()` → `getB()`.
2. `renameAllUsages` walks `NameExpr` only — `this.a = x` (a `FieldAccessExpr`) is not renamed when field `a` → `b`.
3. `BaseTuple.get/set` are **abstract** — `return super.get(index)` in the template `Tuple1` is a compile error (calling abstract method through super). Must change to **concrete** (default-throw).

---

## File Map

| Action | File |
|--------|------|
| Modify | `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDeclr.java` |
| Modify | `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java` |
| Create | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/example/GetterRename2.java` |
| Modify | `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java` |
| Modify | `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseTuple.java` |
| Create | `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java` |

---

## Task 1 — Add `ElementType.METHOD` to `@PermuteDeclr`

**Files:**
- Modify: `permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDeclr.java`

- [ ] **Step 1: Add METHOD to @Target**

In `PermuteDeclr.java`, change the `@Target` line from:
```java
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.PARAMETER, ElementType.TYPE_USE })
```
to:
```java
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.PARAMETER,
          ElementType.TYPE_USE, ElementType.METHOD })
```

Also extend the Javadoc `<ul>` block to include:
```
 * <li><b>Method</b> — renames the method declaration itself: {@code name} sets the new
 *     method name, {@code type} (optional) sets the new return type. No body propagation —
 *     body identifier renames are driven by the field-level {@code @PermuteDeclr}.</li>
```

- [ ] **Step 2: Verify annotation compiles**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-annotations -q
```
Expected: `BUILD SUCCESS`

---

## Task 2 — Implement `transformMethods()` in `PermuteDeclrTransformer`

**Files:**
- Modify: `permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java`

- [ ] **Step 1: Add `transformMethods()` call to `transform()`**

In `transform()`, add the call just before `transformMethodParams()`:
```java
// Method declarations — rename name and/or return type per permutation
transformMethods(classDecl, ctx, messager);
// Method parameters — type always; name+body rename only when name is non-empty
transformMethodParams(classDecl, ctx, messager);
```

- [ ] **Step 2: Add the `transformMethods()` implementation**

Add this private static method after the `transformFields()` block (around line 127):
```java
// -------------------------------------------------------------------------
// Method declarations — rename name and/or return type
// -------------------------------------------------------------------------

private static void transformMethods(TypeDeclaration<?> classDecl,
        EvaluationContext ctx,
        Messager messager) {
    List<MethodDeclaration> annotated = new ArrayList<>();
    classDecl.getMethods().forEach(m -> {
        if (hasPermuteDeclr(m.getAnnotations()))
            annotated.add(m);
    });

    for (MethodDeclaration method : annotated) {
        AnnotationExpr ann = getPermuteDeclr(method.getAnnotations());
        String[] params = extractTwoParams(ann, messager);
        if (params == null)
            continue;

        String newType = params[0].isEmpty() ? "" : ctx.evaluate(params[0]);
        String newName = params[1].isEmpty() ? "" : ctx.evaluate(params[1]);

        if (!newType.isEmpty()) {
            method.setType(StaticJavaParser.parseType(newType));
        }
        if (!newName.isEmpty()) {
            method.setName(newName);
        }
        method.getAnnotations().remove(ann);
    }
}
```

> **Note:** `extractTwoParams` reads `[type, name]` from the annotation. For method rename-only (no return type change), set `type=""` on the annotation (or just omit it — but `type` has no default, so callers must supply it). For setter-style renames where the return type stays `void`, pass an empty string: `@PermuteDeclr(type="", name="set${alpha(i)}")`. Update `extractTwoParams` to allow `type` to be empty if that is not already supported. Check the implementation.

- [ ] **Step 3: Handle `type=""` for void methods**

Look at `extractTwoParams` (search for it in the file). If it currently validates that `type` is non-empty, relax that check — allow `type=""` to mean "keep the existing return type". The change should be: if `newType.isEmpty()` skip `setType()` (already handled in the code above).

If `extractTwoParams` enforces non-empty type (it likely prints an error), change the validation to allow an empty string, since for method renames the return type is often unchanged:
```java
// In extractTwoParams or just in transformMethods():
// type="" is allowed for METHOD targets — means "keep existing return type"
```

- [ ] **Step 4: Extend `renameAllUsages` to cover `FieldAccessExpr`**

The current `renameAllUsages` only renames `NameExpr` nodes. This misses `this.fieldName` patterns (which are `FieldAccessExpr`). Without this fix, a setter body `this.a = a;` would become `this.a = b;` after field rename `a→b` — the `this.a` part is not renamed, causing a compile error (field no longer exists under that name).

Add a second `visit` override to the `ModifierVisitor` inside `renameAllUsages`:
```java
static void renameAllUsages(Node scope, String oldName, String newName) {
    scope.accept(new ModifierVisitor<Void>() {
        @Override
        public Visitable visit(NameExpr n, Void arg) {
            if (n.getNameAsString().equals(oldName))
                return new NameExpr(newName);
            return super.visit(n, arg);
        }

        @Override
        public Visitable visit(FieldAccessExpr n, Void arg) {
            // Rename `this.oldName` → `this.newName`
            if (n.getNameAsString().equals(oldName))
                n.setName(newName);
            return super.visit(n, arg);
        }
    }, null);
}
```

- [ ] **Step 5: Build core module**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-annotations,permuplate-core -q
```
Expected: `BUILD SUCCESS`

---

## Task 3 — Write and run the test for `@PermuteDeclr` on methods

**Files:**
- Create: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/example/GetterRename2.java`
- Modify: `permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PermuteDeclrTest.java`:
```java
/**
 * @PermuteDeclr on a method declaration renames the method name and return type.
 * GetterRename2<A> generates GetterRename3<A,B> and GetterRename4<A,B,C>, each
 * adding one named getter and one named setter.
 */
@Test
public void testPermuteDeclrOnMethodRenamesNameAndReturnType() {
    var compilation = Compiler.javac()
            .withProcessors(new PermuteProcessor())
            .compile(templateSource(GetterRename2.class));
    assertThat(compilation).succeeded();

    var src3 = sourceOf(compilation.generatedSourceFile(
            generatedClassName(GetterRename2.class, 3)).orElseThrow());

    // Type params expanded
    assertThat(src3).contains("GetterRename3<A, B>");

    // Getter renamed: return type changed + method name changed
    assertThat(src3).contains("public B getB()");
    assertThat(src3).contains("return b;");

    // Setter renamed: method name changed, param type+name changed
    assertThat(src3).contains("public void setB(B b)");
    assertThat(src3).contains("this.b = b;");

    // No leftover annotation noise
    assertThat(src3).doesNotContain("@PermuteDeclr");

    var src4 = sourceOf(compilation.generatedSourceFile(
            generatedClassName(GetterRename2.class, 4)).orElseThrow());
    assertThat(src4).contains("GetterRename4<A, B, C>");
    assertThat(src4).contains("public C getC()");
    assertThat(src4).contains("public void setC(C c)");
}
```

- [ ] **Step 2: Create the template `GetterRename2.java`**

```java
package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteTypeParam;

@Permute(varName = "i", from = "3", to = "4", className = "GetterRename${i}")
public class GetterRename2<
        @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") A> {

    @PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
    protected A a;

    @PermuteDeclr(type = "${alpha(i)}", name = "get${alpha(i)}")
    public A getA() {
        return a;
    }

    @PermuteDeclr(type = "", name = "set${alpha(i)}")
    public void setA(@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}") A a) {
        this.a = a;
    }
}
```

> Note: `type=""` on the setter's `@PermuteDeclr` means "keep the `void` return type, only rename the method". The template class has `A a` as the pre-existing field (Tuple1 analog). `@PermuteTypeParam` on `A` with `from="2"` keeps `A` fixed and adds `B`, `C`, … — matching the "non-first type param" pattern.

- [ ] **Step 3: Run the test to verify it fails**

```bash
/opt/homebrew/bin/mvn test -pl permuplate-tests -Dtest=PermuteDeclrTest#testPermuteDeclrOnMethodRenamesNameAndReturnType -q 2>&1 | tail -20
```
Expected: test fails (method not renamed yet).

- [ ] **Step 4: Run full build to make the test pass**

```bash
/opt/homebrew/bin/mvn clean install -q
```
Expected: `BUILD SUCCESS` with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDeclr.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/example/GetterRename2.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java
git commit -m "feat: @PermuteDeclr on methods — rename method name and return type per permutation"
```

---

## Task 4 — Restructure `BaseTuple`: abstract → concrete, add `unchecked()` helper

**Files:**
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseTuple.java`

The current `BaseTuple.get()` and `set()` are `abstract`. The template `Tuple1` (which extends `BaseTuple`) would call `return super.get(index)` — a compile error against an abstract method. Making them concrete (default-throw) allows `Tuple1`'s `get/set` to safely call `super.get/set` for out-of-range indices.

Also add a private `unchecked()` cast helper to avoid explicit cast types in the template body (e.g., `(A) t`) — after field renaming `a→b`, the cast type `(A)` wouldn't be renamed, causing a type mismatch compile error. The helper infers the cast type from context.

- [ ] **Step 1: Change `get()` and `set()` from abstract to concrete**

Replace:
```java
public abstract <T> T get(int index);

public abstract <T> void set(int index, T t);
```

With:
```java
@SuppressWarnings("unchecked")
public <T> T get(int index) {
    throw new IndexOutOfBoundsException(index);
}

@SuppressWarnings("unchecked")
public <T> void set(int index, T t) {
    throw new IndexOutOfBoundsException(index);
}
```

- [ ] **Step 2: Add `unchecked()` cast helper**

Add this private method to `BaseTuple` (before the `Tuple0` inner class):
```java
@SuppressWarnings("unchecked")
static <F> F unchecked(Object o) {
    return (F) o;
}
```

This allows the template's `get/set` bodies to write `return unchecked(a)` and `a = unchecked(t)` instead of explicit `(A) ...` casts. Since `A` → `B` is a rename that doesn't touch cast type expressions, avoiding the explicit cast prevents a type mismatch after field renaming.

- [ ] **Step 3: Build and run tests**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples -q
```
Expected: `BUILD SUCCESS` — all 74 mvn-examples tests pass.

---

## Task 5 — Create the `BaseTuple` inline template

**Files:**
- Create: `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java`
- Modify: `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseTuple.java` (remove Tuple2..6)

The Maven plugin reads from `src/main/permuplate/`, generates augmented output to `target/generated-sources/permuplate/`. The outer class `BaseTuple` and non-template inner classes (`Tuple0`, `Tuple1`) are preserved via `keepTemplate=true`. The template `Tuple1` generates `Tuple2..6` as siblings inside `BaseTuple`.

- [ ] **Step 1: Create the template file**

Create `permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java`:

```java
package io.quarkiverse.permuplate.example.drools;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteExtends;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteStatements;
import io.quarkiverse.permuplate.PermuteTypeParam;
import io.quarkiverse.permuplate.PermuteValue;

/**
 * Abstract base for the typed tuple hierarchy used by OOPath traversal.
 * Tuple0 is hand-written. Tuple1 is the template (kept via keepTemplate=true).
 * Tuple2..Tuple6 are generated by Permuplate from the Tuple1 template.
 *
 * <p>
 * Each TupleN adds one field ({@code a}, {@code b}, …) with a typed
 * getter/setter, and delegates out-of-range index access to its parent.
 * This delegation makes each class express only what is new about it.
 */
public abstract class BaseTuple {
    protected int size;

    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        throw new IndexOutOfBoundsException(index);
    }

    @SuppressWarnings("unchecked")
    public <T> void set(int index, T t) {
        throw new IndexOutOfBoundsException(index);
    }

    public int size() {
        return size;
    }

    /**
     * Projects this tuple's values into an instance of the target type, inferred
     * from the assignment context via the Java varargs type-capture trick.
     *
     * <p>
     * Supports:
     * <ul>
     * <li><b>Records</b> — calls the canonical constructor with tuple values in order.
     * <li><b>Classes</b> — finds a constructor whose parameter count matches the tuple size
     * and calls it with tuple values in order.
     * </ul>
     */
    @SuppressWarnings({ "unchecked", "varargs" })
    public <T> T as(T... v) {
        Class<T> clazz = (Class<T>) v.getClass().getComponentType();
        try {
            Object[] args = new Object[size()];
            for (int i = 0; i < size(); i++) {
                args[i] = get(i);
            }
            for (java.lang.reflect.Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == size()) {
                    ctor.setAccessible(true);
                    return (T) ctor.newInstance(args);
                }
            }
            throw new IllegalArgumentException(
                    "No constructor with " + size() + " parameters found on " + clazz.getName());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("as() projection failed for " + clazz.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static <F> F unchecked(Object o) {
        return (F) o;
    }

    public static class Tuple0 extends BaseTuple {
        public Tuple0() {
            this.size = 0;
        }

        @Override
        public <T> T get(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public <T> void set(int index, T t) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    @Permute(varName = "i", from = "2", to = "6", className = "Tuple${i}",
             inline = true, keepTemplate = true)
    @PermuteExtends("Tuple${i-1}<${typeArgList(1, i-1, 'alpha')}>")
    public static class Tuple1<
            @PermuteTypeParam(varName = "k", from = "1", to = "${i}", name = "${alpha(k)}") A>
            extends BaseTuple {

        @PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}")
        protected A a;

        @PermuteValue(index = 0, value = "${i}")
        public Tuple1() {
            this.size = 1;
        }

        @PermuteStatements(position = "first", body = "super(${typeArgList(1, i-1, 'lower')});")
        @PermuteParam(varName = "k", from = "1", to = "${i}", type = "${alpha(k)}", name = "${lower(k)}")
        @PermuteValue(index = 1, value = "${i}")
        public Tuple1(A a) {
            this.a = a;
            this.size = 1;
        }

        @PermuteDeclr(type = "${alpha(i)}", name = "get${alpha(i)}")
        public A getA() {
            return a;
        }

        @PermuteDeclr(type = "", name = "set${alpha(i)}")
        public void setA(@PermuteDeclr(type = "${alpha(i)}", name = "${lower(i)}") A a) {
            this.a = a;
        }

        @Override
        public <T> T get(int index) {
            @PermuteConst("${i-1}") int idx = 0;
            if (index == idx)
                return unchecked(a);
            return super.get(index);
        }

        @Override
        public <T> void set(int index, T t) {
            @PermuteConst("${i-1}") int idx = 0;
            if (index == idx) {
                a = unchecked(t);
                return;
            }
            super.set(index, t);
        }
    }
}
```

**Key annotation choices explained:**

| Annotation | Why |
|---|---|
| `@PermuteExtends("Tuple${i-1}<...>")` | Generated Tuple2 extends Tuple1<A>, Tuple3 extends Tuple2<A,B>, etc. Tuple1 (kept) keeps `extends BaseTuple`. |
| `@PermuteTypeParam(k, 1, ${i}, alpha(k))` on A | A expands → [A,B] for i=2, [A,B,C] for i=3, etc. From=1 so A itself is included in the expansion. |
| `@PermuteDeclr(type=alpha(i), name=lower(i))` on field | For i=2: `A a` → `B b`. Propagates rename through all body usages via `renameAllUsages`. |
| `@PermuteValue(index=0, value="${i}")` on no-arg ctor | `this.size = 1` → `this.size = 2` (etc.). Java auto-inserts `super()` calling parent's no-arg ctor. |
| `@PermuteStatements(first, super(...))` on full ctor | Inserts `super(a)` (i=2), `super(a, b)` (i=3), etc. at top of body BEFORE field assignment. |
| `@PermuteParam(k, 1, ${i}, ...)` on full ctor | Expands `A a` → `(A a, B b)` for i=2, etc. |
| `@PermuteValue(index=1, value="${i}")` on full ctor | `this.size = 1` (statement 1) → `this.size = 2`, etc. Index evaluated BEFORE @PermuteStatements. |
| `@PermuteDeclr(type=alpha(i), name=get${alpha(i)})` on getter | Rename method: `getA()` → `getB()`, return type `A` → `B`. Body `return a` already renamed by field rename. |
| `@PermuteDeclr(type="", name=set${alpha(i)})` on setter | Rename method: `setA()` → `setB()`. `type=""` = keep `void`. |
| `@PermuteDeclr(type=alpha(i), name=lower(i))` on setter param | Rename param: `A a` → `B b`. Body propagated separately. |
| `@PermuteConst("${i-1}")` on `int idx` | `idx=0` for Tuple1 (kept, annotation stripped), `idx=1` for Tuple2, `idx=2` for Tuple3, etc. |
| `unchecked(a)` / `unchecked(t)` instead of `(A) a` | Avoids explicit cast type which would NOT be renamed by `renameAllUsages`. Java infers the cast type from assignment context. |

**Transformation trace for i=2 (Tuple2):**
```
Field: protected A a → protected B b  (renameAllUsages: a→b throughout class)
No-arg ctor: Tuple2() { this.size = 2; }
Full ctor:   Tuple2(A a, B b) { super(a); this.b = b; this.size = 2; }
Getter:      public B getB() { return b; }
Setter:      public void setB(B b) { this.b = b; }
get():       int idx = 1; if (index == 1) return unchecked(b); return super.get(index);
set():       int idx = 1; if (index == 1) { b = unchecked(t); return; } super.set(index, t);
Extends:     extends Tuple1<A>
```

**Note on `@PermuteConst` import:** `@PermuteConst` is used inside method bodies on local variables. Ensure it is imported (`import io.quarkiverse.permuplate.PermuteConst;`). Add it to the import block in the template file.

- [ ] **Step 2: Remove Tuple2..Tuple6 from the hand-written BaseTuple**

In `permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseTuple.java`, delete all inner class declarations from `Tuple2` through `Tuple6` (the closing brace of `Tuple6` through just before the final `}` of the file). Keep `Tuple0` and `Tuple1`. The template will generate Tuple2..6.

Also add the `unchecked()` helper and change `get/set` from abstract to concrete (this was done in Task 4, but confirm it's in this file too if that file is still the main source).

**Wait** — after creating the template in `src/main/permuplate/`, the Maven plugin will generate a new `BaseTuple.java` in `target/generated-sources/permuplate/`. The `src/main/java/` version is the **source of truth** for the outer class (Tuple0, Tuple1). The plugin reads `src/main/permuplate/BaseTuple.java` as the template, generates the augmented version in target. The `src/main/java/BaseTuple.java` is NOT compiled (only `src/main/permuplate/` and `target/generated-sources/permuplate/` feed the build).

So: **move** `BaseTuple.java` entirely to `src/main/permuplate/`. The complete file is the template. Delete `src/main/java/.../BaseTuple.java`.

- [ ] **Step 3: Delete the hand-written `src/main/java` version**

```bash
rm permuplate-mvn-examples/src/main/java/io/quarkiverse/permuplate/example/drools/BaseTuple.java
```

- [ ] **Step 4: Build and verify generated output**

```bash
/opt/homebrew/bin/mvn clean install -pl permuplate-mvn-examples 2>&1 | tail -20
```

Check the generated file:
```bash
grep "public static class" \
  permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java
```

Expected output:
```
    public static class Tuple0 extends BaseTuple {
    public static class Tuple1<A> extends BaseTuple {
    public static class Tuple2<A, B> extends Tuple1<A> {
    public static class Tuple3<A, B, C> extends Tuple2<A, B> {
    public static class Tuple4<A, B, C, D> extends Tuple3<A, B, C> {
    public static class Tuple5<A, B, C, D, E> extends Tuple4<A, B, C, D> {
    public static class Tuple6<A, B, C, D, E, F> extends Tuple5<A, B, C, D, E> {
```

Also check a getter:
```bash
grep "getB\|getC\|setB\|setC" \
  permuplate-mvn-examples/target/generated-sources/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java | head -8
```

Expected (Tuple2 and Tuple3):
```
        public B getB() {
        public void setB(B b) {
        public C getC() {
        public void setC(C c) {
```

- [ ] **Step 5: Run full test suite**

```bash
/opt/homebrew/bin/mvn clean install -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

---

## Task 6 — Update CLAUDE.md and commit

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the @PermuteDeclr row**

Find the `@PermuteDeclr` row in the annotations table and update its Target column from `field, parameter, for-each var` to `field, parameter, for-each var, method`.

- [ ] **Step 2: Add a Key non-obvious decisions row**

In the "Key non-obvious decisions and past bugs" table, add:

| `@PermuteDeclr` on methods | Renames the method name and (optionally) return type. `type=""` means keep existing return type (useful for `void` setters). No call-site propagation — only the signature changes. `renameAllUsages` also covers `FieldAccessExpr` (`this.fieldName`) so that setter/constructor bodies with explicit `this.` access are correctly renamed when the field is renamed. |
| BaseTuple Tuple1..6 inline template | Template is in `src/main/permuplate/`. Tuple0 and Tuple1 are kept (`keepTemplate=true`). Tuple2..6 generated. `BaseTuple.get/set` must be concrete (not abstract) so `return super.get(index)` compiles in Tuple1. `unchecked()` helper avoids explicit cast types that would be left stale after field renaming. |

- [ ] **Step 3: Create issues and commit**

Create two GitHub issues:
```bash
gh issue create --title "@PermuteDeclr: add ElementType.METHOD support for method rename + return type" --label "enhancement"
gh issue create --title "BaseTuple: full Permuplate template — Tuple2..6 generated from Tuple1" --label "enhancement"
```

Commit everything:
```bash
git add permuplate-annotations/src/main/java/io/quarkiverse/permuplate/PermuteDeclr.java \
        permuplate-core/src/main/java/io/quarkiverse/permuplate/core/PermuteDeclrTransformer.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/example/GetterRename2.java \
        permuplate-tests/src/test/java/io/quarkiverse/permuplate/PermuteDeclrTest.java \
        permuplate-mvn-examples/src/main/permuplate/io/quarkiverse/permuplate/example/drools/BaseTuple.java \
        CLAUDE.md
git commit -m "feat: @PermuteDeclr on methods + BaseTuple fully templated (Tuple2..6 generated)

Closes #<annotation-issue> #<basetuple-issue>"
git push
```

---

## Self-Review

**Spec coverage:**
- ✅ `@PermuteDeclr` on methods (Task 1–3)
- ✅ `renameAllUsages` FieldAccessExpr fix (Task 2, step 4)
- ✅ `BaseTuple` abstract→concrete get/set (Task 4)
- ✅ `unchecked()` helper (Task 4)
- ✅ `BaseTuple` template (Task 5)
- ✅ CLAUDE.md updated (Task 6)

**Potential issues to watch:**
1. `extractTwoParams` — currently enforces non-empty `type`. Check whether passing `type=""` for void setters causes an error; if so, relax the validation in `PermuteDeclrTransformer`.
2. `@PermuteConst` import — the template uses `@PermuteConst` on local variables. Ensure the import `io.quarkiverse.permuplate.PermuteConst` is present in the template file.
3. `@PermuteExtends` — inline mode only. Verify that `PermuteExtends` is properly imported in the template and that the plugin processes it for inner class templates (confirmed by `RuleExtendsPoint` which uses the same pattern).
4. Setter body `this.a = a` — with `renameAllUsages` FieldAccessExpr fix, `this.a` → `this.b`. The parameter `a` (NameExpr) → `b`. Then `@PermuteDeclr` on param also renames `a→b` (no-op since already renamed). Final: `this.b = b` ✓
5. `@PermuteValue index=1` on full ctor — body is `{ this.a = a; this.size = 1; }`, index 0 = `this.a = a`, index 1 = `this.size = 1`. @PermuteValue replaces RHS of stmt 1 → `this.size = i`. ✓
6. Tuple1 kept template — all Permuplate annotations stripped. Body: `this.size = 1`, `this.a = a`. `int idx = 0`. `return unchecked(a)`. `return super.get(index)` — calls BaseTuple.get() which now throws OOB (not abstract). ✓
