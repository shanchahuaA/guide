# 采集容错命中普查（票 #21）

**这份文档是「哪些防御是必要的」的数字依据。** #22–#25 的 Python 重写可以直接引用它决定哪段防御照搬、哪段丢掉。

生成方式：`guide/tools/freeze_baseline.py --step3`，语料取自 134 个条目的页面源文。
数字是**一次性快照**（2026-09-27），数据源随时会变 —— 重写期间若怀疑数据源动了，重跑 --step2 / --step3 即可。

对错的判据不是「这条防御还有没有用」，而是**「今天的数据源会不会触发它」**。
命中为 0 只说明当前语料触发不了，不等于可以删——除非重写选用的解析库本身就消灭了这类输入。

---

## 一、三样产出

| 产出 | 路径 | 是否进仓库 |
|---|---|---|
| 金标准快照 | `guide/src/test/resources/crawler/golden-items.json` | **进**（回归基准） |
| 离线页面源文语料 | 仓库外本地目录（如 `<仓库父目录>/_baseline-corpus/`） | 不进 |
| 容错命中普查 | 本文件 + `census.json`（与语料同目录） | 本文件进 |

**金标准快照**：JSON，schema `guide-golden-items/1`，按 `nameEn` 升序，134 条。
字段：`nameEn` / `weight` / `icon` / `description` / `achievement` / `tag` / `effect`
（即「采集产出的字段」）。**明确排除** `nameZh` / `descriptionZh`（不属采集职责，由对照表回填，见 #19）与 `id`（自增主键，重灌会变）。

**离线语料**：134 份 `<页面名>.wikitext`（数据源原样） + `index.json`。
页面名 = `nameEn` 对全部 134 条都成立，**直取 134 / 存档页兜底 0 / 缺失 0**。
Windows 文件名不允许 `?`，含该字符的页面名做了改写，映射记在 `index.json`。

**复跑**：

```bash
python guide/tools/freeze_baseline.py --step1
python guide/tools/freeze_baseline.py --step2 --out <仓库外的语料目录>
python guide/tools/freeze_baseline.py --step3 --corpus <语料目录> --report <语料目录>/census.json
```

数据库连接照 `GUIDE_DB_HOST/PORT/USER/PASSWORD/NAME` 环境变量。

---

## 二、命中统计

### 模板白名单与前置模板

| 防御 | 命中条目 | 命中为 0 的 |
|---|---|---|
| `{{Infobox item}}` | **131** | 3 条：`Bugle Shroom (Poisonous)`、`Button Shroom (Poisonous)`、`Cluster Shroom (Poisonous)` |
| `{{Infobox iteminfobox}}`（变体） | **0** | 全部 134 条 |
| 多 Infobox 页面 | **0** | 全部 134 条 |
| ├ 覆写值真的不同 | **0** | 全部 134 条 |
| ├ 靠 `display` 认领成功 | 0（无多框可认） | 全部 134 条 |
| └ 认领失败退回第一个框 | **0** | 全部 134 条 |
| `{{for}}` 前置模板 | 1（`Backpack`） | 133 条 |
| `{{ambox}}` 前置模板 | 2（`Bugle?`、`Warp Compass`） | 132 条 |
| `{{Infobox location}}` 前置模板 | **0** | 全部 134 条 |
| 裸 `{{Infobox}}` | **0** | 全部 134 条 |

前置模板计数（`skipLeadingTemplates` 要跳掉的那些）：
`infoboxitem` 130、`stub` 7、`spoilerwarning` 4、`ambox` 2、`quote` 2、`for` 1、`distinguish` 1、`disambiguation` 1、`hatnote` 1。

### 结构级防御

| 防御 | 命中 | 命中为 0 的 |
|---|---|---|
| 括号不配对 | **0 次** | 全部 134 条 |
| `HungerCooked` 非数字 | **0 次** | 全部 134 条 |
| `BonusCooked` 非数字 | **0 次** | 全部 134 条 |
| 换行为 `\r\n` | **0 条** | 全部 134 条 |

### 正文清洗（`WikitextUtil` 各项）

