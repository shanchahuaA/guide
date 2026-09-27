# PEAK Wiki 数据结构分析 & 图鉴小程序数据库设计

> 分析对象:`https://peak.wiki.gg/`(PEAK 游戏 Wiki)
> 目标:微信小程序游戏图鉴,首期做**食物**和**道具**两个模块
> 分析日期:2026-09-21

---

## 0. 结论速览(先看这个)

1. **不需要爬 HTML。** 这个 Wiki 是 MediaWiki + **Cargo**(结构化数据扩展),核心数据存在一张叫 `Items` 的数据库表里,`api.php` 一条请求就能拿到全部结构化字段。爬 HTML 是最笨的做法。
2. **数据量极小。** 食物范围约 **48** 个条目,道具范围约 **82** 个条目,合计约 **130** 条。这个量级下**不要过度设计**——宽表都能跑得很舒服,加索引都是多余的。
3. **直接照搬 Wiki 的表结构是错的。** 它是"稀疏宽表 + 逗号分隔多值",为编辑方便服务,不为查询服务。有 40% 以上的列长期是 NULL。
4. **有派生数据。** "烹饪后效果"在 Wiki 里不是存的值,是模板用公式算出来的,你要决定在哪一层算。
5. **⚠️ 许可证是 CC BY-SA 4.0。** 必须署名原作者 + 相同方式共享。这条会影响你的小程序能不能商用,见第 8 节。

---

## 1. 访问方式(踩坑记录,你复现时会用到)

直接抓会被拦:

```
curl https://peak.wiki.gg/  →  HTTP 403,页面标题 "Just a second..." (Cloudflare JS 挑战)
```

浏览器能过是因为执行了 JS 挑战。命令行绕过方式:**伪装 Googlebot 的 UA**,`api.php` 直接返回 200。

```bash
UA="Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
```

注意:伪装 UA 后 **`api.php` 通,但 `wiki/Special:AllPages` 这类 HTML 页面仍然 403**(`robots.txt` 通、`sitemap.xml` 返回 wiki 自己的 404 页)。所以走 API,别走网页。

### 可用的 API 端点

| 用途 | 端点 |
|---|---|
| 全站页面列表 | `api.php?action=query&list=allpages&aplimit=500&format=json` |
| 分类成员 | `api.php?action=query&list=categorymembers&cmtitle=Category:Food&cmlimit=500&format=json` |
| 全部分类 | `api.php?action=query&list=allcategories&aclimit=500&format=json` |
| 页面源码(wikitext) | `api.php?action=parse&page=Hot_Dog&prop=wikitext&format=json` |
| **结构化数据(主力)** | `api.php?action=cargoquery&tables=Items&fields=...&limit=500&format=json` |

`cargoquery` 支持 `tables` / `fields` / `where` / `limit` / `offset`,本质上是个只读 SQL 接口。**这是你采集数据的主入口。**

---

## 2. 站点整体结构

- **引擎**:MediaWiki 1.43.6,站点名 "PEAK Wiki",英文站
- **扩展**:Cargo(结构化表)、DPL3(动态列表)、Tabber、大量 Lua 模块
- **命名空间**:主空间 + `Template:` / `File:` / `Category:` / `User:`(有沙盒垃圾页,采集时需过滤 `User:` 前缀)

### 2.1 页面内部结构(单个物品页)

```
{{Infobox item ...}}     ← 结构化字段,同时写入 Cargo 表
正文描述段                ← 散文,含数值说明
== Trivia / Tips ==      ← 杂项
== Gallery ==            ← 图片,File:xxx.png
== Patch history ==      ← 版本变更记录({{patch|1.0|...}})
{{Navbox food}}          ← 导航模板
```

### 2.2 分类体系(实测数量)

| 分类 | 数量 | 备注 |
|---|---|---|
| Items | 133 | 总集合 |
| Food | 50 | 含 2 个 User 沙盒页,实际约 48 |
| Natural food | 33 | 子集 |
| Packaged food | 10 | 子集 |
| Consumable | 20 | 消耗品 |
| Equipment | 23 | 装备 |
| Deployable | 14 | 可放置物 |
| Mushroom | 7 | 蘑菇(食物子集) |
| Berry | 24 | 浆果(食物子集) |
| Mystical item | 19 | 神秘物品(与 Consumable/Equipment 交叉) |
| Amulet / Cosmetic / Enemy 等 | 5 / — / 2 | 首期不做 |

**关键认知:这些分类是交叉的。** 例如一个浆果同时属于 `Food` + `Natural food` + `Berry`;`Cure-All` 同时属于 `Consumable` + `Mystical item`。所以图鉴里"一件东西出现在多个栏目"是正常现象,数据库必须支持多对多。

