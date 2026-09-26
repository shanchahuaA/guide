# guide

一款轻量级微信小程序，你随身的游戏宝典

## 项目结构

- `guide/` —— Spring Boot 后端
  - `guide/src/main/java/org/example/guide` —— 后端代码
  - `guide/src/main/resources/application.properties` —— 配置文件
  - `guide/src/test/java` —— 测试代码
- `guide-mini/` —— 微信小程序前端
- `scripts/` —— 验收脚本（`smoke-test.ps1`：对运行中的后端发真实请求的 HTTP 冒烟脚本，用法见 `scripts/README.md`）
- `docs/` —— 团队文档（入职指南、分支模型等）

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
