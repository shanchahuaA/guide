# Issue tracker: GitHub

Issues and specs for this repo live in GitHub Issues
(<https://github.com/shanchahuaA/guide/issues>), accessed via the `gh` CLI.

## Conventions

- One issue per task. Open one with `gh issue create --title "..." --body "..."`.
- Reference an issue from a commit by putting `refs #NN` in the message, or
  `closes #NN` to close it automatically.
- Labels: see `triage-labels.md`. Not all of them exist in the repo yet —
  create one with `gh label create <name>` the first time you need it.
- Assign an issue to whoever is doing it. Closing it is the record that it is done.

## When a skill says "publish to the issue tracker"

Run `gh issue create --title "..." --body "..."`, then apply the label.

## When a skill says "fetch the relevant ticket"

Run `gh issue view NN --comments`.

## 与项目实际任务表的关系

本仓库的**真实任务来源**是 `docs/分工与目标清单.md`（六天倒排日历 + 接口契约 + 降级预案）。
GitHub Issues 用来给「六项技术栈」各建一个可追溯的任务，让提交能关联到具体任务，
作为分工的可核验证据。

两者冲突时以 `docs/分工与目标清单.md` 为准 —— 它记录人的排期，Issues 是证据链。
