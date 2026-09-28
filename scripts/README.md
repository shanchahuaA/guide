# HTTP 冒烟验收脚本（T6 / issue #17）

对**运行中的后端**发真实请求，一次穿过控制器、持久层、静态资源映射、缓存与鉴权，
用「已知答案断言」给接口层做端到端回归保护。票面见 #17，断言清单来自父票 #11 的「测试决定」一节。

## 前置：先跑一次采集

**采集不在应用里**（#25）：应用只读库对外服务，采集是 `guide/tools/` 下的 Python 脚本。
库里的数据由采集决定，所以**先跑采集，再跑本脚本**，否则断言反映的只是库里现有的那批数据：

```bash
cd guide && python tools/crawl_items.py
```

采集的用法（重跑、离线复跑、只看差异不写库）见仓库根 `README.md`。

## 一条命令运行

在 `guide/` 下把后端起起来（工作目录必须是 `guide/`：图标要落到 `guide/icons/`）：

```bash
cd guide && mvn -o spring-boot:run
```

**另开一个终端**，在仓库根跑：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
```

默认打 `http://localhost:8080`，可以用 `-BaseUrl` 换：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1 -BaseUrl http://localhost:9090
```

脚本自己会**等后端 ready**（先在 `/testItemList` 上探到响应再往下跑），后端还没起来时会打
「等后端 ready（已等 N 秒）」而不是当场报一堆连接失败。

## 断言清单（全部走既有读取路径）

下表按**票面锚点**列出核心断言。脚本实跑时报的条数比表里多 —— 多出来的是
"条目/节点存在性"前置检查与几条对照断言（例如"Hot Dog 在列表里"、
"毒变体与普通版是两条不同的行"）。它们不是凑数：没有这些前置检查，
"条目根本没落库"这种失败会以上面某条断言 FAIL 的形式暴露，而定位不到真正的原因。

> `Assert-Equal` 的判据是 `$null -ne $actual -and $actual -eq $expected`，
> 所以"字段缺失（null）"与"值不对"是两种不同的 FAIL，不会混成一个。
> 每条形如 `POISON_COOKED=0` 的断言前面都有"该 effect 存在"的前置断言，
> 不存在"因为取不到值而侥幸通过"的可能。

## 第 4 组（图鉴接口）的两次收口

第 4 组早期是按"接口接受筛选参数"写的，契约收口后那批断言已经与契约冲突，分两次订正：

- **#28 本轮**：`?primaryType=` / `?keyword=` 那批改成"**参数被忽略**"的显式断言
  （带参数 = 不带参数 = 全量 134 条），`/api/biomes` 的 `count` 断言改成"**不带 count**、
  元素形状只有 value / nameZh"。原来的写法里有 3 条 FAIL、3 条**假 PASS** ——
  服务端忽略参数后返回全量，`primaryType 入参不区分大小写` 这种断言靠"全量里当然有 EQUIPMENT"
  侥幸通过，比 FAIL 更危险（会把"参数被静默忽略"这种漂移藏起来）。
- **#29**：详情相关的断言（`raw` / `cooked` / `isCookable` / `data` 直接是条目对象）按契约 §2 订正。

`/api/tags`、`/api/biomes` 的取值直接取自 `TagDictionary` 的定稿清单（type 12 / biome 11 /
rarity 7 / source 19 / location 9 / flag 2，与生态 11 个），**不查库**：字典是静态的，不随采集变化。

| # | 接缝 | 断言 | 来自 | 为什么 |
|---|---|---|---|---|
| 1 | `POST /admin/crawl` | HTTP 404 | **新增（#25）** | 采集搬出应用进程后，手动触发它的入口必须不存在。写死 404 而不是"非 200"，才能发现"入口又被加回来、但换了路径或动词"这类漂移 |
| 2 | `GET /testItemList` | 行数 ≥ 130 | 票面 | 全量列表口径 |
| 3 | | 行数 = 去重后 nameEn 数 | **新增** | 票面锚点只要求行数下限；幂等是 User Story 16 的要求，顺带在这儿咬住 |
| 4 | | 每行 `tag` / `effect` 都不是 null | **新增** | 顺带回归保护 JSON 列映射（`autoResultMap` 漏了会静默全 null 且不报错）。判据用「不是 null」而不是「非空」：`Scout's Ambition` 这类纯功能护身符**真的**一个状态效果都没有，`effect` 合法地就是 `[]`，断言非空会把正确行为判成 FAIL |
| 5 | | 每行 `icon` 以 `/icons/` 开头 | **新增** | 条目 icon 列是相对路径，不外链 wiki（票面只要求 `/icons/Hot_Dog.png` 能取到） |
| 6 | | Hot Dog：`biome=Gloom` | 票面 | 五维标签 |
| 7 | | Hot Dog：`rarity=Rare` | 票面 | 同上 |
| 8 | | Hot Dog：`HUNGER=-30` | 票面 | 生食状态效果 |
| 9 | | Hot Dog：`HUNGER_COOKED=-60` | 票面 | 熟食总量口径（生值 × 2） |
| 10 | | Bugle Shroom (Poisonous) 独立成行 | 票面 | 毒变体不并进普通版 |
| 11 | | 该行 `POISON=20` | 票面 | 毒变体带着毒数值 |
| 12 | | 该行 `POISON duration=8` | 票面 | 附属值：`poisonTime` → `duration` |
| 13 | | 该行 `POISON startDelay=10` | 票面 | 附属值：`poisonStart` → `startDelay` |
| 14 | | Warp Compass 带 `flag=removed` | 票面 | 已移除条目标记 |
| 15 | | First Aid Kit 不带 `flag=cookable` | 票面 | 可烹饪判据反例 |
| 16 | | Green Crispberry：`POISON_COOKED=0` | 票面 | 浆果煮熟毒清零（显式写 0） |
| 17 | | Green Crispberry：`POISON_COOKED` 无附属值 | **新增** | 清零是"煮掉了"，不该残留 duration/startDelay —— 毒蘑菇那条"原样保留含附属值"的反面 |
| 18 | | Green Crispberry：`POISON duration=4` | **新增** | 生毒仍在，两侧对照才有意义（否则"煮掉了"没有对照物） |
| 19 | `GET /icons/Hot_Dog.png` | HTTP 200 | 票面 | 静态资源映射 |
| 20 | | `Content-Type` 含 `image/png` | 票面 | 真的是 PNG，不是错误页 |
| 21 | | 响应体以 PNG 魔数开头 | **新增** | 内容是 PNG，不是被静态映射当成品发出去的半张图（票面只要求 Content-Type） |