| 项 | 命中条目 | 总次数 | 命中为 0 的条目数 |
|---|---|---|---|
| HTML 注释 | 28 | 40 | 106 |
| `[[File:…]]` 媒体链接 | 11 | 24 | 123 |
| 表格 `{\|` | 2 | 2 | 132 |
| `ref` / `gallery` 扩展标签 | 54 | 59 | 80 |
| 站外链 | 4 | 15 | 130 |
| 嵌套方括号 | 6 | 7 | 128 |
| `{{PAGENAME}}` 占位符 | 120 | 265 | 14 |
| 三撇号粗体 | 131 | — | 3 |
| 双撇号斜体 | 131 | — | 3 |
| 章节标题 | 131 | — | 3 |
| 行内模板 | 131 | — | 3 |
| `badge` 徽章模板 | 28 | — | 106 |

逐条明细（谁命中几次）在 `census.json` 的 `perItem` 里。

---

## 三、结论：哪些防御今天触发不了

**完全没有触发的（命中 0）**：

1. `iteminfobox` 变体白名单 —— 空集，可以只认 `infoboxitem` 一个名字。
2. 多 Infobox 的**全部**处理：多框检测、`display` 认领、覆写值比对、退回第一个框 —— 134 页里一个多框页面都没有。
3. 括号不配对 —— 一条都没有，`matchingClose` 返回 -1 的分支从未走到。
4. `HungerCooked` / `BonusCooked` 非数字兜底 —— 一次没触发。
5. `\r\n` 换行归一 —— 数据源全是 `\n`。
6. `Infobox location` 前置模板、裸 `Infobox` 前置模板 —— Java 注释点名的这两类组合今天不存在。

**仍然高频触发的**（重写必须保留）：`{{PAGENAME}}` 占位符（120 条 / 265 次）、粗斜体、章节标题、行内模板（各 131 条）、`ref`/`gallery`（54 条）、HTML 注释与 `badge`（各 28 条）、媒体链接（11 条）。

**低频但真实存在的**：表格（2 条）、站外链（4 条）、嵌套方括号（6 条）、`for`/`ambox` 前置模板（1 条 / 2 条）。低频 ≠ 可以不做 —— 这几类一旦漏掉，命中的那几条就会解析出脏数据。

---

## 四、与代码注释冲突的两条事实

普查推翻了 `crawler/` 里两处注释的说法，后续票以**普查数字**为准：

> ⚠️ **#25（2026-09-28）之后 `crawler/` 整个包已从仓库删除**（采集只剩 `guide/tools/` 那套 Python），
> 所以下面两处 Java 注释今天在仓库里已经查不到了。这一节作为**普查当时的记录**保留：
> 它记的是"当时的注释与实测数字对不上"，不是"现在该去哪读注释"。

1. **`ItemPageParser` 类注释**称「实测 3 个毒蘑菇页各放了普通版和中毒版两个框」。
   **今天不成立**：134 页里多 Infobox 页面为 0，且那 3 个毒蘑菇页**连 `{{Infobox item}}` 都没有** —— 它们是仅有的 3 个无 Infobox 页面，走的应该是另一条取值路径。

2. **`WikitextParams` 注释**称「`HungerCooked` 只有 4 个页面用、`BonusCooked` 只有 15 个」「62 个页面用了 `HasCookingBonus` 开关」。
   **#23 已核对（2026-09-27）**：按采集真正看到的页面（`row.page` 去重，131 页）逐页解析，
   `HungerCooked` **4 页**、`BonusCooked` **15 页**、`HasCookingBonus` **62 页**（取值全是 `no`/`breaks`，
   即抑制熟食值）—— **与注释一致**，注释不必改。非数字覆写值仍为 0 次。

---

## 五、给 #22–#25 的三条提醒

1. **无 Infobox 的 3 个毒蘑菇页是硬边界。** 它们是纯页面源文的一类样本，重写要明确这 3 条走什么路径 —— 现在的 Java 侧行为是「跳过前置模板后仍有正文」，转换结果要拿金标准快照逐字段对。
2. **金标准快照里 `effect` 有 1 条为空。** 比对时别把它当差异。
3. **快照不含中文名。** 重写的验收标准里「已有中文名在采集后仍在」（#22）要用**库里实际数据**验，不能拿快照验 —— 快照里根本没有这一列。
