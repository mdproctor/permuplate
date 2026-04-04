# Permuplate — Non-Obvious Bugs and Behaviours

Things discovered the hard way building this annotation processor. Each entry was a real debugging session. Future Claude: don't retry the things that didn't work.

---

## `rd.asNext()` unchecked cast causes ClassCastException at runtime, not at the cast site

**Stack:** Permuplate inline generation, Java type erasure, JVM bytecode
**Symptom:** A fluent chain like `builder.from(...).join(...).filter(...)` compiles and works in isolation but throws `ClassCastException` when `.filter()` is called. The exception points to the `.filter()` call, not the `.join()` call.
**Context:** Any Permuplate-generated class where `join()` returns `rd.asNext()` — an unchecked cast of `RuleDefinition` to the declared return type (`JoinNFirst`).

### What was tried (didn't work)
- `@SuppressWarnings("unchecked")` on the cast — suppresses the compiler warning but doesn't prevent the runtime failure
- Returning `this` from `join()` — `RuleDefinition` doesn't extend `JoinNFirst`, so `this` can't be returned as the declared type
- `rd.asNext()` where `asNext()` is `@SuppressWarnings("unchecked") public <T> T asNext() { return (T) this; }` — looks correct, compiles fine, fails at runtime

### Root cause
When the compiled `join()` method has return type `JoinNFirst` (after Permuplate rewrites it from `Object`), the Java compiler inserts a `checkcast JoinNFirst` bytecode instruction at every call site that uses the return value. This `checkcast` fires when `.filter()` is invoked on the result — not at the `asNext()` call. Since `RuleDefinition` doesn't extend `JoinNFirst`, the cast fails. The fix cannot be purely in the method body.

### Fix
Use reflective instantiation to create a real `JoinNFirst` instance wrapping the same `RuleDefinition`:

```java
String cn = getClass().getSimpleName();           // "Join1First"
int n = Integer.parseInt(cn.replaceAll("[^0-9]", ""));
String nextName = getClass().getEnclosingClass().getName() + "$Join" + (n + 1) + "First";
return (T) Class.forName(nextName).getConstructor(RuleDefinition.class).newInstance(rd);
```

### Why this is non-obvious
`@SuppressWarnings("unchecked")` + the erasure principle ("generics are erased at runtime") leads you to believe the cast is never checked. It IS checked — just at the call site, not the cast point, and only when the declared return type is a concrete class (not `Object`). The symptom (exception on `.filter()`) doesn't point to the actual cause (wrong return type from `.join()`).

---

## JEXL3 does not autobox `Integer` to `int` for static method dispatch via namespaces

**Stack:** Apache Commons JEXL3, EvaluationContext, built-in functions
**Symptom:** Registering `alpha(int n)` as a static method via `JexlBuilder.namespaces("", MyFunctions.class)` — calling `${alpha(j)}` in a template throws `NoSuchMethodException` or silently returns null. Works fine when called with a literal int in Java but fails when called with a JEXL-evaluated integer variable.
**Context:** Any JEXL3 expression that calls a static method expecting a primitive `int` parameter with an integer variable as argument.

### What was tried (didn't work)
- `JexlBuilder.namespaces(Map.of("", MyFunctions.class))` with `alpha(int n)` — fails
- Overloading with `alpha(Integer n)` — partially works but JEXL3 uberspect is unreliable
- `JexlContext.set("alpha", ...)` with a `java.lang.reflect.Method` — fails

### Root cause
JEXL3's method resolution (uberspect) does not autobox `Integer` to `int` when looking up static methods in a namespace class. The integer variable from a JEXL expression arrives as `java.lang.Integer` boxed, but the method expects primitive `int`. The uberspect can't find the method and silently skips or throws.

### Fix
Register functions as JEXL lambda scripts in the `MapContext` directly — not as namespace classes:

```java
JexlScript alphaScript = jexl.createScript("n -> { ... }");
ctx.set("alpha", alphaScript);
// Then in expressions: ${alpha(j)} works because j is passed as-is to the lambda
```

Lambda scripts receive arguments without type-matching requirements, so the Integer/int problem disappears.

