# Permuplate

**Write one class. Compile it. Get N.**

Permuplate is a Java annotation processor that generates type-safe arity permutations from a single template class — at compile time, with no external tools, no committed generated sources, and no code the IDE cannot read.

---

## The problem Java doesn't solve

Java has no variadic generics. When you need the same logic across multiple arities — a join that works on 2-tuples, 3-tuples, 4-tuples, up to N-tuples — you have three options, and they all hurt:

**Option 1: Write them all by hand.**
You end up with 8 nearly-identical classes. When the shared logic changes, you edit 8 files.

**Option 2: Use an external code generator.**
You write a Freemarker template or a Python script. The template is not valid Java — your IDE can't refactor it, navigate it, or type-check it. The generated files are usually committed to source control. The generator is a separate tool that must be run manually or wired into the build.

**Option 3: Accept a worse API.**
Pass a `List<Object>` or use varargs and lose compile-time type safety entirely.

Permuplate is a fourth option.

---

## Why this is different from everything else

Arity permutation is a well-known problem in the Java ecosystem. Every existing solution involves a template that is *not* valid Java:

| Tool | Approach | Template is valid Java? | IDE refactor support? | No committed generated files? |
|---|---|:---:|:---:|:---:|
| Vavr / RxJava internal scripts | Python/Groovy script | No | No | No |
| Freemarker + Maven plugin | `.ftl` template | No | No | No |
| JavaPoet | Imperative generator class | — (no template) | No | Yes |
| Lombok | Fixed AST transformations | Yes | Partial | Yes |
| **Permuplate** | Annotated Java template | **Yes** | **Yes** | **Yes** |

Permuplate is the only approach where:

1. The template is a **real, compilable Java class** your IDE understands fully
2. Generation is triggered by **standard `javac`** with no extra build step
3. The processor is **AST-aware** — it renames usages with correct scope, expands call sites, and preserves all surrounding logic
4. **No generated files are committed** — they live only in `target/`

---

## How Permuplate works

You write **one template class** using standard Java. It compiles. Your IDE understands it. You can set breakpoints in it, rename its fields with refactor tooling, and navigate to its usages.

At compile time, `javac` invokes the Permuplate annotation processor. It reads the template's source via the compiler API, parses it with [JavaParser](https://javaparser.org), applies scope-aware AST transformations for each permutation value, and writes the generated variants directly into `target/generated-sources/annotations/` — no external script, no extra build step.

Three things make the transformations correct rather than just textual:

- **Scope-aware renaming** — `@PermuteDeclr` on a field propagates the rename through the entire class body; on a loop variable it is contained to the loop body only.
- **Positional parameter expansion** — `@PermuteParam` replaces a single sentinel parameter with N generated ones, preserving any fixed parameters before and after it.
- **Call-site inference** — the sentinel's original name is used as an anchor; every call in the method body that passes the anchor as an argument is automatically expanded to the full generated sequence, with no annotation required at the call site.

---

## Permuplate in Action: The Drools DSL Sandbox

Permuplate's practical value is best illustrated by the Drools RuleBuilder DSL sandbox — a fully type-safe rule-construction API built entirely from Permuplate-generated classes. Six phases of DSL evolution (arity-polymorphic joins, constraint scopes, OOPath traversal, variable binding, cross-rule inheritance, and named params) are each generated from a single annotated template.

See [`permuplate-mvn-examples/DROOLS-DSL.md`](permuplate-mvn-examples/DROOLS-DSL.md) for a complete walkthrough.

---

## Quick start

**Step 1: Add to your `pom.xml`**

```xml
<dependencies>
    <dependency>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-annotations</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.quarkiverse.permuplate</groupId>
                        <artifactId>quarkus-permuplate-processor</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </path>
                    <path>
                        <groupId>com.github.javaparser</groupId>
                        <artifactId>javaparser-core</artifactId>
                        <version>3.25.9</version>
                    </path>
                    <path>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-jexl3</artifactId>
                        <version>3.3</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

> **Why list transitive deps?** `maven-compiler-plugin` 3.x runs the processor in an isolated classloader. You must list the processor and all its runtime dependencies explicitly under `annotationProcessorPaths`.

**Step 2: Write a template class**

```java
@Permute(varName = "i", from = "3", to = "5", className = "Join${i}")
public class Join2 {

    private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
    private List<Object> right;

    public void join(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1) {
        for (@PermuteDeclr(type = "Object", name = "o${i}") Object o2 : right) {
            c2.call(o1, o2);
        }
    }
}
```

**Step 3: Run `mvn compile`**

`Join3.java`, `Join4.java`, and `Join5.java` appear in `target/generated-sources/annotations/`. No scripts. No committed generated files. Just `javac`.

---

## APT vs Maven Plugin

Permuplate offers two generation modes. Choose based on what your project needs.

### Annotation Processor (APT) — for top-level generation

The simplest setup. Add `permuplate-processor` to `annotationProcessorPaths` and `javac` invokes it automatically. Every permuted class becomes a separate top-level `.java` file.

**Use the APT when:**
- All your generated classes can be top-level files
- You want minimal build configuration
- You don't need `@Permute(inline = true)`

**Complete `pom.xml` configuration:**

```xml
<dependencies>
    <dependency>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-annotations</artifactId>
        <version>1.0-SNAPSHOT</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.quarkiverse.permuplate</groupId>
                        <artifactId>quarkus-permuplate-processor</artifactId>
                        <version>1.0-SNAPSHOT</version>
                    </path>
                    <path>
                        <groupId>com.github.javaparser</groupId>
                        <artifactId>javaparser-core</artifactId>
                        <version>3.25.9</version>
                    </path>
                    <path>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-jexl3</artifactId>
                        <version>3.3</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Template files live in `src/main/java/` as usual.

### Maven Plugin — for inline generation (and everything the APT does)

The Maven plugin runs in the `generate-sources` phase — before `javac` touches anything. It supports all APT functionality **plus** `@Permute(inline = true)`: permuted classes can be generated as nested siblings inside the parent class.

**Use the Maven plugin when:**
- You want generated classes nested inside a parent (`inline = true`)
- You prefer a pre-compilation approach (plugin runs before javac)
- You are switching from APT and want to keep all existing templates working