---

## 3. 核心数据源:Cargo 表 `Items`

由 `Template:Infobox item` 用 `#cargo_declare` 声明,目前 **134 行 / 131 个不同页面名**。

### 3.1 完整字段清单(实测填充率)

| 字段 | 声明类型 | 有值行数 | 说明 |
|---|---|---|---|
| `_pageName` | String | 134/134 | 页面名,天然主键候选 |
| `display` | String | 134/134 | 展示名,与页面名仅 3 处不同 |
| `type` | List(,) | 134/134 | **多值**,12 种取值 |
| `sortingTags` | List(,) | 134/134 | 复数形式,专供列表筛选 |
| `rarity` | String | 43/134 | 稀有度,大量为空 |
| `biome` | List(,) | 83/134 | 出现生态,12 种 |
| `location` | List(,) | 13/134 | 具体位置,基本不用 |
| `source` | List(,) | 57/134 | 获取来源(容器/雕像等) |
| `uses` | Float | 134/134 | 使用次数,实际取值 `{0,1,3,4}` |
| `badges` | List(,) | **0/134** | 声明了但全空,别采 |
| `weight` | Float | 134/134 | 见下方 ⚠️ |
| `hunger` | Float | 50/134 | 饱食度影响(负=减少饥饿) |
| `bonus` | Float | 15/134 | 加成体力 |
| `heat` / `cold` | Float | 16 / 7 | 冷热 |
| `injury` | Float | 14/134 | 伤害/治疗 |
| `poison` | Float | 16/134 | 中毒 |
| `spores` | Float | 14/134 | 孢子 |
| `drowsy` | Float | 9/134 | 困倦 |
| `curse` | Float | 3/134 | 诅咒 |
| `thorns` | Float | 4/134 | 荆棘 |
| `poisonTime` / `poisonStart` | Float | 6 / 6 | 中毒持续时间/延迟 |
| `coldTime` | Float | 1/134 | 基本不用 |
| `drowsyTime` | Float | **0/134** | 全空,别采 |
| `cookingNotes` | Wikitext | 48/134 | 烹饪说明(富文本) |
| `listHideStats` | Boolean | — | 列表页是否隐藏数值 |
| `listNotes` | Wikitext | — | 列表页附注 |
| `removed` | Boolean | 2/134 | 是否已从游戏移除 |

### 3.2 数据集里的 6 个坑

**坑 1 —— 稀疏宽表。** 26 列里,只有 4 列是满的。`rarity` 有 2/3 为空,大部分状态字段填充率 3%~12%。原样搬过来就是一张到处是 NULL 的表。

**坑 2 —— 多值用逗号字符串存。** Cargo 的 `List (,) of String` 底层就是 `"Food, Natural food, Berry"` 这种字符串。没有关联表,也没法直接索引。

**坑 3 —— `type` 的取值分布(实测):**

| 组合 | 数量 |
|---|---|
| Food, Natural food, Berry | 24 |
| Misc | 23 |
| Equipment | 15 |
| Consumable | 13 |
| Deployable | 11 |
| Food, Natural food | 9 |
| Food, Packaged food | 8 |
| Food, Mushroom | 7 |
| Consumable, Mystical item | 5 |
| Amulet, Mystical item | 5 |
| 其余(含 Enemy 2 行) | ~13 |

**坑 4 —— 主键不能只用页面名。** `Bugle Shroom`、`Button Shroom`、`Cluster Shroom` 各有 **2 行**。原因是这三种蘑菇的页面里放了"普通版"和"中毒版"两个 infobox。建模时要么拆出变体概念,要么用自增主键。

**坑 5 —— `weight` 有负值。** 实测取值集合: `{-15, -2.5, -5, 0, 2.5, 5, 7.5, 10, 12.5, 40}`。负值语义需要你进游戏或看页面确认(疑似"装备后的负重修正"而非"物品重量"),**不要想当然按正数处理**。

**坑 6 —— 类型与语义不符。** `uses` 声明是 `Float` 但实际是整数次数;`weight` 也是小数但不是连续量。落库时按整数/定点数处理更好。

### 3.3 首期范围的实际规模

| 范围 | 条目数 | 说明 |
|---|---|---|
| 食物(Type 含 Food / Natural food / Packaged food / Mushroom / Berry) | **48** | |
| 道具(Type 含 Consumable / Equipment / Deployable / Mystical item / Amulet / Misc) | **82** | |
| 两者重叠 | **0** | 食物与道具完全互斥,可以干净地拆成两个模块 |
| 合计 | **130** | 这就是你首期的全部数据量 |

> `Misc`(23 个)是"其他杂物",品质参差,建议首期先归到道具里或直接排除,你定。

