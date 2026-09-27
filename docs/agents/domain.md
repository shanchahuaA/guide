# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root (single-context repo — there is no `CONTEXT-MAP.md`)
- **`docs/adr/`**: read ADRs that touch the area you're about to work in

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo (most repos):

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-<decision>.md
│   └── 0002-<decision>.md
└── src/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_

## 不在仓库里的资料（重要）

`resul.md` 是本项目的**分析笔记**，在作者本地硬盘上，**有意不提交到仓库**。

**它的地位：参考资料，不是规格。** 它记录的是"分析数据源时发现了什么事实"（例如数据源的访问方式、字段填充率实测、熟食公式的推导过程、许可证约束）。它**不定义**这个项目的表结构、字段命名或接口形状 —— **那些由项目所有者定稿**，写在 `CONTEXT.md` 和 `CLAUDE.md` 里。

**使用规则：**

- 从 `resul.md` 可以引用**事实**（数据源怎么访问、某个字段的取值范围、许可证要求）
- **不要**把 `resul.md` 里的表结构草案、字段清单、方案取舍当成已决定的事
- 遇到"笔记里有但项目所有者没确认过"的设计，**只提问，不要照做**
- 表结构和字段以 `CONTEXT.md` + `CLAUDE.md` 为准；不要假设 `resul.md` 存在

