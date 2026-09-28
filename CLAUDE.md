# guide —— PEAK 微信小程序图鉴（后端）

Spring Boot 4.1.1 / Java 21 / MyBatis-Plus 3.5.17 / Redis / Shiro 3.0.1 /
weixin-java-miniapp / JJWT。MySQL + Druid。小程序前端独立。

## Agent skills

### Issue tracker

Issues live in GitHub Issues, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, default names. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `CONTEXT.md` + `docs/adr/`. See `docs/agents/domain.md`.

## 这个仓库现在是什么状态

**采集与数据链路已跑通；接口层、缓存、登录鉴权、AI 都还没做。**

能跑的：

- **采集 = 应用外面的一条命令**（`guide/tools/crawl_items.py`，Python）：拉取 → 页面源文 →
  图标本地化 → 按 `nameEn` upsert 落库 → 删缓存 key。**应用里没有采集代码，也没有触发它的
  入口** —— `POST /admin/crawl` 已删（打它回 404），应用只读库对外服务。
  理由见 `docs/adr/0001-采集搬出应用进程.md`；用法见仓库根 `README.md` 的「采集数据」。
- **134 条真实数据已落库**，`name_zh` 已回填 134/134。连跑多次采集幂等（脚本按 `nameEn` upsert）。
- **MyBatis-Plus 数据访问**：`ItemMapper` / `UserMapper` 继承 `BaseMapper`，
  `@MapperScan("org.example.guide.mapper")` 挂在 `GuideApplication` 上。
- 统一响应包（`utils/BaseResult` + `ResultCodeEnum`）与全局异常（`ExceptionAutoUtil`）。
- 图标本地化：`/icons/**` 映射到本地目录，条目 `icon` 列存相对路径，不外链数据源。

还没做的（`pom.xml` 里依赖已引入，但**零代码使用**）：

- **Redis** —— 一行代码都没有。三处落点（图鉴缓存 / AI 回答缓存 / 用户 API Key）都没落，
  见 `CONTEXT.md` 的「缓存」。
- **登录与鉴权** —— `ShiroConfig` 的过滤链是 `/** = anon`（全放行），`AdminRealm` 是
  "任意账号都能登录"的占位实现。`weixin-java-miniapp` 与 `jjwt` 都还没用上。
- **AI 助手** —— 没有 `/api/ai/chat`。
- **图鉴接口** —— 本轮落地 4 个：`/api/items`、`/api/items/{slug}`、`/api/tags`、`/api/biomes`。
  `/api/items/search` 保留但不使用；登录（`/api/auth/login`）与 AI（`/api/ai/chat`）不在本轮。
  **字段级口径已收口**，见 `docs/接口契约.md`（附录 A 是 Q1–Q24 的逐条答复）。
  现在只有两个早期自测端点：`ItemController` 的 `/testItemList`、`UserController` 的 `/testInsertUser` —— **都留着不删**。
  `pojo/dto/ItemDto` 还是旧的 `tag`/`effect` 直传结构，要拆成列表 DTO / 详情 DTO 两个类。
  **`slug` 与 `primaryType` 是计算值**，表里没有这两列；`isCookable` 不用算，
  直接取 `flag=cookable` 标签。
  **筛选与搜索在小程序本地做**：`/api/items` 不接受筛选参数、一次返全量 134 条、不分页；
  一级导航（8 个主类型格）与关键词搜索都在客户端，详情页跳转靠列表里的 `slug`。

两条事实：

- **仓库里没有建表 SQL**，表结构的唯一真相是 `pojo/Item.java`（见下一节）。
- GitHub Issues `#20`–`#25` 是"把 Java 采集改写成 Python"那条线。
  **行为保持是硬约束**：改写后的脚本写出的六列与 Java 版逐字段一致，靠金标准快照 +
  `crawl_items.py --check` 比对（`guide/src/test/resources/crawler/golden-items.json`）。
  #25 之后仓库里只剩 Python 一套。

