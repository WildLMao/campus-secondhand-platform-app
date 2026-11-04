# 接口契约（B 维护）

版本：2026-10-29，对应当前实现项目 `reference-app/`。机器可读版本见
[`openapi.yaml`](openapi.yaml)。B 维护本契约，C 按此核对前端消费方式，D 核对业务语义；
修改接口需在 PR 中说明受影响的 FR/UC/BR 与调用方。

## 1. 通用约定

| 项目 | 约定 |
| --- | --- |
| 前缀 | 全部业务接口在 `/api` 下；前端开发用 Vite 代理 `/api`，生产由同一后端提供静态页面 |
| 身份 | 基于服务端 Session（Cookie `JSESSIONID`，`HttpOnly`、`SameSite=Strict`，60 分钟空闲过期；HTTPS 交付时 `Secure`） |
| 初始化令牌 | `GET /api/me` 返回当前用户（未登录为 `null`）与 `csrf`；注册/登录成功后更换会话标识并重新签发 `csrf` |
| CSRF | 所有非 `GET/HEAD/OPTIONS` 的 `/api/**` 请求须带 `X-CSRF-Token: <csrf>`，缺失或不符返回 403 |
| 幂等 | `POST /api/orders` 与 `POST /api/orders/{id}/{action}` 必须带 `Idempotency-Key`（`[A-Za-z0-9_-]{8,100}`）。同一操作者、同一标识只产生一次业务结果；重放返回原订单当前进度；同一标识改变操作或请求内容返回 400；不同操作者互不可见 |
| 响应 | 成功为 JSON（资产下载除外）；错误统一 `{"message":"中文原因"}` |
| 状态码 | 400 输入/业务条件不满足；401 未登录；403 越权或 CSRF 失败；404 对象不存在；409 唯一约束冲突或并发锁冲突（本次未生效）；500 未预期异常；503 数据库不可用（健康检查） |
| 金额 | JSON 数字，人民币元，两位小数；订单保存成立时快照，统计按快照求和 |
| 时间 | 字符串，UTC ISO-8601（以 `Z` 结尾），如 `2026-10-29T07:30:00.123Z`；统计筛选为左闭右开 `[from, to)` |
| 图片 | 先 `POST /api/assets` 上传得到 `asset_id`（UUID），再作为 `image_id` 用于物品或认证材料；读取需登录并按归属/可见性判断，未授权返回 403 |
| 分页 | `GET /api/items?page=1`，固定每页 20 条，`page<1` 按 1 处理；响应为数组，不返回总数 |
| 空值 | 未赋值的可选字段为 `null`（如订单 `completed_at`）；列表无数据返回 `[]` |
| 隐私 | 公开接口不含真实姓名、学号、认证材料、联系方式、私有订单与交接地点 |

## 2. 状态与枚举

- 物品状态：`待审`、`在售`、`审核退回`、`已撤下`、`交易中`、`已成交`、`交易异常终止`。
- 订单阶段：`待卖方确认`、`待交付`、`待买方确认`、`已完成`、`已取消`、`已拒绝`、`已异常终止`。
- 校园认证：`待审`、`通过`、`退回`；履约争议：`处理中`、`已结案`；举报：`待处理`、`已处理`。
- 物品分类：`教材书籍`、`数码配件`、`宿舍生活`、`运动户外`、`其他`。
- 隐藏、账户受限、争议标记与会话只读是独立标记，不改变上述生命周期状态。

## 3. 接口表

