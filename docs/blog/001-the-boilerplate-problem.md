# The Boilerplate Problem

*Part 1 of the Permuplate development series.*

---

## The Code That Started Everything

There's a file in the Drools codebase I've been staring at for years. It's the RuleBuilder — the fluent API that lets you build rule conditions in a type-safe, composable way. Each step in the chain accumulates one more fact type:

```java
builder.rule("age-check")
        .from(ctx -> ctx.persons())          // 1 fact: Person
        .join(ctx -> ctx.colleagues())       // 2 facts: Person, Person
        .filter((ctx, p1, p2) -> p1.age() > p2.age())
        .fn((ctx, p1, p2) -> System.out.println("match"));
```

The genius of this API is that the compiler enforces correctness: if you pass a lambda to `filter()` that expects three parameters when only two facts are accumulated, it won't compile. The DSL is genuinely type-safe. The type parameter list *grows* as you join more facts.

The problem is what makes this work under the hood. The codebase has `Join2First`, `Join3First`, `Join4First`, `Join5First` — four nearly identical classes, each differing from the previous only in arity. Then there's `Join2Second`, `Join3Second`, `Join4Second`, `Join5Second`. Then `Consumer1`, `Consumer2`, `Consumer3`, `Consumer4`. Then `Predicate2` through `Predicate10`. Then `Function1` through `Function5`.

It's the same class, over and over, with an incrementing number and one extra type parameter.

I've wanted to fix this for a long time. Not because the hand-written code is wrong — it works perfectly. But because it's the kind of code that future maintainers dread touching. Adding a sixth join arity means editing six files. Finding the bug in one means checking if the same bug exists in the others. The code is correct but fragile in the social sense: it requires discipline to keep consistent, and discipline erodes.

The question was: how do you eliminate this class of boilerplate in Java without resorting to something that breaks the developer experience?

---

## What Already Exists (and Why It's Not Enough)

The existing approaches to this problem all have the same problem: they break the IDE.

Vavr uses a Pythonic code generator. You write a `.java.generator` template, run a script, and get a file like `Function1.java`, `Function2.java`, ... committed to the repository. This works, but the source of truth is the template — the generated files are artifacts. If you navigate to `Function3.java` in the IDE, you're looking at generated output, not the thing you'd actually edit.

RxJava had custom Python scripts too. Freemarker templates, shell scripts, Groovy templates — the ecosystem has tried many variations of this pattern. They all share the same fundamental limitation: **the template is not valid Java**. The IDE can't type-check it, navigate it, or refactor it. You've left the IDE's world and entered a text-manipulation world.

This isn't an academic concern. If `Consumer3.java` references a class that doesn't exist, the IDE shows you a red squiggle in `Consumer3.java`. If the template that generates `Consumer3.java` has the same mistake, there is no squiggle. The error only surfaces when you run the generator, wait for the compile step, and interpret an error message that refers to the generated file rather than the template line.

The developer experience degrades in proportion to how much logic lives in the template.

---

## The Constraint I Cared About Most

Before writing a line of code, I knew there was one constraint I wasn't willing to compromise on: **the template must be valid, compilable Java**.

Not "mostly Java with some template expressions." Not "Java with comments that drive code generation." Valid, compilable Java that the IDE can navigate, type-check, and refactor. A class that compiles at arity 2 (or 3, or whatever the template's base arity is). An interface where `Command+Click` on a method takes you somewhere real.

This constraint rules out most of the existing approaches immediately.

The second constraint: **no committed generated files**. The generated classes are artifacts of the build, not source. They belong in `target/`, not in `src/`. Committing them clutters diffs, creates merge conflicts, and confuses the human-maintained/machine-generated distinction.

These two constraints together pointed to one mechanism: an annotation processor. You annotate the template class with something like `@Permute(from=2, to=10, className="Join${i}")`, the processor runs at compile time, reads the template source, clones and transforms it for each arity value, and writes the output files to the generated sources directory. The template stays in `src/`; the generated classes go to `target/generated-sources/`.

The template compiles as-is (at whatever the base arity is). The processor generates the rest at build time. No Python scripts, no committed artifacts, no broken IDE experience.

---

## The Name and the First Sketch

I called it Permuplate — a portmanteau of "permutation" and "template." The name felt right: it's not code generation in the traditional sense. It's permutation of a valid template. You write one version; Permuplate writes the others.

The initial design was deliberately minimal. One annotation, `@Permute`, with four attributes:

```java
@Permute(varName = "i", from = 2, to = 6, className = "Join${i}")
public class Join2 {
    // ...
}
```

For each value of `i` from `from` to `to`, clone the annotated class, evaluate `className` with `i` bound to the current value, rename the clone, and write it. The class body — fields, methods, constructors — comes along unchanged in the initial design.

That's not very useful on its own. The point is that the class body will also contain expressions that depend on `i`, via two companion annotations:

- `@PermuteDeclr` — on a field or variable: rename its type and name based on `i`, propagate the rename to all usages within the scope
- `@PermuteParam` — on a single "sentinel" method parameter: expand it into a sequence of `i` parameters

Together, these three annotations can express the Join2/Join3/Join4 pattern. You write `Join2` with `@PermuteDeclr` on the fields and `@PermuteParam` on the parameters, and Permuplate generates `Join3`, `Join4`, `Join5` automatically.

That was the starting point. It was, in retrospect, quite modest compared to where the project ended up. But it was enough to prove the concept was viable — and enough to reveal the first of many complications.

---

## Choosing the Tools

Two decisions shaped everything that followed: the AST manipulation library and the expression evaluator.

For AST manipulation, I chose **JavaParser**. It's a mature, actively maintained Java AST library with clean read-write APIs. You can parse a `.java` file, navigate the AST, modify nodes, and print the result back to source text. Critically, it works without a classpath — you can parse the template file without needing to resolve all its dependencies. This matters because the template might reference classes that don't exist yet (the classes being generated).

For expression evaluation in `${...}` placeholders, I chose **Apache Commons JEXL3**. It's a lightweight expression language that evaluates things like `"Join${i+1}"` with `i=3` to `"Join4"`. Clean, embeddable, no heavy dependencies. Later I'd discover a significant limitation in how it handles primitive types — but that came later.

The first working version parsed the template source using `StaticJavaParser.parse()`, found the template class declaration, cloned it for each value of `i`, renamed it by evaluating the `className` expression via JEXL, and wrote the result. About 200 lines of Java. It worked on the first example.

That was the moment I knew this was going to be worth building properly.

---

*Next: Discovering that "rename the class and copy the body" is the easy part — and that making field renaming, parameter expansion, and anchor call-site propagation work correctly is where the real complexity lives.*
