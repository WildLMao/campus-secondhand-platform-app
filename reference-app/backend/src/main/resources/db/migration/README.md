# 数据库迁移脚本

执行者：`cn.campus.SchemaMigrator`。应用启动时按版本号升序执行尚未应用的脚本，并把
`version / description / checksum / applied_at` 写入 `schema_migrations`。

## 规则

1. 文件名固定为 `V{整数}__{描述}.sql`，例如 `V3__add_statistics_index.sql`；版本号不得重复。
2. **已执行的脚本不能修改**。启动时校验 SHA-256，内容被改动会直接失败并提示下一个版本号。
   结构变化一律新增脚本。
3. 空库初始化与后续升级共用本目录；启动流程不依赖 `CREATE TABLE IF NOT EXISTS` 猜测结构，
   V1 中的 `IF NOT EXISTS` 仅用于对已存在的旧库重复执行时不报错。
4. 脚本按 `;` 拆分语句，支持 `--`、`#`、`/* */` 注释；不支持存储过程、`DELIMITER` 与触发器。
5. MySQL 的 DDL 不能回滚：单个脚本中途失败时，`schema_migrations` 不会记录该版本，
   需人工核对已执行的语句后再重跑。编写脚本时优先使用可重复执行的写法。
6. 时间列统一保存 UTC 的 ISO-8601 字符串（`Instant.toString()`，`Z` 结尾，`VARCHAR(35)`），
   不使用数据库默认时区；金额统一 `DECIMAL(12,2)`。新增时间列沿用同一口径，并为区间查询加索引。
7. 索引名在整个 schema 内唯一，便于后续变更与排查。
8. **即使只改注释也不能修改已执行脚本**（SHA-256 校验和会变，启动将拒绝）。V1/V2 中的历史说明文字
   保持原样，新的说明写在本文件或新版本脚本中。

## 当前顺序

| 版本 | 内容 |
| --- | --- |
| V1 | 空库基线：账户、认证、物品与版本、订单、占用、交付、回执、收藏、评价、争议、举报、会话、审计、通知 |
| V2 | 查询索引：公开列表、本人订单、占用互斥、治理与统计区间 |

执行结果可用 `GET /api/health` 的 `schema` 字段或 `select * from schema_migrations` 查看。
MySQL 环境准备与真实库验证步骤见 [`docs/database/mysql.md`](../../../../../../docs/database/mysql.md)。
