# Permuplate — Cleaning house and finding the gap

**Date:** 2026-04-07
**Type:** phase-update

---

## What I was trying to do: fix three bugs before touching migration

The last session ended with three vol2 bugs logged in IDEAS.md — a naming typo, a wrong return type on `path5()`, and a broken varargs pattern in `params()`. I wanted them gone before starting the migration. Stale bugs in the reference implementation have a way of becoming real confusions once you're moving fast.

## More bugs than the bug report said

Claude and I opened vol2's `RuleBuilder.java` expecting three surgical fixes. The `params()` fix was one line — `Class... cls` → `B... cls`. The naming typo in `RuleExtensionPoint6` was one string.

Then we hit `Join3Second.path5()`.

The return type referenced `Join2First` and `Tuple4` where it needed `Join4First` and `Tuple5`. Wrong join arity, wrong tuple type, wrong fact variable throughout. But the reported bug was path5 only. Claude kept reading. Every path method in that block — `path2`, `path3`, `path4`, `path5`, `path6` — had the exact same copy-paste error. All five referenced `Join2First` and used `B` instead of `D`. All five compiled cleanly.

That's the insidious part. In a stub returning `new Path5<>(null, null, null)`, the declared return type and the constructed type are internally consistent — both wrong in the same way — so the compiler sees no mismatch. Erasure makes the actual type arguments irrelevant at runtime. The only signal is manual inspection: compare `JoinNSecond.pathM()` against `Join(N-1)Second.pathM()` and notice the pattern breaks. We submitted that one to the garden (GE-0058).

Five fixes, not three.

## The plugin gap

With the bugs cleared, I wanted to brainstorm the IDE refactoring story before migration. My assumption: IntelliJ and VS Code plugins already exist, and we just need to add the rename ripple behaviour.

Claude came back differently. The project has `permuplate-ide-support` — a pure-Java algorithm library covering annotation string parsing, rename calculation, and validation, kept deliberately free of IDE dependencies. OVERVIEW.md is clear about what comes next: "the remaining work is the IntelliJ plugin implementation... and a VS Code extension." The plugins are designed for, not built.

I'd misread the roadmap.

## Eleven interaction points

Once the framing was right — build the plugins from scratch with the algorithm foundation already in place — we mapped the problem space. The rename ripple (template → all generated permutations) was the obvious one. But the surface is wider than that:

- The other direction: rename in any generated file should ripple back to the template
- Class rename hitting the `className=` annotation string, which is opaque to the IDE
- Cross-family annotation string references breaking silently when a type family is renamed
- `@PermuteMethod` overloads making source-method identity ambiguous
- Boundary omission causing a "partial rename" that stops short of the last permutation
- Find Usages missing call sites across sibling-family types
- Safe Delete silently doing nothing on a generated file
- Direct edits of generated files overwritten on the next build
- Package moves leaving orphan generated files at the old location

The list reached eleven before we stopped. That's the work for the next session: design the plugin architecture properly, all eleven handled, before a line of plugin code is written.