### Why this is non-obvious
The JEXL3 documentation shows namespace registration as the standard approach for extension functions. It works for `String` parameters and for methods explicitly typed as `Integer` but fails inconsistently for `int`. The error message (if any) doesn't mention autoboxing. This is a well-hidden JEXL3 limitation.

---

## `cu.getClassByName()` misses nested classes — must use `cu.findFirst()`

**Stack:** JavaParser, `CompilationUnit`, nested class lookup
**Symptom:** `cu.getClassByName("NestedClass")` returns `Optional.empty()` even though the class clearly exists in the source. The class is a nested static class inside a parent class.
**Context:** Any JavaParser traversal looking for a class that is nested (not top-level).

### What was tried (didn't work)
- `cu.getClassByName("NestedClass")` — returns empty
- `cu.getClassByName("OuterClass.NestedClass")` — returns empty (doesn't accept qualified names)

### Fix
```java
cu.findFirst(ClassOrInterfaceDeclaration.class,
    c -> c.getNameAsString().equals("NestedClass"))
```

`findFirst` with a predicate does a recursive traversal of the AST and finds nested types. `getClassByName` only searches top-level types in the CU.

### Why this is non-obvious
`getClassByName` sounds like a general "find any class by name" utility. It is not. The JavaParser docs don't prominently call this out. The symptom (empty optional) gives no indication of whether the class doesn't exist or the search is too shallow.

---

## `getCharContent(true)` required for in-memory compile-testing sources

**Stack:** JavaParser, Google compile-testing, APT processor
**Symptom:** `PermuteProcessor` works against real `.java` files but throws `IOException` or processes nothing when run via Google compile-testing's in-memory `JavaFileObjects.forSourceString(...)`.
**Context:** Any APT processor that reads source files by URI.

### What was tried (didn't work)
- `new File(sourceFile.toUri()).toPath()` — throws `IllegalArgumentException` for `mem://` URIs
- `Paths.get(sourceFile.toUri()).toFile()` — throws `FileSystemNotFoundException`

### Fix
```java
CharSequence content = sourceFile.getCharContent(true);
StaticJavaParser.parse(content.toString());
```

`getCharContent(true)` works for both file-based and in-memory sources, abstracting the URI scheme.

### Why this is non-obvious
The URI of an in-memory source has a `mem:` scheme. Converting it to `File` fails silently or with an opaque exception. `getCharContent()` is the correct JavaCompiler API for source content but isn't the first thing you reach for when you want to "read the file."

---

## G3 extends expansion produced forward references (Join2First extends Join3Second)

**Stack:** Permuplate `applyExtendsExpansion()`, InlineGenerator
**Symptom:** Template `Join1First<T1> extends Join1Second<T1>` with `@Permute(from=2, className="Join${i}First")` generates `Join2First<T1,T2> extends Join3Second<T1,T2,T3>` — the class extends the NEXT arity's Second, not its own.
**Context:** G3 extends clause expansion for any sibling-class hierarchy. The old test even had a wrong comment reinforcing the bug.

### What was tried (didn't work)
- Reading the code — the formula `newNum = currentEmbeddedNum + 1` looks intentional
- Trusting the test comment ("At i=2: Join3First extends Join3Second") — the comment was WRONG (at forI=2, the class is Join2First, not Join3First)

### Root cause
`newNum = currentEmbeddedNum + 1` was always the wrong formula. `newNum` should equal `currentEmbeddedNum` (same-N: Join2First extends Join2Second). The `+1` was a bug introduced at the time G3 was first written and never caught because no test ever asserted "does NOT extend Join3Second."

### Fix
```java
int newNum = currentEmbeddedNum;  // NOT currentEmbeddedNum + 1
```

Also update the existing test's `doesNotContain` assertions to verify same-N behavior is enforced.

### Why this is non-obvious
The `+1` formula is plausible if you squint — the extends class "advances" by one. The test described a wrong expected output with confidence. Reading the generated source is the only way to catch it; no compilation error results.

---

## G3 extends expansion silently skips alpha-named type args

**Stack:** Permuplate `applyExtendsExpansion()`, `@PermuteTypeParam`, alpha naming
**Symptom:** Template `Join0First<DS, A> extends Join0Second<DS, A>` with alpha naming generates `Join2First<DS, A, B>` correctly (class type params expanded) but the extends clause stays as `extends Join0Second<DS, A>` — completely unchanged. No error, no warning.
**Context:** Any use of alpha naming (A, B, C) with an extends clause targeting a sibling class.

### What was tried (didn't work)
- Checking the template — looks correct syntactically
- Running the build — compiles fine, but generated class has wrong extends
- Checking `@PermuteTypeParam` processing — it runs correctly, class type params expand fine

### Root cause
`applyExtendsExpansion()` required `allTNumber` — all type arguments in the extends clause must match the `T<digits>` pattern. `<DS, A>` fails this check (DS and A are not T+number). The method silently `continue`s and leaves the extends clause unchanged. There was no way to know this was happening other than inspecting the generated source.

### Fix
Add an alpha branch after the allTNumber check:
```java
} else {
    // Alpha case: extends type args must be a prefix of postG1TypeParams
    boolean isPrefix = extArgNames.size() <= postG1TypeParams.size()
            && IntStream.range(0, extArgNames.size())
                       .allMatch(k -> extArgNames.get(k).equals(postG1TypeParams.get(k)));
    if (!isPrefix) continue;
    newTypeArgNames = postG1TypeParams;
}
```

`postG1TypeParams` is the list of type parameter names AFTER `@PermuteTypeParam` has expanded the class type params. This was already captured (line 91-92 of InlineGenerator.generate() with a TODO comment) but not wired to `applyExtendsExpansion`.

### Why this is non-obvious
Silent `continue` means you see correct class type params but wrong extends. The TODO comment "used in Task 5" existed in the code next to `postG1TypeParams` capture — but "Task 5" referred to an internal plan, not something visible in the git history. The bug was invisible unless you diffed the generated source against the expected output.

---

## Consumer/Predicate `to="${i-1}"` not `to="${i}"` — spec had it wrong

**Stack:** Permuplate `@PermuteTypeParam`, `@PermuteParam`, Consumer/Predicate templates
**Symptom:** Using `to="${i}"` on `@PermuteTypeParam` in Consumer1 template with `@Permute(from=2, to=7, className="Consumer${i}")` generates `Consumer2<DS, A, B>` — two fact type params — but `Consumer2` should have only one fact type param (A). The type param count is off by one.
**Context:** Any template where the class name number (i) counts both DS and the expanding type params.

### Root cause
At i=2 (generating Consumer2), `to="${i}"` evaluates to 2, generating j=1..2, producing `A, B` — making `Consumer2<DS, A, B>` with 3 total type params. But `Consumer2` means DS + 1 fact = 2 params total, so we want only `A`. The correct range is `to="${i-1}"` — at i=2, j=1..1, producing just `A`.

### Fix
```java
@PermuteTypeParam(varName="j", from="1", to="${i-1}", name="${alpha(j)}")
@PermuteParam(varName="j", from="1", to="${i-1}", ...)
```

### Why this is non-obvious
The spec document initially said `to="${i}"` and was wrong. The `to="${i-1}"` correction is non-obvious because `i` is the "arity number" in the class name, and you'd expect the expansion to go from 1 to i. The subtlety: `i` already counts DS, so the fact params only fill `i-1` slots.

---

## `wrapPredicate` arity truncation — intermediate filters see too many facts

**Stack:** `RuleDefinition.run()`, NaryPredicate, cross-product join evaluator
**Symptom:** A filter added at arity 2 (Predicate3, 3 params: ctx+a+b) throws `IllegalArgumentException: wrong number of arguments` when the rule also has a join at arity 3. The filter works fine if the rule only has 2 fact sources.
**Context:** Any multi-arity rule where filters are added at different arity levels.

### Root cause
`run()` builds the full cross-product of ALL sources, then applies ALL filters to each full-arity fact tuple. A Predicate3 registered at arity 2 expects 3 args (ctx + 2 facts). If there are 3 fact sources, the tuple has 3 facts. The reflective `m.invoke(predicate, ctx, fact1, fact2, fact3)` sends 4 args to a 3-param method.

### Fix
```java
int factArity = m.getParameterCount() - 1;  // -1 for ctx
return (ctx, facts) -> {
    Object[] trimmed = facts.length > factArity 
        ? Arrays.copyOf(facts, factArity) : facts;
    return (Boolean) m.invoke(typed, buildArgs(ctx, trimmed));
};
```

Semantically correct: a filter registered at arity 2 should only see the first 2 facts. Facts accumulated after its registration point are irrelevant to it.

### Why this is non-obvious
The design of `RuleDefinition` (all filters in one list, applied to full tuples) seems straightforward until you mix arities. The bug only manifests when you add filters at intermediate arity steps (not at the final arity). A simple arity-1 test or an all-filters-at-max-arity test would pass.

---

## `PermuteTypeParamTransformer` not called for non-inline top-level templates in PermuteMojo

**Stack:** `PermuteMojo.generateTopLevel()`, `PermuteTypeParamTransformer`
**Symptom:** `@PermuteTypeParam` annotations are correctly processed in inline mode but silently ignored when `inline=false` and the template is a top-level class. Generated classes have the template's original single type parameter instead of the expanded list.
**Context:** Maven plugin, non-inline templates with explicit `@PermuteTypeParam`.

### Root cause
`PermuteMojo.generateTopLevel()` called `PermuteDeclrTransformer` and `PermuteParamTransformer` but forgot to call `PermuteTypeParamTransformer.transform()` for the non-inline path. The inline path (`InlineGenerator.generate()`) called it correctly.

### Fix
Add `PermuteTypeParamTransformer.transform(generated, ctx, null, null);` before `PermuteDeclrTransformer.transform()` in `generateTopLevel()`.

### Why this is non-obvious
The inline path works, so if you only test inline templates you never see the bug. Top-level templates compile fine (the template type param `A` stays as `A`) — the failure is that you have `Consumer2<DS, A>` instead of `Consumer2<DS, A, B>` which may only become visible when you try to use the generated class.

---

## `@PermuteMethod.to` inferral: `@Permute.to - i`, not `@Permute.to - 1`

**Stack:** `@PermuteMethod`, InlineGenerator, `applyPermuteMethod()`
**Symptom:** `@PermuteMethod(varName="j")` with `to` not specified behaves differently than expected — generates fewer overloads than intended.
**Context:** Multi-step join overloads where the number of overloads decreases as arity increases (leaf node pattern).

### Root cause
The inference is `to = @Permute.to - i` (subtract the CURRENT generated class's value), not `@Permute.to - 1` (a fixed value). For `@Permute(to=6)` at i=4 (Join4Second), `to = 6 - 4 = 2` — meaning `j` generates 2 overloads (j=1, j=2). At i=6 (Join6Second), `to = 6 - 6 = 0` — empty range, no overloads. This is the leaf-node elimination mechanism.

### Why this is non-obvious
The `- i` dependency on the outer loop variable is not intuitive. It looks like it should be a fixed "max overloads" constant. The correct mental model: the number of join-advance overloads decreases as you accumulate more facts, reaching zero at the maximum arity (leaf node).

---

## Constructor names not automatically updated after class rename in JavaParser

**Stack:** JavaParser, `ClassOrInterfaceDeclaration.setName()`
**Symptom:** Renaming a class via `classDecl.setName("NewName")` compiles and generates a class named `NewName`, but the constructor inside it is still named `OldName`. The generated Java won't compile.
**Context:** Any Permuplate template class that has an explicit constructor.

### Fix
```java
classDecl.setName(newClassName);
classDecl.getConstructors().forEach(ctor -> ctor.setName(newClassName));
```

### Why this is non-obvious
In a regular Java IDE, renaming a class renames its constructors automatically. JavaParser is a parser, not a refactoring tool — `setName` on the class declaration only changes the class name node, not the constructor nodes, which are siblings in the AST, not children of the class name.
