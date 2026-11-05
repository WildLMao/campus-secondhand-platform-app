# 前端修复交接（main 36bf02a，11 Issue 范围内）

范围：仅 `frontend/src`、`frontend/test`、本文件。未改 backend、脚本、锁文件、package.json；未安装依赖、未访问远端。

## 实际修改

- `src/api.js`：网络失败错误加 `transport=true`（与带 `status` 的 HTTP 响应区分；有 `status` 也不等于可释放 key，须经 `settleIfApplied` 判定）；新增 `setFavorite(id, favorite)` → `PUT /favorites/{id}` body `{favorite:true|false}`；新增 `orderDetails(id)` → `GET /orders/{id}/details`。
- `src/intent.js`（新，纯逻辑）：按 `(账户, 操作槽位)` 保存未决意图 key（sessionStorage 不可用时退化为内存）；`newKey()` 优先 `crypto.randomUUID`，无安全上下文时回退并保证满足后端 `[A-Za-z0-9_-]{8,100}`；`settle()` 与 `settleIfApplied()` 只在成功或可证明无业务效果时释放 key；5xx、408/409/429 与网络失败一律保留原 key。
- `src/iam.js`（新）：`enterAction`（Enter 发送 / Shift·Ctrl·Alt·Meta+Enter 换行 / `isComposing`、keyCode 229 不发）、`chatEditable`、`messageList`。
- `src/orders.js`（新）：`orderDetails` 形状归一 `{order,delivery,reviews}`；`reviewRows` 按 `author_id` 映射买卖双方名字；`canReview/alreadyReviewed/addReview` 本人已评判定（服务端列表 + 本地已评标记，不依赖仅详情缓存）；`actionsFor` 订单阶段按钮（卖方登记交付后无“完成”入口、本人不可自我确认）。
- `src/identity.js`（新）：与后端 `saveItem` 一致的前端预检与发布请求体。
- `src/session.js`（新）：`emptyPrivate()` 一次给出全部私有状态初值（认证名单/学号/材料标识 `asset_id`、物品表单图片标识、弹窗、聊天草稿、公共评价）；`accountChanged/staleFor` 判断换号与旧响应丢弃。
- `src/stats.js`（新）：统计时间只接受可解析的合法时间，统一 `toISOString()`（毫秒 UTC）后提交；开始不早于截止则不发请求。
- `src/App.vue`：脚本重写，模板只替换绑定名并新增订单详情块、待认证按钮、聊天只读提示与 Enter 处理；`src/style.css` 仅追加卡片截断/布局等 4 条规则。
- `src/evidence.js`（新，纯逻辑）：`evidenceLog/authorLabel/evidenceView`，把原始 `evidence` 与 `evidence_log` 分开、按时间编号，补证人只按可核实名字映射（未命中显示“用户·<编号前缀>”）。`n- 无新增引入，未新增多图、`page` 以外的分页接口、信誉等依赖。

## 逐条对应

- #4 退出/换号/会话失效：`applyUser` 在 userId 变化（含退出为 null）时调用 `resetPrivate()` 重置所有私有表单、认证材料标识、聊天与弹窗草稿并回到发现页；`safe` 在 401/403 后用 `/me` 重新确认，若期间身份已变则丢弃旧响应与旧错误提示（`staleFor`）。登录弹窗没有背景自动关闭（已删除原 `@click.self`），只能点明确“返回”按钮关闭，提交失败保留；头部“待认证”为按钮，已登录跳个人中心（`goVerify`）。
- #5/#1 未决意图：`idempotent()` 以 `order:{id}:{action}`、`buy:{itemId}`、`item:{id}:{内容}` 为槽位，多个槽位并存互不覆盖；同内容重试沿用原 key（含换页/重新打开后）；内容变化的未决槽位必须用户确认后才换新 key（不偷偷换 key 重复下单）；只有成功或可证明无业务效果的 4xx（400/401/403/404/413/415/422）释放 key，编辑可获新意图；5xx、408/409/429、网络未知均保留原 key，重试不换 key。发布（POST/PUT `/items`）均带 `Idempotency-Key`，前端预检与后端一致。
- #9 订单详情：每个订单“查看详情”调用 `GET /orders/{id}/details`，渲染交付登记人/时间/说明与双方评价（名字由 `author_id` 映射 `buyer_name/seller_name`）；本人已评（含隐藏评价）不再出现“评价交易”；后端 `GET /orders/{id}/reviews` 保留，且当 `/details` 返回 404/5xx 时对已完成订单退避到该接口。
- 聊天：`GET /messages/context` 按 `{messages, readOnly}` 解构；`readOnly` 只认服务端字段，终态不渲染发送框；每 5 秒刷新，阶段变终态后一次轮询即切换为只读；后端拒绝仍以错误提示保留草稿。Enter 发送、Shift+Enter 换行、中文输入法 Enter 不误发；发送中防重入。
- #8 收藏：请求表达目标状态（收藏 `true`、取消 `false`），重试同目标不会反向；自己发布的物品在列表中不显示下单/收藏/咨询入口。
- 确认收到：买方本人确认成功后按服务端返回订单更新并 `refresh()`，阶段显示已完成；卖方仅能“登记交付/提醒买方”，无改写完成入口。
- 商品列表：沿用后端 20 条分页（`items.length<20` 禁用下一页），卡片描述 CSS 三行截断，详情/订单保留全文，卡片等高。
- #10/#11：管理操作（含争议 continue 结案）成功后重新拉取 `/admin/dashboard` 并 `refresh()` 通知、争议与订单；统计请求只提交合法毫秒 UTC 时间；解析一致性仍由后端 #11 修复，前端未以此替代。

## API 依赖（不要求扩展不存在接口）

- 聊天读取用 `GET /messages/context`（后端已新增）→ `{messages, readOnly}`；`GET /messages` 仍返回纯数组，前端不再用它读取。只读只认服务端 `readOnly`。
- `GET /orders/{id}/details`（后端已新增）→ `{order, delivery{actor_id,actor_name,note,created_at}, reviews}`；`GET /orders/{id}/reviews` 保留作已完成订单的退避。
- `PUT /favorites/{id}` body `{favorite:Boolean}`（后端已新增 PUT）；`POST /items`、`PUT /items/{id}` 已读 `Idempotency-Key`。
- 仍依赖 `/me`、订单动作 `{reason, declared}`、`GET /items?page=`（后端 20 条）不变。说明：开始本任务时后端只读控制器尚未合入上述接口，以上为交付前只读核对当前后端 controllers 的结果；未改动后端。

## 测试（真实执行，非 regex 断言源码）

- `node frontend/test/behavior.test.mjs`：36 项通过（第四轮扩充证据日志）。第二轮：#5 5xx/408/409/429/网络错误保留原 key、仅 400/401/403/404/413/415/422 与成功释放、明确失败后新意图；#4 换号清理旧 scope 意图表/输入指纹且不碰无关键；#6 治理订单 id 选取且详情函数只读；#4 登录弹窗无背景关闭且保留返回按钮。
- `node frontend/test/static-check.mjs`：helpers 可导入、App.vue 脚本在 vue 桩下可解析、模板标识符均已在脚本绑定（非行为验收）。
- 运行器：`C:/Users/22721/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe`（未用系统 node14）。

## 未验证

- `vite build`：已用 bundled node + 现成 node_modules 执行，但沙箱禁止 esbuild 子进程（spawn EPERM，esbuild/esbuild sync 与 vite build 均失败）。主协调者需在非受限环境运行 `npm run build`。本轮替代验证：`@vue/compiler-sfc` 对 App.vue 做真实 SFC 编译（script+template 均通过，137 个绑定）。
- 未做 Vue 挂载/浏览器整页回归（登录框、输入法实机 Enter、卡片视觉截断需主协调者真机确认）。
- 前端按契约实现 `/orders/{id}/details`、`/admin/orders/{id}/details`、PUT 收藏、`/messages/context`、发布幂等；只读核对了后端 controllers 已有对应路由，未做前后端联调或真实 MySQL 验证（由主协调者复核）。
- 未完成：`/reports`、`/disputes`、`/verifications`、评价提交等非本轮必改项未加幂等 key（按范围限制）。

## 第二轮（主协调者复核遗漏）

- 聊天输入改为多行 `textarea`（`iam.composeAction`）：Enter 发送并阻止默认换行；Shift/Ctrl/Alt/Meta+Enter 不拦截，由 textarea 真正插入换行；IME 组合中的 Enter 不发送、不拦截。测试用 App.vue 真实 `onEnter` 函数体 + textarea 值模型驱动按键事件（Enter/Shift+Enter/IME/只读/发送后清空）。`draftAfterSend` 未使用，草稿由 `send()` 成功后清空。`n- 交接记录清理：删除“明确失败（4xx/5xx）后释放 key”“明确响应后释放”等过期结论，统一为“5xx/网络未知保留，只对确定无业务效果的明确响应释放”。`n- App.vue：删除登录/表单弹窗 `@click.self` 背景关闭，仅保留“返回/关闭”按钮（聊天与公开评价弹窗行为不变）。
- intent.js 新增 `classify/settleIfApplied`：仅成功与可证明未生效的 4xx（400/401/403/404/413/415/422）释放 key；5xx、408/409/429、网络失败均保留原 key。App.vue 的 `idempotent` 不再区分“有无 status”，统一走该判定；内容变化未决槽位确认取消失败时不发送。
- 账户切换：`resetPrivate(oldId)` 先 `bumpEpoch()` 再按旧 id 调 `clearScope`（只删 `campus:intent:*`、`campus:unknown:<id>`），再清内存；`campus:unknown` 已按账户拆分。`safe` 记录发起时世代，世代变化后旧错误不展示、不做 401 重载；订单动作结果在世代不一致时丢弃。
- #6：新增 `governanceOrderId` 与 `adminOrderDetails`（GET `/admin/orders/{id}/details`）。争议卡按 `d.order_id`、订单类举报卡按 `r.object_id`（`object_type=ORDER`）打开“治理订单详情”，展示交付登记（actor_name/note/created_at）与双方评价（author_id 映射名字、隐藏评价标注），纯只读，无代收货/代评价按钮。
- 测试层次更新：behavior 25 项通过；`@vue/compiler-sfc` 编译 App.vue 通过；`vite build` 因沙箱 esbuild spawn EPERM 未能执行（非代码错误），留给主协调者在放开环境执行。

## 第三轮（复核追加）

- 聊天 `textarea` + `composeAction` 按键语义，含真实按键事件模拟测试；
- 交接报告过期结论已统一：5xx/408/409/429/网络未知保留 key，仅成功与可证明无业务效果的 4xx 释放；
- 测试：behavior 31 项通过；`@vue/compiler-sfc` SFC 编译通过；`vite build` 仍受沙箱 esbuild spawn EPERM 限制未执行。

## 第四轮（争议补证证据日志）

- 原始证据：我的订单/履约争议卡与管理台争议卡均先单独显示原始 `d.evidence`（标题“原始证据”，未填写时明确提示）。
- 补充证据：渲染 `evidence_log:[{id,author_id,content,created_at}]`（标题“补充证据（N 条）”，有序列表，每条显示补证人+时间）。我的订单按订单快照 `buyer_name/seller_name` 与当前用户昵称匹配作者；管理台按管理台返回的账户昵称匹配，未命中显示“用户·<编号前缀>”，从不冒造姓名。
- 安全：证据全部以 `{{ }}` 文本插值渲染，全模板不使用 `v-html`；内容原样保留，换行由 `pre.evidence` 的 `pre-wrap` 呈现。
- 测试：新增 5 项（原始/补充分离与编号、缺字段非数组不伪造、作者未知显示编号且不冒用买方名、无 v-html 原样渲染、两处模板都绑定 evidence_log + 作者 + 时间）。共 36 项通过；`@vue/compiler-sfc` 编译通过（142 个绑定）。
- 未验证：真实接口数据由后端 `GET /disputes` / dashboard 提供 `evidence_log`；页面视觉效果与真实补证流程由主审在真实构建/浏览器中确认。

## 第五轮（主审真实 Edge 发现的集成错误修复，仅改 `frontend/src/App.vue`）

1. 登录后 `ui.busy` 永久为 true：原 `safe` 记录 `gen=epoch`，`finally` 仅在 `epoch===gen` 时解锁；而登录成功走 `applyUser → resetPrivate → bumpEpoch`，世代已变，永不解锁（退出同理）。现在 `ui.busy` 用独立操作所有权 token（`uiOwner`）解锁：只有仍持有锁的那次操作在 `finally` 里 `ui.busy=false`，**不再受 epoch 影响**；旧操作（token 已不是当前 owner）不会误清新操作的忙碌态。错误/旧响应的 epoch 隔离（旧错误不展示、401/403 不按旧世代重载、结果不回填）保持不变。另：登录失败保留已填账号等输入；登录成功后的资料刷新失败不回滚登录态。`safe` 改为返回 Promise（`submitDialog` 用其 await），其余 `safe(fn)` 调用方式不变。
2. 弹窗提交被嵌套 `safe` 跳过：原 `submitDialog` 最外层 `safe` 调 `current.submit`，而 buy/orderAction/manage/review 的 `open()` 回调内部又各自 `safe`，内层看到 `ui.busy` 立即返回，真实请求未发出、弹窗却关闭。现在**只有 `submitDialog` 进一次 `safe`**，所有 `open()` 回调改为接收 `(b,actor)` 直接执行请求并返回结果：
   - `orderAction`/`buy`/`review`/`manage` 去掉内层 `safe`；`withdraw`/`reportObject`/`startDispute`/`supplement` 原本就无内层 `safe`（现统一由 `submitDialog` 持有）。
   - 验证未通过（回调用户校验失败返回 `false`）或接口失败：弹窗、输入、未决 key 都保留，不显示成功、不刷新、不清草稿；接口失败时 `safe` 显示后端错误原因，`submitDialog` 不用“操作未完成”之类的通用文案覆盖（仅在身份世代已变、原因被 epoch 隔离吞掉时补一句）。`authenticate`（登录/注册弹窗）保留自己的 `safe`（不是 `open()` 表单），重复提交时忽略；其余独立页面操作（导航/刷新/发布/认证/收藏/聊天/详情/治理详情/统计/退出）维持 `safe`。
   - `submitDialog` 不再区分 `result===undefined` 与成功：回调返回 `false` 即失败；成功返回字符串/`null` 时关闭弹窗并提示。
3. 首次下单被静默取消（主审真实浏览器：下单弹窗点三次 0 个 POST）：原 `idempotent` 先 `decideIntent`（内部 `acquire` 已写入新 key），再以 `campus:unknown` 单条元数据判断 `changedUnknown`，无未决记录时返回 `false` → `if(!changedUnknown)return {cancelled:true}` 把所有**首次**请求取消；且 `buy` 回调用了未定义的 `actor`。
   - 重写 `idempotent`：一次取 `actor/gen/store/scopes`，`prior=peek(scopes,slot,store)`；**仅当 prior 存在且 `prior.fp!==fingerprint(actor.id,path,body)`** 时才 `confirm`，不同意返回 `{cancelled:true}`（不 acquire、不覆盖旧 key）；其余情形（无 prior、内容相同）直接 `acquire` 后发请求。成功 `settleIfApplied(scopes,slot,key,'ok')`；异常按 `status` 交给 `settleIfApplied`（5xx/网络未知保留原 key）。事实来源只有 intent map（按槽位保存 fp/key，多槽位并存）。
   - 删除误用的 `decideIntent`/`changedUnknown`/`rememberUnknown`/`sameIntent`/`settleOutcome`；`import { peek, fingerprint }` from `intent.js`。
   - `buy`/`orderAction`/`review`/`manage` 回调签名改为 `(b,actor)`；所有 `out.cancelled` 路径返回 `false`（保留弹窗与草稿，不下单、不导航、不显示成功）；`publish` 在 `cancelled` 时不清表单、不跳转，并提示未提交。
4. 其他小修：`market()` 响应在身份变化后不回填；`viewReviews` 响应按账户隔离；`send()` 的 `msgPending` 改在调用处 `finally` 释放；`onMounted` 中未使用的参数移除。
5. 保持不变：退出/换号清私有状态与旧 scope 意图表、旧响应 epoch 隔离、聊天 textarea Enter/Shift+Enter/IME 语义、evidence_log 展示。

## 测试与验证情况（第五轮，诚实记录）

- `frontend/test/behavior.test.mjs`（原有 36 项，未改动）与 `static-check.mjs` 用 bundled Node24 直接执行均通过（36 passed / 0 failed；static-check 模板标识符全部已绑定）。`node --test test/behavior.test.mjs` 在本次沙箱内因测试运行器子进程 `spawn EPERM` 无法启动，改为直接 `node test/behavior.test.mjs` 执行同一文件。
- 本轮未向仓库提交任何“真实 App 脚本控制流”自动化测试，也未做浏览器验证或 Vue 挂载模拟。编写 App 脚本执行装置时多次探针挂起（onMounted 轮询计时器），按主审要求已删除本轮新建的临时文件 `frontend/test/app-harness.mjs`、`frontend/test/_probe.mjs`；原有 `behavior.test.mjs` 与 `static-check.mjs` 保留。
- 真实 Edge 端到端验证（登录后 busy 解除可退出/导航、重新登录、确认下单弹窗实际只 POST 一次、HTTP500 保留弹窗和 key、A500→B 成功→A 重试仍用 K1、注册/认证/发布/举报/争议/评价/管理弹窗调用链、SFC 编译与生产构建）由主审执行；本报告不声称这些场景已由本 agent 通过。

## 第六轮（主审最终可视检查的模板清理，仅一处）

- 管理台“履约争议”卡片原有两个内容相同的原始证据 `<pre class="evidence">{{ d.evidence }}</pre>`（“原始证据”标题之前一个、标题之下一个）。只删除标题之前重复的那个 `<pre>`，保留“原始证据”标题下那份与后面的“补充证据（N 条）”列表；未改任何脚本逻辑、样式或其他模板。编译/浏览器复核由主审执行。

## 第七轮（#7 可用性：管理台“操作记录”展示结构化处置事实）

- 后端 dashboard 已返回 `facts`（结构化 `audit_facts`），但管理台“操作记录”原只渲染 `management.value.logs`。现仅在 `ui2.adminTab==='logs'` 模板中新增：
  - “处置事实”区域：`v-for "(f,i) in (management.value.facts||[])"`，显示 `actor_name`（缺失显示“操作人未知”）、`object_type`/`short(object_id)`、`action`（`result` 非空时以标签显示）、`created_at`；`version` 仅在非 `null/undefined/''`（含 0）时显示；`before_value`/`after_value` 仅以 `!==null && !==undefined` 判断，`0` 不会被误判为空（缺失一侧显示“—”）；`reason` 非空才显示。全部为 `{{ }}` 文本插值，不使用 `v-html`。
  - 原 `logs` 列表保留并改标题为“其他操作记录”。
  - 空态：该标签页数据为空且“处置事实”也为空时才显示“暂无相关记录”（`facts` 有数据而旧 `logs` 为空时不显示空态）。
- 未改任何请求、业务逻辑、样式或其他模板；未新增测试装置。运行原有 `behavior.test.mjs`（36 passed / 0 failed）与 `static-check.mjs`（通过，模板标识符全部已绑定）。SFC 编译与真实浏览器日志字段展示由主审验证。
