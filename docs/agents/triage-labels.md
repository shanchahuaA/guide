# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Triage state is written as a `Status:` line near the top of each `.scratch/<feature>/issues/NN-<slug>.md` file.

> **说明**：本仓库的任务来源是 `docs/分工与目标清单.md` 里人工排的六天日历表，不是外部提交的 issue。上面的标签在**引入外部需求**（同学报的 bug、老师追加的要求）时才会真正用到。
