# Permuplate — Design Snapshot
**Date:** 2026-04-23
**Topic:** JEXL string assistance in IntelliJ plugin — full IDE authoring support for ${...} expressions
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

The Permuplate IntelliJ plugin now provides full IDE authoring assistance inside `${...}` expressions in all annotation string attributes. This covers syntax highlighting (JexlSyntaxHighlighter), variable/built-in completion (JexlCompletionContributor), parameter hints for built-in function calls (JexlParameterInfoHandler), and undefined variable warnings with syntax validation (JexlAnnotator). The implementation is a hand-written language injection stack: JexlLexer (flat token grammar) → JexlLanguageInjector (MultiHostInjector, one session per expression) → JexlContextResolver (PSI walker collecting variables from @Permute context) → three IDE services. 178 plugin tests pass. The rename propagation layer was also extended to cover @PermuteReturn and @PermuteDefaultReturn className= attributes.

## How We Got Here

Key decisions made to reach this point, in rough chronological order.

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Hand-written lexer vs Grammar-Kit | Hand-written JexlLexer | JEXL expression syntax is simple enough; Grammar-Kit adds build complexity and generated code overhead | Grammar-Kit (overkill for 10 token types) |
| No reusable JEXL IntelliJ plugin | Build from scratch | Nothing on JetBrains Marketplace; Stapler plugin only has a 385-line inspection, no lexer or language | Stapler plugin (BSD-2-Clause); validation pattern borrowed for annotator |
| Flat parser vs grammar tree | Flat PsiParser (consume-all) | All IDE services operate at token level; no composite AST nodes needed | Full grammar tree (over-engineered for token-level completion/highlighting) |
| One injection session per `${...}` range | One `startInjecting`/`doneInjecting` per range | Multiple `addPlace()` in one session concatenates range content into one JexlFile — produces false-positive annotator warnings | Single session with multiple `addPlace()` (broken: concatenates expressions) |
| Annotator root guard | `instanceof JexlFile` | In a flat PSI tree every token's parent IS the file; `getParent() != file` is always false | `element.getParent() != file` (logically inverted; runs O(tokens²) with no user-visible symptom due to IntelliJ deduplication) |
| `@PermuteVar` resolution | Read `extraVars=` from `@Permute` | `@PermuteVar` has `@Target({})` — only valid nested inside `@Permute(extraVars={...})`; class-level scan finds nothing | Scanning class annotations for `@PermuteVar` (dead code — never matches) |
| BUILTIN_NAMES source of truth | `JexlBuiltin.ALL.keySet()` | Single canonical source; parallel constant on JexlSyntaxHighlighter could diverge silently | Parallel `BUILTIN_NAMES` set on JexlSyntaxHighlighter |
| Annotator warning severity | WARNING not ERROR | System properties (`-Dpermuplate.*`) and APT options are invisible to the IDE — variables from those sources appear undefined | ERROR (produces false errors for valid templates using external property injection) |
| JEXL keywords in identifier skip set | Static `JEXL_KEYWORDS` Set | Lexer tokenises `true`, `false`, `null`, `empty`, etc. as IDENTIFIER; without filtering they produce false "unknown variable" warnings | No keyword set (false positives for boolean/null expressions) |
| `updateParameterInfo` comma counting | Depth-tracked comma count | Commas inside nested calls (e.g. `max(i,2)` inside `typeArgList(...)`) must not inflate the outer argument index | Flat comma count (wrong index for arguments after nested calls) |

## Where We're Going

**Next steps:**
- Maven Central release — group ID choice still pending
- Gradle plugin — inline generation mode entirely unavailable to Gradle projects today
- Quarkus extension — implied by `io.quarkiverse` group ID if chosen
- VS Code extension — algorithm ready in `permuplate-ide-support`; TypeScript port needed

**Open questions:**
- Group ID for Maven Central: `io.github.mdproctor` (instant namespace approval) vs `io.quarkiverse` (slower review, implies a real Quarkus extension commitment)
- `@PermuteStatements` marked "under review for removal" in its Javadoc — no template uses it; `@PermuteBody` covers all its use cases; decision deferred
- JEXL completion for bare-JEXL attributes (`@PermuteFilter(when="i > 2")` uses no `${...}`) — not addressed; would require full-string injection rather than subrange injection

## Linked ADRs

| ADR | Decision |
|---|---|
| [ADR-0001](../docs/adr/0001-standalone-method-level-permutetypeparam-with-propagation.md) | Standalone method `@PermuteTypeParam` with single-value propagation |
| [ADR-0006](../docs/adr/0006-extendsrule-duplication.md) | `extendsRule()` duplication is structurally unavoidable |

## Context Links

- Design spec: [`docs/superpowers/specs/2026-04-22-jexl-completion-design.md`](../docs/superpowers/specs/2026-04-22-jexl-completion-design.md)
- Implementation plan: [`docs/superpowers/plans/2026-04-22-jexl-completion.md`](../docs/superpowers/plans/2026-04-22-jexl-completion.md)
- Retro-issues audit: [`docs/retro-issues.md`](../docs/retro-issues.md)
- JEXL architecture section: [`ARCHITECTURE.md § JEXL String Assistance`](../ARCHITECTURE.md)
- Epic: #114 (closed); child issues: #115–#122 (all closed)
