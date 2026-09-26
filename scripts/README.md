# HTTP 冒烟验收脚本（T6 / issue #17）

对**运行中的后端**发真实请求，一次穿过控制器、采集、转换、持久层、静态资源映射，
用「已知答案断言」给转换逻辑做端到端回归保护。票面见 #17，断言清单来自父票 #11 的「测试决定」一节。

## 一条命令运行

先在 `guide/` 下把后端起起来（工作目录必须是 `guide/`：图标要落到 `guide/icons/`）：

```bash
cd guide && mvn -o spring-boot:run
```

**另开一个终端**，在本脚本所在目录跑：

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

| # | 接缝 | 断言 | 为什么 |
|---|---|---|---|
| 1 | `POST /admin/crawl` | HTTP 200 | 采集接口能跑完 |
| 2 | | `fetchError` 为空 | 拉取阶段没挂（被 Cloudflare 拦时这里会亮） |
| 3 | | `fetchedRows ≥ 130` | 下限口径，数据源增删物品不误报 |
| 4 | | `successCount ≥ 130` | 真落库了，不是只拉到没写 |
| 5 | | `failures` 为空或列明 | 单条失败不影响整批，但必须看得见 |
| 6 | | `wikitextError` 为空 | 源文管道通了（熟食覆盖值/可烹饪判据的来源） |
| 7 | | `iconSuccessCount ≥ 130` | 图标真下下来了 |
| 8 | | `iconFailures` 为空或列明 | 同上，且可列明 |
| 9 | | `warnings` 打印 | 字典未知取值要有痕迹 |
| 10 | `GET /testItemList` | 行数 ≥ 130 | 全量列表口径 |
| 11 | | 行数 = 去重后 nameEn 数 | 重复触发采集不产生重复行（幂等） |
| 12 | | 每行 `tag` / `effect` 都不是 null | 顺带回归保护 JSON 列映射（`autoResultMap` 漏了会静默全 null 且不报错）。判据用「不是 null」而不是「非空」：`Scout's Ambition` 这类纯功能护身符**真的**一个状态效果都没有，`effect` 合法地就是 `[]`，断言非空会把正确行为判成 FAIL |
| 13 | | 每行 `icon` 以 `/icons/` 开头 | 条目 icon 列是相对路径，不外链 wiki |
| 14 | | Hot Dog：`biome=Gloom` | 五维标签 |
| 15 | | Hot Dog：`rarity=Rare` | 同上 |
| 16 | | Hot Dog：`HUNGER=-30` | 生食状态效果 |
| 17 | | Hot Dog：`HUNGER_COOKED=-60` | 熟食总量口径（生值 × 2） |
| 18 | | Bugle Shroom (Poisonous) 独立成行 | 毒变体不并进普通版 |
| 19 | | 该行 `POISON=20` | 毒变体带着毒数值 |
| 20 | | 该行 `POISON duration=8` | 附属值：`poisonTime` → `duration` |
| 21 | | 该行 `POISON startDelay=10` | 附属值：`poisonStart` → `startDelay` |
| 22 | | Warp Compass 带 `flag=removed` | 已移除条目标记 |
| 23 | | First Aid Kit 不带 `flag=cookable` | 可烹饪判据反例 |
| 24 | | Green Crispberry：`POISON_COOKED=0` | 浆果煮熟毒清零（显式写 0） |
| 25 | | Green Crispberry：`POISON_COOKED` 无附属值 | 清零是"煮掉了"，不该残留 duration/startDelay |
| 26 | | Green Crispberry：`POISON duration=4` | 生毒仍在，两侧对照才有意义 |
| 27 | `GET /icons/Hot_Dog.png` | HTTP 200 | 静态资源映射 |
| 28 | | `Content-Type` 含 `image/png` | 真的是 PNG，不是错误页 |
| 29 | | 响应体以 PNG 魔数开头 | 内容是 PNG，不是被静态映射当成品发出去的半张图 |

**退出码**：全过 0，有 FAIL 非 0 —— 可以直接挂进演示前的检查清单。

## 交付当天实跑留档（2026-09-26）

真实采集一次、脚本全绿的记录，作为"库里有 134 条真实数据"的证据：

```
采集报告：{"failures":[],"fetchError":null,"fetchedRows":134,"iconFailures":[],
          "iconSuccessCount":134,"successCount":134,"warnings":[],"wikitextError":null}

列表接口：134 行，去重后 134 个 nameEn
图标：GET /icons/Hot_Dog.png → 200，Content-Type: image/png，28589 字节

全部通过：36/36 条断言 PASS
退出码：0
```

连跑多次采集后 `select count(*), count(distinct name_en) from item` 都是 `134 / 134`，
幂等成立（按 nameEn upsert，只更新不重复插入）。

## 数据源被 Cloudflare 拦死时

脚本会变红，这是**正确信号**而不是误报（触发离线导入降级预案）。
脚本对这种情形给了明确提示，并直接把报告 JSON 里的 `fetchError` 打印出来，
不用去翻后端日志猜是被拦了还是字段清单过期了。

## 为什么是 PowerShell 而不是 bash

仓库里的 `.cmd` 约定（见 `.gitattributes`）与演示机是 Windows，PowerShell 是这台机器上
零依赖的选择——不需要装 curl / jq，`Invoke-RestMethod` 自带 JSON 解析。

> ⚠️ 脚本存为**带 BOM 的 UTF-8**。Windows PowerShell 5.1 读无 BOM 的 UTF-8 文件会按 ANSI
> 解码，中文全变乱码、脚本直接语法错误跑不起来。改动这个文件时别把 BOM 弄丢。
