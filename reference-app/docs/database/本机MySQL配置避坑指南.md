# 本机 MySQL 配置与启动避坑指南

版本：2026-10-29。适用：在 Windows 本机把 `reference-app` 从 H2 演示模式切到 MySQL 8.4，并避免重复踩坑。
来源：B 角色本轮实际配置过程（含真实报错与实测结论）。后端规则见 [`docs/api/README.md`](../api/README.md)，
部署侧数据库说明见 [`mysql.md`](mysql.md)。

> **先看这条**：文中的 `<...>` 都是**占位符，必须替换成真实值**再执行。

## 0. 标准流程（一次跑通）

### 0.1 建库与账号（只需一次）

```sql
-- 用 root 登录后执行
CREATE DATABASE campus
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE campus_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'campus'@'localhost' IDENTIFIED BY '<应用密码>';
GRANT ALL PRIVILEGES ON campus.* TO 'campus'@'localhost';
GRANT ALL PRIVILEGES ON campus_test.* TO 'campus'@'localhost';
FLUSH PRIVILEGES;
```

### 0.2 验证应用账号（先验证，再启动应用）

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe' -h 127.0.0.1 -P 3306 -u campus -p campus -e "select database(), current_user();"
```

必须返回 `campus  campus@localhost`。返回 `Access denied` 时先按第 4 节处理，不要继续启动应用。

### 0.3 停掉可能占用 18080 的旧服务

```powershell
cd 'C:\Users\Admin\Documents\ForSE\campus-secondhand-platform-app\reference-app'
powershell -ExecutionPolicy Bypass -File stop.ps1
```

### 0.4 同一个窗口设置变量并启动

```powershell
cd 'C:\Users\Admin\Documents\ForSE\campus-secondhand-platform-app\reference-app'

$env:DB_URL='jdbc:mysql://127.0.0.1:3306/campus?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false'
$env:DB_USER='campus'
$env:DB_PASSWORD='<应用密码>'
$env:DEMO_SEED='false'
$env:BOOTSTRAP_ADMIN_USERNAME='ops_admin'
$env:BOOTSTRAP_ADMIN_PASSWORD='<管理员密码：≥12 位，含字母和数字>'

java "-Dfile.encoding=UTF-8" -jar "backend\target\campus-market-1.0.0.jar"
```

`-Dfile.encoding=UTF-8` 必须带引号（原因见 1.2）；也可以整段去掉，JDK 18 起默认 UTF-8。

### 0.5 另开窗口验证，必须看到 MySQL

```powershell
Invoke-RestMethod http://127.0.0.1:18080/api/health
```

```
schema database checkedAt                      status
------ -------- ---------                      ------
V2     MySQL    2026-10-29T08:32:35.172519Z    UP
```

`database: MySQL` 才算切换成功；显示 `H2` 说明又走了演示模式（第 2 节）。

## 1. 三个最容易踩的坑（本轮实际遇到）

### 1.1 `&` 只属于 PowerShell，CMD 里是分隔符

| 终端 | 提示符 | 正确写法 |
| --- | --- | --- |
| PowerShell | `PS C:\...>` | `& 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe' -h 127.0.0.1 -P 3306 -u root -p` |
| CMD | `C:\...>` | `"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -h 127.0.0.1 -P 3306 -u root -p` |

把 PowerShell 写法粘进 CMD 会报"此处不应有 &"。两种终端都通用的写法：

```powershell
cd 'C:\Program Files\MySQL\MySQL Server 8.4\bin'
.\mysql.exe -h 127.0.0.1 -P 3306 -u root -p     # PowerShell；CMD 下用 mysql ... 即可
```

### 1.2 `-Dfile.encoding=UTF-8` 不加引号会被 PowerShell 拆开
```

正确写法：`java "-Dfile.encoding=UTF-8" -jar "backend\target\campus-market-1.0.0.jar"`。
省略该参数也可以；推荐用项目脚本启动，避免手敲。

### 1.3 相对路径只对当前目录有效

在 `C:\WINDOWS\system32` 执行 `java -jar backend\target\...jar` 会找不到 jar。先进入项目目录：

```powershell
cd 'C:\Users\Admin\Documents\ForSE\campus-secondhand-platform-app\reference-app'
java "-Dfile.encoding=UTF-8" -jar "backend\target\campus-market-1.0.0.jar"
```

`启动项目.cmd` / `start.ps1` 会把工作目录切到 `backend/`，所以 H2 文件库在
`backend/data/campus.mv.db`、上传目录在 `backend/data/uploads`。

## 2. 为什么健康检查显示 H2，以及如何切换

`start.ps1` 的逻辑是：

```powershell
if (!$env:DB_URL -and !$env:APP_PROFILE) {
  # 注入 -Dspring.profiles.active=h2 -DDEMO_SEED=true
}
```

即：**读不到 `DB_URL` 就用 H2 演示模式**。双击 `启动项目.cmd` 时，进程继承的是系统/用户环境变量，
**不会继承另一个 PowerShell 窗口里设置的 `$env:DB_URL`**。这正是本轮"变量都设了、health 还是 H2"的原因。

### 判定当前实例到底是什么模式

```powershell
# 1) 健康检查
Invoke-RestMethod http://127.0.0.1:18080/api/health