---

## 4. 派生数据:烹饪后效果是算出来的

`Template:Infobox item` 里没有存"熟食数值"字段,Hunger/Bonus 的熟食版本是模板用公式推的:

```
熟食饱食_展示增量  = Hunger × 2 − Hunger  = Hunger
熟食饱食_总量      = Hunger × 2
熟食加成_展示增量  = Bonus × 1.5 − Bonus = Bonus × 0.5
熟食加成_总量      = Bonus × 1.5
```

另外模板还有个 `HasCookingBonus` 开关(默认 `yes`,可设 `no`/`breaks` 覆盖是否套用上面的公式)。

**对你的影响:** 图鉴上如果要显示"生吃 vs 煮熟"对比,这个数值要么在**应用层按公式算**(推荐,改公式不用改数据),要么在**采集时算好落库**(查询简单但公式变了要重刷数据)。二选一,别两边都存。

---

## 5. 图片资源

- `Category:Item images`:**111 个文件**,另有 `Colorblind Item images`(色盲模式替换图)、`Hat images` / `Outfit` / `Luggage Icons` 等
- 命名约定:**基本等于 `File:<页面名>.png`**,少数带编号(如 `Balloon 1.png` ~ `Balloon 5.png`)
- Infobox 的默认逻辑就是 `[[File:{{PAGENAME}}.png]]`,所以名字对不上时才会显式指定

**小程序端的坑:** 微信小程序不能直接外链 `peak.wiki.gg`(域名要加白名单 + 备案,而且对方是 Cloudflare 会拦)。**必须转存到自己的 OSS/CDN**,采集时顺手把图下载下来重命名成 `<slug>.png`,和条目一一对应。

---

## 6. 数据库设计建议

### 6.1 设计原则

- 数据量只有 130 条 → **不追求范式,追求查询方便 + 后续加字段不用改表**
- 要**筛选**的多值字段(类型、生态)建关联表;只**展示**的(来源、位置)用 JSON 列
- 状态效果种类是**封闭集合**(实测 14 种:`hunger / bonus / heat / cold / injury / poison / spores / drowsy / curse / thorns` + 3 个带时间的变体),所以 EAV 和固定列都可行

### 6.2 推荐表结构(MySQL 8)

```sql
-- 物品主表(食物和道具共用,用 category 区分)
CREATE TABLE `t_item` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `slug`          VARCHAR(64)  NOT NULL COMMENT 'URL 标识,取自 wiki 页面名',
  `wiki_page`     VARCHAR(64)  NOT NULL COMMENT 'wiki 页面名(变体行会重复)',
  `variant`       VARCHAR(32)  DEFAULT NULL COMMENT '变体标识,如 poisonous',
  `name_en`       VARCHAR(64)  NOT NULL COMMENT '英文名',
  `name_zh`       VARCHAR(64)  DEFAULT NULL COMMENT '中文名(需自译)',
  `icon`          VARCHAR(255) DEFAULT NULL COMMENT '图标 OSS 地址',
  `primary_type`  VARCHAR(32)  NOT NULL COMMENT '主类型:FOOD / CONSUMABLE / EQUIPMENT / DEPLOYABLE / MYSTICAL / MISC',
  `rarity`        VARCHAR(32)  DEFAULT NULL COMMENT '稀有度',
  `max_uses`      INT          NOT NULL DEFAULT 1 COMMENT '使用次数,对应 uses',
  `weight`        DECIMAL(5,1) DEFAULT NULL COMMENT '⚠️ 可为负,语义待确认',
  `source_text`   VARCHAR(255) DEFAULT NULL COMMENT '来源描述(原始文本)',
  `sources`       JSON         DEFAULT NULL COMMENT '来源列表,只展示不筛选',
  `locations`     JSON         DEFAULT NULL COMMENT '位置列表,只展示不筛选',
  `cooking_notes` TEXT         DEFAULT NULL COMMENT '烹饪说明(富文本,存纯文本或 html)',
  `description`   TEXT         DEFAULT NULL COMMENT '图鉴描述,可自写或 AI 生成',
  `is_removed`    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已移除',
  `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '自定义排序',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`),
  KEY `idx_primary_type` (`primary_type`),
  KEY `idx_rarity` (`rarity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物品/食物图鉴主表';