## 常用命令

```bash
# 编译（跳过测试）
cd guide && mvn -DskipTests compile

# 启动后端。**工作目录必须是 guide/**：图标要落到 guide/icons/
cd guide && mvn -o spring-boot:run

# 测试
cd guide && mvn test
cd guide && mvn -Dtest=GuideApplicationTests test    # 单个测试类

# 采集（Python；后端不必在跑，采集直接写库 + 删缓存 key）
cd guide && python tools/crawl_items.py              # 真跑一次全量采集并落库
cd guide && python tools/crawl_items.py --check      # 只转换、与金标准比对，不写库不下图
cd guide && python tools/test_crawl_items.py         # 采集脚本自己的单测（stdlib unittest）

# HTTP 冒烟验收（后端已在跑时，另开一个终端、在仓库根跑）
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
```

- 采集的外部连接走环境变量：`GUIDE_DB_*`、`GUIDE_REDIS_*`、`GUIDE_ICON_DIR`（默认值见
  `crawl_items.py` 的文件头）；依赖 `pymysql`（写库）与 `mwparserfromhell`（解析页面源文）。
- 数据源的 url / 账号 / 密码可在 `application-local.yml` 里覆盖（`spring.profiles.include: local`）。
  该文件已 gitignore，且**仓库里没有 `.example` 模板** —— 换机器要照 `application.yml` 的键自己补一份。
- `scripts/smoke-test.ps1` 存为**带 BOM 的 UTF-8**：Windows PowerShell 5.1 读无 BOM 的 UTF-8
  会按 ANSI 解码，中文变乱码后脚本直接语法错误。改它时别把 BOM 弄丢。

## 架构要点

两个平级模块：`guide/`（Spring Boot 后端，Maven）与 `guide-mini/`（小程序，不在 Maven 构建里）。

**后端分层**：`controller` → `service`(+`impl`) → `mapper` → `pojo`(+`dto`)；
`config/` 放 Shiro、静态资源映射与头像/图标目录；`utils/` 放统一响应包、全局异常与计算字段；
`dictionary/` 放标签与状态效果的中文字典；`cache/` 放三个 Redis 缓存组件；`ai/` 放 DeepSeek 调用。
`mapper-locations` 指向 `classpath:mapper/*.xml`，但**仓库里没有任何 XML** ——
查询全走 `BaseMapper` 与 `LambdaQueryWrapper`。

**采集脚本**（`guide/tools/`，Python；**应用里没有采集代码**）。读这几个文件时按下面的顺序理解，
它的出口是一份报告（`--report` 落到文件，否则打到标准输出）：
`fetchedRows` / `successCount` / `failures` / `iconSuccessCount` / `iconFailures` / `warnings`，
以及拉取、页面源文两个阶段各自的错误字段。

| 文件 | 职责 |
|---|---|
| `crawl_items.py` | 编排：拉取 → 页面源文 → 转换 → 图标 → 落库 → 删缓存 key；`--check` 只转换并比对金标准 |
| `page_source.py` | 认 `{{Infobox item}}`；熟食覆写值（熟食饱食 = 生值 × 2、加成 × 1.5，无生值给 10）+ 描述 + 成就 |
| `item_icons.py` | 图标下到 `guide/icons/`；文件名规则与 `utils/IconFileNames` 同一套白名单 |
| `freeze_baseline.py` | 冻结重写基线：金标准快照 + 离线语料（#21） |
| `test_crawl_items.py` | 纯函数与假 HTTP 接缝的单测（`python tools/test_crawl_items.py`） |

采集写库时**只写六列**（`weight` / `icon` / `tag` / `effect` / `description` / `achievement`），
`name_zh` 与 `description_zh` 是「对照表回填」的地盘，一个字都不动。
跑完删三个缓存 key（图鉴全量 / 问答 / 题库）——这就是 `cache/` 里那几个 `evict*` 方法的调用方，
只不过那个调用方现在在应用外面。

