# 本轮只读取得的Issue输入
基线：36bf02ae9d2e3d9734cb3a46e0c131338a081b3d

## #1 [DEV-D-001][P1] 发布接口未识别同次意图，响应丢失后重试会生成重复物品
<!-- requirements-review:2026-11-01:DEV-D-001:4deb366 -->

**编号：DEV-D-001｜优先级：P1｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

UC04 明确“同一提交意图重试只产生一次业务效果”；BR10 要求按操作者、对象、操作及意图区分重试和新操作。见 [UC04 重试约束](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L156)、[BR10](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L389)。

### 复现与实际结果（已执行）

在独立 H2 内存测试库通过 MockMvc 执行真实 Controller/Service：

1. 使用已认证卖方会话、有效 CSRF、本人已上传图片。
2. 向 `POST /api/items` 提交有效 JSON，保存响应；将此步视为服务端成功但客户端未收到响应。
3. 原样重发请求，身份、请求体和 `Idempotency-Key` 完全相同。

两次均返回 200，得到两个不同物品：

```text
first  = 41572edf-bd0a-4cf6-ab9d-a8cfcbe6cfb5
second = 6a44b707-ba06-4842-82b2-24bb3065886e
same Idempotency-Key = 79fa039f-d419-4161-9d40-478e3b0e65db
```

相同请求会分别生成待审物品。修改接口也没有提交意图，重试会再次增加版本。

### 原因定位

