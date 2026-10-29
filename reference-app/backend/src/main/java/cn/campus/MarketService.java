package cn.campus;

import static cn.campus.Store.p;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class MarketService {
    final Store db;
    final ObjectMapper json;
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    static final Set<String> ACTIVE = Set.of("待卖方确认", "待交付", "待买方确认");
    static final Set<String> CATEGORIES = Set.of("教材书籍", "数码配件", "宿舍生活", "运动户外", "其他");

    MarketService(Store db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static String now() {
        return Instant.now().toString();
    }

    static String s(Map<String, Object> m, String k) {
        return Objects.toString(m.get(k), "");
    }

    static int n(Map<String, Object> m, String k) {
        return m.get(k) == null ? 0 : ((Number) m.get(k)).intValue();
    }

    static String text(Map<String, Object> b, String k, int max) {
        String v = s(b, k).trim();
        if (v.isEmpty() || v.length() > max)
            throw new ApiError(k + " 必须填写，且不超过 " + max + " 字。");
        return v;
    }

    static void require(boolean ok, String message) {
        if (!ok)
            throw new ApiError(message);
    }

    Map<String, Object> account(String uid) {
        var u = db.one("select * from accounts where id=#{p.id}", p("id", uid));
        if (u == null)
            throw new ApiError(401, "请先登录。");
        return u;
    }

    Map<String, Object> publicUser(Map<String, Object> u) {
        return p("id", u.get("id"), "nickname", u.get("nickname"), "username", u.get("username"), "role", u.get("role"),
                "verified", u.get("verified"), "restricted", u.get("restricted"));
    }

    void admin(String uid) {
        if (!s(account(uid), "role").equals("ADMIN"))
            throw new ApiError(403, "需要管理员权限。");
    }

    void eligible(String uid) {
        var u = account(uid);
        require(n(u, "verified") == 1, "请先通过校园认证。");
        require(n(u, "restricted") == 0, "账户受限，不能开展新业务；仍可处理本人旧单。");
    }

    void lockAccounts(String... ids) {
        Arrays.stream(ids).distinct().sorted()
                .forEach(x -> db.one("select id from accounts where id=#{p.id} for update", p("id", x)));
    }

    Map<String, Object> item(String id, boolean lock) {
        var i = db.one("select * from items where id=#{p.id}" + (lock ? " for update" : ""), p("id", id));
        if (i == null)
            throw new ApiError(404, "物品不存在。");
        return i;
    }

    Map<String, Object> order(String uid, String oid, boolean lock) {
        var o = db.one("select * from orders where id=#{p.id}" + (lock ? " for update" : ""), p("id", oid));
        if (o == null)
            throw new ApiError(404, "订单不存在。");
        if (!uid.equals(s(o, "buyer_id")) && !uid.equals(s(o, "seller_id")))
            throw new ApiError(403, "仅交易双方可以访问该订单。");
        return o;
    }

    Map<String, Object> lockOrder(String uid, String oid, boolean management) {
        var o = db.one("select * from orders where id=#{p.id}", p("id", oid));
        if (o == null)
            throw new ApiError(404, "订单不存在。");
        if (management)
            admin(uid);
        else
            order(uid, oid, false);
        lockAccounts(s(o, "buyer_id"), s(o, "seller_id"));
        item(s(o, "item_id"), true);
        return db.one("select * from orders where id=#{p.id} for update", p("id", oid));
    }

    boolean available(Map<String, Object> i) {
        return s(i, "status").equals("在售") && n(i, "hidden") == 0 && n(account(s(i, "seller_id")), "restricted") == 0
                && db.one("select * from reservations where item_id=#{p.id}", p("id", i.get("id"))) == null;
    }

    void audit(String uid, String action, String obj, String reason) {
        db.exec("insert into audit_logs(id,actor_id,action,object_id,reason,created_at) values(#{p.id},#{p.u},#{p.a},#{p.o},#{p.r},#{p.t})",
                p("id", id(), "u", uid, "a", action, "o", obj, "r", reason, "t", now()));
    }

    void notify(String uid, String content) {
        db.exec("insert into notifications(id,user_id,content,created_at) values(#{p.id},#{p.u},#{p.c},#{p.t})",
                p("id", id(), "u", uid, "c", content, "t", now()));
    }

    @Transactional
    public Map<String, Object> register(Map<String, Object> b) {
        String username = text(b, "username", 40), password = text(b, "password", 72),
                nickname = text(b, "nickname", 30);
        require(username.matches("[A-Za-z0-9_]{3,40}"), "账号使用 3—40 位字母、数字或下划线。");
        require(password.length() >= 8, "密码至少 8 位。");
        require(password.getBytes(StandardCharsets.UTF_8).length <= 72, "密码 UTF-8 长度不能超过 72 字节。");
        String uid = id();
        db.exec("insert into accounts(id,username,password_hash,nickname,created_at) values(#{p.id},#{p.u},#{p.p},#{p.n},#{p.t})",
                p("id", uid, "u", username, "p", encoder.encode(password), "n", nickname, "t", now()));
        return publicUser(account(uid));
    }

    public Map<String, Object> login(Map<String, Object> b) {
        var u = db.one("select * from accounts where username=#{p.u}", p("u", s(b, "username")));
        if (u == null || !encoder.matches(s(b, "password"), s(u, "password_hash")))
            throw new ApiError(401, "账号或密码错误。");
        return publicUser(u);
    }

    public List<Map<String, Object>> market(String query, String category, int page) {
        return db.list(
                "select i.*,a.nickname as seller_name from items i join accounts a on a.id=i.seller_id where i.status='在售' and i.hidden=0 and a.restricted=0 and not exists(select 1 from reservations r where r.item_id=i.id) and (i.title like #{p.q} or i.description like #{p.q})"
                        + (category.isBlank() ? "" : " and i.category=#{p.c}")
                        + " order by i.created_at desc limit 20 offset #{p.offset}",
                p("q", "%" + query + "%", "c", category, "offset", Math.max(0, page - 1) * 20));
    }

    public List<Map<String, Object>> mine(String uid) {
        account(uid);
        return db.list("select * from items where seller_id=#{p.u} order by created_at desc", p("u", uid));
    }

    public List<Map<String, Object>> orders(String uid) {
        account(uid);
        return db.list("select * from orders where buyer_id=#{p.u} or seller_id=#{p.u} order by created_at desc",
                p("u", uid));
    }

    @Transactional
    public Map<String, Object> saveItem(String uid, String iid, Map<String, Object> b) {
        lockAccounts(uid);
        eligible(uid);
        String title = text(b, "title", 120), desc = text(b, "description", 5000), category = text(b, "category", 30),
                image = text(b, "image_id", 40);
        require(CATEGORIES.contains(category), "分类无效。");
        BigDecimal price;
        try {
            price = new BigDecimal(s(b, "price")).setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (Exception e) {
            throw new ApiError("价格必须是最多两位小数的正数。");
        }
        require(price.signum() > 0 && price.compareTo(new BigDecimal("9999999999.99")) <= 0, "价格超出范围。");
        var asset = db.one("select * from assets where id=#{p.id} and owner_id=#{p.u} and kind='ITEM'",
                p("id", image, "u", uid));
        require(asset != null, "请先上传本人的物品图片。");
        int version = 1;
        String itemId = iid == null ? id() : iid;
        if (iid != null) {
            var old = item(iid, true);
            require(uid.equals(s(old, "seller_id")), "不能修改他人物品。");
            require(Set.of("在售", "待审", "审核退回", "已撤下", "交易异常终止").contains(s(old, "status")), "当前阶段不能修改物品。");
            require(db.one("select * from reservations where item_id=#{p.id}", p("id", iid)) == null, "有效订单存在时不能修改。");
            require(!s(old, "status").equals("交易异常终止") || n(old, "relist_allowed") == 1, "异常终止后须先由管理员核实归属。");
            version = n(old, "version") + 1;
        }
        var params = p("id", itemId, "u", uid, "title", title, "d", desc, "c", category, "price", price, "image", image,
                "v", version, "t", now());
        if (iid == null)
            db.exec("insert into items(id,seller_id,title,category,description,price,image_id,version,status,created_at) values(#{p.id},#{p.u},#{p.title},#{p.c},#{p.d},#{p.price},#{p.image},#{p.v},'待审',#{p.t})",
                    params);
        else
            db.exec("update items set title=#{p.title},category=#{p.c},description=#{p.d},price=#{p.price},image_id=#{p.image},version=#{p.v},status='待审',reason=null,relist_allowed=0 where id=#{p.id}",
                    params);
        params.put("vid", id());
        db.exec("insert into item_versions(id,item_id,version,title,description,price,image_id,category,created_at) values(#{p.vid},#{p.id},#{p.v},#{p.title},#{p.d},#{p.price},#{p.image},#{p.c},#{p.t})",
                params);
        audit(uid, "物品提交审核", itemId, "版本 " + version);
        return item(itemId, false);
    }

    @Transactional
    public void withdraw(String uid, String iid) {
        lockAccounts(uid);
        eligible(uid);
        var i = item(iid, true);
        require(uid.equals(s(i, "seller_id")), "仅物主可撤下。");
        require(Set.of("在售", "待审", "审核退回").contains(s(i, "status")), "当前状态不能撤下。");
        require(db.one("select * from reservations where item_id=#{p.id}", p("id", iid)) == null, "有有效订单，不能撤下。");
        db.exec("update items set status='已撤下' where id=#{p.id}", p("id", iid));
        audit(uid, "撤下物品", iid, "");
    }

    @Transactional
    public void verify(String uid, Map<String, Object> b) {
        lockAccounts(uid);
        require(n(account(uid), "restricted") == 0, "受限账户不能重提认证。");
        require(n(account(uid), "verified") == 0, "已通过认证。");
        require(db.one("select id from verifications where user_id=#{p.u} and status='待审'", p("u", uid)) == null,
                "已有待审申请，请勿重复提交。");
        String student = text(b, "student_no", 50), name = text(b, "real_name", 50), asset = text(b, "asset_id", 40);
        require(db.one("select id from assets where id=#{p.a} and owner_id=#{p.u} and kind='VERIFY'",
                p("a", asset, "u", uid)) != null, "请上传本人的认证材料图片。");
        db.exec("insert into verifications(id,user_id,student_no,real_name,asset_id,status,created_at) values(#{p.id},#{p.u},#{p.s},#{p.n},#{p.a},'待审',#{p.t})",
                p("id", id(), "u", uid, "s", student, "n", name, "a", asset, "t", now()));
        audit(uid, "提交认证", uid, "");
    }

    public List<Map<String, Object>> verifications(String uid) {
        return db.list("select * from verifications where user_id=#{p.u} order by created_at desc", p("u", uid));
    }

    String hash(Map<String, Object> b) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(new TreeMap<>(b))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> replay(String uid, String key, String op, Map<String, Object> b) {
        require(key != null && key.matches("[A-Za-z0-9_-]{8,100}"), "请提供有效的操作标识。");
        var r = db.one("select * from receipts where actor_id=#{p.u} and request_key=#{p.k}", p("u", uid, "k", key));
        if (r == null)
            return null;
        require(op.equals(s(r, "operation")) && hash(b).equals(s(r, "payload_hash")), "相同操作标识不能改变请求内容。");
        return order(uid, s(r, "order_id"), false);
    }

    void receipt(String uid, String key, String op, Map<String, Object> b, String oid) {
        db.exec("insert into receipts(actor_id,request_key,operation,payload_hash,order_id,created_at) values(#{p.u},#{p.k},#{p.op},#{p.h},#{p.o},#{p.t})",
                p("u", uid, "k", key, "op", op, "h", hash(b), "o", oid, "t", now()));
    }

    @Transactional
    public Map<String, Object> createOrder(String uid, Map<String, Object> b, String key) {
        String iid = text(b, "item_id", 40);
        var hint = item(iid, false);
        lockAccounts(uid, s(hint, "seller_id"));
        var i = item(iid, true);
        var prior = replay(uid, key, "下单:" + iid, b);
        if (prior != null)
            return prior;
        eligible(uid);
        eligible(s(i, "seller_id"));
        require(!uid.equals(s(i, "seller_id")), "不能购买本人物品。");
        require(available(i), "物品已不可交易。");
        require(s(b, "version").equals(String.valueOf(n(i, "version"))), "物品版本发生变化，请重新查看确认。");
        String oid = id();
        var seller = account(s(i, "seller_id"));
        db.exec("insert into orders(id,item_id,buyer_id,seller_id,title,description,price,image_id,buyer_name,seller_name,item_version,stage,created_at) values(#{p.id},#{p.i},#{p.b},#{p.s},#{p.title},#{p.d},#{p.price},#{p.image},#{p.bn},#{p.sn},#{p.v},'待卖方确认',#{p.t})",
                p("id", oid, "i", iid, "b", uid, "s", i.get("seller_id"), "title", i.get("title"), "d",
                        i.get("description"), "price", i.get("price"), "image", i.get("image_id"), "bn",
                        account(uid).get("nickname"), "sn", seller.get("nickname"), "v", i.get("version"), "t", now()));
        db.exec("insert into reservations(item_id,order_id) values(#{p.i},#{p.o})", p("i", iid, "o", oid));
        db.exec("update items set status='交易中' where id=#{p.i}", p("i", iid));
        receipt(uid, key, "下单:" + iid, b, oid);
        audit(uid, "下单", oid, "");
        notify(s(i, "seller_id"), "收到新订单：" + s(i, "title"));
        return order(uid, oid, false);
    }

    void transition(String uid, Map<String, Object> o, String stage, String reason) {
        String oid = s(o, "id"), iid = s(o, "item_id");
        db.exec("update orders set stage=#{p.s},reason=#{p.r},completed_at=#{p.t} where id=#{p.id}",
                p("s", stage, "r", reason, "t", stage.equals("已完成") ? now() : null, "id", oid));
        if (!ACTIVE.contains(stage)) {
            db.exec("delete from reservations where item_id=#{p.i} and order_id=#{p.o}", p("i", iid, "o", oid));
            db.exec("update items set status=#{p.s} where id=#{p.id}",
                    p("s", stage.equals("已完成") ? "已成交" : stage.equals("已异常终止") ? "交易异常终止" : "在售", "id", iid));
        }
        audit(uid, stage, oid, reason);
        notify(uid.equals(s(o, "buyer_id")) ? s(o, "seller_id") : s(o, "buyer_id"), s(o, "title") + "：" + stage);
    }

    @Transactional
    public Map<String, Object> action(String uid, String oid, String action, Map<String, Object> b, String key) {
        var o = lockOrder(uid, oid, false);
        var prior = replay(uid, key, action + ":" + oid, b);
        if (prior != null)
            return prior;
        String stage = s(o, "stage");
        boolean seller = uid.equals(s(o, "seller_id"));
        String reason = s(b, "reason");
        switch (action) {
            case "accept" -> {
                require(seller && stage.equals("待卖方确认") && n(o, "disputed") == 0, "不能接单：身份、阶段或争议条件不符。");
                transition(uid, o, "待交付", "");
            }
            case "reject" -> {
                require(seller && stage.equals("待卖方确认") && n(o, "disputed") == 0, "当前不能拒单。");
                transition(uid, o, "已拒绝", text(b, "reason", 1000));
            }
            case "deliver" -> {
                require(seller && stage.equals("待交付") && n(o, "disputed") == 0, "当前不能登记交付。");
                require(Boolean.TRUE.equals(b.get("declared")), "必须确认已经实际交付。");
                db.exec("insert into deliveries(order_id,actor_id,note,created_at) values(#{p.o},#{p.u},#{p.n},#{p.t})",
                        p("o", oid, "u", uid, "n", text(b, "reason", 1000), "t", now()));
                transition(uid, o, "待买方确认", "");
            }
            case "confirm" -> {
                require(!seller && stage.equals("待买方确认"), "仅订单买方可在登记后确认收到。");
                require(Boolean.TRUE.equals(b.get("declared")), "请确认本人已经收到物品。");
                require(db.one("select * from deliveries where order_id=#{p.id}", p("id", oid)) != null, "尚未登记交付。");
                transition(uid, o, "已完成", "");
                db.exec("update orders set disputed=0 where id=#{p.id}", p("id", oid));
                db.exec("update disputes set status='已结案',outcome='买方确认完成',decision='买方本人确认收到' where order_id=#{p.id} and status='处理中'",
                        p("id", oid));
            }
            case "cancel" -> {
                require(Set.of("待卖方确认", "待交付").contains(stage) && n(o, "disputed") == 0, "已有登记、争议或订单结束，不能普通取消。");
                require(Boolean.TRUE.equals(b.get("declared")), "请声明尚未实际交付；已交付或有分歧应申请争议处理。");
                require(db.one("select * from deliveries where order_id=#{p.id}", p("id", oid)) == null, "已登记交付，不能取消。");
                transition(uid, o, "已取消", text(b, "reason", 1000));
            }
            case "remind" -> {
                require(seller && stage.equals("待买方确认"), "仅卖方可在登记后提醒。");
                if (n(o, "reminded") == 0) {
                    db.exec("update orders set reminded=1 where id=#{p.id}", p("id", oid));
                    notify(s(o, "buyer_id"), "卖方提醒确认收到：" + s(o, "title"));
                    audit(uid, "提醒买方", oid, "");
                }
            }
            default -> throw new ApiError(404, "未知订单操作。");
        }
        receipt(uid, key, action + ":" + oid, b, oid);
        return order(uid, oid, false);
    }

    @Transactional
    public void favorite(String uid, String iid) {
        lockAccounts(uid);
        var exists = db.one("select * from favorites where user_id=#{p.u} and item_id=#{p.i}", p("u", uid, "i", iid));
        if (exists != null) {
            db.exec("delete from favorites where user_id=#{p.u} and item_id=#{p.i}", p("u", uid, "i", iid));
            return;
        }
        eligible(uid);
        require(available(item(iid, false)), "物品不可访问。");
        db.exec("insert into favorites(user_id,item_id) values(#{p.u},#{p.i})", p("u", uid, "i", iid));
    }

    public List<Map<String, Object>> favorites(String uid) {
        return db.list(
                "select i.id,i.title,i.price,i.status from favorites f join items i on i.id=f.item_id where f.user_id=#{p.u}",
                p("u", uid));
    }

    @Transactional
    public void review(String uid, String oid, Map<String, Object> b) {
        var o = lockOrder(uid, oid, false);
        require(s(o, "stage").equals("已完成"), "仅完成订单可评价。");
        int score;
        try {
            score = Integer.parseInt(s(b, "score"));
        } catch (Exception e) {
            throw new ApiError("评分应为 1—5。");
        }
        require(score >= 1 && score <= 5, "评分应为 1—5。");
        db.exec("insert into reviews(id,order_id,author_id,target_id,score,content,created_at) values(#{p.id},#{p.o},#{p.u},#{p.target},#{p.s},#{p.c},#{p.t})",
                p("id", id(), "o", oid, "u", uid, "target",
                        uid.equals(s(o, "buyer_id")) ? o.get("seller_id") : o.get("buyer_id"), "s", score, "c",
                        text(b, "content", 1000), "t", now()));
        audit(uid, "评价", oid, "");
    }

    public List<Map<String, Object>> reviews(String seller) {
        account(seller);
        return db.list(
                "select r.score,r.content,r.created_at,a.nickname from reviews r join accounts a on a.id=r.author_id join orders o on o.id=r.order_id where r.target_id=#{p.u} and o.seller_id=#{p.u} and r.hidden=0 order by r.created_at desc",
                p("u", seller));
    }

    public List<Map<String, Object>> orderReviews(String uid, String oid) {
        order(uid, oid, false);
        return db.list("select id,author_id,score,content,hidden,created_at from reviews where order_id=#{p.o}",
                p("o", oid));
    }

    @Transactional
    public void dispute(String uid, String oid, Map<String, Object> b) {
        var o = lockOrder(uid, oid, false);
        require(ACTIVE.contains(s(o, "stage")), "订单已结束，请使用一般举报。");
        require(n(o, "disputed") == 0, "已有处理中争议，请在原申请补证。");
        db.exec("insert into disputes(id,order_id,author_id,reason,evidence,status,created_at) values(#{p.id},#{p.o},#{p.u},#{p.r},#{p.e},'处理中',#{p.t})",
                p("id", id(), "o", oid, "u", uid, "r", text(b, "reason", 1500), "e", s(b, "evidence"), "t", now()));
        db.exec("update orders set disputed=1 where id=#{p.id}", p("id", oid));
        audit(uid, "申请履约争议", oid, s(b, "reason"));
    }

    public List<Map<String, Object>> disputes(String uid) {
        return db.list(
                "select d.* from disputes d join orders o on o.id=d.order_id where o.buyer_id=#{p.u} or o.seller_id=#{p.u} order by d.created_at desc",
                p("u", uid));
    }

    @Transactional
    public void supplement(String uid, String did, Map<String, Object> b) {
        var d = db.one("select * from disputes where id=#{p.id}", p("id", did));
        require(d != null, "争议不存在。");
        lockOrder(uid, s(d, "order_id"), false);
        require(s(d, "status").equals("处理中"), "争议已结案，后续反馈请举报。");
        db.exec("update disputes set evidence=#{p.e} where id=#{p.id}", p("id", did, "e", s(d, "evidence") + "\n["
                + now() + " / " + s(account(uid), "nickname") + "] " + text(b, "evidence", 1500)));
        audit(uid, "争议补证", did, "");
    }

    @Transactional
    public void report(String uid, Map<String, Object> b) {
        account(uid);
        String type = text(b, "object_type", 20), obj = text(b, "object_id", 40);
        if (type.equals("ITEM")) {
            var i = item(obj, false);
            require(available(i) || uid.equals(s(i, "seller_id"))
                    || db.one("select id from orders where item_id=#{p.i} and (buyer_id=#{p.u} or seller_id=#{p.u})",
                            p("i", obj, "u", uid)) != null,
                    "对象不可访问。");
        } else if (type.equals("ORDER"))
            order(uid, obj, false);
        else if (type.equals("ACCOUNT"))
            account(obj);
        else
            throw new ApiError("举报对象类型无效。");
        db.exec("insert into reports(id,author_id,object_type,object_id,reason,evidence,status,created_at) values(#{p.id},#{p.u},#{p.ty},#{p.o},#{p.r},#{p.e},'待处理',#{p.t})",
                p("id", id(), "u", uid, "ty", type, "o", obj, "r", text(b, "reason", 1500), "e", s(b, "evidence"), "t",
                        now()));
        audit(uid, "提交举报", obj, s(b, "reason"));
    }

    public List<Map<String, Object>> reports(String uid) {
        return db.list("select * from reports where author_id=#{p.u} order by created_at desc", p("u", uid));
    }

    // 物品咨询与订单会话分开；前者按咨询发起人隔离，后者仅交易双方可访问。
    String peer(String uid, String type, String obj, String buyer, boolean writing) {
        if (type.equals("ORDER")) {
            var o = order(uid, obj, false);
            if (writing)
                require(ACTIVE.contains(s(o, "stage")), "订单已结束，会话只读。");
            return uid.equals(s(o, "buyer_id")) ? s(o, "seller_id") : s(o, "buyer_id");
        }
        require(type.equals("ITEM"), "会话类型无效。");
        var i = item(obj, false);
        String seller = s(i, "seller_id");
        String other = uid.equals(seller) ? buyer : seller;
        require(!other.isBlank() && !uid.equals(other), "请选择咨询对象。");
        if (uid.equals(seller)) {
            require(db.one(
                    "select id from messages where context_type='ITEM' and context_id=#{p.i} and sender_id=#{p.b} and receiver_id=#{p.s}",
                    p("i", obj, "b", buyer, "s", seller)) != null, "咨询会话不存在。");
        } else if (writing) {
            eligible(uid);
            require(available(i), "物品已不可公开咨询，请使用对应订单会话。");
        } else
            require(available(i) || db.one(
                    "select id from messages where context_type='ITEM' and context_id=#{p.i} and sender_id=#{p.u}",
                    p("i", obj, "u", uid)) != null, "会话不可访问。");
        if (writing)
            eligible(uid);
        return other;
    }

    public List<Map<String, Object>> messages(String uid, String type, String obj, String buyer) {
        String other = peer(uid, type, obj, buyer, false);
        return db.list(
                "select m.*,a.nickname as sender_name from messages m join accounts a on a.id=m.sender_id where context_type=#{p.ty} and context_id=#{p.i} and ((sender_id=#{p.u} and receiver_id=#{p.o}) or (sender_id=#{p.o} and receiver_id=#{p.u})) order by created_at,id",
                p("ty", type, "i", obj, "u", uid, "o", other));
    }

    @Transactional
    public void message(String uid, String type, String obj, String buyer, Map<String, Object> b) {
        if (type.equals("ORDER"))
            lockOrder(uid, obj, false);
        else
            lockAccounts(uid);
        String other = peer(uid, type, obj, buyer, true);
        db.exec("insert into messages(id,context_type,context_id,sender_id,receiver_id,content,created_at) values(#{p.id},#{p.ty},#{p.i},#{p.u},#{p.o},#{p.c},#{p.t})",
                p("id", id(), "ty", type, "i", obj, "u", uid, "o", other, "c", text(b, "content", 1500), "t", now()));
        notify(other, "收到一条新消息。");
    }

    public List<Map<String, Object>> inbox(String uid) {
        return db.list(
                "select distinct m.context_type,m.context_id,case when m.sender_id=#{p.u} then m.receiver_id else m.sender_id end as peer_id from messages m where sender_id=#{p.u} or receiver_id=#{p.u}",
                p("u", uid));
    }

    @Transactional
    public Map<String, Object> dashboard(String uid) {
        admin(uid);
        audit(uid, "查看管理台", "dashboard", "审核治理范围");
        return p("items", db.list(
                "select i.*,a.nickname as seller_name from items i join accounts a on a.id=i.seller_id order by i.created_at desc",
                p()), "verifications",
                db.list("select v.*,a.nickname from verifications v join accounts a on a.id=v.user_id order by v.created_at desc",
                        p()),
                "accounts",
                db.list("select id,username,nickname,role,verified,restricted from accounts order by created_at", p()),
                "disputes",
                db.list("select d.*,o.title,o.stage from disputes d join orders o on o.id=d.order_id order by d.created_at desc",
                        p()),
                "reports", db.list("select * from reports order by created_at desc", p()), "reviews",
                db.list("select * from reviews order by created_at desc", p()), "logs",
                db.list("select l.*,a.nickname from audit_logs l join accounts a on a.id=l.actor_id order by l.created_at desc limit 100",
                        p()));
    }

    public Map<String, Object> statistics(String uid, String from, String to) {
        admin(uid);
        String clause = "";
        Map<String, Object> params = p();
        if (!from.isBlank()) {
            try {
                Instant.parse(from);
            } catch (Exception e) {
                throw new ApiError("开始时间格式错误。");
            }
            clause += " and completed_at>=#{p.from}";
            params.put("from", from);
        }
        if (!to.isBlank()) {
            try {
                Instant.parse(to);
            } catch (Exception e) {
                throw new ApiError("结束时间格式错误。");
            }
            clause += " and completed_at<#{p.to}";
            params.put("to", to);
        }
        return p("sales",
                db.one("select count(*) as count,coalesce(sum(price),0) as amount from orders where stage='已完成'"
                        + clause, params),
                "items", db.one("select count(*) as count from items", p()), "reports",
                db.one("select count(*) as count from reports", p()), "basis",
                "成交按完成时间筛选：[开始, 结束)，金额按订单快照；发布/举报数量为累计值。");
    }

    @Transactional
    public void manage(String uid, String kind, String obj, Map<String, Object> b) {
        admin(uid);
        String reason = text(b, "reason", 1500);
        switch (kind) {
            case "verification" -> {
                var hint = db.one("select * from verifications where id=#{p.id}", p("id", obj));
                require(hint != null, "申请不存在。");
                lockAccounts(s(hint, "user_id"));
                var v = db.one("select * from verifications where id=#{p.id} for update", p("id", obj));
                require(s(v, "status").equals("待审"), "该版本已审核。");
                boolean pass = Boolean.TRUE.equals(b.get("pass"));
                if (pass) {
                    require(n(account(s(v, "user_id")), "restricted") == 0, "账户受限，不能授予资格。");
                    var identity = db.one("select * from identities where student_no=#{p.s}",
                            p("s", v.get("student_no")));
                    require(identity == null || s(identity, "user_id").equals(s(v, "user_id")), "该校园身份已绑定其他账户。");
                    if (identity == null)
                        db.exec("insert into identities(student_no,user_id) values(#{p.s},#{p.u})",
                                p("s", v.get("student_no"), "u", v.get("user_id")));
                    db.exec("update accounts set verified=1 where id=#{p.id}", p("id", v.get("user_id")));
                }
                db.exec("update verifications set status=#{p.s},reason=#{p.r} where id=#{p.id}",
                        p("s", pass ? "通过" : "退回", "r", reason, "id", obj));
                notify(s(v, "user_id"), "校园认证" + (pass ? "通过" : "退回：" + reason));
            }
            case "item" -> {
                var i = item(obj, true);
                require(s(i, "status").equals("待审"), "物品已非待审。");
                require(s(b, "version").equals(String.valueOf(n(i, "version"))), "审核版本已经变化，请刷新。");
                boolean pass = Boolean.TRUE.equals(b.get("pass"));
                db.exec("update items set status=#{p.s},reason=#{p.r} where id=#{p.id}",
                        p("s", pass ? "在售" : "审核退回", "r", reason, "id", obj));
                notify(s(i, "seller_id"), "物品审核" + (pass ? "通过" : "退回：" + reason));
            }
            case "hide" -> {
                item(obj, true);
                db.exec("update items set hidden=#{p.h} where id=#{p.id}",
                        p("h", Boolean.TRUE.equals(b.get("hidden")) ? 1 : 0, "id", obj));
            }
            case "restrict" -> {
                lockAccounts(obj);
                var u = account(obj);
                require(!s(u, "role").equals("ADMIN"), "不能通过此入口限制管理员。");
                db.exec("update accounts set restricted=#{p.r} where id=#{p.id}",
                        p("r", Boolean.TRUE.equals(b.get("restricted")) ? 1 : 0, "id", obj));
                notify(obj, reason);
            }
            case "relist" -> {
                var i = item(obj, true);
                require(s(i, "status").equals("交易异常终止"), "仅异常终止物品需要核实归属。");
                db.exec("update items set relist_allowed=1 where id=#{p.id}", p("id", obj));
                notify(s(i, "seller_id"), "物品归属已核实，可修改后重新提交审核。");
            }
            case "dispute" -> {
                var d = db.one("select * from disputes where id=#{p.id}", p("id", obj));
                require(d != null, "争议不存在。");
                var o = lockOrder(uid, s(d, "order_id"), true);
                d = db.one("select * from disputes where id=#{p.id} for update", p("id", obj));
                require(s(d, "status").equals("处理中") && ACTIVE.contains(s(o, "stage")), "争议已结束。");
                String outcome = text(b, "outcome", 20);
                require(Set.of("continue", "terminate").contains(outcome), "处置方式无效。");
                if (outcome.equals("terminate")) {
                    require(Boolean.TRUE.equals(b.get("evidence_confirmed")), "须确认已核查无法履约依据，不能仅因沉默终止。");
                    transition(uid, o, "已异常终止", reason);
                }
                db.exec("update orders set disputed=0 where id=#{p.id}", p("id", o.get("id")));
                db.exec("update disputes set status='已结案',outcome=#{p.o},decision=#{p.r} where id=#{p.id}",
                        p("o", outcome.equals("continue") ? "继续履约" : "异常终止", "r", reason, "id", obj));
            }
            case "report" -> {
                var r = db.one("select * from reports where id=#{p.id} for update", p("id", obj));
                require(r != null, "举报不存在。");
                db.exec("update reports set status='已处理',result=#{p.r},event_ref=#{p.e} where id=#{p.id}",
                        p("r", reason, "e", s(b, "event_ref"), "id", obj));
                notify(s(r, "author_id"), "举报处理结果：" + reason);
            }
            case "review" -> {
                require(db.one("select id from reviews where id=#{p.id}", p("id", obj)) != null, "评价不存在。");
                db.exec("update reviews set hidden=#{p.h} where id=#{p.id}",
                        p("h", Boolean.TRUE.equals(b.get("hidden")) ? 1 : 0, "id", obj));
            }
            default -> throw new ApiError("未知管理操作。");
        }
        audit(uid, "管理:" + kind, obj, reason);
    }
}
