---
layout: post
title: "Transforming the Template"
date: 2026-04-04
phase: 1
phase_label: "Phase 1 — The Annotation Processor"
---
# Transforming the Template

*Part 2 of the Permuplate development series.*

---

## The Gap Between "Clone" and "Transform"

Once Claude and I had basic cloning working — parse template, copy class, rename it — the question was what actual transformation means in this context.

The Drools `Join2` class looks something like this:

```java
public class Join2 {
    private Callable2 c2;

    public Join2(Callable2 c2) {
        this.c2 = c2;
    }

    public void execute(Object o1, Object o2) {
        c2.call(o1, o2);
    }
}
```

For `Join3`, you need:

```java
public class Join3 {
    private Callable3 c3;

    public Join3(Callable3 c3) {
        this.c3 = c3;
    }

    public void execute(Object o1, Object o2, Object o3) {
        c3.call(o1, o2, o3);
    }
}
```

Three things changed: the field type and name (`Callable2 c2` → `Callable3 c3`), the constructor parameter, and the `execute` method's parameter list plus the call site inside it. That last part — `c2.call(o1, o2)` expanding to `c3.call(o1, o2, o3)` — is the interesting one. It's not enough to expand the parameter list. You have to find the call sites that use those parameters and expand them too.

---

## @PermuteDeclr: Renaming With Scope Awareness

The `@PermuteDeclr` annotation handles the field renaming problem. You annotate the field:

```java
private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
```

At transform time, the processor evaluates `type` and `name` with the current value of `i`, updates the `VariableDeclarator` for `c2`, and then — this is the critical part — scans the entire class body for `NameExpr` nodes that reference `c2` and replaces them with `c3`.

The scope matters. Permuplate has three placement modes for `@PermuteDeclr`:

- **Field** → class-wide scope. Every reference to `c2` in any method gets renamed.
- **Constructor parameter** → constructor body only.
- **For-each loop variable** → loop body only.

Getting the scope right took some careful thought. The for-each case is subtle: `getVariable()` on a `ForEachStmt` returns a `VariableDeclarationExpr`, not a `Parameter`. The type lives on `getVariables().get(0)`, not directly on the statement. Early versions got this wrong and produced malformed output — the type would update but the loop variable wouldn't, or vice versa.

The transform order also matters. Fields must be processed first because they have class-wide scope — their renames need to be applied before the for-each loop body walker runs, so it sees the already-renamed field references. Getting this order wrong produces bugs that are subtle and hard to notice.

---

## @PermuteParam: The Sentinel Pattern

The `@PermuteParam` annotation handles the parameter expansion problem. You annotate a single "sentinel" parameter:

```java
public void execute(
        @PermuteParam(varName = "j", from = "1", to = "${i}", 
                      type = "Object", name = "o${j}") Object o1) {
    c2.call(o1);
}
```

The sentinel `o1` is replaced by a generated sequence. At `i=3`, the expansion produces `Object o1, Object o2, Object o3`. At `i=5`, it produces five parameters.

But the call site — `c2.call(o1)` — also needs to expand. The solution is the **anchor** concept: the sentinel's original name (`o1`) is registered as an anchor. Every method call in the body where `o1` appears as an argument has it replaced by the full generated argument sequence. So `c2.call(o1)` becomes `c2.call(o1, o2, o3)` automatically.

The anchor propagation uses a `ModifierVisitor` over `MethodCallExpr` nodes. For each call, if the anchor name appears in the argument list, replace it with the sequence. Parameters before and after the sentinel argument are preserved in position.

This is why Permuplate templates are so unusual: a call like `c2.call(o1)` is valid Java (it compiles at arity 2), but at arity 3 it gets transformed to `c3.call(o1, o2, o3)` — both the receiver (`c2` → `c3`, via `@PermuteDeclr`) and the argument list (via `@PermuteParam` anchor). The template is simultaneously valid Java and a compact encoding of what to generate.

---

## The JavaParser Gotchas

Two JavaParser surprises made early development interesting.