[publish/edit 接口](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/ApiController.java#L78) 未读取操作标识；[saveItem](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L183) 对新发布始终创建 UUID，对修改始终递增版本。订单的 `replay/receipt` 机制没有覆盖发布。

### 建议与验收条件

- 发布和修改携带持久的提交意图，服务端校验身份、内容与归属后返回原结果。
- 同意图顺序/并发重试，分别只产生一件物品或一次新版本；相同标识修改关键内容应拒绝。
- 明确新发布意图仍可正常创建，不能按相同标题/正文粗暴去重。

独立测试：`RequirementReviewTest.repeatedPublishMustReturnOneBusinessEffect`。本轮以“应返回同一物品”为断言，实际失败。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #2 [DEV-D-002][P1] 争议补证在加锁前读取旧内容，会丢失并发证据并越过结案边界
<!-- requirements-review:2026-11-01:DEV-D-002:4deb366 -->

**编号：DEV-D-002｜优先级：P1｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

FR24、UC10-b、BR16 要求补证可核查、历史依据保留；当前实现也明确规定已结案争议的后续反馈改走举报。见 [UC10-b 核查补证](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L218)、[BR16 历史保留](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L401)。

### 两个反例（均已执行）

使用真实 Spring 事务和两个工作线程，在独立 H2 内存库用测试 spy 只协调首个读取时点，不改查询结果或业务实现。

**反例 A：双方同时补证。**

1. 建立有效订单和处理中争议，原证据 `BASE`。
2. 让买方、卖方都执行完 `supplement()` 最开始的争议读取，再释放它们继续争抢订单锁。
3. 双方分别补 `EVIDENCE_BUYER_A`、`EVIDENCE_SELLER_B`，两个调用均成功。

最终只剩 `BASE + EVIDENCE_SELLER_B`，买方已成功提交的补证丢失。哪个补证保留下来取决于写入先后；缺陷是另一个被覆盖。

**反例 B：读取后管理员结案。**

1. 补证线程完成初次争议读取后暂停。
2. 管理员通过现有 `manage(dispute, continue)` 正常结案。
3. 放行补证线程。

补证调用没有被拒绝，已结案记录又追加 `AFTER_CLOSURE`。这绕过了实现本身的“已结案请举报”限制，且新增内容未经过原结案决定的核查。

### 原因定位

[supplement()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L425) 在 `lockOrder()` 之前读取 `d`，加锁后仍用旧 `status`、旧 `evidence` 判断和拼接，没有重新读取争议行。串行持有订单锁没有保护锁前已读取的快照。

### 建议与验收条件

- 按统一锁顺序获取订单/争议保护后，重新读取最新状态及证据；或改为带作者、时间的追加证据记录。
- 两个成功补证都必须保留；与结案竞争时，以实际生效顺序检查阶段。
- 结案后新事实仍应有正式反馈入口，但不能悄悄改写已经据以结案的证据集合。

独立测试：`simultaneousSupplementsMustPreserveBoth`、`supplementMustRecheckClosedDisputeAfterLock`；两条需求断言本轮均失败。并发时点由测试设施强制协调，不冒充公网并发验证。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #3 [DEV-D-003][P1] 站内通知写入与交易同事务，通知异常会撤销订单及占用
<!-- requirements-review:2026-11-01:DEV-D-003:4deb366 -->

**编号：DEV-D-003｜优先级：P1｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

1.5、2.6 及 AC-D-12 明确：通知失败不撤销已经成立的业务结果；用户可查询原业务记录，通知重试不重新执行业务。见 [1.5 通知边界](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L49)、[2.6 通知失败处理](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L252)。

### 复现与实际结果（已执行）

1. 独立 H2 内存库中准备可售物品和合资格买方。
2. 使用 Spring 测试 spy，仅让 `Store.exec()` 的 `insert into notifications...` 抛出受控异常；其他账户、物品、订单、占用、回执 SQL 保持原样。
3. 调用真实 `createOrder()`。

```text
notification_fault = IllegalStateException
orders for item = 0
reservations for item = 0
```

订单、占用、回执的写入在通知前已经执行，但通知异常使整个事务回滚；业务成功取决于通知模块是否正常。

### 原因定位

[createOrder() 的事务](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L262) 在最后调用 [notify()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L286)；通知直接同步写库，没有把通知故障与业务提交解耦。其他调用该方法的事务也需要检查。

### 建议与验收条件

- 明确业务提交与通知投递的边界，可采用可靠事件/待发送记录及提交后处理等简单适用方案。
- 不能仅在原事务里无条件吞掉所有数据库异常，掩盖真实业务提交失败。
- 对通知模块单独注入失败，交易应能按约定提交并查询；恢复通知不新建订单、不重复成交。

独立测试：`notificationFailureMustNotRollBackOrder`。这是服务层通知写入故障注入，不是短信/邮件服务实测，也不是声称已返回成功的订单随后被删除。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #4 [DEV-D-004][P1] 退出或切换账户未清理认证表单草稿，后续用户可能看到前一人的姓名学号
<!-- requirements-review:2026-11-01:DEV-D-004:4deb366 -->

**编号：DEV-D-004｜优先级：P1｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

BR14 和个人身份材料的访问约束要求：真实姓名、学号、认证材料只在本人及必要审核范围使用。FR23 退出后不应继续以旧身份保留可见的私有操作数据。见 [BR14](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L397)。

### 复现与实际结果（函数级已执行）

1. 用户甲在个人中心填写姓名、学号并上传认证材料，暂不提交。
2. 退出登录，保持当前 SPA 页面，不整页刷新。
3. 另一未认证账户登录并进入个人中心，认证表单仍绑定原 `verifyForm`。

直接提取并执行当前源码的 `logout()`，仅使用虚构测试数据和桩 API：

```text
loggedOut = true
verificationDraftRetained = true
real_name  = SYNTHETIC_PRIVATE_NAME
student_no = SYNTHETIC_PRIVATE_STUDENT_NO
asset_id   = SYNTHETIC_PRIVATE_ASSET
```

原材料文件仍可能被后端权限拒绝，但文本草稿已经留在前端；后端图片权限不能替代前端清理。

### 原因定位

[logout()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/frontend/src/App.vue#L38) 清理列表但没有重置 `verifyForm`、`itemForm` 等表单；[clearPrivate()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/frontend/src/App.vue#L20) 的账户变化路径同样漏掉草稿。认证页面继续绑定 [verifyForm](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/frontend/src/App.vue#L89)。

### 建议与验收条件

- 统一清理私有列表、表单草稿、对话内容及其材料标识，覆盖主动退出、会话失效、检测到账户变化的入口。
- 不重新加载页面完成甲→退出→乙的浏览器回归，乙看不到甲的姓名、学号、材料标识和私有草稿。
- 本条已证明退出函数保留草稿；完整浏览器换号场景仍需修复后验证，不能记为本轮已做整页走查。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #5 [DEV-D-005][P2] 未决操作只保存一个重试标识，处理其他订单会覆盖原意图
<!-- requirements-review:2026-11-01:DEV-D-005:4deb366 -->

**编号：DEV-D-005｜优先级：P2｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

UC05、2.5 和 BR10 要求响应丢失/结果未知时保留原意图并提供查询；重试不能被当作新的下单。见 [UC05 未决结果](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L170)、[2.5 交互要求](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L236)。

### 复现与实际结果（函数级已执行）

提取 [command()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/frontend/src/App.vue#L41) 原函数，桩化传输和 `sessionStorage`，不改函数逻辑：

1. 下单 A，使用标识 K1；桩 API 抛出“响应未知”。
2. 对其他订单 B 执行接单；得到成功响应。
3. 继续重试原来相同路径、相同内容的下单 A。

```text
A original key = ddfc90d6-834b-4de1-b173-0f6ba79c78c7
A retry key    = 44c2410c-dcd9-4b5d-862e-0c30970d9553
preserved      = false
```

### 原因及影响

每个账户只有一个 `campus:last:<userId>` 槽位。B 的 fingerprint 与 A 不同，会覆盖 A；B 成功后又删除该槽位。再次尝试 A 时只能新建标识。

若旧订单后来取消、物品重新可售，旧意图可能按新意图再次建单。本轮只实际证明原标识被覆盖，没有把“已产生第二笔订单”登记为端到端实测结果；普通订单列表也不能替代准确的意图关联。

### 建议与验收条件

- 按未决意图保存多个操作，或在明确查询并处理完未知结果前保护该意图；不要按一切相同请求永久复用标识。
- A 结果未知→正常处理 B→重试 A，仍携带 K1；同时提供明确的查询、继续原操作与新意图边界。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #6 [DEV-D-006][P2] 治理界面缺少关联订单和交付依据查询，管理员难以完成实质核查
<!-- requirements-review:2026-11-01:DEV-D-006:4deb366 -->

**编号：DEV-D-006｜优先级：P2｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

UC10-b 要求管理员联系双方并核查交付及补证；BR14 允许必要治理范围访问并留痕，BR19 要求有依据处置。本人订单还应能查看登记情况。见 [UC10-b](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L218)、[2.5 订单信息](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L230)。

### 源码确认的缺口

- [dashboard()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L516) 返回争议理由、证据、标题和阶段，但不提供关联订单快照、交付登记明细或与该案相关的沟通上下文；返回数据也没有 `orders`/`deliveries`。
- [orders()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L160) 只返回当前用户作为买卖双方的订单。管理员不会因为治理身份自动成为当事人，这一限制本身应保留。
- `deliveries.note/actor_id/created_at` 已入库，当前 API 没有给当事人或授权治理人员读取该登记明细的入口。
- [争议管理界面](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/frontend/src/App.vue#L94) 可以选择继续履约或异常终止，但未补上核查原业务依据的路径。

例如卖方登记“图书馆交付”，买方争议称未收到；管理员可以点击处置，但无法在产品内查看原登记时间和说明。依赖开发者直接查数据库不能算完整的业务能力。

### 建议与验收条件

- 从具体举报/争议进入与该案关联的订单、成立时快照、交付记录及必要证据上下文。
- 继续实行对象范围和查看留痕；本条不要求给所有管理员任意浏览全部私人会话的权限。
- 当事人可读本人登记明细；非当事普通用户仍拒绝；管理员只在授权治理范围可读。
- 以“看得见核查依据→记录决定→双方查结果”走通一次真实前后端流程。

状态：已静态核对 Service、Controller、SQL 及 Vue 入口；本条没有冒充浏览器实测。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #7 [DEV-D-007][P2] 审计只记录管理动作和原因，缺少处置结果与审核版本，历史决定无法完整重建
<!-- requirements-review:2026-11-01:DEV-D-007:4deb366 -->

**编号：DEV-D-007｜优先级：P2｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

FR22、BR16 和 3.4 要求历史记录保存操作者、对象、时间、原因及结果；物品按版本审核，旧结论应可追溯。见 [处置记录职责](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L331)、[BR16](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L401)。

### 源码确认的缺口

[audit_logs 表](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/resources/schema.sql#L20) 只有 `actor_id/action/object_id/reason/created_at` 等字段，[manage() 统一审计](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L649) 仅调用：

```java
audit(uid, "管理:" + kind, obj, reason);
```

具体影响：

1. 限制后恢复同一账户，如果理由相同，两个历史动作都只叫 `管理:restrict`，没有记录当次是限制还是恢复。
2. 隐藏后恢复同理，都是 `管理:hide`。
3. 物品审核只在 `items` 保存最新 `status/reason`；再次修改会清空旧原因。`item_versions` 保存原内容，但无该版本审核通过/退回结果；日志又未记录审核版本和 pass 值。

因此“有日志”尚不能完整证明“谁对哪版做了什么决定”。当前状态只代表最后结果，不能替代历史。

### 建议与验收条件

- 记录对象类型、具体动作、结果/前后值；审核额外关联明确版本。
- 自由填写的原因保留，但不能依赖管理员每次手写结果和版本号。
- 连续执行限制→恢复、隐藏→解除、v1 退回→v2 通过，历史仍能独立还原各次结果和原因。
- 采用结构化日志或等价的追加处置记录即可，无需为课程项目引入复杂审计平台。

状态：源码和表结构已确认；未声称执行了本条的新增运行回归。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #8 [DEV-D-008][P2] 收藏接口以切换代替明确操作，相同请求重试会反向取消收藏
<!-- requirements-review:2026-11-01:DEV-D-008:4deb366 -->

**编号：DEV-D-008｜优先级：P2｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

UC03-a 区分“收藏”和“取消收藏”；BR10 要求同次意图不产生相反的新业务效果；AC-D-04 包含重复收藏。见 [UC03-a](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L130)、[BR10](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L389)。

### 复现与实际结果（已执行）

在独立 H2 内存库通过真实 MockMvc 接口：

1. 合资格用户对尚未收藏的可售物品调用 `POST /api/favorites/{id}`。
2. 按响应丢失后的原请求重试，路径、身份及 `Idempotency-Key` 相同。
3. 两次均成功；查数据库最终收藏关系数为 **0**。

第一次加入收藏，第二次却执行了删除。接口没有表达“本次意图是收藏还是取消”，只有 toggle。

### 原因定位

[收藏 API](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/ApiController.java#L127) 未读取操作意图；[favorite()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L357) 发现已有关系就删除。唯一主键只能保证不重复，无法保证重试语义正确。

### 建议与验收条件

- 分开加入/删除，或请求明确目标状态；重复加入保持已收藏，重复取消保持未收藏。
- 同时检查并发、响应丢失重试，以及受限账户和不可访问物品边界。
- UI 仍可显示单个收藏切换按钮，但请求须表达用户想达到的状态。

独立测试：`repeatedFavoriteMustRemainFavorite`；期望 1，实际 0。测试使用真实接口和内存库，不是前端模拟收藏表。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #9 [DEV-D-009][P2] 订单内评价查询已有后端接口，前端未提供查看入口且评价后仍持续显示提交按钮
<!-- requirements-review:2026-11-01:DEV-D-009:4deb366 -->

**编号：DEV-D-009｜优先级：P2｜审查版本：`4deb366154c9bfc6e682848a233cab22a065d56d`**

### 需求依据

FR16、UC08 和 BR11 要求双方各可评价一次，当事人能在本人订单查看该单评价；卖方公开评价不是订单私有评价的替代品。见 [UC08](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L134)、[BR11](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/requirements/documents/软件工程课程需求报告.md#L391)。

### 源码确认的缺口

- 后端已有 [GET /orders/{{id}}/reviews](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/ApiController.java#L138)，由 [orderReviews()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/backend/src/main/java/cn/campus/MarketService.java#L401) 校验当事人后返回本单评价。
- 当前 `App.vue` 未调用该 GET 接口，也不渲染订单评价列表。
- [viewReviews()](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/frontend/src/App.vue#L61) 只读取 `/sellers/{id}/reviews`，只覆盖卖方信息区的公开评价。
- [订单界面](https://github.com/WildLMao/campus-secondhand-platform-app/blob/4deb366154c9bfc6e682848a233cab22a065d56d/reference-app/frontend/src/App.vue#L87) 对所有已完成订单始终展示“评价交易”，未按本人是否已评调整；重复点击提交只会走到数据库唯一约束冲突。

结果是双方已提交的订单评价缺少查询入口，卖方对买方的评价也不能靠“卖方公开评价”展示补齐。

### 建议与验收条件

- 在本人订单读取并展示该单评价，保留公开评价与当事人查询的访问范围区别。
- 本人已评后显示已评价及查看入口，避免继续引导重复提交；后端唯一约束继续保留。
- 完成一笔交易，双方各评一次，均能在本人订单查看；第三人查询拒绝，治理隐藏的评价不向公开卖方区域泄漏。

状态：已静态核对调用和模板；本轮未执行浏览器评价流程。

本条来自 2026-11-01 的需求对照审查；未修改产品代码，不指定负责人。原有 10 项 H2 集成测试本轮通过，但不涵盖本条全部反例。本条不代表真实 MySQL、公网或浏览器整页验收结论。


## #10 [P2] 争议继续履约结案后，买卖双方收不到结果通知
# [P2] 争议继续履约结案后，买卖双方收不到结果通知

管理员已经处理完争议，但双方的消息页没有结案通知，用户只能主动打开订单查找结果。

## 复现步骤

1. 买方对羽毛球拍下单（本次订单 65e143ff），在我的订单申请争议。
2. 管理员进入管理台 → 履约争议 → 核查结案，选择“继续履约，保留原阶段”，填写原因后提交。
3. 页面显示“已结案 · 待卖方确认”。
4. 分别登录买卖双方，打开消息并刷新。

## 实际结果与预期结果

买方只有之前支架订单的待交付、待买方确认通知；卖方只有新订单及支架已完成通知。双方均没有球拍争议结案通知。

预期双方各收到一条结案通知，包含订单、结论及查询入口。需求报告 2.6 要求处置结果成立后通知有权接收者。

## 修改建议

MarketService.java 的 manage(dispute) 在 continue 路径更新状态后未调用 notify。应明确向订单双方发送结案通知，按争议结案事件去重，并让通知失败不影响结案结果。

## 验收条件

继续履约后双方各收到一条通知，第三人收不到；通知结论与订单一致；重复请求不重复发送。


## #11 [P2] 成交统计对同一开始时刻返回不同结果
# [P2] 成交统计对同一开始时刻返回不同结果

同一开始时间，仅改变小数秒或时区写法，统计从 1 单、32 元变成 0 单。

## 复现步骤

环境：36bf02a，MySQL 8.0.21。

1. 正常完成一笔 32 元订单。本次订单 48dbd841-6e13-4e40-bd88-1ac5420bb90f，完成时间 2026-11-04T13:55:56.892773700Z。
2. 管理员登录后调用 GET /api/admin/stats，不传 to，依次使用以下 from 值（加号需 URL 编码为 %2B）。

| from | 实际成交数 | 实际金额 |
| --- | --- | --- |
| 2026-11-04T13:55:56Z | 0 | 0 |
| 2026-11-04T13:55:56.000Z | 1 | 32 |
| 2026-11-04T21:55:56+08:00 | 0 | 0 |

## 预期结果

三个参数表示同一时刻，均早于订单完成时间，应全部返回 1 单、32 元。

管理台填写本地开始时间 2026-11-04 21:55:56 时显示 1 单、32 元。前端统一转换为毫秒格式，所以页面这一结果正常，但接口其他合法写法仍会漏单。

## 修改建议

MarketService.statistics() 用 Instant.parse 校验格式，却仍将原始字符串传给 SQL。completed_at 为 VARCHAR，比较的是字符串顺序。

使用数据库时间类型和解析后的 UTC 参数，或把存量数据与参数统一为固定精度、固定 UTC 格式。迁移时明确小数秒精度，保留左闭右开语义。

## 验收条件

同一时刻的 Z、.000Z、+08:00 写法结果一致；等于开始时计入，等于截止时排除；不同小数秒精度均正确；金额按订单快照汇总。