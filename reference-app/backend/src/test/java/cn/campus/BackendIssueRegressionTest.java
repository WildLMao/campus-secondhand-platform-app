package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 本轮 11 条 Issue 与历史缺陷的后端回归（独立上下文与独立内存库，test scope）。
 * 覆盖真实 Controller/Service/事务；并发用例使用真实 Spring 事务与工作线程。
 * 故障与时序协调只在测试层的 Store 上完成：{@link TestStoreSpy} 基于下方 @SpyBean 的真实 Store，
 * 普通实现不变，产品代码中没有任何测试开关或测试专用 SQL。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.upload-dir=./target/test-uploads", "app.seed=true" })
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendIssueRegressionTest {
    @Autowired
    MarketService service;
    @Autowired
    Store db;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;
    @SpyBean
    Store dbSpy;

    ObjectMapper0 om = new ObjectMapper0();

    @BeforeEach
    void installTestStoreSpy() {
        TestStoreSpy.install(dbSpy);
    }

    @AfterEach
    void clearTestStoreSpy() {
        TestStoreSpy.clearNotificationFaults();
        TestStoreSpy.clearPauses();
    }

    static final class ObjectMapper0 extends com.fasterxml.jackson.databind.ObjectMapper {
    }

    String uid(String name) {
        return s(db.one("select id from accounts where username=#{p.u}", p("u", name)), "id");
    }

    String seller() {
        return uid("seller");
    }

    String buyer() {
        return uid("buyer");
    }

    String admin() {
        return uid("admin");
    }

    /** 待审（未审核）的新物品；用于审核版本类用例。 */
    Map<String, Object> pendingItem() {
        String asset = s(db.one("select id from assets where owner_id=#{p.u} and kind='ITEM' limit 1", p("u", seller())),
                "id");
        return service.saveItem(seller(), null, p("title", "待审物品 " + id(), "category", "教材书籍", "description",
                "等待审核的回归测试物品。", "price", "30.00", "image_id", asset));
    }

    Map<String, Object> onSale() {
        String asset = s(db.one("select id from assets where owner_id=#{p.u} and kind='ITEM' limit 1", p("u", seller())),
                "id");
        var i = service.saveItem(seller(), null, p("title", "回归物品 " + id(), "category", "教材书籍", "description",
                "回归测试用品，非真实交易。", "price", "32.00", "image_id", asset));
        service.manage(admin(), "item", s(i, "id"), p("reason", "回归审核通过", "pass", true, "version", i.get("version")));
        return service.item(s(i, "id"), false);
    }

    Map<String, Object> order(Map<String, Object> i) {
        return service.createOrder(buyer(), p("item_id", i.get("id"), "version", i.get("version")), id());
    }

    Map<String, Object> act(String who, Map<String, Object> o, String action) {
        return service.action(who, s(o, "id"), action, p("reason", "回归动作说明", "declared", true), id());
    }

    /** 直接调用下单（用于构造前置条件）。 */
    Map<String, Object> rawOrder(Map<String, Object> i) {
        return service.createOrder(buyer(), p("item_id", i.get("id"), "version", i.get("version")), id());
    }

    Map<String, Object> orderBody() {
        var i = onSale();
        return p("item_id", i.get("id"), "version", "1", "title", "回归发布 " + id(), "category", "数码配件",
                "description", "独立回归测试发布", "price", "25.00", "image_id", i.get("image_id"));
    }

    static com.fasterxml.jackson.databind.JsonNode dashboardDispute(com.fasterxml.jackson.databind.JsonNode body, String did) {
        for (var d : body.get("disputes"))
            if (did.equals(d.get("id").asText()))
                return d;
        return null;
    }

    static Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    MockHttpSession session(String name) {
        var s = new MockHttpSession();
        s.setAttribute("uid", uid(name));
        s.setAttribute("csrf", "csrf-" + name);
        return s;
    }

    /** 以账户 id 直接构造会话（用于运行期注册的新账户）。 */
    MockHttpSession sessionOf(String userId) {
        var s = new MockHttpSession();
        s.setAttribute("uid", userId);
        s.setAttribute("csrf", "csrf-" + userId);
        return s;
    }

    /** 新会话：先经真实 GET /api/me 取得由服务端签发的 CSRF，再用于写请求。 */
    MockHttpSession freshSession() throws Exception {
        var res = mvc.perform(get("/api/me")).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession();
    }

    int notifications(String user, String contains) {
        return ((Number) db.one("select count(*) as c from notifications where user_id=#{p.u} and content like #{p.c}",
                p("u", user, "c", "%" + contains + "%")).get("c")).intValue();
    }

    /** 在调用线程事务之外反复补投，直到该用户没有未发送记录（仍失败的记录保留 FAILED，不掩盖失败）。 */
    void drainAll(String userId) {
        for (int k = 0; k < 10; k++) {
            service.notifications().deliverPending();
            if (((Number) db.one("select count(*) as c from notification_outbox where status<>'SENT' and user_id=#{p.u}",
                    p("u", userId)).get("c")).intValue() == 0)
                return;
        }
        fail("补投 10 轮后仍有未发送通知：" + db.list(
                "select id,status,attempts,last_error from notification_outbox where status<>'SENT' and user_id=#{p.u}",
                p("u", userId)));
    }

    /**
     * 测试层故障窗口：先清空此前残留的失败通知，再开启“写 notifications 必失败”，执行一次会产生新通知的业务动作，
     * 投递后断言新通知记 FAILED 且未写入 notifications；随后恢复并在事务外补投，断言最终恰好多通知一次。
     */
    void withNotificationFaultWindow(Runnable action, String user, String contains) {
        drainAll(user);
        int expectedBefore = notifications(user, contains);
        int failedBefore = ((Number) db.one("select count(*) as c from notification_outbox where status='FAILED' and user_id=#{p.u}",
                p("u", user)).get("c")).intValue();
        int createdBefore = ((Number) db.one("select count(*) as c from notification_outbox where user_id=#{p.u}",
                p("u", user)).get("c")).intValue();
        TestStoreSpy.failNextNotificationInserts(Integer.MAX_VALUE);
        try {
            action.run();
            service.notifications().deliverPending();
            assertEquals(createdBefore + 1, ((Number) db.one("select count(*) as c from notification_outbox where user_id=#{p.u}",
                    p("u", user)).get("c")).intValue(), "该次事件只登记一条待投递记录");
            assertEquals(failedBefore + 1, ((Number) db.one("select count(*) as c from notification_outbox where status='FAILED' and user_id=#{p.u}",
                    p("u", user)).get("c")).intValue(), "故障窗口内新通知应记 FAILED");
            // 故障前已 SENT 的同一内容通知仍可查：只比较窗口前后是否新增（不依赖历史累计为 0）
            assertEquals(expectedBefore, notifications(user, contains), "故障期间未写入通知");
        } finally {
            TestStoreSpy.clearNotificationFaults();
        }
        drainAll(user);
        assertEquals(expectedBefore + 1, notifications(user, contains), "恢复后恰好补投一次：" + contains);
    }

    @Test
    @Order(1)
    void migrationsV3ToV5AppliedWithRevisionColumns() {
        var versions = db.list("select version as v from schema_migrations order by version", p()).stream()
                .map(r -> n(r, "v")).toList();
        assertEquals(List.of(1, 2, 3, 4, 5), versions);
        // 版本列存在（空表聚合也为 0 行，故用 H2 元数据确认列）
        assertTrue(jdbc.queryForObject("select count(*) from information_schema.columns where lower(table_name) in ('disputes','deliveries') and lower(column_name)='revision'",
                Integer.class) == 2, "争议与交付登记应有版本列");
    }

    // ---------------- #1 发布/修改幂等 ----------------
    @Test
    @Order(2)
    void repeatedPublishWithSameKeyReturnsOneItem() throws Exception {
        String key = id();
        var body = orderBody();
        String json = om.writeValueAsString(body);
        String first = om.readTree(mvc.perform(post("/api/items").session(session("seller"))
                .header("X-CSRF-Token", "csrf-seller").header("Idempotency-Key", key)
                .contentType("application/json").content(json)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("id").asText();
        String second = om.readTree(mvc.perform(post("/api/items").session(session("seller"))
                .header("X-CSRF-Token", "csrf-seller").header("Idempotency-Key", key)
                .contentType("application/json").content(json)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("id").asText();
        assertEquals(first, second, "相同 Idempotency-Key 应返回原物品");
        assertEquals(1, db.list("select id from item_versions where item_id=#{p.i}", p("i", first)).size());

        var changed = new HashMap<>(body);
        changed.put("price", "26.00");
        mvc.perform(post("/api/items").session(session("seller")).header("X-CSRF-Token", "csrf-seller")
                .header("Idempotency-Key", key).contentType("application/json").content(om.writeValueAsString(changed)))
                .andExpect(status().isBadRequest());
        // 新意图（不同 key）仍正常创建
        String other = om.readTree(mvc.perform(post("/api/items").session(session("seller"))
                .header("X-CSRF-Token", "csrf-seller").header("Idempotency-Key", id())
                .contentType("application/json").content(json)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("id").asText();
        assertNotEquals(first, other);
    }

    @Test
    @Order(3)
    void concurrentPublishSameKeyCreatesOneItemAndFailedPublishLeavesNoRecords() throws Exception {
        var body = orderBody();
        String key = id();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var ids = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<String>();
        List<Future<?>> jobs = new ArrayList<>();
        for (int k = 0; k < 2; k++)
            jobs.add(pool.submit(() -> {
                try {
                    start.await();
                    ids.add(s(service.saveItem(seller(), null, new HashMap<>(body), key), "id"));
                } catch (ApiError e) {
                    errors.add(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        start.countDown();
        for (var f : jobs)
            f.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(1, new HashSet<>(ids).size(), "并发同 key 只产生一件物品：" + ids);
        assertEquals(1, new HashSet<>(ids).size() == 1 ? db.list("select id from items where id=#{p.i}", p("i", ids.get(0))).size() : 0);

        // 失败发布：图片不属于本人，不留下物品/版本/意图记录
        var victim = onSale();
        String badKey = id();
        assertThrows(ApiError.class, () -> service.saveItem(buyer(), null, p("title", "失败发布", "category", "其他",
                "description", "失败用例", "price", "10.00", "image_id", victim.get("image_id")), badKey));
        assertEquals(0, db.list("select id from item_receipts where request_key=#{p.k}", p("k", badKey)).size());
        assertEquals(0, db.list("select id from items where title='失败发布'", p()).size());
    }

    @Test
    @Order(4)
    void repeatedEditWithSameKeyReturnsOriginalVersion() {
        var i = onSale();
        String key = id();
        String iid = s(i, "id");
        var body = p("title", "修改后标题", "category", "教材书籍", "description", "修改后的描述", "price", "40.00",
                "image_id", i.get("image_id"));
        var v2 = service.saveItem(seller(), iid, body, key);
        assertEquals(2L, ((Number) v2.get("version")).longValue());
        var again = service.saveItem(seller(), iid, body, key);
        assertEquals(2L, ((Number) again.get("version")).longValue());
        assertEquals(2, db.list("select id from item_versions where item_id=#{p.i}", p("i", iid)).size());
        assertThrows(ApiError.class, () -> service.saveItem(seller(), iid,
                p("title", "不同标题", "category", "教材书籍", "description", "修改后的描述", "price", "40.00",
                        "image_id", i.get("image_id")), key));
        assertThrows(ApiError.class, () -> service.saveItem(buyer(), iid, body, id()), "他人不能借他人物品修改");
    }

    // ---------------- #2 补证 ----------------
    @Test
    @Order(5)
    void simultaneousSupplementsPreserveBothAndThirdPartyRejected() throws Exception {
        var i = onSale();
        var o = order(i);
        act(seller(), o, "accept");
        service.dispute(buyer(), s(o, "id"), p("reason", "交付时间分歧", "evidence", "BASE"));
        String did = s(db.one("select id from disputes where order_id=#{p.o}", p("o", o.get("id"))), "id");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var errors = new CopyOnWriteArrayList<String>();
        List<Future<?>> jobs = new ArrayList<>();
        for (var pair : List.of(Map.entry(buyer(), "BUYER_A"), Map.entry(seller(), "SELLER_B")))
            jobs.add(pool.submit(() -> {
                try {
                    start.await();
                    service.supplement(pair.getKey(), did, p("evidence", pair.getValue()));
                } catch (ApiError e) {
                    errors.add(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        start.countDown();
        for (var f : jobs)
            f.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(errors.isEmpty(), "双方补证都应成功：" + errors);
        var log = db.list("select content from dispute_evidence where dispute_id=#{p.d} order by created_at,id", p("d", did));
        assertEquals(2, log.size(), "双方补证都必须保留");
        var joined = String.join("|", log.stream().map(r -> s(r, "content")).toList());
        assertTrue(joined.contains("BUYER_A") && joined.contains("SELLER_B"));
        assertEquals("BASE", s(service.disputes(buyer()).stream().filter(d -> did.equals(s(d, "id"))).findFirst().orElseThrow(),
                "evidence"), "原提交证据不被改写");
        assertThrows(ApiError.class, () -> service.supplement(uid("buyer2"), did, p("evidence", "THIRD_PARTY")),
                "第三人不能补证");
    }

    @Test
    @Order(6)
    void supplementAfterClosureIsRejectedAndRaceDoesNotCrossBoundary() throws Exception {
        var i = onSale();
        var o = order(i);
        act(seller(), o, "accept");
        service.dispute(buyer(), s(o, "id"), p("reason", "实物与描述不符", "evidence", "IMG"));
        String did = s(db.one("select id from disputes where order_id=#{p.o}", p("o", o.get("id"))), "id");
        service.manage(admin(), "dispute", did, p("reason", "双方确认可继续", "outcome", "continue"));
        assertThrows(ApiError.class, () -> service.supplement(seller(), did, p("evidence", "AFTER_CLOSURE")));

        // 由卖方重新发起争议；买方在“锁前读取后、加锁前”挂起，期间管理员异常终止结案
        service.dispute(seller(), s(o, "id"), p("reason", "买方称未收到", "evidence", "BASE2"));
        String did2 = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", o.get("id"))),
                "id");
        // 确定性时序（审查用例 A；真实 Spring 事务、真实行锁、真实结案逻辑，只在测试层 Store spy 上协调读取时机）：
        // 1) 补证线程在真实事务中完成锁前普通读取（返回真实“处理中”行）后按测试层闸门挂起，尚未加锁；
        // 2) 管理员在另一线程、另一事务中正常结案并提交；
        // 3) 放行补证线程：它继续按统一顺序加锁，并以锁后 SELECT ... FOR UPDATE 的当前读取判定阶段。
        // 若锁后读取被旧快照/一级缓存代替，补证就会越过结案边界——本用例以“被拒绝且无新增证据”断言。
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        TestStoreSpy.pauseAfterDisputeHintRead(did2, arrived, release);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> job = pool.submit(() -> {
                try {
                    service.supplement(buyer(), did2, p("evidence", "AFTER_CLOSURE"));
                    return "";
                } catch (ApiError e) {
                    return e.getMessage();
                }
            });
            assertTrue(arrived.await(20, TimeUnit.SECONDS), "补证线程应完成锁前读取");
            assertTrue(TestStoreSpy.paused(did2), "确定性时序：补证线程确实在锁前读取后被测试层闸门挂起");
            assertEquals(0, db.list("select id from dispute_evidence where dispute_id=#{p.d}", p("d", did2)).size(),
                    "补证线程应停在加锁之前，尚未追加证据");
            assertEquals("处理中", s(db.one("select status from disputes where id=#{p.d}", p("d", did2)), "status"),
                    "补证挂起期间争议仍为处理中");
            service.manage(admin(), "dispute", did2,
                    p("reason", "核查后无法履约", "outcome", "terminate", "evidence_confirmed", true));
            assertEquals("已结案", s(db.one("select status from disputes where id=#{p.d}", p("d", did2)), "status"),
                    "管理员结案已提交");
            release.countDown();
            String supplement = job.get(20, TimeUnit.SECONDS);
            assertTrue(supplement.contains("已结案"), "锁前读取后结案，补证应被锁后当前读取拒绝：" + supplement);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            release.countDown();
            TestStoreSpy.clearPauses();
            pool.shutdownNow();
        }
        assertEquals(0, db.list("select id from dispute_evidence where dispute_id=#{p.d}", p("d", did2)).size(),
                "结案后不得再追加证据");
        assertEquals(1, db.list("select id from disputes where id=#{p.d} and status='已结案'", p("d", did2)).size());
    }

    // ---------------- #3 通知失败不回滚业务 ----------------
    /** 反复补投，直到当前事件不再留下未发送记录（避免把其他用例的数据混入断言）。 */
    void drain(String userId, String contains) {
        for (int k = 0; k < 10; k++) {
            if (((Number) db.one("select count(*) as c from notification_outbox where status<>'SENT' and user_id=#{p.u}",
                    p("u", userId)).get("c")).intValue() == 0)
                return;
            service.notifications().deliverPending();
        }
    }

    @Test
    @Order(7)
    void notificationFailureMustNotRollBackOrder() {
        for (int k = 0; k < 10; k++)
            service.notifications().deliverPending();
        int before = notifications(seller(), "收到新订单");
        var i = onSale();
        // 测试层故障注入：只让写 notifications 的 SQL 抛 DataAccessResourceFailureException，
        // 业务事务、占用/回执/待投递记录写入以及投递用的 REQUIRES_NEW 事务全部是真实执行。
        TestStoreSpy.failNextNotificationInserts(Integer.MAX_VALUE);
        String oid;
        try {
            var o = order(i);
            oid = s(o, "id");
            // 与 HTTP 通知列表相同的补投路径在故障下失败，但只影响通知
            service.notifications().deliverPending();
            assertEquals(1, db.list("select id from orders where id=#{p.o}", p("o", oid)).size(), "订单应提交");
            assertEquals(1, db.list("select order_id from reservations where item_id=#{p.i}", p("i", i.get("id"))).size());
            assertEquals("待卖方确认", s(o, "stage"));
            assertEquals("交易中", s(service.item(s(i, "id"), false), "status"));
            var failed = db.list("select * from notification_outbox where status='FAILED' and user_id=#{p.u}",
                    p("u", seller()));
            assertEquals(1, failed.size(), "投递失败应记为 FAILED 并保留该事件");
            assertTrue(s(failed.get(0), "last_error").contains("通知存储不可写"), "应保留 last_error：" + failed.get(0));
            assertTrue(n(failed.get(0), "attempts") >= 1, "应记录失败次数");
            assertEquals(before, notifications(seller(), "收到新订单"));
        } finally {
            TestStoreSpy.clearNotificationFaults();
        }
        assertEquals(0, TestStoreSpy.remainingNotificationFaults());
        // 恢复后补投：FAILED 记录在真实新事务中重试成功，不新建订单、不重复成交
        for (int k = 0; k < 5; k++)
            service.notifications().deliverPending();
        assertEquals(before + 1, notifications(seller(), "收到新订单"));
        assertEquals(0, ((Number) db.one("select count(*) as c from notification_outbox where status<>'SENT' and user_id=#{p.u}",
                p("u", seller())).get("c")).intValue(), "FAILED 记录恢复后应全部补投");
        assertEquals(before + 1, notifications(seller(), "收到新订单"), "补投一次且只一次");
        assertEquals(1, db.list("select id from orders where item_id=#{p.i}", p("i", i.get("id"))).size());
        assertEquals(1, db.list("select id from orders where id=#{p.o}", p("o", oid)).size());
    }

    @Test
    @Order(7)
    void failedNotificationRetriesRotateByAttemptsWithoutPermanentDrop() {
        // 直接登记 45 条待投递记录（不受单业务事件去重影响），在通知存储连续不可写下全部失败，
        // 使失败次数超过原 50 次上限的规模：每批 20 条按 attempts 轮转，所有记录最终都能补投成功。
        String uid = seller();
        List<String> outboxIds = new ArrayList<>();
        for (int k = 0; k < 45; k++) {
            String outboxId = "rot" + k + id().replace("-", "").substring(0, 31);
            db.exec("insert into notification_outbox(id,user_id,content,created_at,status,attempts) values(#{p.id},#{p.u},#{p.c},#{p.t},'PENDING',0)",
                    p("id", outboxId, "u", uid, "c", "轮转补投测试 " + k, "t", now()));
            outboxIds.add(outboxId);
        }
        TestStoreSpy.failNextNotificationInserts(Integer.MAX_VALUE);
        try {
            for (int round = 0; round < 3; round++)
                service.notifications().deliverPending();
            var failed = db.list(
                    "select * from notification_outbox where status='FAILED' and last_error like '%通知存储不可写%' order by attempts",
                    p());
            assertEquals(45, failed.size(), "三轮后 45 条记录都应处于 FAILED");
            // 每批 20 条按 attempts 升序轮转：第 1 轮 45 条各失败 1 次；第 2、3 轮重试各 20 条。
            // 若只按登记顺序取前 20 条，后 25 条会永远停在 1 次；这里断言重试次数在记录间轮转。
            long retriedTwiceOrMore = failed.stream().filter(r -> n(r, "attempts") >= 2).count();
            long neverRetried = failed.stream().filter(r -> n(r, "attempts") <= 1).count();
            assertEquals(40, retriedTwiceOrMore, "两轮重试共 40 条记录应被再次尝试：" + failed);
            assertEquals(5, neverRetried, "45 条记录只有 5 条尚未被第二轮重试覆盖：" + failed);
            // 超过 50 次失败也不丢弃：仍保留为 FAILED 且可继续轮转
            db.exec("update notification_outbox set attempts=60 where id=#{p.id}", p("id", outboxIds.get(0)));
        } finally {
            TestStoreSpy.clearNotificationFaults();
        }
        for (int k = 0; k < 5; k++)
            service.notifications().deliverPending();
        assertEquals(45, notifications(uid, "轮转补投测试"), "恢复后 45 条全部补投");
        assertEquals(0, ((Number) db.one("select count(*) as c from notification_outbox where status<>'SENT' and id like 'rot%'",
                p()).get("c")).intValue(), "attempts=60 的记录也不能被永久丢弃");
        // 已 SENT 的绝不重复投递
        for (int k = 0; k < 3; k++)
            service.notifications().deliverPending();
        assertEquals(45, notifications(uid, "轮转补投测试"), "已 SENT 不重复投递");
    }

    @Test
    @Order(10)
    void repeatedReportAndRelistDispositionsDoNotCollide() {
        // 同一举报因新增事实再次处置：两次都是独立事件，都能通知，不因事件键重复回滚业务
        var o = order(onSale());
        service.report(buyer(), p("object_type", "ORDER", "object_id", s(o, "id"), "reason", "交付说明存疑", "evidence", "x"));
        String rid = s(db.one("select id from reports where object_id=#{p.o} order by created_at desc", p("o", o.get("id"))), "id");
        int before = notifications(buyer(), "举报处理结果");
        service.manage(admin(), "report", rid, p("reason", "首次核查", "event_ref", "E1"));
        service.manage(admin(), "report", rid, p("reason", "补充事实后再次处置", "event_ref", "E2"));
        drain(buyer(), "举报处理结果");
        assertEquals(before + 2, notifications(buyer(), "举报处理结果"), "每次处置各通知一次");
        assertEquals("补充事实后再次处置", s(db.one("select result from reports where id=#{p.id}", p("id", rid)), "result"));
        // 处置事件键取本次事实标识：两次处置各对应一条待投递记录，且该记录在故障后重试仍是同一事件（不重复通知）
        var reportEvent = db.one("select id from notification_outbox where user_id=#{p.u} and content like '%补充事实后再次处置%'",
                p("u", buyer()));
        assertNotNull(reportEvent, "report-result 事件键使用本次 factId 登记");
        withNotificationFaultWindow(() -> service.manage(admin(), "report", rid, p("reason", "第三次核查", "event_ref", "E3")),
                buyer(), "举报处理结果");

        // 物品重复异常终止后再次核实归属：每次核实各通知一次
        var i = onSale();
        String buyer2 = uid("buyer2");
        for (String b : List.of(buyer(), buyer2)) {
            if (!"在售".equals(s(service.item(s(i, "id"), false), "status"))) {
                // 首次核实后修改即进入待审：重新提交并审核通过，才能发生第二次异常终止
                var again = service.saveItem(seller(), s(i, "id"), p("title", "再次异常终止测试", "category", "教材书籍",
                        "description", "归属核实后重新提交", "price", "32.00", "image_id", i.get("image_id")));
                service.manage(admin(), "item", s(i, "id"), p("reason", "重新提交通过", "pass", true, "version", again.get("version")));
            }
            var o2 = service.createOrder(b, p("item_id", i.get("id"), "version", service.item(s(i, "id"), false).get("version")), id());
            act(seller(), o2, "accept");
            service.dispute(b, s(o2, "id"), p("reason", "无法履约", "evidence", "证据"));
            String did = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", o2.get("id"))), "id");
            service.manage(admin(), "dispute", did, p("reason", "核查无法履约", "outcome", "terminate", "evidence_confirmed", true));
            int relistBefore = notifications(seller(), "归属已核实");
            service.manage(admin(), "relist", s(i, "id"), p("reason", "归属核查" + b));
            drain(seller(), "归属已核实");
            assertEquals(relistBefore + 1, notifications(seller(), "归属已核实"), "第 " + b + " 次核实应通知一次");
        }
        // 同一物品再次异常终止后可再次核实：relist 事件键按每次核实生成的 factId 区分；
        // 该次核实在其通知投递失败、恢复后重试时仍是同一事件，只补投一次。
        var again = service.saveItem(seller(), s(i, "id"), p("title", "第三次异常终止测试", "category", "教材书籍",
                "description", "归属核实后再次提交", "price", "32.00", "image_id", i.get("image_id")));
        service.manage(admin(), "item", s(i, "id"), p("reason", "再次通过", "pass", true, "version", again.get("version")));
        var o3 = service.createOrder(buyer2, p("item_id", i.get("id"), "version", service.item(s(i, "id"), false).get("version")), id());
        act(seller(), o3, "accept");
        service.dispute(buyer2, s(o3, "id"), p("reason", "无法履约", "evidence", "证据"));
        String did3 = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", o3.get("id"))), "id");
        service.manage(admin(), "dispute", did3, p("reason", "核查无法履约", "outcome", "terminate", "evidence_confirmed", true));
        int factsBefore = db.list("select id from audit_facts where object_type='ITEM' and object_id=#{p.i} and action='核实归属'",
                p("i", i.get("id"))).size();
        withNotificationFaultWindow(() -> service.manage(admin(), "relist", s(i, "id"), p("reason", "第三次归属核查")),
                seller(), "归属已核实");
        assertEquals(factsBefore + 1, db.list("select id from audit_facts where object_type='ITEM' and object_id=#{p.i} and action='核实归属'",
                p("i", i.get("id"))).size(), "每次核实追加一条独立处置事实");
    }

    @Test
    @Order(8)
    void businessSqlErrorsAreNotSwallowedByNotificationBoundary() {
        // 对方账户不存在时 eligible(seller) 抛业务异常，订单与占用都不应留下
        var i = onSale();
        String iid = s(i, "id");
        db.exec("update accounts set verified=0 where id=#{p.u}", p("u", seller()));
        try {
            assertThrows(ApiError.class, () -> service.createOrder(buyer(),
                    p("item_id", iid, "version", i.get("version")), id()));
        } finally {
            db.exec("update accounts set verified=1 where id=#{p.u}", p("u", seller()));
        }
        assertEquals(0, db.list("select id from orders where item_id=#{p.i}", p("i", iid)).size());
        assertEquals(0, db.list("select order_id from reservations where item_id=#{p.i}", p("i", iid)).size());
    }

    // ---------------- #6 订单核查视图 ----------------
    @Test
    @Order(9)
    void orderDetailsForPartiesAndGovernanceScopedAdmin() throws Exception {
        var i = onSale();
        var o = order(i);
        act(seller(), o, "accept");
        act(seller(), o, "deliver");
        String oid = s(o, "id");
        var party = om.readTree(mvc.perform(get("/api/orders/" + oid + "/details").session(session("buyer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(oid, party.get("order").get("id").asText());
        assertEquals(seller(), party.get("delivery").get("actor_id").asText());
        assertTrue(party.get("delivery").get("note").asText().contains("回归动作说明"));
        assertTrue(party.get("delivery").get("created_at").asText().endsWith("Z"));
        assertTrue(party.get("reviews").isArray());
        mvc.perform(get("/api/orders/" + oid + "/details").session(session("buyer2"))).andExpect(status().isForbidden());
        // 无关联争议/举报：管理员也看不到
        mvc.perform(get("/api/admin/orders/" + oid + "/details").session(session("admin")))
                .andExpect(status().isBadRequest());
        // 订单举报建立治理关联后可见并留痕
        service.report(buyer(), p("object_type", "ORDER", "object_id", oid, "reason", "交付说明存疑", "evidence", "x"));
        var adm = om.readTree(mvc.perform(get("/api/admin/orders/" + oid + "/details").session(session("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(seller(), adm.get("delivery").get("actor_id").asText());
        assertEquals(1, db.list("select id from audit_facts where object_type='ORDER' and object_id=#{p.o} and action='管理员查看订单核查依据'",
                p("o", oid)).size(), "管理员查看必须留痕");
        // 管理员不能冒用买方确认
        assertThrows(ApiError.class, () -> act(admin(), o, "confirm"));
        // 旧接口保持可用
        mvc.perform(get("/api/orders/" + oid + "/reviews").session(session("seller"))).andExpect(status().isOk());
        mvc.perform(get("/api/orders").session(session("admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @Order(11)
    void adminDetailsThroughDisputeAndMessageAccessStaysScoped() throws Exception {
        var i = onSale();
        var o = order(i);
        String oid = s(o, "id");
        service.dispute(seller(), oid, p("reason", "买方未按时交接", "evidence", "聊天截图"));
        mvc.perform(get("/api/admin/orders/" + oid + "/details").session(session("admin"))).andExpect(status().isOk());
        // 管理员不自动获得订单私聊
        assertThrows(ApiError.class, () -> service.messages(admin(), "ORDER", oid, ""));
        var other = order(onSale());
        mvc.perform(get("/api/admin/orders/" + s(other, "id") + "/details").session(session("admin")))
                .andExpect(status().isBadRequest());
    }

    // ---------------- #7 审计事实 ----------------
    @Test
    @Order(12)
    void auditFactsRecordResultAndVersionWithoutRewritingHistory() {
        // 本类共享一个上下文：断言按本测试的目标对象过滤，不删除/改写既有审计历史
        String uidTarget = uid("buyer2");
        service.manage(admin(), "restrict", uidTarget, p("reason", "核查违规行为", "restricted", true));
        service.manage(admin(), "restrict", uidTarget, p("reason", "核查违规行为", "restricted", false));
        var restrictFacts = db.list("select * from audit_facts where object_type='ACCOUNT' and object_id=#{p.o} and action='账户限制开关' order by created_at",
                p("o", uidTarget));
        assertEquals(2, restrictFacts.size());
        assertEquals("已限制", s(restrictFacts.get(0), "result"));
        assertEquals("已恢复", s(restrictFacts.get(1), "result"));
        assertEquals("0", s(restrictFacts.get(0), "before_value"));
        assertEquals("1", s(restrictFacts.get(0), "after_value"));
        assertEquals("核查违规行为", s(restrictFacts.get(0), "reason"));

        // 直接用待审夹具：v1 退回（不先通过），再提交 v2 并通过；该物品的审核事实只有两条
        var i = pendingItem();
        String iid = s(i, "id");
        service.manage(admin(), "item", iid, p("reason", "图片模糊", "pass", false, "version", 1));
        var edit = service.saveItem(seller(), iid, p("title", "重新提交", "category", "教材书籍", "description", "清晰图片",
                "price", "30.00", "image_id", i.get("image_id")));
        assertEquals(2L, ((Number) edit.get("version")).longValue());
        service.manage(admin(), "item", iid, p("reason", "新版本清晰", "pass", true, "version", edit.get("version")));
        // 以时间为序需要同一秒内稳定；按写入顺序用 created_at+version 排序，并只取本物品的审核事实
        var itemFacts = db.list("select * from audit_facts where object_type='ITEM' and object_id=#{p.i} and action like '审核版本%' order by created_at, version",
                p("i", iid));
        assertEquals(2, itemFacts.size());
        assertEquals(1, n(itemFacts.get(0), "version"));
        assertEquals("退回", s(itemFacts.get(0), "result"));
        assertEquals(2, n(itemFacts.get(1), "version"));
        assertEquals("通过", s(itemFacts.get(1), "result"));
        assertEquals("待审", s(itemFacts.get(1), "before_value"));
        assertEquals("在售", s(itemFacts.get(1), "after_value"));
        // 旧 audit_logs 仍追加，不改写
        assertTrue(db.list("select id from audit_logs where object_id=#{p.i}", p("i", iid)).size() >= 4);
    }

    @Test
    @Order(13)
    void hundredFiftyCharacterDisputeDecisionIsSaved() {
        var i = onSale();
        var o = order(i);
        act(seller(), o, "accept");
        service.dispute(buyer(), s(o, "id"), p("reason", "损坏", "evidence", "照片"));
        String did = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", o.get("id"))),
                "id");
        String reason = "处置".repeat(750);
        assertEquals(1500, reason.length());
        service.manage(admin(), "dispute", did,
                p("reason", reason, "outcome", "terminate", "evidence_confirmed", true));
        assertEquals(reason, s(db.one("select decision from disputes where id=#{p.d}", p("d", did)), "decision"));
        assertEquals(reason, s(db.one("select reason from audit_facts where object_id=#{p.d} and action='争议结案'",
                p("d", did)), "reason"));
    }

    /**
     * 1500 字处置说明下，结案通知经 1000 字截断后仍保留订单/争议标识（完整 id 与短号）和结论：
     * 说明只保留摘要并指向全文，全文仍在 disputes.decision 与处置事实中；摘要以可识别的标记开头便于断言。
     */
    @Test
    @Order(14)
    void longDecisionNotificationKeepsOrderAndDisputeReferenceAfterTruncation() {
        var i = onSale();
        var o = order(i);
        String oid = s(o, "id");
        act(seller(), o, "accept");
        service.dispute(buyer(), oid, p("reason", "交付争议", "evidence", "照片"));
        String did = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", oid)), "id");
        String reason = "摘要起点" + "处置细节".repeat(374);
        assertEquals(1500, reason.length());
        drain(buyer(), "争议已结案");
        drain(seller(), "争议已结案");
        int buyerBefore = notifications(buyer(), "争议已结案"), sellerBefore = notifications(seller(), "争议已结案");
        service.manage(admin(), "dispute", did, p("reason", reason, "outcome", "continue"));
        assertEquals(buyerBefore + 1, notifications(buyer(), "争议已结案"));
        assertEquals(sellerBefore + 1, notifications(seller(), "争议已结案"));
        for (String uid : List.of(buyer(), seller())) {
            var row = db.one("select content from notifications where user_id=#{p.u} and content like '%争议已结案%' "
                    + "order by created_at desc", p("u", uid));
            String msg = s(row, "content");
            assertTrue(msg.contains(oid), "通知必须含完整订单 id：" + msg);
            assertTrue(msg.contains(oid.substring(0, 8)), "通知必须含订单短号：" + msg);
            assertTrue(msg.contains(did), "通知必须含完整争议 id：" + msg);
            assertTrue(msg.contains(did.substring(0, 8)), "通知必须含争议短号：" + msg);
            assertTrue(msg.contains("结论：继续履约"), "通知必须含结论：" + msg);
            assertTrue(msg.contains("摘要起点"), "长说明保留摘要起点：" + msg);
            assertTrue(msg.contains("我的订单") && msg.contains("履约争议"), "摘要须指向全文查看入口：" + msg);
            assertTrue(msg.indexOf(did) < msg.indexOf("说明（摘要）"), "标识在说明之前，截断也不会丢失");
        }
        assertEquals(reason, s(db.one("select decision from disputes where id=#{p.d}", p("d", did)), "decision"),
                "完整原因仍保存在 disputes.decision");
        assertEquals(reason, s(db.one("select reason from audit_facts where object_id=#{p.d} and action='争议结案'",
                p("d", did)), "reason"), "完整原因仍保存在处置事实");
    }

    /** 管理台争议同样附带补证记录 {id,author_id,content,created_at}，与当事人列表一致。 */
    @Test
    @Order(15)
    void dashboardDisputesIncludeEvidenceLogForAdminsOnly() throws Exception {
        var i = onSale();
        var o = order(i);
        String oid = s(o, "id");
        act(seller(), o, "accept");
        service.dispute(buyer(), oid, p("reason", "交付争议", "evidence", "DASH_BASE"));
        String did = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", oid)), "id");
        service.supplement(seller(), did, p("evidence", "DASH_SUPPLEMENT"));
        var body = om.readTree(mvc.perform(get("/api/admin/dashboard").session(session("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (var d : body.get("disputes"))
            assertTrue(d.has("evidence_log") && d.get("evidence_log").isArray(), "每条管理台争议都应附带 evidence_log：" + d.get("id"));
        var matched = dashboardDispute(body, did);
        assertNotNull(matched, "管理台应包含该争议");
        var log = matched.get("evidence_log");
        assertEquals(1, log.size(), "管理台争议应附带该争议的补证记录：" + matched);
        var entry = log.get(0);
        assertEquals(Set.of("id", "author_id", "content", "created_at"), fieldNames(entry));
        assertEquals(seller(), entry.get("author_id").asText());
        assertEquals("DASH_SUPPLEMENT", entry.get("content").asText());
        assertFalse(entry.get("created_at").asText().isBlank());
        assertEquals("DASH_BASE", matched.get("evidence").asText(), "原提交证据仍保留");
        // 当事人列表使用同一辅助方法，内容一致
        var mine = service.disputes(buyer()).stream().filter(d -> did.equals(s(d, "id"))).findFirst().orElseThrow();
        assertEquals(1, ((List<?>) mine.get("evidence_log")).size());
        // 非管理员不能读取管理台
        mvc.perform(get("/api/admin/dashboard").session(session("buyer"))).andExpect(status().isForbidden());
    }

    // ---------------- #8 收藏 ----------------
    @Test
    @Order(16)
    void favoritesAreExplicitAndIdempotent() throws Exception {
        var i = onSale();
        String iid = s(i, "id");
        var session = session("buyer2");
        mvc.perform(post("/api/favorites/" + iid).session(session).header("X-CSRF-Token", "csrf-buyer2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.favorite").value(true));
        mvc.perform(post("/api/favorites/" + iid).session(session).header("X-CSRF-Token", "csrf-buyer2"))
                .andExpect(status().isOk());
        assertEquals(1, db.list("select * from favorites where item_id=#{p.i}", p("i", iid)).size(), "重复加入仍为已收藏");
        mvc.perform(put("/api/favorites/" + iid).session(session).header("X-CSRF-Token", "csrf-buyer2")
                .contentType("application/json").content("{\"favorite\":false}")).andExpect(status().isOk());
        mvc.perform(delete("/api/favorites/" + iid).session(session).header("X-CSRF-Token", "csrf-buyer2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.favorite").value(false));
        mvc.perform(delete("/api/favorites/" + iid).session(session).header("X-CSRF-Token", "csrf-buyer2"))
                .andExpect(status().isOk());
        assertEquals(0, db.list("select * from favorites where item_id=#{p.i}", p("i", iid)).size(), "重复取消仍为未收藏");
        mvc.perform(put("/api/favorites/" + iid).session(session).header("X-CSRF-Token", "csrf-buyer2")
                .contentType("application/json").content("{\"favorite\":true}")).andExpect(status().isOk());
        assertEquals(1, db.list("select * from favorites where item_id=#{p.i}", p("i", iid)).size());
        service.favorite(uid("buyer"), iid);
        assertEquals(2, db.list("select * from favorites where item_id=#{p.i}", p("i", iid)).size(), "旧 favorite 为加入");
    }

    // ---------------- #9 评价与会话上下文 ----------------
    @Test
    @Order(17)
    void reviewsReadableByPartiesAndMessageContextReadOnly() throws Exception {
        var i = onSale();
        var o = order(i);
        String oid = s(o, "id");
        service.message(buyer(), "ORDER", oid, "", p("content", "图书馆交接"));
        act(seller(), o, "accept");
        act(seller(), o, "deliver");
        act(buyer(), o, "confirm");
        var live = om.readTree(mvc.perform(get("/api/messages/context").param("type", "ORDER").param("object", oid)
                .session(session("buyer"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(live.get("readOnly").asBoolean(), "终态订单上下文应只读");
        assertEquals(1, live.get("messages").size());
        mvc.perform(post("/api/messages").param("type", "ORDER").param("object", oid).session(session("buyer"))
                .header("X-CSRF-Token", "csrf-buyer").contentType("application/json").content("{\"content\":\"终态发送\"}"))
                .andExpect(status().isBadRequest());
        service.review(buyer(), oid, p("score", 5, "content", "手机 iPhone13，成色很好，交易顺利"));
        service.review(seller(), oid, p("score", 4, "content", "买方守约"));
        var rows = om.readTree(mvc.perform(get("/api/orders/" + oid + "/reviews").session(session("seller")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(2, rows.size(), "双方评价都应保留可查");
        var authors = new HashSet<String>();
        rows.forEach(r -> authors.add(r.get("author_id").asText()));
        assertEquals(Set.of(buyer(), seller()), authors);
        mvc.perform(get("/api/orders/" + oid + "/reviews").session(session("buyer2"))).andExpect(status().isForbidden());
        var publicReviews = om.readTree(mvc.perform(get("/api/sellers/" + seller() + "/reviews"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(publicReviews.size() >= 1);
        assertEquals(buyer(), publicReviews.get(0).get("author_id").asText());
    }

    @Test
    @Order(18)
    void contactInfoNeverPublicButLegalPhoneMentionsAllowed() throws Exception {
        var i = onSale();
        var o = order(i);
        act(seller(), o, "accept");
        act(seller(), o, "deliver");
        act(buyer(), o, "confirm");
        String oid = s(o, "id");
        for (String bad : List.of("电话 13800138000", "联系 138 0013 8000", "电话13800138000.", "邮箱 ab@cd.com",
                "微信 abc_12345", "QQ：123456789", "tel: 13800138000，工作日白天"))
            assertThrows(ApiError.class, () -> service.review(buyer(), oid, p("score", 5, "content", bad)), bad);
        service.review(buyer(), oid, p("score", 5, "content", "手机 iPhone13，成色很好，小米14备用机可小刀，2024年购"));
        // 历史记录：直接写入含联系方式的公开评价，读取时必须脱敏
        String reviewId = id();
        db.exec("insert into reviews(id,order_id,author_id,target_id,score,content,hidden,created_at) values(#{p.id},#{p.o},#{p.u},#{p.t},5,#{p.c},0,#{p.n})",
                p("id", reviewId, "o", oid, "u", seller(), "t", buyer(), "c", "旧评 13800138000 或 a@b.cn", "n", now()));
        var publicReviews = service.reviews(seller());
        for (var r : publicReviews)
            assertFalse(s(r, "content").contains("13800138000") || s(r, "content").contains("a@b.cn"),
                    "公开评价历史读取须脱敏：" + r.get("content"));
        var mine = service.orderReviews(buyer(), oid);
        assertTrue(mine.stream().anyMatch(r -> s(r, "content").contains("iPhone13")), "合法描述不误拦");
    }

    // ---------------- #10 争议结案通知 ----------------
    @Test
    @Order(19)
    void disputeClosureNotifiesBothPartiesOnceWithOutcomeAndReference() {
        var i = onSale();
        var o = order(i);
        String oid = s(o, "id");
        act(seller(), o, "accept");
        service.dispute(buyer(), oid, p("reason", "交付物品不符", "evidence", "照片"));
        String did = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", oid)), "id");
        int buyerBefore = notifications(buyer(), "争议已结案"), sellerBefore = notifications(seller(), "争议已结案"),
                thirdBefore = notifications(uid("buyer2"), "争议已结案");
        service.manage(admin(), "dispute", did, p("reason", "双方确认可继续履约", "outcome", "continue"));
        assertEquals(buyerBefore + 1, notifications(buyer(), "争议已结案"), "买方收到一次结案通知");
        assertEquals(sellerBefore + 1, notifications(seller(), "争议已结案"), "卖方收到一次结案通知");
        assertEquals(thirdBefore, notifications(uid("buyer2"), "争议已结案"), "第三人收不到");
        var msg = s(db.one("select content from notifications where user_id=#{p.u} and content like '%争议已结案%' order by created_at desc",
                p("u", buyer())), "content");
        assertTrue(msg.contains("继续履约") && msg.contains(oid) && msg.contains(did), "通知含结论与查询标识：" + msg);
        assertThrows(ApiError.class, () -> service.manage(admin(), "dispute", did,
                p("reason", "重复结案", "outcome", "continue")));
        assertEquals(buyerBefore + 1, notifications(buyer(), "争议已结案"), "重复结案不重复通知");

        // terminate 分支同样通知；通知模块不可用时结案照样成立，恢复后补投
        act(seller(), o, "deliver");
        service.dispute(buyer(), oid, p("reason", "损坏无法履约", "evidence", "照片"));
        String did2 = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", oid)), "id");
        // 测试层故障注入：只让写 notifications 的 SQL 失败，结案事务与投递的 REQUIRES_NEW 事务仍真实执行
        TestStoreSpy.failNextNotificationInserts(Integer.MAX_VALUE);
        try {
            service.manage(admin(), "dispute", did2,
                    p("reason", "核查后无法履约", "outcome", "terminate", "evidence_confirmed", true));
            assertEquals("已结案", s(db.one("select status from disputes where id=#{p.d}", p("d", did2)), "status"),
                    "通知失败不影响结案结果");
            assertEquals("已异常终止", s(service.order(buyer(), oid, false), "stage"));
            var failed = db.list("select * from notification_outbox where status='FAILED' "
                    + "and content like '%争议已结案%' and content like #{p.d}", p("d", "%" + did2.substring(0, 8) + "%"));
            assertEquals(2, failed.size(), "双方各一条结案通知，投递失败时都保留为 FAILED（未重复登记）");
        } finally {
            TestStoreSpy.clearNotificationFaults();
            service.notifications().deliverPending();
        }
        assertEquals("已结案", s(db.one("select status from disputes where id=#{p.d}", p("d", did2)), "status"));
        assertEquals("已异常终止", s(service.order(buyer(), oid, false), "stage"));
        drain(seller(), "争议已结案");
        assertEquals(sellerBefore + 2, notifications(seller(), "争议已结案"));
        assertTrue(s(db.one("select content from notifications where user_id=#{p.u} order by created_at desc", p("u", seller())),
                "content").contains("异常终止"));
    }

    // ---------------- #11 统计 ----------------
    @Test
    @Order(20)
    void statisticsComparesRealInstantsNotIsoStrings() throws Exception {
        statisticsScenario();
    }

    void statisticsScenario() throws Exception {
        String seller = seller(), buyer = buyer();
        String itemId = s(db.one("select id from items limit 1", p()), "id");
        // 使用独立的 2025 年时间窗构造历史数据：其他用例产生的完成订单不在该窗口内，不清库、不改产品逻辑。
        // 行：完成时刻（同一时刻的三种写法 + 下界前 1 纳秒 + 下界的 0 位小数 + 上界前 1 纳秒 + 上界处）
        List<String[]> rows = List.of(
                new String[] { "2025-03-10T08:30:00.123456789Z", "32.00" },
                new String[] { "2025-03-10T08:30:00.123Z", "1.10" },
                new String[] { "2025-03-10T16:30:00.123456789+08:00", "0.90" },
                new String[] { "2025-03-10T08:30:00Z", "2.00" },
                new String[] { "2025-03-10T08:29:59.999999999Z", "0.30" },
                new String[] { "2025-03-10T10:00:00.000000001Z", "3.00" },
                new String[] { "2025-03-10T18:00:00+08:00", "7.00" });
        int k = 0;
        for (String[] r : rows) {
            String oid = "legacy-" + (++k) + "-" + id().substring(0, 8);
            db.exec("insert into orders(id,item_id,buyer_id,seller_id,title,description,price,buyer_name,seller_name,item_version,stage,created_at,completed_at) values(#{p.id},#{p.i},#{p.b},#{p.s},#{p.t},'历史',#{p.price},'甲','乙',1,'已完成',#{p.c},#{p.done})",
                    p("id", oid, "i", itemId, "b", buyer, "s", seller, "t", "历史订单" + k, "price", r[1],
                            "c", "2025-03-10T00:00:00Z", "done", r[0]));
        }
        // [08:30:00.000Z, 10:00:00.000Z)：计入 .123456789/.123/+08:00 同刻/.000（等于 from）共 4 单，
        // 合计 32.00+1.10+0.90+2.00 = 36.00；排除 from 前 1ns、to 前 1ns（10:00:00.000000001Z）与 to 处。
        String from = "2025-03-10T08:30:00Z", until = "2025-03-10T10:00:00Z";
        for (String variant : List.of("2025-03-10T08:30:00Z", "2025-03-10T08:30:00.000Z",
                "2025-03-10T16:30:00+08:00", "2025-03-10T08:30:00.000000000Z")) {
            var body = om.readTree(mvc.perform(get("/api/admin/stats").param("from", variant)
                    .param("to", until).session(session("admin")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals(4, body.get("sales").get("count").asInt(), "同一开始时刻写法必须一致：" + variant + " -> " + body);
            assertTrue(body.get("sales").get("amount").decimalValue().compareTo(new BigDecimal("36.00")) == 0,
                    "金额按快照汇总且不丢分：" + variant + " -> " + body);
        }
        // 左闭右开：等于 from 计入；等于 to 排除；to 的 +08:00 写法等价
        for (String toVariant : List.of("2025-03-10T10:00:00Z", "2025-03-10T18:00:00+08:00")) {
            var body = om.readTree(mvc.perform(get("/api/admin/stats").param("from", from).param("to", toVariant)
                    .session(session("admin"))).andReturn().getResponse().getContentAsString());
            assertEquals(4, body.get("sales").get("count").asInt(), "to 边界（不含）写法等价：" + toVariant + " -> " + body);
        }
        // 纳秒边界：16:00:00.000000001Z 不在 [.., 10:00:00Z) 内；从 .000000001Z 起则计入
        var nanosecond = om.readTree(mvc.perform(get("/api/admin/stats").param("from", "2025-03-10T10:00:00.000000001Z")
                .param("to", "2025-03-10T23:00:00Z").session(session("admin"))).andReturn().getResponse()
                .getContentAsString());
        assertEquals(1, nanosecond.get("sales").get("count").asInt(), "纳秒边界：10:00:00.000000001Z 应计入");
        assertTrue(nanosecond.get("sales").get("amount").decimalValue().compareTo(new BigDecimal("3.00")) == 0,
                "纳秒边界金额：" + nanosecond);
        mvc.perform(get("/api/admin/stats").param("from", "2026-11-04 13:55").session(session("admin")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/stats").session(session("buyer"))).andExpect(status().isForbidden());
    }

    // ---------------- 历史回归 ----------------
    @Test
    @Order(21)
    void existingOrderIdempotencyKeepsOldContract() {
        var i = onSale();
        String key = id();
        var first = service.createOrder(buyer(), p("item_id", i.get("id"), "version", i.get("version")), key);
        var replay = service.createOrder(buyer(), p("item_id", i.get("id"), "version", i.get("version")), key);
        assertEquals(first.get("id"), replay.get("id"));
        assertThrows(ApiError.class,
                () -> service.createOrder(buyer(), p("item_id", i.get("id"), "version", 99), key));
        assertEquals(1, db.list("select id from orders where item_id=#{p.i}", p("i", i.get("id"))).size());
    }

    // ---------------- 通知补投的 HTTP 入口（真实 Controller + 真实 Spring 代理） ----------------
    @Test
    @Order(22)
    void notificationEndpointDeliversPendingThroughRealControllerOnProxiedService() throws Exception {
        notificationEndpointScenario();
    }

    void notificationEndpointScenario() throws Exception {
        // @Autowired 的 service 是 Spring 代理；控制器必须经 getter/构造注入拿到真实依赖，不能直接读代理壳字段。
        // 若 CommunityController 重新出现 service.xxx 字段直读（代理壳为 null 且被 catch 吞掉），下列断言失败。
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service), "回归前提：注入的 MarketService 是代理");
        String seller = seller(), buyer = buyer();
        for (String u : List.of(seller, buyer, admin(), uid("buyer2")))
            drainAll(u); // 事务外反复补投，直到没有未发送记录

        // 1) 业务事务已登记、尚未投递（PENDING）的记录在 notifications 中不可见；
        //    真实 Controller 的 GET /api/notifications 必须在本次请求内把它投递出去。
        String pending = "HTTP 补投验证 " + id();
        TestStoreSpy.insertPendingOutbox(db, buyer, pending);
        assertEquals(0, notifications(buyer, pending), "PENDING 记录尚未投递");
        TestStoreSpy.resetProbe();
        var body = om.readTree(mvc.perform(get("/api/notifications").session(session("buyer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(body.isArray());
        assertEquals(1, TestStoreSpy.notificationInsertsOf(pending),
                "GET /api/notifications 必须在真实控制器内补投待发送记录，而不是被吞掉的空指针");
        assertEquals(0, ((Number) db.one("select count(*) as c from notification_outbox where status<>'SENT' and content=#{p.c}",
                p("c", pending)).get("c")).intValue());

        // 2) 故障窗口（测试层 Store 只让写 notifications 的 SQL 失败；等价于真实 RENAME 期间）：
        //    下单业务照常成立，通知记 FAILED；恢复后由下一个 GET 在真实控制器内补投，且已 SENT 不重复。
        String leftover = "HTTP 遗留 PENDING " + id();
        TestStoreSpy.insertPendingOutbox(db, buyer, leftover);
        int sellerBefore = notifications(seller, "收到新订单");
        TestStoreSpy.resetProbe();
        TestStoreSpy.failNextNotificationInserts(Integer.MAX_VALUE);
        try {
            mvc.perform(post("/api/orders").session(session("buyer")).header("X-CSRF-Token", "csrf-buyer")
                    .header("Idempotency-Key", id()).contentType("application/json")
                    .content("{\"item_id\":\"" + onSale().get("id") + "\",\"version\":1}"))
                    .andExpect(status().isOk());
            assertTrue(TestStoreSpy.notificationInsertFailures() >= 2, "故障窗口内投递确实尝试过写 notifications");
            assertEquals(0, ((Number) db.one("select count(*) as c from notification_outbox where status='PENDING'",
                    p()).get("c")).intValue(), "故障期间未发送记录全部转 FAILED，不丢失");
            assertEquals(sellerBefore, notifications(seller, "收到新订单"), "故障期间未写入通知");
        } finally {
            TestStoreSpy.clearNotificationFaults();
        }
        TestStoreSpy.resetProbe();
        mvc.perform(get("/api/notifications").session(session("buyer"))).andExpect(status().isOk());
        assertEquals(1, TestStoreSpy.notificationInsertsOf(leftover), "GET 补投买方的遗留 PENDING");
        mvc.perform(get("/api/notifications").session(session("seller"))).andExpect(status().isOk());
        assertEquals(sellerBefore + 1, notifications(seller, "收到新订单"), "恢复后 GET 补投卖方的订单通知");
        mvc.perform(get("/api/notifications").session(session("seller"))).andExpect(status().isOk());
        assertEquals(sellerBefore + 1, notifications(seller, "收到新订单"), "已 SENT 不重复补投");
        assertEquals(0, ((Number) db.one("select count(*) as c from notification_outbox where status<>'SENT' and user_id in (#{p.s},#{p.b})",
                p("s", seller, "b", buyer)).get("c")).intValue(), "未发送记录应全部清空");
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    // ---------------- 2026-11-06 新 Issue #12～#15（真实 Controller/Service/事务） ----------------

    static String reasonOf(String iid, Store db) {
        return s(db.one("select reason from items where id=#{p.i}", p("i", iid)), "reason");
    }

    /**
     * #14 公开商品查询按字段列出：管理员审核说明不随公开响应下发。
     * 审核说明只写在审核说明里（描述为 demo），游客/普通买方检索均找不到原文；本人仍可读；
     * 管理员仍能查询原审核记录与处置事实。
     */
    @Test
    @Order(30)
    void publicMarketProjectionExcludesReviewNotesForGuestsAndBuyers() throws Exception {
        // 真实待审夹具：发布后未审核；描述只填 demo，测试文本只写进管理员审核说明
        var pending = pendingItem();
        String iid = s(pending, "id");
        assertEquals("待审", s(pending, "status"));
        String probeTitle = "公开投影探测 " + id().substring(0, 8);
        // 与主审 privacy-probe 同形：描述里写入联系方式（含正常型号 “iPhone13” 不应被误伤）
        String leadDescription = "联系 13800138000 或 test@example.com";
        String cleanDescription = "iPhone13 备用机，成色很好，2024年购";
        service.saveItem(seller(), iid, p("title", probeTitle, "category", "教材书籍",
                "description", leadDescription, "price", "32.00", "image_id", pending.get("image_id")));
        var toApprove = service.item(iid, false);
        String note = "REVIEW_ONLY_STUDENT_NO_SYNTH_" + id().replace("-", "").substring(0, 8);
        service.manage(admin(), "item", iid, p("reason", "审核通过 " + note, "pass", true, "version", toApprove.get("version")));
        String marked = reasonOf(iid, db);
        assertTrue(marked.contains(note), "审核说明确实保存了测试文本");
        assertEquals("在售", s(service.item(iid, false), "status"));
        String hiddenProbe = s(toApprove, "title");

        var guest = mvc.perform(get("/api/items").param("q", hiddenProbe)).andExpect(status().isOk()).andReturn();
        var guestBody = om.readTree(guest.getResponse().getContentAsString());
        assertEquals(1, guestBody.size(), "游客应能检索到该公开物品");
        var row = guestBody.get(0);
        assertEquals(Set.of("id", "seller_id", "title", "category", "description", "price", "image_id", "version",
                "status", "created_at", "seller_name"), fieldNames(row), "公开商品只返回展示字段：不返回 reason 等治理字段");
        assertFalse(row.has("reason"), "公开响应不含审核说明字段");
        assertFalse(guest.getResponse().getContentAsString().contains(note), "游客响应中找不到审核说明原文");
        assertEquals(iid, row.get("id").asText());
        assertEquals("在售", row.get("status").asText());
        // BR14：公开描述中的联系方式按 ImageGuard.mask 脱敏（历史已存内容同样受保护），价格/ID 不变
        assertFalse(row.get("description").asText().contains("13800138000"), "公开描述不得原样返回手机号");
        assertFalse(row.get("description").asText().contains("test@example.com"), "公开描述不得原样返回邮箱");
        assertTrue(row.get("description").asText().contains("[联系方式已隐藏]"), "替换为占位说明：" + row.get("description"));
        assertEquals(0, row.get("price").decimalValue().compareTo(new BigDecimal("32.00")), "价格不改写");
        assertEquals(iid, row.get("id").asText(), "ID 不改写");
        assertEquals(probeTitle, row.get("title").asText(), "不含联系方式的标题原样返回");
        assertEquals(leadDescription, s(service.item(iid, false), "description"), "库中原文不被改写（不存在读写回写）");

        // 正常型号/年份不被误伤：仅含 “iPhone13”“2024年购” 的描述原样公开
        var cleanPending = pendingItem();
        String cleanId = s(cleanPending, "id");
        service.saveItem(seller(), cleanId, p("title", "iPhone13 备用机", "category", "数码配件", "description",
                cleanDescription, "price", "999.00", "image_id", cleanPending.get("image_id")));
        var clean = service.item(cleanId, false);
        service.manage(admin(), "item", cleanId, p("reason", "正常型号", "pass", true, "version", clean.get("version")));
        var cleanBody = om.readTree(mvc.perform(get("/api/items").param("q", "iPhone13")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var cleanRow = StreamSupport.stream(cleanBody.spliterator(), false)
                .filter(r -> cleanId.equals(r.get("id").asText())).findFirst().orElseThrow();
        assertEquals(cleanDescription, cleanRow.get("description").asText(), "正常型号与年份不被误伤");
        assertEquals("iPhone13 备用机", cleanRow.get("title").asText());

        var buyerBody = mvc.perform(get("/api/items").param("q", hiddenProbe).session(session("buyer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(buyerBody.contains(note), "普通买方检索响应同样找不到审核说明");
        assertFalse(buyerBody.contains("13800138000") || buyerBody.contains("test@example.com"),
                "普通买方检索响应同样不原样返回联系方式");
        var all = mvc.perform(get("/api/items")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(all.contains(note), "未带检索词的公开列表也不含审核说明");

        // 本人与管理端各自按权限读取说明
        var own = om.readTree(mvc.perform(get("/api/items/mine").session(session("seller"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var ownRow = StreamSupport.stream(own.spliterator(), false).filter(r -> iid.equals(r.get("id").asText()))
                .findFirst().orElse(null);
        assertNotNull(ownRow, "物主列表应包含该物品");
        assertTrue(ownRow.get("reason").asText().contains(note), "物主仍可读取本人的审核说明");
        assertEquals(leadDescription, ownRow.get("description").asText(), "物主读取本人描述保持原文（不脱敏）");
        String mineRaw = mvc.perform(get("/api/items/mine").session(session("seller"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(mineRaw.contains("13800138000"), "物主「我的发布」保留原文联系方式");
        var dash = mvc.perform(get("/api/admin/dashboard").session(session("admin"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(dash.contains(note), "管理员仍能查询原审核记录");
        assertTrue(dash.contains("13800138000") && dash.contains("test@example.com"),
                "管理台审核读取描述保持原文（不脱敏），便于核查");
        assertEquals(1, db.list("select id from audit_facts where object_type='ITEM' and object_id=#{p.i} and reason like #{p.n}",
                p("i", iid, "n", "%" + note + "%")).size(), "审核处置事实保留原说明");
    }

    /**
     * #12 收藏不可访问条目：保留收藏关系，只返回 {id,unavailable:true}，不露出待审/隐藏/受限/撤回后的内容。
     * 新版本通过审核恢复公开后展示最新内容；期间仍可取消收藏。
     */
    @Test
    @Order(31)
    void favoritesHideContentsOfInaccessibleItemsAndRecoverAfterApproval() throws Exception {
        String collector = registered("favuser");
        var session = sessionOf(collector);
        String csrf = "csrf-" + collector;
        var i = onSale();
        String iid = s(i, "id");
        mvc.perform(post("/api/favorites/" + iid).session(session).header("X-CSRF-Token", csrf)).andExpect(status().isOk());
        // 另一件收藏：标题里含联系方式（合法标题长度内），可用收藏的标题按公开规则脱敏（BR14）
        var contactItem = onSale();
        String contactId = s(contactItem, "id");
        var titled = service.saveItem(seller(), contactId, p("title", "台灯 微信 abc_12345 联系", "category", "宿舍生活",
                "description", "正常描述", "price", "39.00", "image_id", contactItem.get("image_id")));
        service.manage(admin(), "item", contactId, p("reason", "标题含联系方式", "pass", true, "version", titled.get("version")));
        mvc.perform(post("/api/favorites/" + contactId).session(session).header("X-CSRF-Token", csrf)).andExpect(status().isOk());

        com.fasterxml.jackson.databind.JsonNode favOf = om.readTree(mvc.perform(get("/api/favorites").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var visible = StreamSupport.stream(favOf.spliterator(), false).filter(r -> iid.equals(r.get("id").asText()))
                .findFirst().orElseThrow();
        assertEquals(s(i, "title"), visible.get("title").asText());
        assertEquals(false, visible.get("unavailable").asBoolean());
        var contactRow = StreamSupport.stream(favOf.spliterator(), false).filter(r -> contactId.equals(r.get("id").asText()))
                .findFirst().orElseThrow();
        assertEquals(false, contactRow.get("unavailable").asBoolean());
        assertFalse(contactRow.get("title").asText().contains("abc_12345"), "可用收藏标题不得原样返回社交账号");
        assertTrue(contactRow.get("title").asText().contains("台灯"), "标题正文保留：" + contactRow.get("title"));
        assertEquals(0, contactRow.get("price").decimalValue().compareTo(new BigDecimal("39.00")), "收藏价格不改写");
        assertEquals("台灯 微信 abc_12345 联系", s(service.item(contactId, false), "title"), "库中标题原文不被改写");

        // 卖方修改并重新提交（未审核）：原收藏者不得看到新标题/新价格
        String newTitle = "NOT-APPROVED-REVIEW-" + id().substring(0, 8);
        var edited = service.saveItem(seller(), iid, p("title", newTitle, "category", "教材书籍", "description",
                "修改后等待审核", "price", "123.45", "image_id", i.get("image_id")));
        favOf = om.readTree(mvc.perform(get("/api/favorites").session(session)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        String raw = favOf.toString();
        assertFalse(raw.contains(newTitle), "待审新标题不得出现在收藏列表");
        assertFalse(raw.contains("123.45"), "待审新价格不得出现在收藏列表");
        assertFalse(raw.contains("待审"), "收藏列表不得暴露待审状态");
        var hidden = StreamSupport.stream(favOf.spliterator(), false).filter(r -> iid.equals(r.get("id").asText()))
                .findFirst().orElseThrow();
        assertEquals(Set.of("id", "unavailable"), fieldNames(hidden), "不可访问时只返回 id + unavailable 占位");
        assertTrue(hidden.get("unavailable").asBoolean());
        // 公开市场已看不到该物品；本人列表仍可看到完整内容（物主权限）
        assertEquals(0, om.readTree(mvc.perform(get("/api/items").param("q", newTitle)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).size(), "待审商品已从市场消失");

        // 管理员退回：说明与状态也不能从收藏列表泄露
        service.manage(admin(), "item", iid, p("reason", "退回说明仅治理可见", "pass", false, "version", edited.get("version")));
        raw = mvc.perform(get("/api/favorites").session(session)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertFalse(raw.contains("退回说明仅治理可见") || raw.contains("审核退回"));

        // 恢复公开前仍可取消收藏；重新加入也只能得到占位（加入要求物品可访问，这里直接验证取消）
        mvc.perform(delete("/api/favorites/" + iid).session(session).header("X-CSRF-Token", csrf)).andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(false));
        assertEquals(0, db.list("select * from favorites where user_id=#{p.u} and item_id=#{p.i}", p("u", collector, "i", iid)).size(),
                "不可访问期间可取消收藏");
        // 重新收藏需要恢复在售
        var again = service.saveItem(seller(), iid, p("title", newTitle, "category", "教材书籍", "description",
                "再次提交", "price", "123.45", "image_id", i.get("image_id")));
        mvc.perform(post("/api/favorites/" + iid).session(session).header("X-CSRF-Token", csrf)).andExpect(status().isBadRequest());
        service.manage(admin(), "item", iid, p("reason", "新版本通过", "pass", true, "version", again.get("version")));
        mvc.perform(post("/api/favorites/" + iid).session(session).header("X-CSRF-Token", csrf)).andExpect(status().isOk());
        favOf = om.readTree(mvc.perform(get("/api/favorites").session(session)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        var restored = StreamSupport.stream(favOf.spliterator(), false).filter(r -> iid.equals(r.get("id").asText()))
                .findFirst().orElseThrow();
        assertEquals(newTitle, restored.get("title").asText(), "恢复公开后展示最新内容");
        assertEquals(false, restored.get("unavailable").asBoolean());
        assertFalse(favOf.toString().contains(reasonOf(iid, db) == null ? "\u0000" : reasonOf(iid, db)),
                "收藏列表不含审核说明");

        // 隐藏 / 物主受限：独立标记同样只显示占位
        service.manage(admin(), "hide", iid, p("reason", "独立隐藏", "hidden", true));
        raw = mvc.perform(get("/api/favorites").session(session)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertFalse(raw.contains(newTitle), "隐藏后不得露出标题");
        service.manage(admin(), "hide", iid, p("reason", "解除隐藏", "hidden", false));
        service.manage(admin(), "restrict", seller(), p("reason", "临时限制", "restricted", true));
        try {
            raw = mvc.perform(get("/api/favorites").session(session)).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            assertFalse(raw.contains(newTitle), "物主受限后不得露出标题");
        } finally {
            service.manage(admin(), "restrict", seller(), p("reason", "解除限制", "restricted", false));
        }
        // 撤下：卖方先撤下，再修改会保留“已撤下”前的版本？撤下物品状态为已撤下，收藏者只看到占位；可取消
        service.withdraw(seller(), iid);
        favOf = om.readTree(mvc.perform(get("/api/favorites").session(session)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        var withdrawn = StreamSupport.stream(favOf.spliterator(), false).filter(r -> iid.equals(r.get("id").asText()))
                .findFirst().orElseThrow();
        assertEquals(Set.of("id", "unavailable"), fieldNames(withdrawn), "撤回后只返回占位");
        assertFalse(favOf.toString().contains("已撤下"));
        mvc.perform(delete("/api/favorites/" + iid).session(session).header("X-CSRF-Token", csrf)).andExpect(status().isOk());
        // 他人收藏不受影响：别的用户没有关系
        assertTrue(service.favorites(buyer()).stream().noneMatch(r -> iid.equals(s(r, "id"))));
        mvc.perform(get("/api/favorites")).andExpect(status().isUnauthorized());
    }

    /** 注册一个已通过认证的新账户（真实 register + 管理员认证），用于不污染公共夹具。 */
    String registered(String prefix) {
        String name = prefix + id().replace("-", "").substring(0, 10);
        String u = s(service.register(p("username", name, "password", "BranchPass123!", "nickname", prefix + "用户")), "id");
        String aid = id();
        db.exec("insert into assets(id,owner_id,kind,media_type,created_at) values(#{p.id},#{p.u},'VERIFY','image/png',#{p.t})",
                p("id", aid, "u", u, "t", now()));
        service.verify(u, p("student_no", "T-" + id(), "real_name", "测试", "asset_id", aid));
        service.manage(admin(), "verification", s(service.verifications(u).get(0), "id"),
                p("reason", "回归认证通过", "pass", true));
        return u;
    }

    /**
     * #13 注册密码保留原文（首尾空格属于凭据）：注册成功即可用原样密码登录；
     * 去空格后的写法不能登录；不 trim，字符串与 UTF-8 字节限制在注册时明确拒绝而非静默改写。
     */
    @Test
    @Order(32)
    void registrationKeepsPasswordVerbatimAndLoginMatches() throws Exception {
        String name = "trimpw" + id().replace("-", "").substring(0, 10);
        String password = " BranchPass123! ";
        assertEquals(16, password.length());
        var res = mvc.perform(get("/api/me")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) res.getRequest().getSession();
        String csrf = (String) session.getAttribute("csrf");
        mvc.perform(post("/api/auth/register").session(session).header("X-CSRF-Token", csrf)
                .contentType("application/json")
                .content(om.writeValueAsString(p("username", name, "password", password, "nickname", "空格密码"))))
                .andExpect(status().isOk());
        // 退出后开新会话，用原样 16 字符密码登录
        var fresh = freshSession();
        String freshCsrf = (String) fresh.getAttribute("csrf");
        mvc.perform(post("/api/auth/login").session(fresh).header("X-CSRF-Token", freshCsrf)
                .contentType("application/json").content(om.writeValueAsString(p("username", name, "password", "BranchPass123!"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").session(fresh).header("X-CSRF-Token", freshCsrf).contentType("application/json")
                .content(om.writeValueAsString(p("username", name, "password", password)))).andExpect(status().isOk());
        // 登录端同样不做 trim：仅一侧有空格的写法失败（CSRF 缺失时先被拒绝 403，不触达登录逻辑）
        mvc.perform(post("/api/auth/login").header("X-CSRF-Token", "csrf-fresh")
                .contentType("application/json")
                .content(om.writeValueAsString(p("username", name, "password", " BranchPass123!")))).andExpect(status().isForbidden());
        var again = freshSession();
        mvc.perform(post("/api/auth/login").session(again)
                .header("X-CSRF-Token", (String) again.getAttribute("csrf")).contentType("application/json")
                .content(om.writeValueAsString(p("username", name, "password", " BranchPass123!")))).andExpect(status().isUnauthorized());
        // 过短（7 个字符）、超过 72 字节、全空白的密码在注册时明确拒绝（不注册、不改写）；
        // “  abc123  ”按原文计 10 个字符（若被 trim 则不足 8 位），应被接受且能用原样登录（见下方往返登录）
        String shortName = "sp" + id().replace("-", "").substring(0, 9);
        String longPassword = "密".repeat(25); // 75 字节，25 个字符
        for (String bad : List.of("Abc1234", longPassword, "        ")) {
            var reg = freshSession();
            mvc.perform(post("/api/auth/register").session(reg).header("X-CSRF-Token", (String) reg.getAttribute("csrf"))
                    .contentType("application/json")
                    .content(om.writeValueAsString(p("username", shortName, "password", bad, "nickname", "不合规密码"))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("密码")));
        }
        assertEquals(0, db.list("select id from accounts where username=#{p.u}", p("u", shortName)).size(), "被拒绝的注册不建账户");
        String paddedName = "pad" + id().replace("-", "").substring(0, 8);
        var pad = freshSession();
        mvc.perform(post("/api/auth/register").session(pad).header("X-CSRF-Token", (String) pad.getAttribute("csrf"))
                .contentType("application/json")
                .content(om.writeValueAsString(p("username", paddedName, "password", "  abc123  ", "nickname", "两端空格"))))
                .andExpect(status().isOk());
        var padLogin = freshSession();
        String padCsrf = (String) padLogin.getAttribute("csrf");
        mvc.perform(post("/api/auth/login").session(padLogin).header("X-CSRF-Token", padCsrf).contentType("application/json")
                .content(om.writeValueAsString(p("username", paddedName, "password", "abc123")))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").header("X-CSRF-Token", "not-a-session-token")
                .contentType("application/json")
                .content(om.writeValueAsString(p("username", paddedName, "password", "  abc123  ")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").session(padLogin).header("X-CSRF-Token", padCsrf).contentType("application/json")
                .content(om.writeValueAsString(p("username", paddedName, "password", "  abc123  ")))).andExpect(status().isOk());
        // 非字符串密码明确拒绝
        assertThrows(ApiError.class, () -> service.register(p("username", shortName, "password", 12345678, "nickname", "x")));
        // 恰好 72 字节（24 个中文字符）可注册并可用原样登录
        String edge = "密".repeat(24);
        assertEquals(72, edge.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        String edgeName = "edge" + id().replace("-", "").substring(0, 8);
        service.register(p("username", edgeName, "password", edge, "nickname", "边界"));
        var edgeSession = freshSession();
        mvc.perform(post("/api/auth/login").session(edgeSession).header("X-CSRF-Token", (String) edgeSession.getAttribute("csrf"))
                .contentType("application/json")
                .content(om.writeValueAsString(p("username", edgeName, "password", edge)))).andExpect(status().isOk());
    }

    /**
     * #15 物品咨询双方统一可用性：下单后原咨询对双方都只读，双方发送都被拒绝且消息条数不增加；
     * 有效订单的专用会话仍可正常沟通；未发起咨询的第三人不能进入，未经咨询卖方不能新开会话。
     */
    @Test
    @Order(33)
    void itemInquiryBecomesReadOnlyForBothPartiesAfterOrder() throws Exception {
        var i = onSale();
        String iid = s(i, "id");
        // 买方首次咨询：前端默认 peer 为空字符串，咨询人由认证 uid 确定
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).param("peer", "")
                .session(session("buyer")).header("X-CSRF-Token", "csrf-buyer").contentType("application/json")
                .content("{\"content\":\"还在吗？\"}")).andExpect(status().isOk());
        // 再次发送：空 peer 下仍写入“本人↔卖方”这一条会话
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).session(session("buyer"))
                .header("X-CSRF-Token", "csrf-buyer").contentType("application/json").content("{\"content\":\"可以今天取吗\"}"))
                .andExpect(status().isOk());
        // 读取上下文（空 peer）：可写，且消息只属于本人与卖方
        var buyerCtx = om.readTree(mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid)
                .param("peer", "").session(session("buyer"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertEquals(false, buyerCtx.get("readOnly").asBoolean(), "在售期间咨询可写");
        assertEquals(2, buyerCtx.get("messages").size(), "买方首次/再次发送都落在本会话");
        // 另一个买方同样用空 peer 首次咨询：形成独立会话，读不到买方的消息
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).session(session("buyer2"))
                .header("X-CSRF-Token", "csrf-buyer2").contentType("application/json").content("{\"content\":\"买方乙的咨询\"}"))
                .andExpect(status().isOk());
        var buyer2Ctx = om.readTree(mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid)
                .session(session("buyer2"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, buyer2Ctx.get("messages").size(), "买方乙只能读到自己的咨询");
        assertEquals("买方乙的咨询", buyer2Ctx.get("messages").get(0).get("content").asText());
        // 非空他人 peer 明确拒绝，不能借参数读他人会话
        mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid).param("peer", buyer())
                .session(session("buyer2"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).param("peer", buyer())
                .session(session("buyer2")).header("X-CSRF-Token", "csrf-buyer2").contentType("application/json")
                .content("{\"content\":\"借参数发到他人会话\"}")).andExpect(status().isBadRequest());
        // 卖方回复：必须提交真实咨询人的 peer，回复写入该咨询人的会话
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).param("peer", buyer())
                .session(session("seller")).header("X-CSRF-Token", "csrf-seller").contentType("application/json")
                .content("{\"content\":\"在的\"}")).andExpect(status().isOk());
        var sellerReply = om.readTree(mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid)
                .param("peer", buyer()).session(session("buyer"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertEquals(3, sellerReply.get("messages").size(), "卖方回复进入买方会话");
        assertEquals(buyer(), sellerReply.get("messages").get(2).get("receiver_id").asText());
        // 卖方空 peer 明确拒绝（不能凭空建立会话）
        mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid).param("peer", "")
                .session(session("seller"))).andExpect(status().isBadRequest());
        var buyer2After = om.readTree(mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid)
                .session(session("buyer2"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, buyer2After.get("messages").size(), "卖方回复不串入买方乙的会话");
        int before = db.list("select id from messages where context_type='ITEM' and context_id=#{p.i}", p("i", iid)).size();
        assertEquals(4, before);

        var o = order(i);
        String oid = s(o, "id");
        // 下单后双方读取原咨询：都只读、历史可读（买方空 peer，卖方给真实咨询人）
        var buyerItem = mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid)
                .session(session("buyer"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(om.readTree(buyerItem).get("readOnly").asBoolean(), "买方读取原咨询应为只读");
        assertEquals(3, om.readTree(buyerItem).get("messages").size(), "买方仍可读取本会话历史");
        var sellerItem = om.readTree(mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid)
                .param("peer", buyer()).session(session("seller"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertEquals(true, sellerItem.get("readOnly").asBoolean(), "卖方读取原咨询应为只读");
        assertEquals(3, sellerItem.get("messages").size(), "卖方仍可读取历史消息");
        // 双方发送都被拒绝
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).param("peer", buyer())
                .session(session("seller")).header("X-CSRF-Token", "csrf-seller").contentType("application/json")
                .content("{\"content\":\"卖方下单后发送\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).param("peer", buyer())
                .session(session("buyer")).header("X-CSRF-Token", "csrf-buyer").contentType("application/json")
                .content("{\"content\":\"买方下单后发送\"}")).andExpect(status().isBadRequest());
        assertEquals(before, db.list("select id from messages where context_type='ITEM' and context_id=#{p.i}", p("i", iid)).size(),
                "双方发送被拒绝后消息条数不增加");

        // 有效订单专用会话仍可双向沟通
        mvc.perform(post("/api/messages").param("type", "ORDER").param("object", oid).session(session("buyer"))
                .header("X-CSRF-Token", "csrf-buyer").contentType("application/json").content("{\"content\":\"订单会话买方\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/messages").param("type", "ORDER").param("object", oid).session(session("seller"))
                .header("X-CSRF-Token", "csrf-seller").contentType("application/json").content("{\"content\":\"订单会话卖方\"}"))
                .andExpect(status().isOk());
        var orderCtx = om.readTree(mvc.perform(get("/api/messages/context").param("type", "ORDER").param("object", oid)
                .session(session("seller"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(false, orderCtx.get("readOnly").asBoolean());
        assertEquals(2, orderCtx.get("messages").size());

        // 参与者权限不放松：未发起过咨询的第三人（新人）不能进入、不能发送；
        // 卖方不能凭从未咨询过的用户 id 建立会话
        String newcomer = registered("stranger");
        mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid).session(sessionOf(newcomer)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).session(sessionOf(newcomer))
                .header("X-CSRF-Token", "csrf-" + newcomer).contentType("application/json")
                .content("{\"content\":\"新人也来插话\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).param("peer", newcomer)
                .session(session("seller")).header("X-CSRF-Token", "csrf-seller").contentType("application/json")
                .content("{\"content\":\"卖方主动新开\"}")).andExpect(status().isBadRequest());
        // 卖方给买方乙的 peer 能读到买方乙自己的会话（下单后只读），不会串到买方的会话
        String buyer2 = uid("buyer2");
        mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid).param("peer", buyer2)
                .session(session("seller"))).andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.readOnly").value(true));
        // 订单专用会话第三人同样不能进入
        mvc.perform(get("/api/messages/context").param("type", "ORDER").param("object", oid).session(session("buyer2")))
                .andExpect(status().isForbidden());

        // 订单结束后原咨询仍只读
        act(seller(), o, "accept");
        act(seller(), o, "deliver");
        act(buyer(), o, "confirm");
        mvc.perform(post("/api/messages").param("type", "ITEM").param("object", iid).param("peer", buyer())
                .session(session("seller")).header("X-CSRF-Token", "csrf-seller").contentType("application/json")
                .content("{\"content\":\"完成后发送\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid).param("peer", buyer())
                .session(session("seller"))).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
        mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid).session(session("buyer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.messages.length()").value(3));
    }

    /**
     * #15 并发边界：卖方发送线程在锁前读取（物品仍可写）后被测试层闸门挂起，期间买方完成真实下单并提交；
     * 放行后发送继续按统一顺序加锁，并以锁后的最新状态判定，发送被拒绝、消息条数不增加。
     */
    @Test
    @Order(34)
    void sellerInquirySendRaceWithOrderIsRejectedAtLockedRecheck() throws Exception {
        var i = onSale();
        String iid = s(i, "id");
        service.message(buyer(), "ITEM", iid, "", p("content", "并发前咨询"));
        service.message(seller(), "ITEM", iid, buyer(), p("content", "并发前卖方回复"));
        int before = db.list("select id from messages where context_type='ITEM' and context_id=#{p.i}", p("i", iid)).size();
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        TestStoreSpy.pauseAfterItemHintRead(iid, arrived, release);
        try {
            Future<String> job = pool.submit(() -> {
                try {
                    service.message(seller(), "ITEM", iid, buyer(), p("content", "并发中卖方发送"));
                    return "";
                } catch (ApiError e) {
                    return e.getMessage();
                }
            });
            assertTrue(arrived.await(20, TimeUnit.SECONDS), "卖方发送线程应完成锁前读取");
            assertTrue(TestStoreSpy.itemReadPaused(iid), "确定性时序：发送线程确实在锁前读取后被测试层闸门挂起");
            assertEquals(before, db.list("select id from messages where context_type='ITEM' and context_id=#{p.i}",
                    p("i", iid)).size(), "挂起期间尚未写入消息");
            // 挂起期间买方真实下单并提交（物品状态变为 交易中 并持有占用）
            var o = order(i);
            assertEquals("交易中", s(service.item(iid, false), "status"));
            release.countDown();
            String err = job.get(20, TimeUnit.SECONDS);
            assertFalse(err.isEmpty(), "锁后重新判定应拒绝发送，实际未被拒绝");
            assertTrue(err.contains("订单") || err.contains("不可公开咨询"), "拒绝原因应指向订单会话：" + err);
            assertEquals(1, db.list("select id from orders where item_id=#{p.i}", p("i", iid)).size());
            assertEquals("待卖方确认", s(o, "stage"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            release.countDown();
            TestStoreSpy.clearPauses();
            pool.shutdownNow();
        }
        assertEquals(before, db.list("select id from messages where context_type='ITEM' and context_id=#{p.i}", p("i", iid)).size(),
                "并发下单后卖方发送被拒绝，消息条数不增加");
        var ctx = om.readTree(mvc.perform(get("/api/messages/context").param("type", "ITEM").param("object", iid)
                .param("peer", buyer()).session(session("seller"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertEquals(true, ctx.get("readOnly").asBoolean());
    }

    /**
     * 自查：同类公开/私有读取投影与物品进入不可访问状态后的咨询边界。
     * - 他人撤回后的物品（存量历史咨询）双方只读且不可再发送，历史按原文仍可读；
     * - 物主「我的发布」/管理员管理台仍返回完整记录（含 reason），公开检索不返回。
     */
    @Test
    @Order(35)
    void unavailableInquiryStaysScopedAndOwnerRecordsRemainComplete() throws Exception {
        var i = onSale();
        String iid = s(i, "id");
        // 撤回后的物品：原咨询双方只读、发送被拒绝、历史按原文可读（即时消息不脱敏）
        var j = onSale();
        String jid = s(j, "id");
        service.message(buyer(), "ITEM", jid, "", p("content", "撤回前咨询"));
        service.withdraw(seller(), jid);
        assertTrue(Boolean.TRUE.equals(service.context(buyer(), "ITEM", jid, "").get("readOnly")));
        assertTrue(Boolean.TRUE.equals(service.context(seller(), "ITEM", jid, buyer()).get("readOnly")));
        assertEquals(1, ((List<?>) service.context(seller(), "ITEM", jid, buyer()).get("messages")).size());
        assertEquals("撤回前咨询", s((Map<String, Object>) ((List<?>) service.context(seller(), "ITEM", jid, buyer())
                .get("messages")).get(0), "content"), "历史消息保持原文");
        assertThrows(ApiError.class, () -> service.message(seller(), "ITEM", jid, buyer(), p("content", "撤回后发送")));
        assertThrows(ApiError.class, () -> service.message(buyer(), "ITEM", jid, "", p("content", "撤回后发送")));

        // 物主与管理台的完整记录仍含审核说明（权限各自保留）
        String reason = reasonOf(iid, db);
        assertNotNull(reason);
        var mine = service.mine(seller()).stream().filter(r -> iid.equals(s(r, "id"))).findFirst().orElseThrow();
        assertEquals(reason, s(mine, "reason"));
        var dash = service.dashboard(admin());
        var dashRow = ((List<Map<String, Object>>) dash.get("items")).stream().filter(r -> iid.equals(s(r, "id")))
                .findFirst().orElseThrow();
        assertEquals(reason, s(dashRow, "reason"));
        assertThrows(ApiError.class, () -> service.dashboard(buyer()));
    }

    @Test
    @Order(23)
    void healthStillReportsSchemaAndNoPendingOutboxRemains() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        for (int k = 0; k < 10; k++)
            service.notifications().deliverPending();
        var pending = jdbc.queryForMap("select count(*) as c from notification_outbox where status<>'SENT'");
        assertEquals(0L, ((Number) pending.get("c")).longValue(), "正常流程待投递记录应全部发送");
        assertEquals(0L, ((Number) jdbc.queryForMap("select count(*) as c from notifications n where not exists "
                + "(select 1 from notification_outbox o where o.id=n.id)").get("c")).longValue(),
                "notifications 只由已投递的待投递记录写入");
    }
}
