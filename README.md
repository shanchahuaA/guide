# guide

一款轻量级微信小程序，你随身的游戏宝典

## 项目结构

- `guide/` —— Spring Boot 后端（**只读库对外服务**，不含采集）
  - `guide/src/main/java/org/example/guide` —— 后端代码
  - `guide/src/main/resources/application.yml` —— 配置文件
  - `guide/src/test/java` —— 测试代码
  - `guide/tools/` —— **采集脚本（Python）**，见下节
  - `guide/icons/` —— 图鉴图标（随仓库发布，采集按同样的文件名覆盖）
- `guide-mini/` —— 微信小程序前端
- `scripts/` —— 验收脚本（`smoke-test.ps1`：对运行中的后端发真实请求的 HTTP 冒烟脚本，用法见 `scripts/README.md`）
- `docs/` —— 团队文档（入职指南、分支模型、接口契约等）

## 采集数据

图鉴数据来自 PEAK Wiki，采集是 `guide/tools/` 下的 **Python 脚本**，跑在应用**外面** ——
应用不提供任何采集入口，只负责读库对外服务（理由见 `docs/adr/0001-采集搬出应用进程.md`）。

```bash
# 一条命令：拉取 → 页面源文 → 图标本地化 → 按 nameEn upsert 落库 → 删缓存 key
cd guide && python tools/crawl_items.py
```

**重跑就是再跑一次**：按 `nameEn` upsert，重复跑只更新不新增；跑完自动删掉图鉴 / 问答 /
题库三个缓存 key，不用重启后端。

外部连接走环境变量（都有默认值）：`GUIDE_DB_*`（MySQL，默认 `127.0.0.1:3306/root/peak_guide`）、
`GUIDE_REDIS_*`（默认 `127.0.0.1:6379`）、`GUIDE_ICON_DIR`（图标目录，默认 `guide/icons`）。
依赖两个第三方包：`pip install pymysql mwparserfromhell`（前者写库，后者解析页面源文）。

只看差异、不写库也不下图（与金标准快照逐字段比对）：

```bash
cd guide && python tools/crawl_items.py --check
```

## 分支说明

| 分支 | 用途 |
| --- | --- |
| `main` | 稳定版本，只放能跑通的代码 |
| `dev` | 集成分支，所有人的成果先汇总到这里 |
| 个人分支 | 每人一条，命名用拼音，例如 `laowang` |

## 协作约定

1. 推送之前，先拉取（`Ctrl+T`）
2. 永远不要使用 Force push
3. 只修改自己负责的文件
4. 从 `dev` 建个人分支，完成后合并回 `dev`
