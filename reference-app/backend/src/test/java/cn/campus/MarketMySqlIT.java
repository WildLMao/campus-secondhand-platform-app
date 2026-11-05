package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 真实 MySQL 8.4 验证。仅当设置 IT_DB_URL 时执行，且数据库名必须包含 test，
 * 避免误清其他环境。执行方式见 docs/database/mysql.md：
 *   mvn test -Dtest=MarketMySqlIT
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${IT_DB_URL}",
        "spring.datasource.username=${IT_DB_USER:campus}",
        "spring.datasource.password=${IT_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "app.seed=false",
        "app.bootstrap-admin.username=${IT_ADMIN_USER:it_admin}",
        "app.bootstrap-admin.password=${IT_ADMIN_PASSWORD:ItAdminPass123}",
        "app.upload-dir=./target/mysql-it-uploads" })
@EnabledIfEnvironmentVariable(named = "IT_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketMySqlIT {
    @Autowired
    MarketService service;
    @Autowired
    Store db;
    @Autowired
    DataSource dataSource;

    String admin;

    @BeforeAll
    void guardAndReset() {
        String url = System.getenv("IT_DB_URL");
        assumeTrue(url != null && url.toLowerCase(Locale.ROOT).contains("test"),
                "IT_DB_URL 必须指向名称包含 test 的专用数据库，避免清除其他数据。");
        for (String table : List.of("receipts", "reservations", "deliveries", "reviews", "disputes", "reports",
                "messages", "favorites", "audit_logs", "notifications", "item_versions", "orders", "items",
                "verifications", "identities", "assets"))
            db.exec("delete from " + table, p());
        db.exec("delete from accounts where role<>'ADMIN'", p());
        admin = s(db.one("select id from accounts where role='ADMIN'", p()), "id");
        assertFalse(admin.isBlank(), "应由 BOOTSTRAP_ADMIN_* 创建管理员");
    }

    String user(String prefix) {
        var created = service.register(p("username", prefix + "_" + id().substring(0, 8), "password", "TestPass123!",
                "nickname", prefix + "测试"));
        String uid = s(created, "id");
        db.exec("update accounts set verified=1 where id=#{p.id}", p("id", uid));
        return uid;
    }

    Map<String, Object> listedItem(String seller) {
        String asset = id();
        db.exec("insert into assets(id,owner_id,kind,media_type,created_at) values(#{p.id},#{p.u},'ITEM','image/png',#{p.t})",
                p("id", asset, "u", seller, "t", now()));
        var item = service.saveItem(seller, null, p("title", "MySQL 验证物品 " + id(), "category", "教材书籍",
                "description", "仅用于真实 MySQL 集成验证", "price", "25.00", "image_id", asset));
        service.manage(admin, "item", s(item, "id"), p("reason", "核对当前版本通过", "pass", true, "version",
                item.get("version")));
        return service.item(s(item, "id"), false);
    }

    Map<String, Object> buy(Map<String, Object> item, String buyer) {
        return service.createOrder(buyer, p("item_id", item.get("id"), "version", item.get("version")), id());
    }

    @SuppressWarnings("unchecked")
    BigDecimal completedAmount() {
        return new BigDecimal(s((Map<String, Object>) service.statistics(admin, "", "").get("sales"), "amount"));
    }

    @Test
    void mysqlSchemaCharsetIsolationAndMigrationHistory() throws Exception {
        try (var c = dataSource.getConnection()) {
            assertEquals("MySQL", c.getMetaData().getDatabaseProductName());
            try (var st = c.createStatement();
                    var rs = st.executeQuery(
                            "select @@character_set_database,@@collation_database,@@transaction_isolation")) {
                assertTrue(rs.next());
                assertEquals("utf8mb4", rs.getString(1).toLowerCase(Locale.ROOT));
                assertTrue(rs.getString(2).toLowerCase(Locale.ROOT).startsWith("utf8mb4"));
                assertEquals("READ-COMMITTED", rs.getString(3).toUpperCase(Locale.ROOT));
            }
        }
        var migrations = db.list("select version from schema_migrations order by version", p());
        assertTrue(migrations.size() >= 2, "迁移应可在 MySQL 上执行并记录版本");
        assertEquals(1, n(migrations.get(0), "version"));
    }

    @Test
    void closedLoopReplayAndStatisticsOnMySql() {
        String seller = user("seller"), buyer = user("buyer");
        var item = listedItem(seller);
        String itemId = s(item, "id");
        BigDecimal before = completedAmount();

        var body = p("item_id", itemId, "version", item.get("version"));
        String key = id();
        var order = service.createOrder(buyer, body, key);
        assertEquals("待卖方确认", s(order, "stage"));
        assertEquals(0, new BigDecimal(s(order, "price")).compareTo(new BigDecimal("25.00")));
        assertTrue(s(order, "created_at").endsWith("Z"), "订单时间应为 UTC ISO-8601");

        var replay = service.createOrder(buyer, body, key);
        assertEquals(order.get("id"), replay.get("id"), "同标识重试应返回原订单");
        assertEquals(1, db.list("select id from orders where item_id=#{p.i}", p("i", itemId)).size());

        service.action(seller, s(order, "id"), "accept", p("reason", ""), id());
        service.action(seller, s(order, "id"), "deliver", p("reason", "图书馆当面交接", "declared", true), id());
        var pending = service.order(buyer, s(order, "id"), false);
        assertEquals("待买方确认", s(pending, "stage"));
        assertNull(pending.get("completed_at"), "登记交付不计成交");

        // 与已有契约一致：confirm 须由买方显式声明本人已收到物品（declared=true），不削弱服务端校验
        var done = service.action(buyer, s(order, "id"), "confirm", p("reason", "本人已当面收到物品", "declared", true), id());
        assertEquals("已完成", s(done, "stage"));
        assertNotNull(done.get("completed_at"));
        assertTrue(s(done, "completed_at").endsWith("Z"));
        assertEquals("已成交", s(service.item(itemId, false), "status"));
        assertEquals(0, db.list("select * from reservations where item_id=#{p.i}", p("i", itemId)).size());
        assertEquals(0, completedAmount().subtract(before).subtract(new BigDecimal("25.00")).signum(),
                "成交金额应按订单快照计入且只计一次");
    }

    @Test
    void competingBuyersHaveExactlyOneWinnerOnMySql() throws Exception {
        String seller = user("seller"), first = user("buyer"), second = user("buyer");
        var item = listedItem(seller);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var success = new AtomicInteger();
        List<Future<?>> jobs = new ArrayList<>();
        for (String who : List.of(first, second))
            jobs.add(pool.submit(() -> {
                try {
                    start.await();
                    buy(item, who);
                    success.incrementAndGet();
                } catch (ApiError expected) {
                    // 竞争失败不产生占用
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        start.countDown();
        try {
            for (var f : jobs)
                f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get());
        assertEquals(1, db.list("select id from orders where item_id=#{p.i}", p("i", item.get("id"))).size());
        assertEquals(1, db.list("select * from reservations where item_id=#{p.i}", p("i", item.get("id"))).size());
    }

    @Test
    void cancelAndDeliveryRaceKeepsOneConsistentResult() throws Exception {
        String seller = user("seller"), buyer = user("buyer");
        var item = listedItem(seller);
        var order = buy(item, buyer);
        service.action(seller, s(order, "id"), "accept", p("reason", ""), id());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var success = new AtomicInteger();
        List<Future<?>> jobs = new ArrayList<>();
        for (String action : List.of("cancel", "deliver"))
            jobs.add(pool.submit(() -> {
                try {
                    start.await();
                    service.action(action.equals("cancel") ? buyer : seller, s(order, "id"), action,
                            p("reason", "并发验证", "declared", true), id());
                    success.incrementAndGet();
                } catch (ApiError expected) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        start.countDown();
        try {
            for (var f : jobs)
                f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get());
        String stage = s(service.order(buyer, s(order, "id"), false), "stage");
        assertTrue(Set.of("已取消", "待买方确认").contains(stage), stage);
        assertEquals(stage.equals("已取消") ? 0 : 1,
                db.list("select * from reservations where order_id=#{p.o}", p("o", order.get("id"))).size());
    }
}
