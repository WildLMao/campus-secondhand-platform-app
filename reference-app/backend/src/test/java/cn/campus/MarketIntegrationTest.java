package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.upload-dir=./target/test-uploads", "app.seed=true" })
@AutoConfigureMockMvc
class MarketIntegrationTest {
    @Autowired
    MarketService service;
    @Autowired
    Store db;
    @Autowired
    MockMvc mvc;

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

    Map<String, Object> fresh() {
        String asset = s(
                db.one("select id from assets where owner_id=#{p.u} and kind='ITEM' limit 1", p("u", seller())), "id");
        var i = service.saveItem(seller(), null, p("title", "独立测试物品 " + id(), "category", "教材书籍", "description",
                "测试用，非真实交易。", "price", "25.00", "image_id", asset));
        service.manage(admin(), "item", s(i, "id"), p("reason", "核对当前版本通过", "pass", true, "version", i.get("version")));
        return service.item(s(i, "id"), false);
    }

    Map<String, Object> buy(Map<String, Object> i, String who, String key) {
        return service.createOrder(who, p("item_id", i.get("id"), "version", i.get("version")), key);
    }

    Map<String, Object> act(String who, Map<String, Object> o, String action) {
        return service.action(who, s(o, "id"), action, p("reason", "测试动作说明", "declared", true), id());
    }

    @Test
    void closedLoopAndImmutableSnapshot() {
        var i = fresh();
        var o = buy(i, buyer(), id());
        assertThrows(ApiError.class, () -> act(admin(), o, "confirm"));
        assertThrows(ApiError.class, () -> act(buyer(), o, "confirm"));
        act(seller(), o, "accept");
        assertEquals("待买方确认", s(act(seller(), o, "deliver"), "stage"));
        assertNull(service.order(buyer(), s(o, "id"), false).get("completed_at"));
        var done = act(buyer(), o, "confirm");
        assertEquals("已完成", s(done, "stage"));
        assertEquals("已成交", s(service.item(s(i, "id"), false), "status"));
        assertEquals(0, db.list("select * from reservations where item_id=#{p.i}", p("i", i.get("id"))).size());
        assertEquals("25.00", done.get("price").toString());
        assertThrows(ApiError.class, () -> act(buyer(), o, "confirm"));
    }