> **Important:** Do not configure both the APT processor and the Maven plugin in the same project. They would process the same annotations twice, producing duplicate generated classes and compile errors. When switching from APT to the plugin, remove `permuplate-processor` from `annotationProcessorPaths`.

**Complete `pom.xml` configuration:**

```xml
<dependencies>
    <dependency>
        <groupId>io.quarkiverse.permuplate</groupId>
        <artifactId>quarkus-permuplate-annotations</artifactId>
        <version>1.0-SNAPSHOT</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>io.quarkiverse.permuplate</groupId>
            <artifactId>quarkus-permuplate-maven-plugin</artifactId>
            <version>1.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <goals><goal>generate</goal></goals>
                </execution>
            </executions>
        </plugin>
        <!-- Disable APT — the Maven plugin handles all @Permute processing -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <compilerArgs>
                    <arg>-proc:none</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Template directories with the Maven plugin:**
- Non-inline templates (`inline = false`, the default): place in `src/main/java/` as normal
- Inline templates (`inline = true`): place in `src/main/permuplate/` — the plugin's dedicated template directory. These files are read by the plugin before javac runs; javac never compiles them directly.

**IDE setup for `src/main/permuplate/`:**

The template directory is intentionally not a Maven compile source root (to avoid duplicate class errors with the generated augmented version). Mark it as a source root in your IDE manually so refactoring and navigation work on your templates:

- **IntelliJ IDEA:** Right-click `src/main/permuplate` → *Mark Directory As → Sources Root*
- **VS Code:** Add the path to your Java project source directories in settings

---

## The killer example

The best way to see why this is different is to look at what the template *is* versus what it *produces*.

### The template (one file, ~20 lines)

```java
@Permute(varName = "i", from = "3", to = "10", className = "Join${i}")
public class Join2 {

    private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
    private List<Object> right;

    public void left(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1) {
        System.out.println("shared logic — runs identically in every generated class");
        for (@PermuteDeclr(type = "Object", name = "o${i}") Object o2 : right) {
            c2.call(o1, o2);
        }
        System.out.println("more shared logic — preserved verbatim");
    }
}
```

### What gets generated (8 classes, automatically)

`Join3.java`:
```java
public class Join3 {
    private Callable3 c3;
    private List<Object> right;

    public void left(Object o1, Object o2) {
        System.out.println("shared logic — runs identically in every generated class");
        for (Object o3 : right) {
            c3.call(o1, o2, o3);
        }
        System.out.println("more shared logic — preserved verbatim");
    }
}
```

`Join5.java`:
```java
public class Join5 {
    private Callable5 c5;
    private List<Object> right;

    public void left(Object o1, Object o2, Object o3, Object o4) {
        System.out.println("shared logic — runs identically in every generated class");
        for (Object o5 : right) {
            c5.call(o1, o2, o3, o4, o5);
        }
        System.out.println("more shared logic — preserved verbatim");
    }
}
```

`Join10.java`:
```java
public class Join10 {
    private Callable10 c10;
    private List<Object> right;

    public void left(Object o1, Object o2, Object o3, Object o4, Object o5,
                     Object o6, Object o7, Object o8, Object o9) {
        System.out.println("shared logic — runs identically in every generated class");
        for (Object o10 : right) {
            c10.call(o1, o2, o3, o4, o5, o6, o7, o8, o9, o10);
        }
        System.out.println("more shared logic — preserved verbatim");
    }
}
```

One template. Eight generated classes. Every field rename, parameter expansion, call-site update, and variable rename is done correctly by the processor — including the `c2.call(o1, o2)` call site being expanded to the full argument sequence for each arity.

---

## A more realistic example: fixed parameters before and after

Real methods have context parameters that don't permute. Permuplate handles them naturally — annotate only the sentinel parameter, and everything else stays in place:

```java
@Permute(varName = "i", from = "3", to = "5", className = "ContextJoin${i}")
public class ContextJoin2 {

    private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
    private List<Object> right;

    public void join(
            String ctx,                                                                              // fixed — before
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1,
            List<Object> results) {                                                                  // fixed — after
        System.out.println("Starting join for: " + ctx);
        results.clear();
        for (@PermuteDeclr(type = "Object", name = "o${i}") Object o2 : right) {
            c2.call(o1, o2);
            results.add(o2);
        }
        System.out.println("Join complete: " + results.size() + " results for: " + ctx);
    }
}
```

For `i=3`, the processor generates:

```java
public class ContextJoin3 {

    private Callable3 c3;
    private List<Object> right;

    public void join(String ctx, Object o1, Object o2, List<Object> results) {
        System.out.println("Starting join for: " + ctx);
        results.clear();
        for (Object o3 : right) {
            c3.call(o1, o2, o3);
            results.add(o3);
        }
        System.out.println("Join complete: " + results.size() + " results for: " + ctx);
    }
}
```

Notice what happened:
- `String ctx` stayed in position before the expanded parameters
- `List<Object> results` stayed in position after them
- `results.add(o2)` became `results.add(o3)` — the for-each variable rename propagated to every usage in the loop body, including this separate statement
- The `println` calls were preserved character-for-character

---

## Nested class templates

If you want generated classes to live alongside a related class without polluting the package namespace with the template, put the template inside the host class:

```java
public class JoinLibrary {

    @Permute(varName = "i", from = "3", to = "5", className = "FilterJoin${i}")
    public static class FilterJoin2 {

        private @PermuteDeclr(type = "Callable${i}", name = "c${i}") Callable2 c2;
        private List<Object> right;
        private String label;

