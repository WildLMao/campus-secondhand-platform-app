# DSH 本地修复收尾 · 后端（2026-11-05）

范围：仅 `reference-app/backend` 与本文件。未访问 GitHub，未改 V1/V2 迁移，未读取任何密钥；真实 MySQL 与浏览器复核由主审完成。

## 1. 产品代码改动

| 文件 | 改动 |
| --- | --- |
| `NotificationDelivery.java` | 删除测试专用字段 `volatile corruptDelivery` 与 `delete from notifications where 1=0 ...` 故障 SQL；删除 `MAX_ATTEMPTS=50` 永久丢弃。生产只做：登记 PENDING → 提交后 REQUIRES_NEW 投递 → 失败记 FAILED（`attempts+1`、`last_error`）→ 补投。`deliverPending()` 每批 20 条，FAILED 按 `attempts,created_at,id` 升序轮转重试，再投一批 PENDING；无次数上限，不永久丢弃；`deliverOne` 对 SENT 直接返回，已 SENT 绝不重复投递。 |
| `MarketService.java` | 无静态测试钩子（本轮确认不存在 `beforeDisputeLock` 之类字段；`notifications()` 仅用于读取通知补投，注释改为“供只读访问（通知补投）”）。`supplement()` 锁后 `SELECT ... FOR UPDATE` 保留。“补证记录”抽为 `evidenceLog(did)` + `withEvidenceLog(d)`：`disputes(uid)` 与 `dashboard()` 的 `disputes` 共用，管理台每条争议同样返回 `evidence_log: [{id,author_id,content,created_at}]`，仅管理员可读（`dashboard` 内 `admin(uid)`）。事件键注释改为准确描述：`report-result:`/`relist:`/`restrict:` 用**本次处置生成的 factId**（每次都不同，同一次投递重试复用）；`dispute-closed:` 用 **disputeId**（结案有 `status='处理中'` 条件更新防重，双方各一条，键为 `dispute-closed:<did>:<uid>`）。 |
| `AdminController.java` | 统计参数注释改为准确表述：参数以 `String` 接收，不触发 Spring 参数类型转换/框架时区换算；由 `MarketService.statistics()` 用 `Instant.parse` 解析为时刻并按 `[from,to)` 比较。未声称任何框架时区行为。 |

真实 Spring 事务、事务外补投（`afterCommit`）、锁顺序、业务断言均未改动。

## 2. 测试层改动（仅 test scope）

- 新增 `src/test/java/cn/campus/TestStoreSpy.java`：基于真实 `Store` 的 `@SpyBean`（`BackendIssueRegressionTest.dbSpy`）做两件事，默认全部 `callRealMethod()`：
  1. `failNextNotificationInserts(n)`：只让 `insert into notifications` 抛 `DataAccessResourceFailureException`，其余 SQL 真实；
  2. `pauseAfterDisputeHintRead(did, arrived, release)`：指定争议的**锁前普通读取**返回真实结果后挂起，直到测试放行。
- `notificationFailureMustNotRollBackOrder`：故障窗口内真实 `createOrder()` 提交订单/占用/回执，通知记 FAILED（断言 `last_error` 与 `attempts`）；恢复后补投恰好一次，订单不重建。
- 新增 `failedNotificationRetriesRotateByAttemptsWithoutPermanentDrop`：45 条记录连续故障，3 轮后 40 条已被再次重试、5 条待覆盖（证明按 attempts 轮转而非只反复重试前 20 条）；同时把一条改到 `attempts=60`，恢复后仍被补投（无 50 次上限）。
- `supplementAfterClosureIsRejectedAndRaceDoesNotCrossBoundary`：改用测试层闸门（不再用测试连接手动锁行）→ 补证锁前读取后挂起 → 管理员真实结案提交 → 放行 → 断言补证被拒绝、无新增证据、争议保持已结案；并断言闸门确实命中。
- `disputeClosureNotifiesBothPartiesOnceWithOutcomeAndReference`、`repeatedReportAndRelistDispositionsDoNotCollide`：通知故障改走测试层 spy；并新增“同一事件故障后恢复只补投一次”“第三次 relist/ report 各一条 fact 记录”断言。
- 新增 `dashboardDisputesIncludeEvidenceLogForAdminsOnly`：管理台每条争议带 `evidence_log`，字段恰为 `{id,author_id,content,created_at}`，与当事人列表一致；原 `evidence` 不被改写；非管理员 403。
- 原断言未删弱；`@Order` 已重排为 1–21 的唯一顺序。
- `RootMySqlReviewIT`（真实 `RENAME notifications`）未修改。

## 3. 实际执行

```
JAVA_HOME=D:\Java\jdk17  mvn -o -DargLine=-javaagent:...\byte-buddy-agent-1.17.8.jar test
```

结果：**Tests run: 42, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS
（`BackendIssueRegressionTest` 22、`MarketIntegrationTest` 13、`SchemaMigratorTest` 4、`StartupTest` 3）。
`MarketMySqlIT`、`RootMySqlReviewIT` 无 `IT_DB_URL` 环境变量，Surefire 按默认不收集 `*IT`，本轮未执行；日志中 `Application run failed` 为 `StartupTest` 故意触发的启动失败用例。