**退出码**：全过 0，有 FAIL 非 0 —— 可以直接挂进演示前的检查清单。

## 实跑记录

| 日期 | 结果 | 备注 |
|---|---|---|
| 2026-09-26 | 36/36 PASS | 交付当天；当时脚本还没有 AI 教学那几组 |
| 2026-09-28 | 106/108 PASS | #25 摘掉采集入口后重跑。两条 FAIL 都在 §4b 登录断言上：它要拿**真 js_code** 去换 openid，跑脚本的机器上拿不到（脚本自己造的 `smoke-test-<guid>` 微信一定拒），与本次改动无关 |

库里的数据是 `select count(*), count(distinct name_en) from item` 都是 `134 / 134`，
重复采集幂等（按 nameEn upsert，只更新不重复插入）。

## 采集那半边在哪验

采集**不经过这个脚本**（它只发 HTTP 请求）。采集自己的回归在：

- `guide/tools/test_crawl_items.py` —— 转换规则的单元测试；
- `python guide/tools/crawl_items.py --check` —— 把转换结果与金标准快照逐字段比对（差异逐条列出，有差异退出码非 0）；
- 实跑时的报告：`python guide/tools/crawl_items.py --report <路径>`。

数据源被 Cloudflare 拦死时，变红的是**那条命令**（它的报告里有 `fetchError`），不是本脚本 ——
本脚本不碰数据源。采集侧绕开数据源的办法是走离线语料复跑（`--items-file` / `--wikitext-dir`），
见 `docs/crawler-容错普查.md`。

## 为什么是 PowerShell 而不是 bash

仓库里的 `.cmd` 约定（见 `.gitattributes`）与演示机是 Windows，PowerShell 是这台机器上
零依赖的选择——不需要装 curl / jq，`Invoke-RestMethod` 自带 JSON 解析。

> ⚠️ 脚本存为**带 BOM 的 UTF-8**。Windows PowerShell 5.1 读无 BOM 的 UTF-8 文件会按 ANSI
> 解码，中文全变乱码、脚本直接语法错误跑不起来。改动这个文件时别把 BOM 弄丢。
