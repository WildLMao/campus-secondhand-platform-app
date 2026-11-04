# MySQL 8.4 环境与验证（B）

本文件是后端侧的数据库准备与验证说明；服务器、域名、HTTPS 与容器运行由 A 负责，
接口消费方式由 C 按 [`docs/api/`](../api/README.md) 核对。

> 本机 Windows 配置 MySQL、真实报错与误操作防护，见
> [`本机MySQL配置避坑指南.md`](本机MySQL配置避坑指南.md)。

## 1. 连接口径

| 项目 | 取值 |
| --- | --- |
| 数据库版本 | MySQL 8.4 |
| 默认连接 | `jdbc:mysql://${DB_HOST:127.0.0.1}:${DB_PORT:3306}/${DB_NAME:campus}`，交付配置显式指定 `com.mysql.cj.jdbc.Driver` |
| 环境变量 | `DB_URL`、`DB_USER`、`DB_PASSWORD`；也可只给 `DB_HOST`/`DB_PORT`/`DB_NAME` |
| 字符集 | 库级 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci` |
| 会话时区 | 连接参数 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| 隔离级别 | `TRANSACTION_READ_COMMITTED`（Hikari 配置，业务按此设计） |
| 金额 | `DECIMAL(12,2)`，人民币元 |
| 时间 | UTC ISO-8601 字符串（`Z` 结尾，`VARCHAR(35)`），区间查询为左闭右开 |

只使用 MySQL；连接失败会直接启动失败，**不提供其他数据库回退**，也没有可用于启动应用的备选数据库配置。

## 2. 建库与账号（DBA / A 执行）

```sql
CREATE DATABASE campus CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'campus'@'%' IDENTIFIED BY '<部署环境密码>';
GRANT ALL PRIVILEGES ON campus.* TO 'campus'@'%';
FLUSH PRIVILEGES;
```

容器方式已在 `compose.yml` 中固定 `--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci`。

## 3. 启动

```powershell
$env:DB_URL='jdbc:mysql://127.0.0.1:3306/campus?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false'
$env:DB_USER='campus'
$env:DB_PASSWORD='<密码>'
$env:DEMO_SEED='false'
$env:BOOTSTRAP_ADMIN_USERNAME='ops_admin'
$env:BOOTSTRAP_ADMIN_PASSWORD='<至少12位、含字母和数字的专用密码>'
java -Dfile.encoding=UTF-8 -jar backend/target/campus-market-1.0.0.jar
```

启动时 `SchemaMigrator` 依次执行 `db/migration/V*__*.sql`，`AdminBootstrap` 在没有管理员时
按 `BOOTSTRAP_ADMIN_*` 创建一个已认证管理员（不覆盖已有管理员，不打印密码）。
若关闭演示种子且未提供管理员凭据，启动会失败并提示，避免形成无法管理的空库。
同时提供初始管理员与 `DEMO_SEED=true` 时只创建管理员，不再生成演示账户与演示物品。

检查：`curl http://127.0.0.1:18080/api/health` 应返回 `status=UP`、`database=MySQL`、`schema=V2`。

## 4. 真实 MySQL 集成验证

`backend/src/test/java/cn/campus/MarketMySqlIT.java` 只在设置 `IT_DB_URL` 时执行，且要求库名包含
`test`（防止误清其他数据）。测试会清空业务表后验证：

- 迁移记录、`utf8mb4` 字符集、`READ-COMMITTED` 隔离级别；
- 下单—接单—交付登记—买方确认闭环、同标识重试返回原订单、成交金额按快照计一次；
- 两个买方并发竞争只成立一笔订单与一个占用；
- 取消与交付登记并发只成立一个相容结果；
- 订单与交付时间保存为 UTC ISO-8601。

```powershell
$env:IT_DB_URL='jdbc:mysql://127.0.0.1:3306/campus_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false'
$env:IT_DB_USER='campus'
$env:IT_DB_PASSWORD='<密码>'
$env:IT_ADMIN_PASSWORD='<测试管理员密码，至少12位>'
cd backend
mvn test -Dtest=MarketMySqlIT
```

未执行本文件中的 MySQL 验证时，应在交接和验收记录中写"未执行"，不能以自动化测试或模拟库结果代替真实 MySQL 验证。

## 5. 结构与数据升级

- 结构升级只新增 `V{n+1}__*.sql`，不修改已执行脚本（校验和不一致会拒绝启动）。
- 演示数据只在 `DEMO_SEED=true` 且账户表为空时生成；公网交付保持 `false`。
- 迁移首版演示数据到 MySQL 时需分别导入账户、物品版本、订单快照、占用、回执及图片文件；
  首版演示数据可直接重新初始化，不得只迁移订单主表。
- 备份：先停应用，备份 MySQL 数据卷与上传目录（A 负责实际挂载与恢复演练）。