    @Test
    void competingBuyersHaveExactlyOneWinner() throws Exception {
        var i = fresh();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var success = new AtomicInteger();
        List<Future<?>> jobs = new ArrayList<>();
        for (String u : List.of(buyer(), uid("buyer2")))
            jobs.add(pool.submit(() -> {
                try {
                    start.await();
                    buy(i, u, id());
                    success.incrementAndGet();
                } catch (ApiError e) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        start.countDown();
        try {
            for (var f : jobs)
                f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get());
        assertEquals(1, db.list("select * from orders where item_id=#{p.i}", p("i", i.get("id"))).size());
        assertEquals(1, db.list("select * from reservations where item_id=#{p.i}", p("i", i.get("id"))).size());
    }

    @Test
    void replayReturnsOriginalAfterTerminalState() {
        var i = fresh();
        String key = id();
        var o = buy(i, buyer(), key);
        act(buyer(), o, "cancel");
        var replay = buy(i, buyer(), key);
        assertEquals(o.get("id"), replay.get("id"));
        assertEquals("已取消", s(replay, "stage"));
        assertThrows(ApiError.class,
                () -> service.createOrder(buyer(), p("item_id", i.get("id"), "version", 999), key));
    }

    @Test
    void cancelDeliveryRaceIsConsistent() throws Exception {
        var i = fresh();
        var o = buy(i, buyer(), id());
        act(seller(), o, "accept");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var success = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (String a : List.of("cancel", "deliver"))
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                    act(a.equals("cancel") ? buyer() : seller(), o, a);
                    success.incrementAndGet();
                } catch (ApiError e) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        start.countDown();
        try {
            for (var f : fs)
                f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get());
        var stage = s(service.order(buyer(), s(o, "id"), false), "stage");
        assertTrue(Set.of("已取消", "待买方确认").contains(stage));
        assertEquals(stage.equals("已取消") ? 0 : 1,
                db.list("select * from reservations where order_id=#{p.o}", p("o", o.get("id"))).size());
    }

    @Test
    void restrictedOldOrdersAndHiddenItems() {
        var i = fresh();
        var o = buy(i, buyer(), id());
        try {
            service.manage(admin(), "restrict", buyer(), p("reason", "测试账户限制", "restricted", true));
            assertThrows(ApiError.class, () -> buy(fresh(), buyer(), id()));
            act(seller(), o, "accept");
            act(seller(), o, "deliver");
            assertEquals("已完成", s(act(buyer(), o, "confirm"), "stage"));
        } finally {
            service.manage(admin(), "restrict", buyer(), p("reason", "测试恢复账户", "restricted", false));
        }
        var j = fresh();
        var other = buy(j, buyer(), id());
        service.manage(admin(), "hide", s(j, "id"), p("reason", "独立隐藏", "hidden", true));
        act(buyer(), other, "cancel");
        assertFalse(service.available(service.item(s(j, "id"), false)));
    }

    @Test
    void disputeRestoresOriginalStageOrTerminatesWithoutResale() {
        var i = fresh();
        var o = buy(i, buyer(), id());
        act(seller(), o, "accept");
        service.dispute(buyer(), s(o, "id"), p("reason", "交付安排存在分歧", "evidence", "待核查"));
        assertThrows(ApiError.class, () -> act(seller(), o, "deliver"));
        String did = s(db.one("select id from disputes where order_id=#{p.o}", p("o", o.get("id"))), "id");
        service.manage(admin(), "dispute", did, p("reason", "双方确认可继续履约", "outcome", "continue"));
        assertEquals("待交付", s(service.order(buyer(), s(o, "id"), false), "stage"));
        service.dispute(buyer(), s(o, "id"), p("reason", "实物损坏不能履约", "evidence", "双方提交说明"));
        did = s(db.one("select id from disputes where order_id=#{p.o} and status='处理中'", p("o", o.get("id"))), "id");
        service.manage(admin(), "dispute", did,
                p("reason", "双方说明核实无法履约", "outcome", "terminate", "evidence_confirmed", true));
        assertEquals("交易异常终止", s(service.item(s(i, "id"), false), "status"));
        assertFalse(service.available(service.item(s(i, "id"), false)));
        assertNull(db.one("select * from deliveries where order_id=#{p.o}", p("o", o.get("id"))));
    }

    @Test
    void sessionCsrfLogoutAndPrivateAccess() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        var result = mvc.perform(get("/api/me")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession();
        String csrf = (String) session.getAttribute("csrf");
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-Token", csrf)
                .contentType("application/json").content("{\"username\":\"buyer\",\"password\":\"Campus123!\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/dashboard").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-Token", session.getAttribute("csrf"))
                .contentType("application/json").content("{}")).andExpect(status().isOk());
        assertTrue(session.isInvalid());
    }

    @Test
    void terminalMessagesReadOnlyAndReviewsUnique() {
        var i = fresh();
        var o = buy(i, buyer(), id());
        service.message(buyer(), "ORDER", s(o, "id"), "", p("content", "图书馆交接"));
        assertThrows(ApiError.class, () -> service.messages(uid("buyer2"), "ORDER", s(o, "id"), ""));
        act(seller(), o, "accept");
        act(seller(), o, "deliver");
        act(buyer(), o, "confirm");
        assertThrows(ApiError.class, () -> service.message(buyer(), "ORDER", s(o, "id"), "", p("content", "终态发送")));
        service.review(buyer(), s(o, "id"), p("score", 5, "content", "物品符合描述"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.review(buyer(), s(o, "id"), p("score", 5, "content", "重复评价")));
    }

    @Test
    void registrationVerificationUploadAndPrivacy() throws Exception {
        var created = service.register(
                p("username", "student_" + id().substring(0, 8), "password", "TestPass123!", "nickname", "测试新同学"));
        String u = s(created, "id");
        assertThrows(ApiError.class, () -> service.eligible(u));
        var session = new MockHttpSession();
        session.setAttribute("uid", u);
        session.setAttribute("csrf", "test-csrf");
        var image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        var file = new org.springframework.mock.web.MockMultipartFile("file", "proof.png", "image/png",
                bytes.toByteArray());
        var upload = mvc.perform(multipart("/api/assets").file(file).param("kind", "VERIFY").session(session)
                .header("X-CSRF-Token", "test-csrf")).andExpect(status().isOk()).andReturn();
        String aid = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(upload.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/assets/" + aid)).andExpect(status().isUnauthorized());
        var stranger = new MockHttpSession();
        stranger.setAttribute("uid", buyer());
        mvc.perform(get("/api/assets/" + aid).session(stranger)).andExpect(status().isForbidden());
        mvc.perform(get("/api/assets/" + aid).session(session)).andExpect(status().isOk());
        var body = p("student_no", "TEST-" + id(), "real_name", "测试身份", "asset_id", aid);
        service.verify(u, body);
        String vid = s(service.verifications(u).get(0), "id");
        service.manage(admin(), "verification", vid, p("reason", "测试退回不清晰材料", "pass", false));
        assertEquals(0, n(service.account(u), "verified"));
        service.verify(u, body);
        vid = s(service.verifications(u).get(0), "id");
        service.manage(admin(), "verification", vid, p("reason", "测试人工核验通过", "pass", true));
        assertEquals(1, n(service.account(u), "verified"));
        assertEquals(2, service.verifications(u).size());
    }

    @Test
    void auditCurrentVersionAndPreserveOrderDuringRestriction() {
        var i = fresh();
        String iid = s(i, "id");
        var edited = service.saveItem(seller(), iid, p("title", "修改后的物品", "category", "教材书籍", "description", "修改后的描述",
                "price", "35.00", "image_id", i.get("image_id")));
        assertThrows(ApiError.class,
                () -> service.manage(admin(), "item", iid, p("reason", "旧版本审核", "pass", true, "version", 1)));
        assertEquals("待审", s(service.item(iid, false), "status"));
        service.manage(admin(), "item", iid, p("reason", "通过新版本", "pass", true, "version", edited.get("version")));
        var o = buy(edited, buyer(), id());
        assertThrows(ApiError.class, () -> service.saveItem(seller(), iid, p("title", "占用期间修改", "category", "教材书籍",
                "description", "不能修改", "price", "45.00", "image_id", i.get("image_id"))));
        assertEquals("35.00", service.order(buyer(), s(o, "id"), false).get("price").toString());
    }
}