## 数据模型（改 `Item` 前必读）

**表结构已定稿**（表名 `item`，10 列）。**仓库里没有建表 SQL** —— 换机器重灌数据要照这张表手写 DDL。
`pojo/Item.java` 目前**缺 `descriptionZh` 一个字段**（列在库里已存在且已回填），本轮补上。

| 字段 (`Item.java`) | 类型 | 形态 |
|---|---|---|
| `id` | `Long` | 自增主键 |
| `nameEn` | `String` | 采集按它 upsert，去重口径；`slug` 也由它派生 |
| `nameZh` | `String` | 中文名，可由名称对照表回填 |
| `weight` | `Float` | 含负数与一位小数（见下） |
| `icon` | `String` | 相对路径，如 `/icons/Hot_Dog.png` |
| `description` | `String` | **英文**长文本，最长 1262 字，不下发给小程序 |
| `descriptionZh` | `String` | **中文**长文本，最长 391 字，134/134 已回填；**它是唯一下发给小程序的那份** |
| `achievement` | `String` | 可空，库里 28 条有 |
| `tag` | `List<ItemTag>` | JSON 列，`[{code, value, nameZh}]`，六维：`type`/`biome`/`rarity`/`source`/`location`/`flag` |
| `effect` | `List<Effect>` | JSON 列，`[{code, value, duration, startDelay}]`；**生熟混在一个数组里**，熟值靠 `_COOKED` 后缀区分且存的是**总量** |

已知的**数据事实**（来自对数据源的分析，是事实不是设计决定）：

- 数据源的重量字段实测取值含小数和负数：`{-15, -2.5, -5, 0, 2.5, 5, 7.5, 10, 12.5, 40}`。
  **`int` 存不下 `2.5` / `7.5` / `12.5`** —— 落库类型必须能表示一位小数。
- 分类在**落库后是 JSON 数组**（源数据里是逗号分隔的多值字符串，如 `"Food, Natural food, Berry"`）。
  想用 SQL 精确筛"含某个标签"要么匹配不到、要么误命中 —— **筛选放应用层**
  （见 `CONTEXT.md` 的「标签」「主类型」）。
- 状态效果是**封闭集合**（约 14 种），不是开放集合。

MyBatis-Plus 的坑（与表结构无关，一定会遇到）：

- 用 `JacksonTypeHandler` 映射 JSON 列时，**`@TableName(autoResultMap = true)` 不能漏**，
  否则该字段永远返回 `null` **且不报错**。
- **不要让 JSON 列原样透传给小程序**。接口响应用固定 DTO 组装 ——
  小程序有版本碎片问题，改一个 key 就白屏。
- 拼 `LambdaQueryWrapper` 想表达 `(A OR B)` 时，必须 `.and(w -> w.like(...).or().like(...))`
  包起来。直接在 wrapper 上 `.or()` 会打穿整个 `WHERE`，之后叠加的任何条件都失效（已踩过一次）。

词汇定义以 `CONTEXT.md` 为准（图鉴条目 / 生 / 熟 / 可烹饪 / 主类型 / 标签 / 生态 /
状态效果 / 图鉴 / 数据源 / 爬虫采集 / 缓存 / AI 助手 / 演示环境）。

## 参考资料的地位

`resul.md` 是**分析笔记**（在所有者本地，不进仓库），**不是规格**。
可以引用它记录的事实，但**它的表结构草案已被定稿版取代** —— 表结构以 `pojo/Item.java`
和上一节为准。

## 演示环境

后端跑 `localhost:8080`，小程序在**微信开发者工具**里演示，
需要勾选"不校验合法域名"（**每人每台电脑单独勾一次**）。
**真机演示不在范围内** —— 它需要一个已备案的 HTTPS 域名。

## 文档语言

本仓库的文档和提交信息用**中文**。不要把现有中文文档改写成英文。