# 2) 进程启动参数（关键：有没有 -Dspring.profiles.active=h2）
$c = Get-NetTCPConnection -LocalPort 18080 -State Listen
$procId = ($c | Select-Object -First 1).OwningProcess
Get-CimInstance Win32_Process -Filter "ProcessId=$procId" | Select-Object ProcessId,CommandLine

# 3) 脚本启动时记录在案的 pid
Get-Content 'reference-app\.runtime\server.pid'
```

`database: H2` 或命令行带 `-Dspring.profiles.active=h2` → 演示模式；
`database: MySQL` 且只有 `-Dfile.encoding=UTF-8 -jar ...` → MySQL 模式。

### 三种切换方式

| 方式 | 做法 | 适用 |
| --- | --- | --- |
| 同窗口直接启动 | 0.4 的命令 | 调试、前台看日志 |
| 同窗口用脚本启动 | 设变量后执行 `powershell -ExecutionPolicy Bypass -File start.ps1 -NoBrowser` | 后台运行，日志在 `.runtime\server.log` |
| 永久用户变量 | `setx DB_URL ...` 等，新开窗口/重新双击才生效 | 长期本机开发；明文存注册表，交付机器不要用 |

`setx` 示例（只需一次，之后双击 `启动项目.cmd` 也会走 MySQL）：

```powershell
setx DB_URL "jdbc:mysql://127.0.0.1:3306/campus?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false"
setx DB_USER "campus"
setx DB_PASSWORD "<应用密码>"
setx DEMO_SEED "false"
setx BOOTSTRAP_ADMIN_USERNAME "ops_admin"
setx BOOTSTRAP_ADMIN_PASSWORD "<管理员密码>"
```

### 数据是两套，不要混

- H2 演示库：`backend/data/campus.mv.db`，演示账号 `admin/seller/buyer/buyer2`，密码 `Campus123!`；
- MySQL：`campus` 库，空库由迁移建表、由 `BOOTSTRAP_ADMIN_*` 建管理员。

两套账号不能互相登录；切到 MySQL 后，"原本不存在的账号"不会自动出现。

## 3. 连接类报错对照表

| 报错 | 真实原因 | 处理 |
| --- | --- | --- |
| `此处不应有 &` / `& is not allowed` | PowerShell 语法粘进了 CMD | 用 1.1 的 CMD 写法 |
| `找不到或无法加载主类 .encoding=UTF-8` | `-Dfile.encoding=UTF-8` 未加引号被拆分 | `java "-Dfile.encoding=UTF-8" -jar ...` |
| `Unable to access jarfile` | 不在 `reference-app` 目录，或路径写错 | 先 `cd`，或用绝对路径 |
| `Access denied for user 'campus'@'localhost'` | 占位符未替换、密码错、账号未建或主机不匹配 | 第 4 节；root 下 `ALTER USER ... IDENTIFIED BY` 重置 |
| `Unknown database 'campus'` | 库没建 | 执行 0.1 的建库语句 |
| `Communications link failure` / `Connection refused` | MySQL 服务未启动、端口不对 | `Get-Service MySQL84`；`Test-NetConnection 127.0.0.1 -Port 3306` |
| 启动日志 `无法连接数据库（交付默认 MySQL 8.4）` | 同上，且程序**不会回退 H2** | 先按 0.2 用 CLI 验证连接 |
| `Port 18080 is occupied by another process` | 旧实例仍在跑 | `stop.ps1` / `停止项目.cmd`，或按 PID 结束（第 7 节） |
| health 一直显示 `H2` | 用了演示脚本启动，或该窗口没有 `DB_URL` | 第 2 节判定与切换 |
| `已存在管理员账户，跳过初始管理员创建` | 库里已有 ADMIN，`BOOTSTRAP_ADMIN_*` **不会生效** | 用已有管理员登录；先确认它是谁（第 5 节） |
| schema 不是 `V2` | 迁移未执行完/启动失败 | 看启动日志中 `执行迁移 V1/V2` 与错误堆栈 |

## 4. 建库与账号规范

1. 库显式使用 `utf8mb4` / `utf8mb4_0900_ai_ci`，与 `compose.yml` 一致；MySQL 8.4 默认值虽相同，写出来便于核对。
2. 账号主机按范围选：应用与库同机用 `'campus'@'localhost'`；容器或另一台机器用 `'campus'@'%'`
   （同时确认防火墙与 `bind-address`）。
3. `campus_test` 只给 `MarketMySqlIT` 用：该测试会**清空业务表**，所以要求库名含 `test`，
   不要把 `IT_DB_URL` 指向 `campus`。
4. **不要手工建表**：表由启动时的 `V1/V2` 迁移创建；手工建表后再启动，`V2` 的 `CREATE INDEX`
   会因索引重名失败。
5. 字符集核对：

```sql
SHOW CREATE DATABASE campus;
SELECT @@character_set_server, @@collation_server, @@version;
```

6. 应用账号最小权限：只授 `campus`（测试库另授 `campus_test`），不要用 root 跑应用。
7. 密码遗忘或账号缺失时，用 root 修复：

```sql
-- 账号已存在：重置密码
ALTER USER 'campus'@'localhost' IDENTIFIED BY '<应用密码>';

