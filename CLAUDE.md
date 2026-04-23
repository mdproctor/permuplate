# Permuplate — Claude context

## Project Type

**Type:** java

This file gives future Claude sessions everything needed to contribute to Permuplate without re-deriving the architecture from scratch. Read this first, then consult [OVERVIEW.md](OVERVIEW.md) for the annotation API reference, [ARCHITECTURE.md](ARCHITECTURE.md) for the transformation pipeline, module structure, and test coverage map, and [DECISIONS.md](DECISIONS.md) for non-obvious implementation decisions and past bugs. See [README.md](README.md) for the user-facing picture.

---

## What this project is

**Permuplate** is a Java annotation processor that generates type-safe arity permutations from a single template class. You write one class annotated with `@Permute`, and `javac` generates N classes — one per arity value — via AST transformation using JavaParser.

The key insight: **the template is valid, compilable Java**. The IDE can navigate it, refactor it, and type-check it. No external script. No committed generated files. No separate build step.

This is genuinely novel. Every comparable tool (Vavr generators, Freemarker templates, RxJava scripts) uses a template that is *not* valid Java. See [OVERVIEW.md § Market Comparison](OVERVIEW.md#market-comparison--why-this-is-novel) for details.

---

## Module layout

```
permuplate-parent/
├── permuplate-annotations/     All 14 annotations (no runtime deps)
├── permuplate-core/            shared transformation engine: EvaluationContext, transformers, PermuteConfig, AstUtils (shared AST name/type-string utilities — add new utilities here, not as private methods in InlineGenerator or PermuteProcessor)
├── permuplate-ide-support/     annotation string algorithm (matching, rename, validation); no IDE deps
├── permuplate-processor/       APT entry point only (thin shell depending on permuplate-core)
├── permuplate-maven-plugin/    Maven Mojo for pre-compilation generation including inline mode
├── permuplate-apt-examples/    APT examples (renamed from permuplate-example)
├── permuplate-mvn-examples/    Maven plugin examples with Handlers inline demo
└── permuplate-tests/           Unit tests via Google compile-testing

permuplate-intellij-plugin/     IntelliJ plugin (Gradle, Java 17) — NOT aggregated into Maven parent
```

Maven is at `/opt/homebrew/bin/mvn`. The standard build command is:

```bash
/opt/homebrew/bin/mvn clean install
```

The IntelliJ plugin uses a separate Gradle build. From `permuplate-intellij-plugin/`:

```bash
./gradlew test          # run plugin tests (178 tests)
./gradlew buildPlugin   # produce installable zip in build/distributions/
```

Requires Maven modules built first (`mvn install`) — the plugin depends on `permuplate-ide-support` and `permuplate-annotations` jars from `target/`. IntelliJ's internal compiler does not support the `Trees` API — enable **Delegate IDE build/run actions to Maven** in IntelliJ settings (Build, Execution, Deployment → Build Tools → Maven → Runner).

**Java 17 required for Gradle:** If the shell default JDK is Java 21+ (especially Java 26), Gradle 8.6 fails with a cryptic `JavaVersion.parse` error. Set explicitly: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19 ./gradlew test`

---

## The annotations

27 annotations in `permuplate-annotations/`. For per-annotation API detail and usage examples, see [OVERVIEW.md § Annotation API Detail](OVERVIEW.md#annotation-api-detail).

**`from`/`to` are JEXL expression strings**, not int literals — `"3"`, `"${i-1}"`, `"${max}"` are all valid. Named constants resolve in priority order: system properties (`-Dpermuplate.*`) < APT options (`-Apermuplate.*`, APT only) < annotation `strings`. See [OVERVIEW.md § External Property Injection](OVERVIEW.md#external-property-injection).

---

@DECISIONS.md

---

## Error reporting standard

All errors emitted by `PermuteProcessor` must use the most precise location available, in this priority order:

1. **Attribute-level** — `messager.printMessage(ERROR, msg, element, annotationMirror, annotationValue)` — points the IDE cursor to the specific annotation attribute (e.g. `className = "Foo${i}"`). Use this for errors about a specific annotation attribute value.
2. **Annotation-level** — `messager.printMessage(ERROR, msg, element, annotationMirror)` — highlights the whole annotation. Use when the error is about the annotation as a whole.
3. **Element-level** — `messager.printMessage(ERROR, msg, element)` — highlights the annotated class/method. Use this minimum for all errors; never emit a locationless error.

The helpers `findAnnotationMirror(element, fqn)`, `findAnnotationValue(mirror, attribute)`, and `error(msg, element, mirror, value)` in `PermuteProcessor` encapsulate this pattern.

Transformer-level errors (from `PermuteDeclrTransformer`, `PermuteParamTransformer`) receive the `Element` of the annotated type/method threaded through from the processor, giving at minimum file-level precision. They do not have access to `AnnotationMirror` (they operate on the JavaParser AST), so element-level is their maximum precision.

**Rule: every new error added anywhere in the processor pipeline must include at least an `Element` location. No bare `messager.printMessage(ERROR, msg)` calls.**

---

## Refactoring safety limitation

Annotation string parameters (`type = "Callable${i}"`, `name = "c${i}"`) are opaque strings — the IDE does not treat them as references. If you rename `Callable2`, you must hand-edit these strings.

Workflow: do the rename → `git diff` to spot changed lines → update the matching annotation strings → rebuild.

This is a known limitation. See [OVERVIEW.md § Roadmap](OVERVIEW.md#possible-roadmap) for ideas on addressing it.

---

## Testing

Tests live in `permuplate-tests/` and use Google's `compile-testing` library. For the full test coverage map (which test class covers which annotation), see [ARCHITECTURE.md § Testing Strategy](ARCHITECTURE.md#testing-strategy).

### Drools DSL Sandbox Tests

The sandbox (`permuplate-mvn-examples`) has its own test suite in
`src/test/java/io/quarkiverse/permuplate/example/drools/`. Tests are
organized one class per DSL feature, mirroring the Drools vol2 reference.
Tests that use the standard two-person/two-account Ctx fixture should extend
`DroolsDslTestBase` rather than duplicating the @Before setUp.

**Before beginning any DSL work, read all test files in the vol2 reference
suite — not just the one directly related to the feature.** The full suite
gives a much broader understanding of the DSL's intended behaviour and often
reveals design constraints not obvious from the API alone.

Vol2 tests are at:
`/Users/mdproctor/dev/droolsoct2025/droolsvol2/src/test/java/org/drools/core/`

Key files: `ExtensionPointTest`, `OOPathTest`, `Filter1Test`,
`BiLinearTuplePredicateCacheTest`, `RuleBuilderTest`, `RuleBaseTest`,
`RuleProapgationAndExecutionTest`, `DataBuilderTest`, `ExecutorTest`.

After completing the extends feature, do a systematic review of all sandbox
work to date against the full vol2 test suite to identify gaps.

---

## What to read next

- [README.md](README.md) — user-facing overview with examples and quick start
- [OVERVIEW.md](OVERVIEW.md) — annotation API reference, market comparison, roadmap (user-facing)
- [ARCHITECTURE.md](ARCHITECTURE.md) — transformation pipeline, module structure, testing strategy (contributor-facing). **Serves as `DESIGN.md` for this project** — `java-git-commit` references to `docs/DESIGN.md` should read `ARCHITECTURE.md` instead.
- [DECISIONS.md](DECISIONS.md) — non-obvious implementation decisions and past bugs
- [ADRs](docs/adr/) — formal records of key architectural decisions (ADR-0001..0006 cover DSL sandbox)
- `permuplate-processor/src/main/java/io/quarkiverse/permuplate/processor/` — the processor source files
- `permuplate-tests/src/test/java/io/quarkiverse/permuplate/` — test classes

---

## Blog

**Blog directory:** `site/_posts/`

Blog posts are Jekyll posts — they must have YAML frontmatter (layout, title, date, phase, phase_label). The site is built with Jekyll from `site/` and deployed to `mdproctor.github.io/permuplate/`.

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting any entry. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without first verifying it against the style guide's "What to Avoid" section.

The guide covers Mark's voice and personality, the three collaboration registers (I / we / Claude named directly), structural patterns, quantitative fingerprint, and the heading smell check.

**Phase labels for new posts:**
- Phase 1 — The Annotation Processor (Apr 4)
- Phase 2 — The Drools DSL Sandbox (Apr 6–7)
- Phase 3 — The IntelliJ Plugin (Apr 8–9)
- Phase 4 — JEXL String Assistance (Apr 22–23)
- Add new phases as the project evolves.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/permuplate
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these when this section is present):**
- Before starting any significant task, check if it spans multiple concerns.
  If it does, help break it into separate issues before beginning work.
- When staging changes before a commit, check if they span multiple issues.
  If they do, suggest splitting the commit using `git add -p`.