        public void run(
                @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "o${j}") Object o1) {
            System.out.println("Running: " + label);
            for (@PermuteDeclr(type = "Object", name = "o${i}") Object o2 : right) {
                c2.call(o1, o2);
            }
            System.out.println("Done: " + label);
        }
    }
}
```

`FilterJoin3`, `FilterJoin4`, and `FilterJoin5` are generated as top-level classes in the same package. The `static` modifier is automatically stripped — it only makes sense inside an enclosing class.

---

## The annotations

### `@Permute`

Drives the outer loop. Supported in two positions:

**On a class, interface, or record** (top-level or nested static) — generates N new types, one per permutation value. Nested types are promoted to top-level.

**On a method** — generates a single new class containing one overload of the method per permutation value. Useful for utility classes with multi-arity method families.

| Parameter | Type | Meaning |
|---|---|---|
| `varName` | `String` | The integer loop variable name (e.g. `"i"`) |
| `from` | `String` | Inclusive lower bound — JEXL expression (e.g. `"3"`, `"${start}"`) |
| `to` | `String` | Inclusive upper bound — JEXL expression (e.g. `"10"`, `"${max}"`) |
| `className` | `String` | Output type/class name. For type permutation: a template evaluated per-i (e.g. `"Join${i}"`). For method permutation: a fixed class name (e.g. `"MultiJoin"`) containing all overloads. |
| `strings` | `String[]` | Named string constants available in all `${...}` expressions alongside `varName`. Each entry is `"key=value"`. Example: `strings = {"prefix=Buffered"}` makes `${prefix}` expand to `"Buffered"` in `className`, `@PermuteDeclr`, and `@PermuteParam`. See [Expression syntax — String variables](#string-variables). |
| `extraVars` | `@PermuteVar[]` | Additional integer loop variables for cross-product generation. Each `@PermuteVar(varName="k", from="2", to="4")` adds one axis; one output type is generated per combination. Primary variable is the outermost loop; `extraVars` are inner loops in declaration order. Variable names must not conflict with `varName` or `strings` keys. See [Expression syntax — Multiple permutation variables](#multiple-permutation-variables). |
| `inline` | `boolean` | Default `false`. When `true`, generates permuted classes as nested siblings inside the parent class rather than separate top-level files. Supported on both nested static classes and top-level classes. Requires `permuplate-maven-plugin`; the APT annotation processor reports a compile error if set. Template must be in `src/main/permuplate/`. |
| `keepTemplate` | `boolean` | Default `false`. When `true` and `inline = true`, retains the template class itself in the output alongside the permuted classes. When `false`, the template class is removed. Has no effect when `inline = false`. |

### `values` — string-set iteration (alternative to `from`/`to`)

Instead of an integer range, iterate over a named set of strings. Mutually exclusive with `from`/`to`.

```java
@Permute(varName = "F", values = {"Json", "Xml", "Csv", "Yaml"}, className = "${F}Serializer")
public class JsonSerializer {

    @PermuteConst("${F}")
    public static final String FORMAT = "Json";

    public String serialize(Object obj) {
        return FORMAT + ":" + obj;
    }
}
// Generates: JsonSerializer (FORMAT="Json"), XmlSerializer (FORMAT="Xml"),
//            CsvSerializer (FORMAT="Csv"), YamlSerializer (FORMAT="Yaml")
```

The loop variable is bound as a `String` (not `Integer`), so `${F}` in any JEXL expression evaluates to the current string value. Use `@PermuteDeclr(type="${F}")` to rename field/parameter types, `@PermuteConst("${F}")` to replace string constants, and `className="${F}..."` to name the generated classes.

`values` and `from`/`to` are mutually exclusive — specifying both is a compile error. An empty `values={}` is also a compile error.

**`className` prefix rule (type permutation only):** the static (non-`${...}`) part of `className` must be a prefix of the template class's simple name. `className = "Join${i}"` on `class Join2` is valid; `className = "Bar${i}"` on `class Join2` is a compile error. This rule does not apply when `className` starts with a variable expression or to method-level `@Permute`.

**Inline generation example:**

```java
public class Handlers {
    @Permute(varName = "i", from = "2", to = "5",
             className = "Handler${i}",
             inline = true,
             keepTemplate = true)
    public static class Handler1 {
        private @PermuteDeclr(type = "Callable${i}", name = "delegate${i}") Callable1 delegate1;

        public void handle(
                @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "arg${j}") Object arg1) {
            delegate1.call(arg1);
        }
    }
}
```

With `keepTemplate = true`, the output `Handlers.java` contains `Handler1` through `Handler5` all nested inside `Handlers`. Users write `Handlers.Handler3 h = (a1, a2, a3) -> ...` with no top-level file clutter.

#### Sealed class `permits` expansion

When a sealed interface or class uses the template class name as its `permits` placeholder, the Maven plugin automatically expands it to list all generated class names:

```java
// Parent (src/main/java):
public sealed interface Expr permits ExprTemplate {}

// Template (src/main/permuplate):
@Permute(varName="i", from="1", to="3", className="Expr${i}", inline=true)
static final class ExprTemplate implements Expr { ... }

