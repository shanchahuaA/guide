# AFK 子代理派发模板

给「自动派发子代理去实现一张票」用的公共规则。**派发时不要再重抄这一段** ——
prompt 里只写「先读本文件，然后做 #NN」，其余按本文件执行。

这么做是为了让子代理的请求前缀保持稳定：前缀越长、每次都变，首次请求的 cache write 就越多。

---

## 一、总规则（等同 `/implement` skill 的内容）

1. 只按票面实现。**票是唯一规格来源**，不要顺手做票外的事，不要扩大范围。
2. 能在清楚接缝上做 TDD 就做。
3. 经常跑编译/类型检查，经常跑单测，最后跑一次全量测试。
4. 做完用 Skill 工具调 `code-review` 评审自己的工作。
5. 改动提交到当前分支。
6. 收口：`gh issue close <NN> --comment "<中文评论>"`。评论里写改了哪些文件、
   验收标准逐条怎么过的、哪条未验证、提交 SHA。**不要新建 issue、不要评论别的 issue。**

## 二、这个仓库的硬约束

- 文档与提交信息一律**中文**。
- **`scripts/smoke-test.ps1` 必须保持带 BOM 的 UTF-8**。PowerShell 5.1 读无 BOM 的 UTF-8
  会按 ANSI 解码，中文变乱码后脚本直接语法错误。改完自验前三个字节是 `EF BB BF`
  （`head -c 3 scripts/smoke-test.ps1 | xxd`）。
- 小程序有版本碎片问题：接口响应用固定 DTO 组装，不要让 JSON 列原样透传。
- 表结构的唯一真相是 `pojo/*.java`，**仓库里没有建表 SQL**，不要写 DDL、不要改表结构。
- MyBatis-Plus 的 `LambdaQueryWrapper` 要表达 `(A OR B)` 时必须 `.and(w -> w....or()...)`
  包起来；直接在 wrapper 上 `.or()` 会打穿整个 WHERE。
- 真实密钥（appid / secret / API Key / 数据库密码）**绝不进仓库**，放已 gitignore 的
  `application-local.yml`，仓库里只给空占位。
- 不要防御性设计，不要写没有调用方的凑数方法。三行相似代码好过一个过早的抽象。
- 默认不写注释；只在「为什么」不明显时写一行。

## 三、验证口径

**需要用户本人手工执行的验证一律跳过**，在报告里标「未验证」+ 原因。
包括：开微信开发者工具、手勾「不校验合法域名」、真机扫码。

能自动做的一定要做：`cd guide && mvn -DskipTests compile`、`cd guide && mvn test`。
冒烟脚本要后端在 `localhost:8080` 且连着 MySQL/Redis；跑不起来就标「未验证」，
**不要为了跑通去改数据源或配置**。

外部依赖调不通时（`code2Session` 要真调微信、DeepSeek 要真 Key），
**如实标「未验证」并说明卡在哪，绝不伪造成功**。

## 四、git 纪律

工作区里常驻**大量与本票无关的未提交改动**（`CLAUDE.md`、`CONTEXT.md`、`docs/spec.md`、
`guide-mini/` 下多个页面等）。那是用户的工作，**一个字都别动**。

- 只 `git add <你实际改动的文件的明确路径>`。**绝对不要** `git add -A` / `git add .` / `git commit -a`。
- 提交前 `git status` 复核暂存区里只有自己的文件。
- 提交信息用中文，正文含 `refs #NN`。
- **不要 push。不要 rebase / checkout / reset / stash 任何东西。**
- 撞上 `index.lock`（可能另一个代理在提交）等几秒重试。

## 五、报告格式（中文，简洁）

1. 改了哪些文件、各自用途；
2. 每个新增/修改的类与方法的作用（逐条）；
3. 验收标准逐条对照：通过 / 未通过 / 未验证（说明原因）；
4. 实际跑过的命令与结果；
5. 提交的 SHA 与提交信息；
6. issue 是否已关闭。