-- 账号不存在：创建并授权
CREATE USER 'campus'@'localhost' IDENTIFIED BY '<应用密码>';
GRANT ALL PRIVILEGES ON campus.* TO 'campus'@'localhost';
GRANT ALL PRIVILEGES ON campus_test.* TO 'campus'@'localhost';
FLUSH PRIVILEGES;
```

## 5. 初始管理员与演示数据

- 交付/正常模式：`DEMO_SEED=false`，由 `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD` 建管理员；
- 密码规则：**≥12 位、同时包含字母和数字、UTF-8 ≤72 字节，且不能是演示密码 `Campus123!`**，否则启动直接失败；
- 只创建一次：库中已有 `role='ADMIN'` 时跳过并在日志提示，不覆盖、不改密码；
- 关闭种子且既无管理员也无凭据时，启动**故意失败**并提示如何配置，避免形成无法管理的空库；
- 同时设置 `BOOTSTRAP_ADMIN_*` 与 `DEMO_SEED=true` 时只创建管理员，不生成演示账户/物品。

### 老 `admin` 还能登录？

只有把**旧数据**（H2 文件库或导出的 SQL）带到新库时才会出现。`V1` 用 `CREATE TABLE IF NOT EXISTS`，
迁移只补结构、不动数据，`AdminBootstrap` 也只在"没有管理员"时创建。检查：

```sql
SELECT id, username, role, verified, restricted FROM accounts;
```

不要 `DELETE`（订单/审计引用账户，会破坏 BR16 历史）。需要停用演示账号时改为受限并轮换密码：

```sql
UPDATE accounts SET restricted = 1 WHERE username IN ('admin','seller','buyer','buyer2');
-- 新密码哈希需用应用同一套 BCrypt 生成，或改用正式账号并移除演示账号的登录能力
```

## 6. 迁移脚本规则（改错会导致启动失败）

- 目录：`backend/src/main/resources/db/migration/`，当前 `V1__baseline.sql`、`V2__indexes.sql`；
- 启动时按版本号顺序执行未应用脚本，并写入 `schema_migrations`（版本 + 描述 + SHA-256 校验和 + 时间）；
- **已执行的脚本不能再修改**：内容变化会拒绝启动，并提示下一个可用版本号；
- 结构升级只新增 `V3__xxx.sql`，不要改 V1/V2，也不要手工改库结构却不留脚本；
- MySQL 的 DDL 不能回滚：脚本中途失败时该版本不会记录，需人工核对已执行语句后重跑；
- 核对迁移状态：

```sql
SELECT version, description, applied_at FROM schema_migrations ORDER BY version;
-- 或
-- GET /api/health 的 schema 字段
```

## 7. 停止、重启与排错顺序

### 停止

| 启动方式 | 停止方式 |
| --- | --- |
| 双击 `启动项目.cmd` / `start.ps1` | 双击 `停止项目.cmd` 或执行 `stop.ps1`（按 `.runtime\server.pid` 结束） |
| 前台 `java -jar ...` | 该窗口 `Ctrl+C`，或直接关窗口 |
| 不知道谁占着端口 | 见下面命令 |

```powershell
$c = Get-NetTCPConnection -LocalPort 18080 -State Listen
$procId = ($c | Select-Object -First 1).OwningProcess
Get-CimInstance Win32_Process -Filter "ProcessId=$procId" | Select-Object ProcessId,CommandLine
Stop-Process -Id $procId -Force
```

> `stop.ps1` 只认它自己记录的 pid；手动 `java -jar` 起的实例要按上面的方式结束。

### 重启（改代码或改配置后）

```powershell
cd 'C:\Users\Admin\Documents\ForSE\campus-secondhand-platform-app\reference-app'
powershell -ExecutionPolicy Bypass -File build.ps1      # 仅改源码后需要；只改环境变量不用
powershell -ExecutionPolicy Bypass -File stop.ps1
# 再按 0.4 启动；环境变量必须在启动的同一窗口里设置
```

### 排错顺序（从外到内，逐层排除）

1. `Get-Service MySQL84` → 是否 Running；
2. `Test-NetConnection 127.0.0.1 -Port 3306` → 端口是否可达；
3. 用 `mysql.exe` + `campus` 账号执行 `select 1` → 账号/密码/主机是否正确；
4. `SHOW DATABASES LIKE 'campus%'` → 库是否存在；
5. 启动日志是否出现 `执行迁移 V1/V2`、`数据库：MySQL 8.4.x`；
6. `GET /api/health` 的 `database` 与 `schema` 是否符合预期；
7. 用管理员账号登录，或查看"已存在/已创建管理员"日志。

## 8. 交付前检查表

- [ ] `DEMO_SEED=false`，且没有把演示账号带入交付库；
- [ ] `BOOTSTRAP_ADMIN_*` 已设置、密码符合规则，交付后移除明文配置；
- [ ] `/api/health` 返回 `database: MySQL`、`schema: V2`；
- [ ] `schema_migrations` 有 V1、V2 两行；
- [ ] 应用账号不是 root，且只授权 `campus`（测试库除外）；
- [ ] `SESSION_COOKIE_SECURE=true`（HTTPS 交付）；
- [ ] 端口 18080 只被一个实例占用；
- [ ] `.env` / 环境变量没有提交到仓库，交接文档里没有明文密码；
- [ ] 真实 MySQL 并发与恢复验证已执行，或在交接中注明"未执行"。

## 附录 A 本机实际环境（2026-10-29 核对）

| 项目 | 值 |
| --- | --- |
| MySQL | Server 8.4.13，服务名 `MySQL84`（Running，自动启动） |
| 端口 | 3306（X Protocol 33060） |
| 配置文件 | `C:\ProgramData\MySQL\MySQL Server 8.4\my.ini`（`port=3306`，字符集未覆盖 = 默认 utf8mb4） |
| 客户端 | `C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe`，**不在 PATH** |
| 数据目录 | `C:\ProgramData\MySQL\MySQL Server 8.4\Data`（普通权限不可列目录） |
| Java | Zulu OpenJDK 21.0.3（编译目标 17） |
| 项目 | `C:\Users\Admin\Documents\ForSE\campus-secondhand-platform-app\reference-app` |
| 已确认结果 | `/api/health` → `database: MySQL`、`schema: V2`、`status: UP` |

其他机器上路径/版本可能不同，先按 `Get-Service MySQL*`、`mysql --version` 与 my.ini 核对。

## 附录 B 命令速查

```powershell
# 客户端完整路径
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'

# root 登录
& $mysql -h 127.0.0.1 -P 3306 -u root -p

# 应用账号自检
& $mysql -h 127.0.0.1 -P 3306 -u campus -p campus -e "select database(), current_user();"

# 健康检查
Invoke-RestMethod http://127.0.0.1:18080/api/health

# 端口/进程
Get-NetTCPConnection -LocalPort 18080 -State Listen
Get-CimInstance Win32_Process -Filter "ProcessId=<pid>" | Select-Object CommandLine

# 停止脚本启动的实例
cd 'C:\Users\Admin\Documents\ForSE\campus-secondhand-platform-app\reference-app'
powershell -ExecutionPolicy Bypass -File stop.ps1
```

相关文档：

- 数据库准备与真实 MySQL 验证：[`mysql.md`](mysql.md)
- 接口契约：[`../api/README.md`](../api/README.md) / [`../api/openapi.yaml`](../api/openapi.yaml)
- 本轮交接：[`../handoffs/B-2026-10-29-后端与MySQL.md`](../handoffs/B-2026-10-29-后端与MySQL.md)