// Generated (target/generated-sources/permuplate/):
// sealed interface Expr permits Expr1, Expr2, Expr3 {}
```

The template class name (`ExprTemplate`) is replaced in-place with the full list of generated names (`Expr1, Expr2, Expr3`). Multiple entries in the `permits` clause are supported — only the template placeholder entry is expanded; other entries are preserved unchanged.

### `@PermuteDeclr`

Renames a declaration and propagates the rename to all usages within the declaration's scope.

| Placement | Scope of rename propagation | `name` required? |
|---|---|---|
| On a **field** | Entire class body | Yes |
| On a **constructor parameter** | The constructor body only | Yes |
| On a **for-each variable** | The loop body only | Yes |
| On a **method parameter** | The method body only | No — omit to change type only |

| Parameter | Meaning |
|---|---|
| `type` | New type template (e.g. `"Callable${i}"`, `"Source<T${i+1}>"`) |
| `name` | New identifier template (e.g. `"c${i}"`). **Optional** (default `""`) for method parameters — when empty, only the type changes and the parameter name is preserved. |

**APT mode — method parameter type change only:**
```java
public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
// Only the type changes (Object → Source<T2>, Source<T3>, ...); "src" stays "src"
```

### `@PermuteParam`

Expands a sentinel parameter into a generated sequence, and rewrites call sites in the method or constructor body where the sentinel's name appears as an argument. A method or constructor may have **multiple** `@PermuteParam` sentinels — each is expanded independently in declaration order, with both anchors rewritten at any shared call sites.

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable (e.g. `"j"`) |
| `from` | Inner lower bound — literal or expression (e.g. `"1"`) |
| `to` | Inner upper bound — expression evaluated against outer context (e.g. `"${i-1}"`) |
| `type` | Generated parameter type (e.g. `"Object"`) |
| `name` | Generated parameter name template (e.g. `"o${j}"`) |

Parameters not annotated with `@PermuteParam` are preserved in their original positions before and after each expanded sequence. Works on both method and constructor parameters.

**Dual-sentinel example:** two independent ranges in one method:

```java
public void merge(
        @PermuteParam(varName="j", from="1", to="${i-1}", type="Object", name="left${j}") Object left1,
        @PermuteParam(varName="k", from="1", to="${i-1}", type="Object", name="right${k}") Object right1) {
    Collections.addAll(results, left1, right1);
}
```

For `i=3` generates `merge(Object left1, Object left2, Object right1, Object right2)` with the call site expanded to `Collections.addAll(results, left1, left2, right1, right2)`.

---

### `@PermuteConst`

Replaces the initializer of a field or local variable with the evaluated result of a JEXL expression. The existing initializer is kept only to make the template compile — it is substituted in every generated class.

```java
@PermuteConst("${i}")
int ARITY = 2;
// Generated for i=3: int ARITY = 3;
```

May be combined with `@PermuteDeclr` on the same field — `@PermuteDeclr` updates type and name; `@PermuteConst` updates the value:

```java
@PermuteDeclr(type = "int", name = "ARITY_${i}")
@PermuteConst("${i}")
int ARITY_2 = 2;
// Generated for i=3: int ARITY_3 = 3; and references updated to ARITY_3
```

> **`@PermuteConst` vs `@PermuteValue`:** `@PermuteConst` is a backward-compatible alias for `@PermuteValue` on fields and local variables. Prefer `@PermuteValue` in new code; `@PermuteConst` remains supported.

---

### `@PermuteValue`

A superset of `@PermuteConst`. Replaces:
- On a **field or local variable**: the initializer (same as `@PermuteConst`)
- On a **method or constructor**: the RHS of the assignment statement at position `index` (0-based, counting from the first statement in the original template body)

| Parameter | Meaning |
|---|---|
| `value` | JEXL expression for the replacement value (e.g. `"${i}"`, `"${i * 2}"`) |
| `index` | 0-based index of the statement in the method/constructor body (only for method/constructor target). |

```java
@PermuteValue("${i}")
int ARITY = 2;
// Same as @PermuteConst on a field

@PermuteValue(index = 1, value = "${i}")
public void init() {
    this.name = "x";  // statement 0 — untouched
    this.size = 1;    // statement 1 — RHS "1" becomes ${i}
}
```

---

### `@PermuteStatements`

Inserts statements into a **method or constructor** body at a specified position. Works with or without an inner loop.

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable (omit for single-statement insertion) |
| `from` | Inner loop lower bound |
| `to` | Inner loop upper bound |
| `position` | `"first"` — before all existing statements; `"last"` — after all existing statements |
| `body` | JEXL template for the statement(s) to insert |

Applied **after** `@PermuteValue`, so `@PermuteValue` indices refer to the original template body positions.

```java
@PermuteStatements(varName = "k", from = "1", to = "${i-1}",
                   position = "first", body = "this.${lower(k)} = ${lower(k)};")
public Tuple1(A a) {
    this.a = a;
    this.size = 1;
}
// Tuple3 gets: this.a=a; this.b=b; [original body: this.c=c; this.size=3;]
```

---

### `@PermuteBody`

Replaces the entire annotated method or constructor body with a JEXL-evaluated template per permutation. The `body` attribute must include surrounding braces.

| Parameter | Meaning |
|---|---|
| `body` | JEXL template for the complete method body including `{ }` |

```java
@PermuteBody(body = "{ return ${i}; }")
public int arity() {
    return 1; // template placeholder — replaced entirely in generated classes
}
```

Use `@PermuteBody` when you need to replace the full body. Use `@PermuteStatements` when you want to keep existing statements and insert around them.

---

### `@PermuteCase`

Expands a `switch` statement in the annotated method by inserting new cases per inner-loop value — fully inlined, no inheritance delegation.

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable |
| `from` | Inner loop lower bound |
| `to` | Inner loop upper bound (empty range = no cases inserted) |
| `index` | JEXL expression for the case label integer (e.g. `"${k}"`) |
| `body` | JEXL template for the case body statements |

The seed case and `default` case in the template are preserved unchanged. New cases are inserted immediately before `default`.


```java
@PermuteCase(varName = "k", from = "1", to = "${i-1}",
             index = "${k}", body = "return (T) ${lower(k+1)};")