| 方法与路径 | 身份/归属 | 说明 |
| --- | --- | --- |
| `GET /api/health` | 公开 | 运行状态、数据库类型、已应用迁移版本 |
| `GET /api/me` | 公开 | 当前用户与 CSRF 令牌 |
| `POST /api/auth/register` | 公开（需 CSRF） | 注册；用户名 3—40 位字母数字下划线，密码 ≥8 位且 UTF-8 ≤72 字节 |
| `POST /api/auth/login` | 公开（需 CSRF） | 登录，更换会话标识 |
| `POST /api/auth/logout` | 登录（需 CSRF） | 退出并失效会话 |
| `GET /api/items` | 公开 | 关键词/分类检索，只返回符合 BR03 的在售物品 |
| `GET /api/items/mine` | 本人 | 本人发布的全部物品（含历史状态） |
| `POST /api/items` | 已认证、未受限 | 发布物品，进入 `待审` |
| `PUT /api/items/{id}` | 物主 | 关键内容修改，版本 +1 并重新审核；有有效订单时拒绝 |
| `POST /api/items/{id}/withdraw` | 物主 | 撤下为 `已撤下`；无有效订单才允许 |
| `GET /api/verifications` | 本人 | 本人认证申请历史（含退回原因） |
| `POST /api/verifications` | 本人、未受限 | 提交认证材料 |
| `GET /api/orders` | 本人 | 本人作为买方或卖方的订单 |
| `POST /api/orders` | 已认证、未受限 | 下单建立订单与占用（需 `Idempotency-Key`） |
| `POST /api/orders/{id}/{accept|reject|deliver|confirm|cancel|remind}` | 订单当事人 | 阶段操作（需 `Idempotency-Key`） |
| `GET /api/orders/{id}/reviews` | 当事人 | 该订单评价（含隐藏标记） |
| `POST /api/orders/{id}/reviews` | 完成订单当事人 | 评价一次（1—5 分） |
| `GET /api/favorites` | 本人 | 本人收藏 |
| `POST /api/favorites/{id}` | 已认证、未受限 | 切换收藏；不产生占用 |
| `GET /api/sellers/{id}/reviews` | 公开 | 卖方已公开评价 |
| `GET /api/disputes` | 当事人 | 本人订单相关的履约争议 |
| `POST /api/orders/{id}/disputes` | 未结束订单当事人 | 申请履约争议，保留原阶段与占用 |
| `POST /api/disputes/{id}/evidence` | 当事人 | 追加证据（争议未结案） |
| `GET /api/reports` | 本人 | 本人提交的举报 |
| `POST /api/reports` | 登录 | 举报物品/订单/账户 |
| `GET /api/messages?type=ITEM|ORDER&object={id}&peer={uid}` | 会话参与人 | 物品咨询（`ITEM`）或订单会话（`ORDER`）消息 |
| `POST /api/messages?type=...` | 会话参与人 | 发送消息；终态订单只读；受限账户仅可继续旧单会话 |
| `GET /api/inbox` | 本人 | 本人参与的会话入口 |
| `GET /api/notifications` | 本人 | 最近 100 条站内通知 |
| `GET /api/admin/dashboard` | 管理员 | 审核与治理列表（物品、认证、账户、争议、举报、评价、审计） |
| `GET /api/admin/stats?from=&to=` | 管理员 | 成交笔数/金额、发布数、举报数（BR18 口径） |
| `POST /api/admin/{kind}/{id}` | 管理员 | 治理操作，`kind` 见下 |
| `POST /api/assets` | 登录（`kind=ITEM` 需已认证未受限） | 上传图片，`multipart/form-data`，字段 `file`、`kind=ITEM|VERIFY` |
| `GET /api/assets/{id}` | 按可见性 | 下载图片，`no-store` |

### 管理操作 `POST /api/admin/{kind}/{id}`

统一必填 `reason`（≤1500 字）。各 `kind` 额外字段：

| kind | 额外字段 | 业务结果 |
| --- | --- | --- |
| `verification` | `pass` 布尔 | 通过则绑定校园身份并授予认证；退回保留原因 |
| `item` | `pass` 布尔、`version` 整数 | 仅审核当前版本；通过后仍受隐藏/撤下/成交/占用/账户限制约束 |
| `hide` | `hidden` 布尔 | 仅设置独立隐藏标记，不改变生命周期状态 |
| `restrict` | `restricted` 布尔 | 限制账户新业务，保留旧单权利；不能限制管理员 |
| `relist` | 无 | 仅对 `交易异常终止` 物品核实归属，允许卖方修改后重审 |
| `dispute` | `outcome=continue|terminate`，终止时 `evidence_confirmed=true` | 继续履约保留原阶段；异常终止释放占用、物品不可直接重售、不计成交 |
| `report` | `event_ref`（≤100 字） | 标记已处理并关联同事件核查，各举报保留独立回执 |
| `review` | `hidden` 布尔 | 隐藏或恢复公开评价，保留原记录 |

## 4. 关键业务约束（与实现一致）

1. 下单先校验身份与归属，再识别重复；仅新意图检查双方资格、物品可交易与占用。
2. 订单、快照与唯一占用同时成立；失败不留下孤立订单或残留占用；同一物品可有多笔历史订单。
3. 卖方登记交付后进入 `待买方确认`，只有该订单买方本人可确认；管理员不能代确认。
4. 普通取消限 `待卖方确认`/`待交付`、无交付登记、无处理中争议，且当事人声明未实际交付。
5. 争议受理暂停接单、拒单与交付登记，保留查询与补证；已登记时买方仍可本人确认完成。
6. 隐藏、账户限制与争议是独立标记；取消、解封、结案不会绕过其他条件恢复可售。
7. 受限账户保留旧单查询、允许的取消/交付/收货、旧成交评价与举报，但不能新发布、新下单、收藏或新咨询。

## 5. 变更与联调

- 后端改动接口时同步更新本文件与 `openapi.yaml`，并在交接中写明对 C 的影响。
- C 发现契约与页面不一致时，先按本文件核对请求字段与错误码，再与 B 确认；不自行在前端重定义业务规则。
- 数据库字段变化通过后端 DTO/字段名隔离，前端不依赖表结构。