The first: `cu.getClassByName("NestedClass")` returns `Optional.empty()` for nested classes. The method only searches top-level types in the compilation unit. This is obvious in hindsight — the class is nested, so it's not at the top level — but it took a debugging session to find it. The fix is `cu.findFirst(ClassOrInterfaceDeclaration.class, predicate)`, which does a recursive AST traversal.

The second: when you rename a class via `classDecl.setName("NewName")`, JavaParser does not update the constructor declarations inside the class. Constructors have their own `NameExpr` for the class name, and it's a sibling node, not a child of the class name node. The fix is a single loop after `setName`:

```java
classDecl.setName(newClassName);
classDecl.getConstructors().forEach(ctor -> ctor.setName(newClassName));
```

Missing this produces generated classes where the class is named `Join3` but the constructor is still named `Join2`. The compiler catches it immediately, but it was a confusing failure mode.

---

## @PermuteVar: The Cross-Product Axis

One more early feature worth covering: `@PermuteVar`, which adds additional loop axes.

The motivating example is something like `BiCallable${i}x${k}` — a callable with two independent arity axes. You want `BiCallable2x2`, `BiCallable2x3`, `BiCallable2x4`, `BiCallable3x2`, ... up to whatever bounds you specify. This is a cross-product: for every combination of `(i, k)`, generate one class.

```java
@Permute(varName = "i", from = 2, to = 4,
         className = "BiCallable${i}x${k}",
         extraVars = { @PermuteVar(varName = "k", from = 2, to = 4) })
public class BiCallable2x2 { ... }
```

The implementation builds all combinations first — a `List<Map<String,Object>>` where each map is one `(i, k)` pair — and then generates one output class per combination. The primary variable (`i`) is the outer loop; `extraVars` are inner loops in declaration order.

One constraint: the template class name must not collide with any generated class name. `BiCallable2x2` with `from=2` is fine because no generated class is named `BiCallable2x2`... wait, actually it is. The fix is to ensure the template's own name doesn't appear in the generated set. This rule — which later became important when doing a "two-pass scan" for boundary omission — was first articulated here.

---

## The Test Story: In-Process Compilation and a mem: URI Trap

By this point, we'd built a test suite using **Google's compile-testing library**. Each test compiles a Java source string in-process with the annotation processor attached and asserts on the generated source content:

```java
Compilation compilation = Compiler.javac()
        .withProcessors(new PermuteProcessor())
        .compile(source, callable2(), callable3());

assertThat(compilation).succeeded();
String src = sourceOf(compilation
        .generatedSourceFile("io.permuplate.example.Join3")
        .orElseThrow());
assertThat(src).contains("c3.call(o1, o2, o3)");
```

This testing approach has been invaluable throughout the project. In-process compilation means tests are fast (no subprocess overhead), and asserting on source text is deliberately simple — it doesn't require understanding the generated bytecode or running the generated classes. Just check that the right code appears in the right place.

One subtlety: `getCharContent(true)` is required to read source file content in this context. `new File(sourceFile.toUri())` fails for in-memory compile-testing sources, which use a `mem:` URI scheme. This is an easy mistake to make — the code looks identical to reading a real file, and the failure message is cryptic.

---

## Where Things Stood: Type-Erased and Incomplete

After the initial phase, Permuplate could:
- Generate N classes from a template with `@Permute`
- Rename fields and propagate the rename through the class body with `@PermuteDeclr`
- Expand a sentinel method parameter into a sequence with `@PermuteParam`, including auto-expanding anchor call sites
- Generate cross-products of classes with `@PermuteVar`

This was already useful. A `Callable1` template with the right annotations could generate `Callable2` through `Callable10` with one class definition. The "dogfooding" test — `Callable1` generates `Callable2`–`Callable10` — became a permanent fixture.

But the gap analysis against the full Drools DSL showed this wasn't enough. The hand-written Drools classes weren't just method-parameter permutations. They used **generic type parameters** — `Join2<DS, A, B>`, `Join3<DS, A, B, C>` — and the growing type parameter list was part of what made the API type-safe. Permuplate had no way to generate `<T1, T2, T3>`. All generated interfaces were type-erased.

That gap — and the architecture decision required to close it — led to one of the project's more significant pivots.

---