public <T> T get(int index) {
    switch (index) {
        case 0:
            return (T) a;  // seed case — unchanged in all generated classes
        default:
            throw new IndexOutOfBoundsException(index);
    }
}
// Tuple3 (i=3): switch with cases 0, 1, 2 — all inlined, no super() calls
```

---

### `@PermuteEnumConst`

Expands a sentinel enum constant into a sequence of constants per permutation. The sentinel is removed and replaced by constants generated from the inner loop.

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable name |
| `from` | Inner loop lower bound (JEXL expression) |
| `to` | Inner loop upper bound; empty range (from &gt; to) removes sentinel with no replacement |
| `name` | JEXL template for the generated constant name |
| `args` | JEXL template for constructor arguments (optional; comma-separated, without parens) |

```java
@Permute(varName = "i", from = "2", to = "3", className = "Priority${i}")
public enum Priority1 {
    LOW,
    MED,
    @PermuteEnumConst(varName = "k", from = "3", to = "${i}", name = "LEVEL${k}")
    HIGH_PLACEHOLDER;
}
// Priority2: LOW, MED  (from=3 > to=2 → empty range, sentinel removed)
// Priority3: LOW, MED, LEVEL3
```

`@Permute` on `enum` types works the same as on classes — the enum is renamed per permutation value. The `args` attribute expands to constructor arguments, enabling enums with fields:

```java
@PermuteEnumConst(varName = "k", from = "2", to = "${i}", name = "ITEM${k}", args = "${k}")
PLACEHOLDER(99);
// In Status2: ITEM2(2)  — the sentinel PLACEHOLDER(99) is replaced
```

---

### `@PermuteImport`

Adds a JEXL-evaluated import statement to each generated class. Placed on the template class. Repeatable (`@PermuteImport` / `@PermuteImports`).

```java
@Permute(varName = "i", from = "3", to = "10", className = "Join${i}First", inline = true)
@PermuteImport("org.drools.core.function.BaseTuple.Tuple${i}")
@PermuteImport("org.drools.core.RuleOOPathBuilder.Path${i}")
public static class Join2First<...> { ... }
// Generated Join4First gets: import BaseTuple.Tuple4; import RuleOOPathBuilder.Path4;
```

The annotation and its imports are stripped from the generated output. Useful when generated types reference classes whose import cannot be derived from the template's own imports.

---

### `@PermuteTypeParam`

Expands a sentinel **class or method type parameter** into a sequence, enabling type-safe generic interfaces like `Consumer2<T1,T2>`, `Consumer3<T1,T2,T3>`, etc.

In the common case with `@PermuteParam` and `T${j}` naming, this annotation is **not needed** — the class type parameters are expanded implicitly. Use `@PermuteTypeParam` explicitly only for phantom types (type parameters with no corresponding `@PermuteParam`).

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable (e.g. `"j"`) |
| `from` | Inner lower bound — literal or expression |
| `to` | Inner upper bound — expression evaluated against outer context (e.g. `"${i}"`) |
| `name` | Generated type parameter name template (e.g. `"T${j}"`, `"${alpha(j)}"`) |

**Bounds propagation:** the sentinel's declared bound is copied to each generated type parameter with the sentinel name substituted. `T1 extends Comparable<T1>` → `T2 extends Comparable<T2>`, `T3 extends Comparable<T3>`.

**Type-safe Consumer family — implicit expansion (no `@PermuteTypeParam` needed):**
```java
@Permute(varName="i", from="2", to="5", className="Consumer${i}")
public interface Consumer1<T1> {
    void accept(
        @PermuteParam(varName="j", from="1", to="${i}", type="T${j}", name="arg${j}") T1 arg1);
}
// Generates: Consumer2<T1,T2>, Consumer3<T1,T2,T3>, Consumer4<T1,T2,T3,T4>, Consumer5<T1,T2,T3,T4,T5>
// Each with the correctly-typed accept() method
```

**Phantom type — explicit `@PermuteTypeParam` (no method parameters):**
```java
@Permute(varName="i", from="2", to="5", className="Step${i}", inline=true, keepTemplate=true)
public class Step1<@PermuteTypeParam(varName="j", from="1", to="${i}", name="T${j}") T1> { }
// Generates: Step2<T1,T2>, Step3<T1,T2,T3>, etc.
```

---

### `@PermuteReturn`

Controls the **return type** of a method per permutation. Enables stateful builder chains where each `.join()` call returns the next step type with one additional type parameter.

> **Boundary omission:** by default, when the evaluated return type class is not in the set of classes generated by `@Permute`, the method is **silently omitted** from that generated class. This is intentional — it mirrors the hand-written pattern where the last class in a chain has no `join()` method.

| Parameter | Meaning |
|---|---|
| `className` | Return type class name template (e.g. `"Step${i+1}"`). May also be a type variable name (e.g. `"${alpha(i)}"`). |
| `typeArgVarName` | Variable name for the type argument expansion loop |
| `typeArgFrom` | Loop lower bound (default `"1"`) |
| `typeArgTo` | Loop upper bound (e.g. `"${i+1}"`) — required when `typeArgVarName` is set |
| `typeArgName` | Type argument name template per loop value (e.g. `"T${j}"`) |
| `typeArgs` | Full JEXL expression for the complete type argument list — for mixed fixed+growing args (e.g. `"DS, ${typeArgList(1, i, 'T')}"`) |
| `when` | JEXL guard expression. Default: method is omitted when `className` not in generated set. `when="true"` forces generation regardless. |

**When is `@PermuteReturn` needed?** In Maven plugin inline mode with `T${j}` naming, return type and parameter types are inferred automatically — **no annotation required**. Use `@PermuteReturn` when:

| Situation | Why explicit is needed |
|---|---|
| **APT mode** | Template must compile; use `Object` as the sentinel return type |
| **`alpha(j)` naming** | Inference requires `T${j}` convention — single-letter names don't trigger it |
| **Non-linear offset** | Inference assumes `i+1`; use explicit for other offsets |
| **Mixed fixed+growing type args** | Use `typeArgs="DS, ${typeArgList(1, i, 'T')}"` |

**Builder chain — inline mode (zero annotations):**
```java
@Permute(varName="i", from="1", to="4", className="Step${i}", inline=true, keepTemplate=true)
public class Step1<T1> {
    // Return type and parameter type inferred automatically
    public Step2<T1, T2> join(Source<T2> src) { ... }
    public void execute() {}
}
// Step1: join() returns Step2<T1,T2>
// Step2: join() returns Step3<T1,T2,T3>
// Step3: join() returns Step4<T1,T2,T3,T4>
// Step4: join() OMITTED — Step5 is not in the generated set (boundary omission)
```

> **The leaf class:** the last generated class (`Step4`) has no `join()` method because `Step5` was never generated. This is automatic and intentional — without it, `Step4.join()` would reference a non-existent type and fail to compile. Use `when="true"` to override this if you need the last class to reference an external hand-written class.

**APT mode (explicit annotations required):**
```java
@Permute(varName="i", from="1", to="4", className="Step${i}")
public class Step1<T1> {
    @PermuteReturn(className="Step${i+1}",
                   typeArgVarName="j", typeArgFrom="1", typeArgTo="${i+1}", typeArgName="T${j}")
    public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { return null; }
    // Step4.join() is still omitted (boundary omission applies to both modes)
}
```

---

### `@PermuteMethod`

Generates **multiple method overloads** per class using an inner loop variable. For each outer permutation value `i` and inner value `j`, one overload is generated.

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable name (e.g. `"j"`) |
| `from` | Inner lower bound (default `"1"`) |
| `to` | Inner upper bound. When omitted, **inferred as `@Permute.to - i`**. |
| `name` | Optional method name template. When set (e.g. `"path${k}"`), each overload gets a distinct name. When omitted, all overloads share the sentinel method's name. |

> **The leaf class:** when `from > to` (e.g. when `i = max`), **no overloads are generated** — the method disappears from that class entirely. This is the multi-join equivalent of G2's boundary omission.

**Join chain with multiple overloads — inline mode (zero annotations):**
```java
@Permute(varName="i", from="1", to="5", className="Join${i}Second", inline=true, keepTemplate=true)
public class Join1Second<T1> {
    // @PermuteMethod.to inferred as @Permute.to - i = 5 - i
    // i=1: j=1..4 → 4 join() overloads; i=5: j=1..0 → leaf, 0 overloads
    @PermuteMethod(varName="j")
    public Join2First<T1, T2> join(Join1First<T2> fromJ) { ... }
}
```

**Named method series:**
```java
@PermuteMethod(varName="k", from="2", to="4", name="path${k}")
public <@PermuteTypeParam(varName="j", from="1", to="${k-1}", name="P${j}") PB>
       Object path2() { ... }