## 3.1 追加（主审首次真实 MySQL IT 反馈）

- `MarketMySqlIT.closedLoopReplayAndStatisticsOnMySql`：confirm 夹具由 `p("reason","")` 改为显式本人确认
  `p("reason","本人已当面收到物品","declared",true)`。服务端 `declared` 校验未改、未削弱；已 `mvn -o -DskipTests test-compile` 编译通过（BUILD SUCCESS）。
- 契约最小同步（只补与实际实现不一致/已缺失的条目，未改无关内容）：
  - `README.md`：幂等行补充发布/修改可选 `Idempotency-Key` 语义；新增 `GET /api/orders/{id}/details`、
    `DELETE`/`PUT /api/favorites/{id}`、`GET /api/admin/orders/{id}/details`；`GET /api/disputes` 与管理台 `disputes` 的 `evidence_log`、
    管理台 `facts`、`GET /api/notifications` 的补投触发说明。
  - `openapi.yaml`：新增 `IdempotencyKeyOptional`（POST/PUT items）；`/api/favorites/{id}` 由“切换”改为 POST=收藏、DELETE=取消、
    PUT=`{favorite}` 幂等设置，统一返回 `FavoriteResult`；新增 `/api/orders/{id}/details`、`/api/admin/orders/{id}/details`
    与 `OrderDetails`；`Dispute.evidence_log`（`DisputeEvidence`）；dashboard `disputes` 附 `title/stage`、新增 `facts`；
    `/api/notifications` 补投说明。YAML 解析校验通过（30 个 path）。
- 本轮未再整套运行 H2（仅夹具/文档修正）；未读取任何凭据，未改 `RootMySqlReviewIT`、V1/V2。

## 3.2 追加（主审真实 MySQL：GET /api/notifications 不补投）

- **根因**：`CommunityController.notifications()` 直接 `service.notices.deliverPending()`。注入的 `MarketService` 是 Spring 代理，
  代理壳字段为 null，`NullPointerException` 被 `catch (RuntimeException)` 吞掉，导致 GET 永远不补投。
- **修复（仅 CommunityController）**：构造注入 `NotificationDelivery notices`，改为 `notices.deliverPending()`（不再经 MarketService 字段）。
  `MarketService.notifications()` getter 保留（经代理调用会转发到目标对象），注释写明“不得经代理直接读字段”。
- **同类检查**：控制器/SessionSupport/SeedData/AdminBootstrap 中所有 `service.xxx` 均为方法调用（经代理转发，安全），
  唯一的字段直读就是上述一处；其他组件访问的是各自构造注入的 `db`，不存在代理壳问题。
- **测试**（`BackendIssueRegressionTest.notificationEndpointDeliversPendingThroughRealControllerOnProxiedService`）：
  断言注入对象是代理，然后走真实 Controller：`GET /api/notifications` 补投 PENDING；故障期间下单成功且通知转 FAILED；
  之后的 GET 补投遗留 PENDING 与订单通知、已 SENT 不重复。不直接调用 getter。

## 3.3 追加（长处置说明导致结案通知丢失订单/争议标识）

- **根因**：`manage(dispute)` 通知原为“标题→结论→说明(reason 最多 1500 字)→订单/争议 id”，
  `NotificationDelivery.enqueue` 把 content 截到 1000 字，长说明时 id 被截掉。
- **修复**：通知改为“标题→结论→订单完整 id（短号）→争议完整 id（短号）→说明（摘要，前 200 字，超出以 … 结尾）→
  指向『我的订单』该订单的履约争议查看全文”。标识与结论位于截断点之前，任何长度下都保留。
  **完整原因未删除**：仍保存于 `disputes.decision`（1500 字）与 `audit_facts.reason`；订单 `orders.reason` 仍是 1000 字摘要（未改）。
- **测试**：新增 `longDecisionNotificationKeepsOrderAndDisputeReferenceAfterTruncation`：1500 字原因结案后，
  买卖双方通知内容均含完整 oid/did 与短号、结论、摘要起点、查看入口，且标识在说明之前；同时断言 decision 与处置事实保留全文。
- **本轮执行**：只编译并按方法名运行 4 个相关用例（上述两例 + `disputeClosure…` + `hundredFiftyCharacterDisputeDecisionIsSaved`），
  **4/4 通过**；未整套重跑、未跑 MySQL IT。

## 4. 遗留 / 给主审

- 通知补投目前由“提交后”与 `GET /api/notifications` 触发；若长期无请求，FAILED 记录会等待下一次触发（课程规模，未加定时任务）。
- 管理台 `disputes` 仍是全站队列，仅管理员可读；`evidence_log` 内容为当事人提交的原文（与当事人视图一致），未做额外脱敏。
- 真实 MySQL 下 `attempts` 轮转、`RENAME notifications` 恢复补投、管理台展示仍需主审独立复核。
