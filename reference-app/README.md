# 校园循环

Vue 3 单文件组件（HTML/CSS/JavaScript）＋ Java 17 Spring Boot ＋ MyBatis。界面参考正式需求包的原型；接口、会话和持久数据由真实后端处理。

双击 `启动项目.cmd`，访问 http://127.0.0.1:18080 。双击 `停止项目.cmd` 停止由启动器创建的进程。

本地演示账号：`admin`、`seller`、`buyer`、`buyer2`；密码均为 `Campus123!`。仅当 MySQL 库为空且以 `DEMO_SEED=true` 启动时生成，仅供本地检查。使用不同浏览器或无痕窗口模拟独立用户，不通过页面切换身份。新注册账户须提交认证并由管理员审核。

## 数据库与构建

- **只支持 MySQL 8.4**：设置 `DB_URL`（或 `DB_HOST`/`DB_PORT`/`DB_NAME`）、`DB_USER`、`DB_PASSWORD` 后启动；连接失败会直接启动失败，不提供其他数据库回退。库请使用 `utf8mb4` / `utf8mb4_0900_ai_ci`，详见 [docs/database/mysql.md](docs/database/mysql.md) 与 [本机配置避坑指南](docs/database/本机MySQL配置避坑指南.md)。
- 数据库结构由版本化迁移维护：启动时按顺序执行 `backend/src/main/resources/db/migration/V*__*.sql`，执行记录写入 `schema_migrations`；规则见 [迁移说明](backend/src/main/resources/db/migration/README.md)。已执行的脚本不可修改，结构升级请新增 `V3…`。
- 本地演示同样使用 MySQL：先按上面的环境变量配置连接（可写入当前窗口或用户环境变量），再双击 `启动项目.cmd`。图片仍保存在 `backend/data/uploads`。
- MySQL 容器方式：复制 `.env.example` 为 `.env`，填写数据库密码与 `BOOTSTRAP_ADMIN_USERNAME`/`BOOTSTRAP_ADMIN_PASSWORD`，Docker Desktop 启动后执行 `docker compose up --build -d`。数据库和图片使用独立命名卷；不要同时启动本地模式与容器模式占用相同端口。
- 关闭演示种子（`DEMO_SEED=false`，交付默认）时，后端在库中没有管理员的情况下按 `BOOTSTRAP_ADMIN_*` 创建一个已认证管理员并写入审计；未提供凭据会拒绝启动。演示账户不会带入公网交付。
- 运行健康检查：`GET /api/health` 返回运行状态、数据库类型与已应用迁移版本；`database` 应为 `MySQL`，可用于容器与反向代理探活。
- 修改源码后运行 `powershell -ExecutionPolicy Bypass -File build.ps1`，会构建 Vue、复制静态资源、运行后端测试并生成单个可执行 JAR。已启动的服务须停止并重新启动以加载新构建。
- 前端开发：Node 20.19+，`cd frontend`、`npm install`、`npm run dev`。后端端口 18080，前端代理 `/api`。
- 接口契约由 B 维护：[docs/api/README.md](docs/api/README.md) 与 [openapi.yaml](docs/api/openapi.yaml)。
- 自动化测试使用 test-scope 内存数据库，不会打进交付 JAR；应用不存在 H2 运行方式。


## 已实现范围

注册登录退出、校园认证与审核、图片上传、物品发布与版本审核、检索分页与收藏、唯一下单占用及操作重试、接单拒单、交付登记与买方收货、取消、履约争议及补证结案、物品咨询与订单沟通、评价、举报独立回执与事件关联、隐藏和账户限制恢复、归属核实后重发、成交统计、站内通知与操作记录。

认证材料、私有订单及会话均在后端检查访问权限。交易与治理修改在数据库事务内执行；账户按 ID 排序锁定，再锁物品和订单；占用表的物品主键阻止重复占用；操作回执保存请求摘要并校验同标识内容。

当前通知是站内消息；未接入邮件短信、真实校园身份接口。校园认证由管理员人工审核。举报与争议证据使用文字说明；认证和物品支持图片。首版不含在线支付、自动成交、定时取消、密码找回。统计时间筛选采用完成时点的左闭右开区间，页面按本地时区显示，数据库保留 UTC 时间。

仅供本地课程项目。关闭演示种子可设置 `DEMO_SEED=false`（交付默认），并通过 `BOOTSTRAP_ADMIN_USERNAME`、`BOOTSTRAP_ADMIN_PASSWORD` 创建初始管理员；演示账户仅用于本地检查，不进入公网交付。MySQL 容器方案与真实 MySQL 集成验证需由 A/B 在有 Docker/MySQL 的环境执行，不以自动化测试结果代替真实 MySQL 验收。