// Generates: path2<PB>(), path3<PB,PC>(), path4<PB,PC,PD>()
```

---

### `@PermuteExtends`

Explicit control over `extends`/`implements` clause expansion. In the common case — same `T${j}` naming, type args matching the class's declared type params in order — extends/implements expansion is **automatic** without this annotation.

Use `@PermuteExtends` only when implicit inference doesn't apply (non-standard naming, subset of type args, or targeting `implements` rather than `extends`). **Inline mode (Maven plugin) only.**

When `@PermuteExtends` is present, the automatic same-N extends expansion is skipped for that class.

| Parameter | Meaning |
|---|---|
| `className` | New extends class name template (evaluated with current context) |
| `typeArgVarName` / `typeArgFrom` / `typeArgTo` / `typeArgName` | Loop-based type argument generation |
| `typeArgs` | Full JEXL expression for type argument list (alternative to loop) |
| `interfaceIndex` | `0` (default) = `extends` clause; `1+` = nth `implements` interface (0-indexed) |

```java
@PermuteExtends(className="Join${i}Second",
                typeArgVarName="k", typeArgFrom="1", typeArgTo="${i}", typeArgName="T${k}")
public class Join1First<T1> extends Join1Second<T1> { ... }
// Join3First extends Join3Second<T1, T2, T3>
```

---

### `@PermuteFilter`

Skips generation of a permutation when a JEXL boolean expression evaluates to `false`. Placed on the same class (or method) as `@Permute`. Repeatable — multiple conditions are ANDed.

```java
@Permute(varName = "i", from = "3", to = "7", className = "FilteredCallable${i}")
@PermuteFilter("${i} != 4")  // arity 4 is hand-written elsewhere — skip it
public interface FilteredCallable2 {

    void call(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "o${j}")
            Object o1);
}
// Generates: FilteredCallable3, FilteredCallable5, FilteredCallable6, FilteredCallable7
// (arity 4 is excluded)
```

Multiple filters are ANDed:

```java
@PermuteFilter("${i} != 1")   // skip the singleton case
@PermuteFilter("${i} != 10")  // skip the maximum case (reserved)
```

The APT processor reports a compile error if all values in the range are filtered out. The Maven plugin silently produces no output in that case.

Works with `@PermuteVar` cross-products — each combination (i, j, ...) is evaluated independently.

---

### `@PermuteAnnotation`

Adds a Java annotation to the generated class (or method or field) per permutation. Placed on the same element as `@Permute`. The `value` is a JEXL-evaluated annotation literal; `when` is an optional boolean guard. Repeatable.

Runs **last** in the transform pipeline, so `when` expressions see the final permutation state (field names renamed, type params expanded, etc.).

```java
@Permute(varName = "i", from = "3", to = "5", className = "AnnotatedCallable${i}")
@PermuteAnnotation("@SuppressWarnings(\"unchecked\")")
@PermuteAnnotation(value = "@Deprecated", when = "${i} == 5")  // only on arity 5
public class AnnotatedCallable2 { ... }
// AnnotatedCallable3: @SuppressWarnings("unchecked")
// AnnotatedCallable5: @SuppressWarnings("unchecked") + @Deprecated
```

---

### `@PermuteThrows`

Adds an exception type to a method's `throws` clause per permutation. Add-only; existing throws are preserved. The `value` is a JEXL-evaluated class name; `when` is an optional boolean guard. Repeatable.

Works naturally with `@PermuteImport` when the exception type needs an import that isn't in the template:

```java
@Permute(varName = "i", from = "3", to = "5", className = "AnnotatedCallable${i}")
@PermuteImport("java.io.IOException")
public class AnnotatedCallable2 {

