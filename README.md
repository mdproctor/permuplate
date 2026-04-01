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

## Quick start

**Step 1: Add to your `pom.xml`**

```xml
<dependencies>
    <dependency>
        <groupId>io.permuplate</groupId>
        <artifactId>permuplate-annotations</artifactId>
        <version>1.0-SNAPSHOT</version>
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
                        <groupId>io.permuplate</groupId>
                        <artifactId>permuplate-processor</artifactId>
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

> **Why list transitive deps?** `maven-compiler-plugin` 3.x runs the processor in an isolated classloader. You must list the processor and all its runtime dependencies explicitly under `annotationProcessorPaths`.

**Step 2: Write a template class**

```java
@Permute(varName = "i", from = 3, to = 5, className = "Join${i}")
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
@Permute(varName = "i", from = 3, to = 10, className = "Join${i}")
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
@Permute(varName = "i", from = 3, to = 5, className = "ContextJoin${i}")
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

    @Permute(varName = "i", from = 3, to = 5, className = "FilterJoin${i}")
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

**On a class or interface** (top-level or nested static) — generates N new types, one per permutation value. Nested types are promoted to top-level.

**On a method** — generates a single new class containing one overload of the method per permutation value. Useful for utility classes with multi-arity method families.

| Parameter | Type | Meaning |
|---|---|---|
| `varName` | `String` | The integer loop variable name (e.g. `"i"`) |
| `from` | `int` | Inclusive lower bound |
| `to` | `int` | Inclusive upper bound |
| `className` | `String` | Output type/class name. For type permutation: a template evaluated per-i (e.g. `"Join${i}"`). For method permutation: a fixed class name (e.g. `"MultiJoin"`) containing all overloads. |
| `strings` | `String[]` | Named string constants available in all `${...}` expressions alongside `varName`. Each entry is `"key=value"`. Example: `strings = {"prefix=Buffered"}` makes `${prefix}` expand to `"Buffered"` in `className`, `@PermuteDeclr`, and `@PermuteParam`. See [Expression syntax — String variables](#string-variables). |
| `extraVars` | `@PermuteVar[]` | Additional integer loop variables for cross-product generation. Each `@PermuteVar(varName="k", from=2, to=4)` adds one axis; one output type is generated per combination. Primary variable is the outermost loop; `extraVars` are inner loops in declaration order. Variable names must not conflict with `varName` or `strings` keys. See [Expression syntax — Multiple permutation variables](#multiple-permutation-variables). |
| `inline` | `boolean` | Default `false`. When `true`, generates permuted classes as nested siblings inside the parent class rather than separate top-level files. Only valid on nested static classes. Requires `permuplate-maven-plugin`; the APT annotation processor reports a compile error if set. Template must be in `src/main/permuplate/`. |
| `keepTemplate` | `boolean` | Default `false`. When `true` and `inline = true`, retains the template class itself in the output alongside the permuted classes. When `false`, the template class is removed. Has no effect when `inline = false`. |

**`className` prefix rule (type permutation only):** the static (non-`${...}`) part of `className` must be a prefix of the template class's simple name. `className = "Join${i}"` on `class Join2` is valid; `className = "Bar${i}"` on `class Join2` is a compile error. This rule does not apply when `className` starts with a variable expression or to method-level `@Permute`.

**Inline generation example:**

```java
public class Handlers {
    @Permute(varName = "i", from = 2, to = 5,
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

### `@PermuteDeclr`

Renames a declaration and propagates the rename to all usages within the declaration's scope.

| Placement | Scope of rename propagation |
|---|---|
| On a **field** | Entire class body |
| On a **constructor parameter** | The constructor body only |
| On a **for-each variable** | The loop body only |

| Parameter | Meaning |
|---|---|
| `type` | New type template (e.g. `"Callable${i}"`) |
| `name` | New identifier template (e.g. `"c${i}"`) |

### `@PermuteParam`

Expands a sentinel parameter into a generated sequence, and rewrites call sites in the method body where the sentinel's name appears as an argument. A method may have **multiple** `@PermuteParam` sentinels — each is expanded independently in declaration order, with both anchors rewritten at any shared call sites.

| Parameter | Meaning |
|---|---|
| `varName` | Inner loop variable (e.g. `"j"`) |
| `from` | Inner lower bound — literal or expression (e.g. `"1"`) |
| `to` | Inner upper bound — expression evaluated against outer context (e.g. `"${i-1}"`) |
| `type` | Generated parameter type (e.g. `"Object"`) |
| `name` | Generated parameter name template (e.g. `"o${j}"`) |

Parameters not annotated with `@PermuteParam` are preserved in their original positions before and after each expanded sequence.

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

### String variables

The `strings` attribute on `@Permute` defines named string constants that are available alongside the integer variable in every `${...}` expression:

```java
@Permute(varName = "i", from = 3, to = 5,
         className = "${prefix}Join${i}",
         strings = { "prefix=Buffered" })
public class BufferedJoin2 { ... }
```

Generates `BufferedJoin3`, `BufferedJoin4`, `BufferedJoin5`. The `${prefix}` expression is substituted everywhere: in `className`, `@PermuteDeclr type/name`, and `@PermuteParam type/name`.

Each entry in `strings` must be in `"key=value"` format. The separator is the **first** `=`, so values may contain additional `=` signs. The key must not match `varName` or any `extraVars` variable name.

### Multiple permutation variables

`extraVars` adds additional integer axes. One output type is generated per combination (cross-product):

```java
@Permute(varName = "i", from = 2, to = 4,
         className = "BiCallable${i}x${k}",
         extraVars = { @PermuteVar(varName = "k", from = 2, to = 4) })
public interface BiCallable1x1 {
    void call(
        @PermuteParam(varName="j", from="1", to="${i}", type="Object", name="left${j}") Object left1,
        @PermuteParam(varName="m", from="1", to="${k}", type="Object", name="right${m}") Object right1);
}
```

Generates 9 interfaces — `BiCallable2x2` through `BiCallable4x4` — covering every combination of `i∈[2,4]` and `k∈[2,4]`. Without `extraVars`, this would require 9 separate template files.

The primary variable (`varName`) is the outermost loop; `extraVars` are inner loops in declaration order. For `i∈[2,3]` and `k∈[2,3]`, generation order is `(i=2,k=2)`, `(i=2,k=3)`, `(i=3,k=2)`, `(i=3,k=3)`.

Note: the template class (`BiCallable1x1`) is named with values outside the generated range so it never collides with a generated type. The leading literal `"BiCallable"` of `className` must be a prefix of the template class name — `"BiCallable1x1".startsWith("BiCallable")` ✓.

---

## Compile-time error messages

Permuplate validates your templates at compile time and reports precise, actionable errors — not raw exceptions. Errors are reported with annotation-attribute precision, so your IDE can navigate directly to the offending value.

**Invalid range:**
```
error: @Permute has invalid range: from=5 is greater than to=3 — no classes will be generated
       @Permute(varName = "i", from = 5, to = 3, className = "Join${i}")
                               ^^^^^
```

**`className` doesn't share the template's base name:**
```
error: @Permute className literal part "Bar" is not a prefix of the template class name "Join2"
       @Permute(varName = "i", from = 3, to = 5, className = "Bar${i}")
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
├── permuplate-processor/      The annotation processor (JavaParser + JEXL3)
├── permuplate-example/        Template examples: Join2, ContextJoin2, JoinLibrary
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

- Java 11+
- Maven 3.6+ with `maven-compiler-plugin` 3.x

---

## Deep dive

For a detailed explanation of the architecture, transformation pipeline, design decisions, non-obvious implementation details, and a longer roadmap, see [OVERVIEW.md](OVERVIEW.md).

---

## License

Apache 2.0