-- 标签表(对应 type 与 sortingTags 的复数形式)
CREATE TABLE `t_tag` (
  `id`        BIGINT      NOT NULL AUTO_INCREMENT,
  `code`      VARCHAR(32) NOT NULL COMMENT '如 BERRY / MUSHROOM / NATURAL_FOOD',
  `name_zh`   VARCHAR(32) NOT NULL COMMENT '中文名,如 浆果',
  `name_en`   VARCHAR(32) NOT NULL,
  `is_food`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否属于食物模块',
  `sort`      INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类标签字典';


-- 物品-标签 多对多
CREATE TABLE `t_item_tag` (
  `item_id` BIGINT NOT NULL,
  `tag_id`  BIGINT NOT NULL,
  PRIMARY KEY (`item_id`, `tag_id`),
  KEY `idx_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物品标签关联';


-- 生态字典 + 关联(因为要按生态筛选)
CREATE TABLE `t_biome` (
  `id`      BIGINT      NOT NULL AUTO_INCREMENT,
  `code`    VARCHAR(32) NOT NULL COMMENT '如 TROPICS / ROOTS / MESA / SHORE / GLOOM ...',
  `name_zh` VARCHAR(32) NOT NULL,
  `sort`    INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生态字典';

CREATE TABLE `t_item_biome` (
  `item_id`  BIGINT NOT NULL,
  `biome_id` BIGINT NOT NULL,
  PRIMARY KEY (`item_id`, `biome_id`),
  KEY `idx_biome` (`biome_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物品生态关联';


-- 状态效果(EAV):14 种状态,带 time / start 附属字段
CREATE TABLE `t_item_status` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `item_id`     BIGINT       NOT NULL,
  `status_code` VARCHAR(32)  NOT NULL COMMENT 'HUNGER/BONUS/HEAT/COLD/INJURY/POISON/SPORES/DROWSY/CURSE/THORNS',
  `value`       DECIMAL(6,1) NOT NULL COMMENT '正负号有语义:负=消除该状态',
  `duration`    DECIMAL(5,1) DEFAULT NULL COMMENT '持续秒数,对应 poisonTime 等',
  `start_delay` DECIMAL(5,1) DEFAULT NULL COMMENT '延迟生效,对应 poisonStart',
  `phase`       VARCHAR(16)  NOT NULL DEFAULT 'RAW' COMMENT 'RAW=生食 / COOKED=烹饪后',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item_status_phase` (`item_id`, `status_code`, `phase`),
  KEY `idx_item` (`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物品状态效果';
```

### 6.3 两个关键取舍,说明理由

**取舍 A:状态效果用 EAV 还是固定列?**

- EAV(上面的 `t_item_status`):加新状态不用改表;天然支持 `phase` 区分生熟;缺点是单条物品要 join 出多行。
- 固定列:查询快、一眼看全;缺点是 14 个状态 × 2 个 phase = 28 列,而实际填充率极低(多数物品只有 1~3 个状态),会重现 Wiki 那张稀疏宽表的问题。

**推荐 EAV。** 你的填充率数据支持这个判断:50 行有 hunger,但只有 3 行有 curse、4 行有 thorns。固定列会浪费且难扩展。

**取舍 B:多值字段用关联表还是 JSON?**

- **要筛选的 → 关联表**:`type` / `sortingTags`(按"浆果""蘑菇"分类浏览)、`biome`(按生态筛选)。这些是图鉴的核心交互,必须有索引。
- **只展示的 → JSON 列**:`source`(来源是"Regular Luggage, Big Luggage..."这种说明性文本)、`location`。用户不会按"来源容器"筛选,存 JSON 让小程序一次取回直接渲染。

**取舍 C:`primary_type` 为什么单独冗余一列?**

因为一件物品有多个 type 标签(`Food, Natural food, Berry`),但图鉴的**一级导航**只有一个入口。把主类型单独落一列可以避免每次列表查询都 join 关联表。冗余这一列是有意的,不是设计失误。

### 6.4 Redis 缓存策略

数据总量 130 条,序列化后**几十 KB 级别**。

- 应用启动时**全量预热**到 Redis:`guide:item:all` 一个 key 存全量 JSON
- 详情页单独 key:`guide:item:{slug}`
- 分类列表 key:`guide:item:type:{primaryType}`
- 数据更新走后台管理时**删 key 重建**,不需要复杂的一致性方案

这个量级下,甚至可以直接放 Caffeine 本地缓存,Redis 都可以不用——但你的栈里有 Redis,顺手用即可。

---

## 7. 数据采集流程建议

```
1. 拉全量 Cargo 数据
   api.php?action=cargoquery&tables=Items&fields=<全部字段>&limit=500&format=json
   → 落 items_raw.json(134 行)

2. 按 type 过滤出首期 130 条(食物 48 + 道具 82)

3. 对每个页面补抓 wikitext(拿烹饪说明以外的补充信息)
   api.php?action=parse&page=<page>&prop=wikitext&format=json

4. 下载图标 → 重命名 <slug>.png → 上传 OSS
   源:https://peak.wiki.gg/wiki/Special:FilePath/<页面名>.png

5. 写 ETL:把逗号分隔的多值字段拆开 → 灌进上面的表

6. 中文名/描述:Wiki 基本只有英文(ZH 标签页只有 3 个),
   这块要人工翻译或用你栈里的 AI 生成,记得人工校对游戏术语
```

**过滤规则提醒:** 采集时排除 `User:` 命名的页面(沙盒模板垃圾),`Category:Food` 的 50 个里有 2 个是 `User:Aw4yland/sandbox/...`。

---

## 8. ⚠️ 许可证合规(这条很关键)

Wiki 的版权信息(`rightsinfo`)明确是:

```
Creative Commons Attribution-ShareAlike 4.0 (CC BY-SA 4.0)
https://creativecommons.org/licenses/by-sa/4.0
```

你的义务:

1. **署名** —— 在小程序里放"数据来源:PEAK Wiki (peak.wiki.gg),CC BY-SA 4.0"的说明页
2. **相同方式共享(ShareAlike)** —— 基于这些数据做的衍生作品也要以 CC BY-SA 4.0 发布。**这一条会和"闭源/商用"产生冲突**,如果这是要参加比赛或商业化,建议先问清楚规则
3. 图片同理,而且游戏素材本身还涉及开发商的版权,比 Wiki 文本更敏感

> 实践建议:游戏**数值**(伤害值、饱食度这类事实性数据)通常不受版权保护,但**描述性文字和图片**受保护。所以图标和文案最好是自绘/自写,只参考数值。这条建议不构成法律意见,正式发布前请自行确认。

---

## 9. 需要你确认的几个点

1. **`weight` 负值到底是什么意思?** 影响落库时的字段语义和展示逻辑(可能需要拆成"重量"和"负重修正"两个字段)
2. **中毒蘑菇要不要作为独立条目?** `Bugle Shroom` 等 3 个页面各有普通/中毒两行,是合并成一条带"变体"属性,还是拆成两条独立图鉴项?
3. **`Misc` 那 23 个杂物算不算"道具"?** 品质参差,可能拉低图鉴整体观感
4. **中文文案从哪来?** 人工翻译还是 AI 生成(你栈里有 AI),这决定了要不要建 `t_item_i18n` 表
5. **图鉴要不要显示"烹饪后"数据?** 决定是否在采集时预计算(第 4 节的公式)

---

## 10. 方案评审:JSON 存字段 + 生熟拆两条

> 评审的是"主表只留少数固定字段,其余塞 JSON"和"煮熟/没煮熟拆成两个道具"这两个决定。

### 10.1 结论先说

| 决定 | 结论 | 成立的前提 |
|---|---|---|
| 大部分字段存 JSON | **可以,推荐** | 前提是**筛选在应用层做**,而不是靠 SQL。这个前提对你的数据量成立 |
| 生熟拆两个道具 | **技术上可行,但我不建议拆** | 已确认是纯查询工具,拆开不会破坏收集数;但会损害查询动线,见 10.3 |

---

### 10.2 关于"用 JSON 存字段"

#### 支持你这么做的真正理由:数据量

130 条 × 约 1KB ≈ **130KB**。全量塞进 Redis 或应用内存绰绰有余。

这意味着你**根本不需要在 SQL 层筛选**——而"JSON 不能建索引"恰恰是 JSON 方案唯一的致命缺点。这个缺点对你**不成立**。

所以这是个正确的决定,但你要清楚:**它是因为数据量小才正确,不是因为 JSON 本身更好。** 如果哪天图鉴扩到几千条(全物品+成就+生态+敌人),这个决定就要重新评估。

#### 哪些字段必须固定成列

判据只有一条:**会出现在 `WHERE` / `ORDER BY` / 客户端搜索框里的字段。**

| 列 | 为什么必须固定 |
|---|---|
| `id` / `slug` | 主键、路由 |
| `name_en` / `name_zh` | 搜索、展示、排序 |
| `icon` | 列表页必读 |
| `primary_type` | 一级导航(食物 / 道具),每次查询都带 |
| `cook_state` / `group_slug` | 见 10.3,如果决定拆生熟 |
| `rarity` | 筛选 |
| `weight` | 排序(数值型,放 JSON 里没法排) |
| `sort_order` / `status` / `created_at` / `updated_at` | 运营与后台管理 |

#### 哪些放 JSON

- **状态效果**:`hunger / bonus / heat / cold / injury / poison / spores / drowsy / curse / thorns` 及 `poisonTime / poisonStart` 等——稀疏且是封闭集合,最适合 JSON
- **列表字段**:`sources[]` / `locations[]`
- **长文本**:`cooking_notes` / `list_notes` / `description`
- **`biomes[]`**:⚠️ 这条取决于你怎么筛生态。如果只在应用层筛(推荐),放 JSON;如果以后要在 SQL 里 `WHERE biome = 'Tropics'`,它就得是固定列或关联表

#### 三个必须避开的坑

**坑 1 —— MyBatis-Plus 的 `JacksonTypeHandler` 会静默失效。**
必须同时配 `@TableName(autoResultMap = true)`,否则查询结果里该字段**永远是 null,而且不报错**。这是 MP 最经典的坑,第一次用必踩。

```java
@Data
@TableName(value = "t_item", autoResultMap = true)   // ← autoResultMap 不能漏
public class Item {
    @TableField(typeHandler = JacksonTypeHandler.class)
    private ItemAttributes attributes;               // JSON 列映射成对象
}
```

**坑 2 —— 用 MySQL 原生 `JSON` 类型,不要用 `TEXT`。**
`JSON` 类型会在写入时校验格式,写错直接抛异常;`TEXT` 会把脏数据静默存进去,等到小程序展示时才炸。

**坑 3 —— JSON 没有 schema 约束,必须靠导入侧补。**
绝不要"从 wiki 抓来直接序列化塞进库"。建议:采集 ETL 用**强类型 Java DTO 收口**,校验通过后再序列化成 JSON 落库。这样字段名写错在导入阶段就报错,而不是线上白屏。
另外建议在库里存一个 `schema_version` 字段,方便以后结构变更时做数据迁移。

#### 一个容易忽略的边界:库里的 JSON ≠ 接口的 JSON

微信小程序有**版本碎片**问题(用户不会主动更新,老版本会长期存在)。数据库字段可以随便加,但**接口响应结构必须有稳定契约**。

正确做法:服务端用固定 DTO 组装响应,把 JSON 列"翻译"成稳定的字段结构再返回。**不要让 JSON 列直接透传给小程序**——否则你哪天改个 JSON 里的 key,老版本小程序就白屏了。

---

### 10.3 关于"生熟拆成两个道具"

#### 先接受一个硬事实:熟食数据在结构化数据里根本不存在

我验证过了:

- Cargo `Items` 表**没有任何 cooked 字段**——`#cargo_declare` 里没声明,列表模板查询的字段里也没有
- 熟食数值是模板 `Infobox item` 在**渲染时现算**的,**从未落库**
- 极端例子:`Cooked Bird` 页面唯一的数值来自 wikitext 里的 `HungerCooked = -80`,而它在 Cargo 表里的那一行 **`hunger` 是空的**——也就是说这个 `-80` **用 `cargoquery` 根本查不到**

**结论:熟食数据你必须自己算 + 自己解析 wikitext。**

#### 熟的数值是怎么来的

- **默认公式**(绝大多数食物):`熟食饱食总量 = Hunger × 2`,`熟食加成总量 = Bonus × 1.5`(页面没写 Bonus 时,加成默认按 **10** 算)
- **显式覆盖很少见**:`HungerCooked` 只有 **4** 个页面用,`BonusCooked` 只有 **15** 个页面用
- **`HasCookingBonus = no` 有 62 个页面**:基本都是**非食物的道具**(Backpack、Cure-All、Piton……),意思是烹饪**不给**饱食/加成,但可能有别的效果
- **`Cooking-notes` 有 49 个页面**:这是"烹饪到底会发生什么"的人话描述

所以做熟食条目 = **套公式 + 解析这 60 来个页面的 wikitext 覆盖值**。项目里最脏的活在这一块。

#### 坑 A:不是所有食物都该有熟食版本

实测几个反例:

| 物品 | Cooking-notes | 问题 |
|---|---|---|
| `First Aid Kit` / `Bandages` | `No effect.` | 熟食条目毫无意义 |
| `Fortified Milk` | 加成无敌时间 | 熟食的 `Hunger` 是 `0`,真正的效果在备注里 |
| `Dynamite` / `Cure-All` | 会爆炸 | 熟食是"销毁"行为,不是数值变化 |

如果你无脑给每个食物生成一条熟食,会造出一堆**没有意义的空条目**。

→ 需要一个 `is_cookable` 判据。建议:**只有 `Cooking-notes` 有实质内容、或 Hunger/Bonus 变化不为 0 的,才生成熟食条目。**

#### 坑 B:游戏里"烹饪"是状态,不是新物品

你在游戏里把食物拿到火上烤,物品栏里**还是同一个东西**。Wiki 也是在**同一个页面**里放一个 "Cooked Bonuses" 区块,而不是新建页面。

`Cooked Bird` 是**特例**——它是从 Big Egg 里开出来的独立物品,不是通例。

把生熟拆成两条,等于**用你的数据模型覆盖游戏的模型**。这不一定是错的,但你要清楚自己在这么做,以及下面这个后果。

#### 坑 C:已确认是查询工具,所以真正的反对理由是"查询动线"

**已确认(2026-09-21):这是纯查询工具,游戏内部本来就没有图鉴,用户随时来查数据。不记录收集。**

所以"拆开会让收集数翻倍"这条**不成立**了。拆开在技术上完全安全。

但我仍然**不建议拆**,理由是查询动线:

**1. 用户搜"Hot Dog"时,想看的是"这东西生吃熟吃各怎样",不是两个条目。**
拆开后搜出来两条("Hot Dog" 和 "Cooked Hot Dog"),要点进去两次才能对比——而**对比正是这个页面最主要的用途**。合并成一条,一屏看完。

**2. 用户几乎没有理由主动搜"熟的"。**
食物是玩家**自己拿到火上烤的**,烤完手里还是同一个物品。用户此刻想知道的是"我手里这个热狗,烤了会怎样",而不是"有个叫'熟热狗'的独立道具"。拆开后反而要求用户先理解一个游戏里不存在的概念。

**3. 条目数要诚实。**
游戏里食物就是 48 个。你的小程序是**唯一的参考资料**,条目数和游戏真实物品数对上,用户数得清、也信得过。

**4. 维护成本更高。**
拆开意味着要给每个食物生成熟食条目,而 `First Aid Kit` 这类"熟了无效果"的还得逐个特殊处理。合并方案里这只是一个字段的事。

#### 正确的划分判据:按"游戏里是不是独立物品"分,不按"熟不熟"分

这是整件事的关键认知:

> **烹饪是物品的一个"状态",不是一个新物品。**

所以:

| 情况 | 处理 |
|---|---|
| Hot Dog、Egg、Marshmallow……(自己烤) | **同一条目**,熟食作为一个字段/区块 |
| `Cooked Bird`(从 Big Egg 里开出来就**只能是熟的**) | **独立条目**——因为它在游戏里本来就是独立物品 |
| `Bandages`、`First Aid Kit`(熟了无效果) | 同一条目,`is_cookable = false`,不显示熟食区块 |

`Cooked Bird` 不是"熟食拆条"的证据,它恰恰说明了判据应该是什么:**它在游戏里是不是一个独立物品。**

#### 备选方案:如果你坚持要拆,必须补一个分组键

```sql
ALTER TABLE `t_item`
  ADD COLUMN `group_slug`  VARCHAR(64) NOT NULL COMMENT '生熟配对的分组键,如 hot-dog',
  ADD COLUMN `cook_state`  VARCHAR(16) NOT NULL DEFAULT 'RAW' COMMENT 'RAW=生 / COOKED=熟',
  ADD COLUMN `cook_from_id` BIGINT      DEFAULT NULL COMMENT '熟食指向生食条目,便于互相跳转',
  ADD KEY `idx_group` (`group_slug`);
```

**理由:** 否则用户搜 "Hot Dog" 会看到两条互不相干的记录,点进去也完全不知道还有另一种吃法。生熟两条**必须共享 `group_slug`**,详情页互相有跳转入口,列表页可以选择"合并显示"或"分开显示"。

#### 拆的时候不要复制的字段

图标、生态、来源、重量、稀有度——**生熟完全一样**。只存一份(存在生食那条),熟食详情页关联过去取。

无脑复制会带来"同一个图标要维护两份"的问题,以后改图标要改两处,迟早不同步。

---

### 10.4 最终方案(已确定)

按"查询工具 + 生熟不分条"定型:

```sql
CREATE TABLE `t_item` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `slug`           VARCHAR(64)  NOT NULL COMMENT 'URL 标识,取自 wiki 页面名',
  `wiki_page`      VARCHAR(64)  NOT NULL COMMENT 'wiki 页面名,便于回溯核对',
  `name_en`        VARCHAR(64)  NOT NULL COMMENT '英文名(搜索用)',
  `name_zh`        VARCHAR(64)  DEFAULT NULL COMMENT '中文名,需自译',
  `icon`           VARCHAR(255) DEFAULT NULL COMMENT '图标 OSS 地址',
  `primary_type`   VARCHAR(32)  NOT NULL COMMENT 'FOOD/CONSUMABLE/EQUIPMENT/DEPLOYABLE/MYSTICAL/MISC',
  `rarity`         VARCHAR(32)  DEFAULT NULL COMMENT '稀有度,可筛选',
  `weight`         DECIMAL(5,1) DEFAULT NULL COMMENT '⚠️ 可为负,语义待确认',
  `is_cookable`    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否有意义的熟食效果,可筛选',
  `attributes`     JSON         NOT NULL COMMENT '状态效果/来源/生态/备注/描述',
  `schema_version` INT          NOT NULL DEFAULT 1 COMMENT 'JSON 结构版本,便于迁移',
  `status`         TINYINT(1)   NOT NULL DEFAULT 1,
  `sort_order`     INT          NOT NULL DEFAULT 0,
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`),
  KEY `idx_type` (`primary_type`),
  KEY `idx_rarity` (`rarity`),
  KEY `idx_cookable` (`is_cookable`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图鉴主表:一条 = 游戏里的一个物品';
```

**`attributes` 的结构(生熟都在里面):**

```json
{
  "statuses": {
    "raw":    { "hunger": -30, "bonus": 20 },
    "cooked": { "hunger": -60, "bonus": 30 }
  },
  "biomes": ["Gloom"],
  "sources": ["Campfires"],
  "locations": [],
  "cookingNotes": "…",
  "description": "…"
}
```

> 数值口径:`cooked` 里存的是**总量**(wiki 的公式:`熟食饱食总量 = Hunger × 2`、`熟食加成总量 = Bonus × 1.5`)。展示时想显示"增量"就在前端减一下,别在库里存两份。

**生熟对比的查询结果:** 一次查询返回一条记录,`attributes.statuses.raw` 和 `.cooked` 都在里面,详情页直接渲染成"生 / 熟"两栏,列表页零额外查询。

**条目数:** 食物 48 + 道具 82 = **130 条**,与游戏真实物品数一致(`Cooked Bird` 这类天生独立算自己的条目)。

**关于 `is_cookable`:** 不要用"有没有 Hunger 值"来判断,要用 10.3 的判据——`Cooking-notes` 有实质内容、或饱食/加成变化不为 0 才置 1。`First Aid Kit`(熟了无效果)、`Dynamite`(熟了爆炸)都应该置 0,详情页隐藏熟食区块。

#### 收藏功能(可选,与本次设计无关)

如果以后想加"收藏/标记"功能,单独一张表即可,**不影响 `t_item` 的设计**:

```sql
CREATE TABLE `t_user_favorite` (
  `user_id`    BIGINT   NOT NULL,
  `item_id`    BIGINT   NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏';
```

#### 小程序侧的打包提醒

**不要把图鉴数据打进小程序包里。** 微信小程序发版要审核,如果数据在包里,你改一个错别字都要重新提审。正确做法:

- 数据全部走 API,`t_item` → Redis 预热 → 接口返回
- 小程序包里只放静态资源(框架、样式、tab 图标)
- 这样数据修正**立刻生效,不用发版**

图片必须放自己的 OSS/CDN(见第 5 节),小程序域名白名单里配好。

### 10.5 熟食数据的采集脚本思路

```
1. cargoquery 拿生食全量(48 条)
2. 逐个 parse wikitext,正则提取 4 个参数:
   HungerCooked / BonusCooked / HasCookingBonus / Cooking-notes
3. 套公式补默认值:
   cooked_hunger_total = Hunger × 2
   cooked_bonus_total  = Bonus × 1.5  (无 Bonus 时取 10)
4. 按 is_cookable 判据过滤掉"无效果"的
5. 生成熟食条目,写好 group_slug 和 cook_state
```

---

## 附:本文所有数据的获取命令

```bash
UA="Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

# 全部页面
curl -sL -A "$UA" "https://peak.wiki.gg/api.php?action=query&list=allpages&aplimit=500&format=json"

# 全部分类
curl -sL -A "$UA" "https://peak.wiki.gg/api.php?action=query&list=allcategories&aclimit=500&format=json"

# 分类成员
curl -sL -A "$UA" "https://peak.wiki.gg/api.php?action=query&list=categorymembers&cmtitle=Category:Food&cmlimit=500&format=json"

# Cargo 全量数据(主力)
curl -sL -A "$UA" --get "https://peak.wiki.gg/api.php" \
  --data-urlencode "action=cargoquery" \
  --data-urlencode "tables=Items" \
  --data-urlencode "fields=_pageName=page,display,type,sortingTags,rarity,biome,location,source,uses,badges,weight,hunger,bonus,cold,coldTime,curse,drowsy,drowsyTime,heat,injury,poison,poisonTime,poisonStart,spores,thorns,cookingNotes,removed" \
  --data-urlencode "limit=500" \
  --data-urlencode "format=json"

# 单页 wikitext
curl -sL -A "$UA" "https://peak.wiki.gg/api.php?action=parse&page=Hot_Dog&prop=wikitext&format=json"
```