    @PermuteThrows("java.io.IOException")
    public void execute(
            @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "arg${j}")
            Object arg1) { }
}
// Generated execute() declares: throws IOException  (import added by @PermuteImport)
```

See `permuplate-apt-examples/.../AnnotatedCallable2.java` for a working example combining all four of `@PermuteAnnotation`, `@PermuteThrows`, `@PermuteCase`, and `@PermuteImport`.

---

### Records as templates

Java records work as `@Permute` templates with full annotation parity. The canonical use case is generating immutable typed tuples:

```java
@Permute(varName = "i", from = "3", to = "6", className = "Tuple${i}")
public record Tuple2<
        @PermuteTypeParam(varName = "k", from = "1", to = "${i}",
                          name = "${alpha(k)}") A>(
        @PermuteParam(varName = "j", from = "1", to = "${i}",
                      type = "${alpha(j)}", name = "${lower(j)}")
        A a) {
}
// Generates:
//   record Tuple3<A,B,C>(A a, B b, C c)
//   record Tuple4<A,B,C,D>(A a, B b, C c, D d)
//   record Tuple5<A,B,C,D,E>(A a, B b, C c, D d, E e)
//   record Tuple6<A,B,C,D,E,F>(A a, B b, C c, D d, E e, F f)
```

**What works on records:**
- `@PermuteDeclr` — renames component types per permutation
- `@PermuteTypeParam` — expands record type parameters
- `@PermuteParam` — expands the record component list (the Tuple pattern)
- `@PermuteConst` / `@PermuteValue` — replaces static field initializers
- `@PermuteFilter` — skips specific permutation values
- `@PermuteVar` — cross-product generation
- `@PermuteImport` — adds per-permutation imports
- `@PermuteAnnotation` — adds annotations to generated record types
- `@PermuteThrows` — adds throws declarations to compact constructor methods

**Not applicable to records** (silently skipped):
- `@PermuteMethod` — records cannot have overloaded method families in the same way
- `@PermuteReturn` — records use component types, not method return types
- `@PermuteExtends` — records cannot extend classes

---

### Template composition — `@PermuteSource` and `@PermuteDelegate`

**Maven plugin only.** Generate a second class family derived from an existing one. Type parameters are inferred automatically — no `@PermuteTypeParam` needed on the derived template.

#### Capability A — ordering + type inference

```java
@Permute(varName="i", from="2", to="6", className="TimedCallable${i}", inline=true)
@PermuteSource("Callable${i}")   // type params A..N inferred from Callable${i}
public class TimedCallable2 implements Callable2<A, B, R> {
    // A, B, R are inferred — no @PermuteTypeParam
    private final Callable2<A, B, R> delegate;
    public R result(A a, B b) throws Exception { ... }
}
// Generates: TimedCallable3<A,B,C,R>, TimedCallable4<A,B,C,D,R>, ...
```

#### Capability B — `@PermuteDelegate` (delegation synthesis)

```java
@Permute(varName="i", from="2", to="6", className="SynchronizedCallable${i}", inline=true)
@PermuteSource("Callable${i}")
public class SynchronizedCallable2 {
    @PermuteDelegate(modifier = "synchronized")
    private final Callable2<Object> delegate;
    // All Callable${i} methods synthesised as synchronized delegating calls
}
```

#### Capability C — builder synthesis (empty body)

```java
@Permute(varName="i", from="3", to="6", className="Tuple${i}Builder", inline=true)
@PermuteSource("Tuple${i}")
public class Tuple3Builder {}   // empty — processor generates complete builder
// Generates: Tuple3Builder<A,B,C> with fields, setters, build()
```

See `permuplate-mvn-examples/.../composition/` for complete working examples including the `EventSystem` showing all three capabilities building a typed event system.

---

## Expression syntax

All `${...}` placeholders are evaluated by [Apache Commons JEXL3](https://commons.apache.org/proper/commons-jexl/). The loop variable (`varName`) is an integer; arithmetic expressions are natively supported:

```
${i}         → 3           (integer variable)
${i-1}       → 2           (subtraction)
${i+1}       → 4           (addition)
${i*2}       → 6           (multiplication)
Callable${i} → Callable3   (string interpolation)
o${j}        → o2          (inner loop variable)
```

### External property injection

Named constants can be injected from outside the annotation — useful for making templates configurable at build time without modifying source.

| Source | APT | Maven | Example |
|---|---|---|---|
| System property with `permuplate.` prefix | Yes | Yes | `-Dpermuplate.max=10` → `${max}` |
| APT option with `permuplate.` prefix | Yes | No | `-Apermuplate.max=10` → `${max}` |
| Annotation `strings` constant | Yes | Yes | `strings={"max=10"}` → `${max}` |

Resolution order: `strings` overrides APT options, which override system properties. Prefer `strings` for values fixed per template; prefer `-D` system properties for build-time configuration shared across templates.

**Example — configurable upper bound:**
```java
// In template:
@Permute(varName = "i", from = "3", to = "${max}", className = "Join${i}First")

// At build time (both APT and Maven plugin):
// mvn compile -Dpermuplate.max=10

// APT-only alternative (not available to Maven plugin):
// <compilerArgs><arg>-Apermuplate.max=10</arg></compilerArgs>
```

### String variables

The `strings` attribute on `@Permute` defines named string constants that are available alongside the integer variable in every `${...}` expression:

```java
@Permute(varName = "i", from = "3", to = "5",
         className = "${prefix}Join${i}",
         strings = { "prefix=Buffered" })
public class BufferedJoin2 { ... }
```

Generates `BufferedJoin3`, `BufferedJoin4`, `BufferedJoin5`. The `${prefix}` expression is substituted everywhere: in `className`, `@PermuteDeclr type/name`, and `@PermuteParam type/name`.

Each entry in `strings` must be in `"key=value"` format. The separator is the **first** `=`, so values may contain additional `=` signs. The key must not match `varName` or any `extraVars` variable name.

### Multiple permutation variables

`extraVars` adds additional integer axes. One output type is generated per combination (cross-product):

```java
@Permute(varName = "i", from = "2", to = "4",
         className = "BiCallable${i}x${k}",
         extraVars = { @PermuteVar(varName = "k", from = "2", to = "4") })
public interface BiCallable1x1 {
    void call(
        @PermuteParam(varName="j", from="1", to="${i}", type="Object", name="left${j}") Object left1,
        @PermuteParam(varName="m", from="1", to="${k}", type="Object", name="right${m}") Object right1);
}
```

Generates 9 interfaces — `BiCallable2x2` through `BiCallable4x4` — covering every combination of `i∈[2,4]` and `k∈[2,4]`. Without `extraVars`, this would require 9 separate template files.

The primary variable (`varName`) is the outermost loop; `extraVars` are inner loops in declaration order. For `i∈[2,3]` and `k∈[2,3]`, generation order is `(i=2,k=2)`, `(i=2,k=3)`, `(i=3,k=2)`, `(i=3,k=3)`.

Note: the template class (`BiCallable1x1`) is named with values outside the generated range so it never collides with a generated type. The leading literal `"BiCallable"` of `className` must be a prefix of the template class name — `"BiCallable1x1".startsWith("BiCallable")` ✓.

**String-set axis:** Use `values` instead of `from`/`to` to cross-product over named strings:

```java
@Permute(varName="i", from="1", to="2",
         className="${capitalize(T)}Widget${i}Factory",
         extraVars={@PermuteVar(varName="T", values={"sync","async"})})
public class WidgetFactory1 { ... }
// Generates: SyncWidget1Factory, SyncWidget2Factory, AsyncWidget1Factory, AsyncWidget2Factory
```

String variables bind as `String` in JEXL. `capitalize(T)`, `decapitalize(T)`, and string comparison (`T == "sync"`) all work. `values` and `from`/`to` are mutually exclusive — specifying both is a compile error.

---

## Built-in Expression Functions

Permuplate provides built-in functions available in **every annotation string attribute** (`className`, `type`, `name`, `from`, `to`, etc.) via the `${...}` expression syntax.

| Function | Description | Example |
|---|---|---|
| `alpha(n)` | Integer → uppercase letter, 1-indexed (1=A, 26=Z) | `${alpha(j)}` → `A`, `B`, `C` |
| `lower(n)` | Integer → lowercase letter, 1-indexed (1=a, 26=z) | `${lower(j)}` → `a`, `b`, `c` |
| `typeArgList(from, to, style)` | Comma-separated type argument list | `${typeArgList(1, i, 'T')}` → `T1, T2, T3` |

**`typeArgList` styles:**

| Style | Output (from=2, to=4) |
|---|---|
| `"T"` | `T2, T3, T4` |
| `"alpha"` | `B, C, D` |
| `"lower"` | `b, c, d` |

When `from > to`, `typeArgList` returns an empty string — useful for the arity-1 case where no type arguments are needed.

Values outside 1–26 for `alpha(n)` and `lower(n)` throw at generation time with a clear message (e.g., `alpha(n): n must be between 1 and 26, got 27`). `typeArgList` throws at generation time for unknown style values.

**Choosing a naming convention:** Using `T${j}` style (e.g., `T1, T2, T3`) enables implicit return-type and parameter-type inference in Maven plugin inline mode. Using `alpha(j)` style (e.g., `A, B, C`) requires explicit `@PermuteReturn` and `@PermuteDeclr` annotations — but produces single-letter names matching conventions like the Drools DSL.

---

## Choosing your approach

Three paths to generating typed APIs. The annotation burden varies:

| Goal | Mode | Type param naming | What you write |
|---|---|---|---|
| Minimum annotations | Maven plugin inline | `T1, T2, T3` (`T${j}`) | Just `@Permute` — return types, param types, and type params all inferred |
| Single-letter type params (`A, B, C`) | Maven plugin inline | `alpha(j)` via N4 | `@Permute` + explicit `@PermuteReturn` + `@PermuteDeclr` on each affected method |
| APT mode (template must compile) | APT | Any | `@Permute` + explicit `@PermuteReturn` + `@PermuteDeclr`, with `Object` sentinels |

**Why does `T${j}` naming enable inference?** The processor identifies the growing type parameter tip by finding type variables that are NOT declared on the class AND match a `T+number` pattern. Single-letter names like `A`, `B`, `C` have no numeric pattern — the processor cannot determine that `B` is second in a growing series.

The `alpha(j)` function produces `A, B, C` output and works fully with explicit `@PermuteReturn` — it only disables *implicit* inference, not the feature itself.

---

## Compile-time error messages

Permuplate validates your templates at compile time and reports precise, actionable errors — not raw exceptions. Errors are reported with annotation-attribute precision, so your IDE can navigate directly to the offending value.

**Invalid range:**
```
error: @Permute has invalid range: from=5 is greater than to=3 — no classes will be generated
       @Permute(varName = "i", from = "5", to = "3", className = "Join${i}")
                               ^^^^^
```

**`className` doesn't share the template's base name:**
```
error: @Permute className literal part "Bar" is not a prefix of the template class name "Join2"
       @Permute(varName = "i", from = "3", to = "5", className = "Bar${i}")
                                                                  ^^^^^^^^^
```

**`@PermuteDeclr` annotation string doesn't match the actual declaration:**
```
error: @PermuteDeclr type literal part "Bar" is not a prefix of the field type "Callable2"
       private @PermuteDeclr(type = "Bar${i}", name = "c${i}") Callable2 c2;
```

**Malformed `strings` entry:**
```
error: @Permute strings entry "badformat" is malformed — each entry must be in "key=value" format
       @Permute(..., strings = {"badformat"})
                               ^^^^^^^^^^^^
```

All errors point to the annotated class or method, so you can navigate to the problem even when the error originates inside a nested expression.

---

## Refactoring safety

Because the template is valid Java, most refactoring works automatically. The one exception is annotation string parameters like `type = "Callable${i}"` — these are strings, so the IDE doesn't know they reference `Callable2`. If you rename `Callable2`, update those strings by hand.

Practical workflow:
1. Do the IDE rename
2. Run `git diff` to see which string annotation parameters reference the old name
3. Update them
4. Rebuild

---

## Project structure

```
permuplate-parent/
├── permuplate-annotations/    Annotation definitions only (tiny jar, no deps)
├── permuplate-core/           Shared transformation engine (EvaluationContext, transformers)
├── permuplate-ide-support/    Annotation string algorithm (matching, rename, validation; no IDE deps)
├── permuplate-processor/      APT annotation processor (thin shell)
├── permuplate-maven-plugin/   Maven plugin for pre-compilation generation including inline mode
├── permuplate-apt-examples/   APT examples
├── permuplate-mvn-examples/   Maven plugin examples (Handlers inline demo)
└── permuplate-tests/          Unit tests using Google compile-testing
```

---

## Running the tests

```bash
mvn clean install
```

The test suite uses Google's [compile-testing](https://github.com/google/compile-testing) library to compile template strings in-process and assert on the generated source. Tests cover field renaming, parameter expansion, fixed params before/after the sentinel, nested class generation, and full usage propagation through complex method bodies.

---

## Requirements

- Java 17+
- Maven 3.6+ with `maven-compiler-plugin` 3.x

---

## Deep dive

For a detailed explanation of the architecture, transformation pipeline, design decisions, non-obvious implementation details, and a longer roadmap, see [OVERVIEW.md](OVERVIEW.md).

---

## Architecture and Design Records

- [`OVERVIEW.md`](OVERVIEW.md) — Deep-dive into the annotation processor architecture
- [`permuplate-mvn-examples/DROOLS-DSL.md`](permuplate-mvn-examples/DROOLS-DSL.md) — Drools DSL sandbox: six phases of type-safe API generation
- [`docs/design-snapshots/`](docs/design-snapshots/) — Frozen architecture state records (2026-04-06 is current)
- [`docs/adr/`](docs/adr/) — Formal architectural decisions (ADR-0001 through ADR-0005)
- [`site/_posts/`](site/_posts/) — Development diary: blog entries covering the full Permuplate journey

---

## License

Apache 2.0
