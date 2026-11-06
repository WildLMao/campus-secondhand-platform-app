package cn.campus;

import static cn.campus.Store.p;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 交易、审核与治理的唯一业务入口。
 *
 * 并发约定：先按账户主键排序加行锁，再锁物品/订单，再锁争议、报告或治理对象；
 * 所有依赖对象状态的判断都在持锁后的最新读取上完成，锁前的读取只用于确定加锁对象。
 * 通知在业务事务内只追加待投递记录，提交后由 NotificationDelivery 的独立 REQUIRES_NEW 事务投递。
 */
@Service
public class MarketService {
    final Store db;
    final ObjectMapper json;
    final NotificationDelivery notices;
    final ImageGuard images = new ImageGuard();
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    static final Set<String> ACTIVE = Set.of("待卖方确认", "待交付", "待买方确认");
    static final Set<String> CATEGORIES = Set.of("教材书籍", "数码配件", "宿舍生活", "运动户外", "其他");

    MarketService(Store db, ObjectMapper json, NotificationDelivery notices) {
        this.db = db;
        this.json = json;
        this.notices = notices;
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

    /**
     * 供只读访问（通知补投）使用；业务写入仍经本类事务入口。
     * 经 Spring 代理调用时 getter 会转发到目标对象读到真实字段；测试直接持有的目标实例同样适用。
     * 但不得通过注入的 MarketService（可能是代理壳，字段为 null）直接读取字段；控制器以构造注入获取依赖。
     */
    NotificationDelivery notifications() {
        return notices;
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

    /** 允许为空的可选文本；超长直接拒绝，避免 MySQL 严格模式下的截断或写入失败。 */
    static String optional(Map<String, Object> b, String k, int max) {
        String v = s(b, k).trim();
        if (v.length() > max)
            throw new ApiError(k + " 不能超过 " + max + " 字。");
        return v;
    }

    /** 争议与举报证据写入 TEXT，按 UTF-8 字节数限制，防止超过列容量。 */
    static String evidence(Map<String, Object> b, String k) {
        String v = optional(b, k, 5000);
        require(v.getBytes(StandardCharsets.UTF_8).length <= 60000, k + " 内容过长，请精简后重试。");
        return v;
    }

    /** 写入短说明列时按列宽截断，保留用户输入主体的同时避免数据库报错。 */
    static String clip(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
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

    /** 兼容旧调用的审计写入；新代码使用 fact()，同时记录对象类型、结果与前后值。 */
    void audit(String uid, String action, String obj, String reason) {
        db.exec("insert into audit_logs(id,actor_id,action,object_id,reason,created_at) values(#{p.id},#{p.u},#{p.a},#{p.o},#{p.r},#{p.t})",
                p("id", id(), "u", uid, "a", action, "o", obj, "r", reason, "t", now()));
    }

    /**
     * 追加一条处置事实（不改写旧记录）：object_type 如 ITEM/ACCOUNT/DISPUTE/REPORT/REVIEW/ORDER；
     * action 为具体动作；result 为明确结果；before/after 为处置前后值；version 为涉及的物品/审核版本。
     */
    Map<String, Object> fact(String uid, String actorNickname, String type, String obj, String action, String result,
            Object before, Object after, Integer version, String reason) {
        String factId = id();
        String t = now();
        db.exec("insert into audit_facts(id,actor_id,object_type,object_id,action,result,before_value,after_value,version,reason,created_at) values(#{p.id},#{p.u},#{p.ty},#{p.o},#{p.a},#{p.r},#{p.b},#{p.f},#{p.v},#{p.reason},#{p.t})",
                p("id", factId, "u", uid, "ty", type, "o", obj, "a", action, "r", result,
                        "b", before == null ? null : clip(String.valueOf(before), 100),
                        "f", after == null ? null : clip(String.valueOf(after), 100),
                        "v", version, "reason", clip(reason, 1500), "t", t));
        db.exec("insert into audit_logs(id,actor_id,action,object_id,reason,created_at) values(#{p.id},#{p.u},#{p.a},#{p.o},#{p.r},#{p.t})",
                p("id", id(), "u", uid, "a", clip(action + "/" + result, 100), "o", obj,
                        "r", clip(reason, 1500), "t", t));
        return p("id", factId, "actor_id", uid, "actor_name", actorNickname, "object_type", type, "object_id", obj,
                "action", action, "result", result, "before_value", before, "after_value", after, "version", version,
                "reason", reason, "created_at", t);
    }

    /** 业务事务内登记通知；提交后由独立事务投递。 */
    void notify(String eventKey, String uid, String content) {
        notices.enqueue(eventKey, uid, content);
    }

    void afterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("通知必须在业务事务内登记。");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notices.afterCommit();
            }
        });
    }

    /**
     * 注册。密码按原文处理：不 trim（首尾空格同样是凭据的一部分），与登录一样用原始字符串比对；
     * 长度按字符数与 UTF-8 字节数分别校验：至少 8 个字符，且 UTF-8 不超过 72 字节（BCrypt 只处理前 72 字节）。
     * 不合规时明确拒绝（400），绝不注册成功后再悄悄改写凭据。
     */
    @Transactional
    public Map<String, Object> register(Map<String, Object> b) {
        String username = text(b, "username", 40), nickname = text(b, "nickname", 30);
        // 只要求是字符串；保留原样（含首尾空格），由下方规则明确接受或拒绝。
        Object raw = b.get("password");
        require(raw instanceof String, "密码必须填写。");
        String password = (String) raw;
        require(username.matches("[A-Za-z0-9_]{3,40}"), "账号使用 3—40 位字母、数字或下划线。");
        require(!password.isBlank(), "密码不能全部为空白字符。");
        require(password.codePointCount(0, password.length()) >= 8, "密码至少 8 个字符。");
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

    /**
     * 游客/普通用户可见的公开物品投影：按展示字段逐列列出，不返回管理员填写的审核说明 reason，
     * 也不返回隐藏、重新提交许可等治理内部字段（隐藏/受限条目本身已被 where 排除）。
     * 物主与管理端各自按权限读取完整记录。
     */
    static final String PUBLIC_ITEM_COLUMNS = "i.id,i.seller_id,i.title,i.category,i.description,i.price,i.image_id,i.version,i.status,i.created_at";

    /**
     * 公开物品返回前的联系方式脱敏（BR14）：只处理公开响应中的 title、description 与卖方公开昵称，
     * 与公开评价读取时同一 ImageGuard.mask 规则；按读取值处理，历史已存内容同样受保护。
     * 不改写库中原文，不影响价格、ID、分类、版本；本人与管理端读取完整记录时保持原文。
     */
    Map<String, Object> maskPublicItem(Map<String, Object> row) {
        for (String k : List.of("title", "description", "seller_name"))
            if (row.get(k) != null)
                row.put(k, images.mask(s(row, k)));
        return row;
    }

    /**
     * 检索只按库中原文匹配（搜索词不经过脱敏），结果行在返回前按公开规则脱敏。
     */
    public List<Map<String, Object>> market(String query, String category, int page) {
        return db.list(
                "select " + PUBLIC_ITEM_COLUMNS + ",a.nickname as seller_name from items i join accounts a on a.id=i.seller_id where i.status='在售' and i.hidden=0 and a.restricted=0 and not exists(select 1 from reservations r where r.item_id=i.id) and (i.title like #{p.q} or i.description like #{p.q})"
                        + (category.isBlank() ? "" : " and i.category=#{p.c}")
                        + " order by i.created_at desc limit 20 offset #{p.offset}",
                p("q", "%" + query + "%", "c", category, "offset", Math.max(0, page - 1) * 20)).stream()
                .map(this::maskPublicItem).toList();
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

    static String keyOf(String key) {
        require(key != null && key.matches("[A-Za-z0-9_-]{8,100}"), "请提供有效的操作标识。");
        return key;
    }

    /** 保留旧签名（服务内调用与测试夹具）：不带提交意图，等价于一次新的提交。 */
    @Transactional
    public Map<String, Object> saveItem(String uid, String iid, Map<String, Object> b) {
        return saveItem(uid, iid, b, null);
    }

    /**
     * 发布/修改。带 Idempotency-Key 时，同一操作者、同一标识只产生一次业务效果：
     * 相同内容返回原物品（修改返回原版本），不同内容视为新意图而拒绝。
     */
    @Transactional
    public Map<String, Object> saveItem(String uid, String iid, Map<String, Object> b, String key) {
        lockAccounts(uid);
        require(iid == null || uid.equals(s(item(iid, false), "seller_id")), "不能修改他人物品。");
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
        String operation = iid == null ? "发布物品" : "修改物品:" + iid;
        // 内容指纹基于校验后的规范化字段（不是原始请求体），因此同一内容即使字段顺序或数值写法不同也视为同一意图。
        Map<String, Object> payload = p("title", title, "description", desc, "category", category,
                "price", price.toPlainString(), "image_id", image);
        String payloadHash = hash(payload);
        if (key != null) {
            String k = keyOf(key);
            var prior = db.one("select * from item_receipts where actor_id=#{p.u} and request_key=#{p.k}",
                    p("u", uid, "k", k));
            if (prior != null) {
                require(operation.equals(s(prior, "operation")) && payloadHash.equals(s(prior, "payload_hash")),
                        "相同操作标识不能改变请求内容。");
                return item(s(prior, "item_id"), false);
            }
        }
        int version = 1;
        String itemId = iid == null ? id() : iid;
        String before = null;
        if (iid != null) {
            var old = item(iid, true);
            require(uid.equals(s(old, "seller_id")), "不能修改他人物品。");
            require(Set.of("在售", "待审", "审核退回", "已撤下", "交易异常终止").contains(s(old, "status")), "当前阶段不能修改物品。");
            require(db.one("select * from reservations where item_id=#{p.id}", p("id", iid)) == null, "有效订单存在时不能修改。");
            require(!s(old, "status").equals("交易异常终止") || n(old, "relist_allowed") == 1, "异常终止后须先由管理员核实归属。");
            version = n(old, "version") + 1;
            before = s(old, "status") + "/v" + n(old, "version");
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
        if (key != null) {
            db.exec("insert into item_receipts(id,actor_id,request_key,operation,payload_hash,item_id,item_version,created_at) values(#{p.id},#{p.u},#{p.k},#{p.op},#{p.h},#{p.i},#{p.v},#{p.t})",
                    p("id", id(), "u", uid, "k", keyOf(key), "op", operation, "h", payloadHash, "i", itemId, "v", version,
                            "t", now()));
        }
        fact(uid, s(account(uid), "nickname"), "ITEM", itemId, iid == null ? "发布物品" : "修改物品",
                iid == null ? "创建待审版本" : "新版本待审", before, "待审/v" + version, version, "版本 " + version);
        return item(itemId, false);
    }

    @Transactional
    public void withdraw(String uid, String iid) {
        lockAccounts(uid);
        eligible(uid);
        var i = item(iid, true);
        require(uid.equals(s(i, "seller_id")), "仅物主可撤下。");
        String priorStatus = s(i, "status");
        require(Set.of("在售", "待审", "审核退回").contains(priorStatus), "当前状态不能撤下。");
        require(db.one("select * from reservations where item_id=#{p.id}", p("id", iid)) == null, "有有效订单，不能撤下。");
        db.exec("update items set status='已撤下' where id=#{p.id}", p("id", iid));
        fact(uid, s(account(uid), "nickname"), "ITEM", iid, "撤下物品", "已撤下", priorStatus, "已撤下",
                n(i, "version"), "");
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
        String vid = id();
        db.exec("insert into verifications(id,user_id,student_no,real_name,asset_id,status,created_at) values(#{p.id},#{p.u},#{p.s},#{p.n},#{p.a},'待审',#{p.t})",
                p("id", vid, "u", uid, "s", student, "n", name, "a", asset, "t", now()));
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
        notify("order-created:" + oid, s(i, "seller_id"), "收到新订单：" + s(i, "title"));
        afterCommit();
        return order(uid, oid, false);
    }

    void transition(String uid, Map<String, Object> o, String stage, String reason) {
        String oid = s(o, "id"), iid = s(o, "item_id");
        // 管理员处置说明最长 1500 字，而 orders.reason 列为 1000；按列宽保存主体，完整说明保留在处置事实与订单快照之外。
        String orderReason = clip(reason, 1000);
        db.exec("update orders set stage=#{p.s},reason=#{p.r},completed_at=#{p.t,jdbcType=VARCHAR} where id=#{p.id}",
                p("s", stage, "r", orderReason, "t", stage.equals("已完成") ? now() : null, "id", oid));
        if (!ACTIVE.contains(stage)) {
            db.exec("delete from reservations where item_id=#{p.i} and order_id=#{p.o}", p("i", iid, "o", oid));
            db.exec("update items set status=#{p.s} where id=#{p.id}",
                    p("s", stage.equals("已完成") ? "已成交" : stage.equals("已异常终止") ? "交易异常终止" : "在售", "id", iid));
        }
        fact(uid, s(account(uid), "nickname"), "ORDER", oid, "订单阶段", stage,
                s(o, "stage"), stage, n(o, "item_version"), reason);
        notify("order-stage:" + oid + ":" + stage, uid.equals(s(o, "buyer_id")) ? s(o, "seller_id") : s(o, "buyer_id"),
                s(o, "title") + "：" + stage);
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
                String note = clip(text(b, "reason", 1000), 1000);
                db.exec("insert into deliveries(order_id,actor_id,note,created_at,revision) values(#{p.o},#{p.u},#{p.n},#{p.t},1)",
                        p("o", oid, "u", uid, "n", note, "t", now()));
                transition(uid, o, "待买方确认", "");
                fact(uid, s(account(uid), "nickname"), "DELIVERY", oid, "登记交付", "已登记", null, note, null, "");
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
                    notify("order-remind:" + oid, s(o, "buyer_id"), "卖方提醒确认收到：" + s(o, "title"));
                    audit(uid, "提醒买方", oid, "");
                }
            }
            default -> throw new ApiError(404, "未知订单操作。");
        }
        receipt(uid, key, action + ":" + oid, b, oid);
        afterCommit();
        return order(uid, oid, false);
    }

    /** 保留旧签名：加入收藏（同一意图重试仍为已收藏）。 */
    @Transactional
    public void favorite(String uid, String iid) {
        favorites(uid, iid, true);
    }

    /**
     * 明确设置收藏目标状态：adding=true 为加入，false 为取消。
     * 加入幂等（重复加入仍为已收藏），取消幂等（重复取消仍为未收藏，未收藏时无需认证或物品在售）。
     */
    @Transactional
    public Map<String, Object> favorites(String uid, String iid, boolean adding) {
        lockAccounts(uid);
        var exists = db.one("select * from favorites where user_id=#{p.u} and item_id=#{p.i}", p("u", uid, "i", iid));
        if (adding) {
            eligible(uid);
            require(available(item(iid, false)), "物品不可访问。");
            if (exists == null)
                db.exec("insert into favorites(user_id,item_id) values(#{p.u},#{p.i})", p("u", uid, "i", iid));
        } else if (exists != null) {
            db.exec("delete from favorites where user_id=#{p.u} and item_id=#{p.i}", p("u", uid, "i", iid));
        }
        return p("ok", true, "favorite", adding, "item_id", iid);
    }

    /**
     * 本人收藏列表。只有当物品当前仍处于公开可见条件时（与 {@link #available} 同一规则）才返回
     * 标题/价格/状态；否则该条只返回 {id,unavailable:true}，不露出待审/隐藏/受限/撤回后的任何内容。
     * 收藏关系本身保留，买方仍可取消收藏；新版本通过审核并恢复公开后自动恢复展示最新内容。
     */
    public List<Map<String, Object>> favorites(String uid) {
        account(uid);
        return db.list(
                "select f.item_id as id,i.title,i.price,i.status,i.hidden,a.restricted,r.item_id as reserved from favorites f left join items i on i.id=f.item_id "
                        + "left join accounts a on a.id=i.seller_id left join reservations r on r.item_id=i.id "
                        + "where f.user_id=#{p.u} order by f.item_id",
                p("u", uid)).stream().map(row -> {
                    // 行存在、物品仍公开、物主未受限且无占用：与可交易判断同一规则，二者任一不成立即视为不可访问。
                    boolean accessible = row.get("title") != null && "在售".equals(s(row, "status"))
                            && n(row, "hidden") == 0 && (row.get("restricted") == null || n(row, "restricted") == 0)
                            && row.get("reserved") == null;
                    // 可用条目的标题按公开规则脱敏（与 GET /api/items 一致）；价格、ID 原样。
                    if (accessible)
                        return p("id", row.get("id"), "title", images.mask(s(row, "title")), "price", row.get("price"),
                                "status", row.get("status"), "unavailable", false);
                    return p("id", row.get("id"), "unavailable", true);
                }).toList();
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
        String content = text(b, "content", 1000);
        require(!ImageGuard.contact(content), "评价不能包含联系方式（手机号、邮箱或社交账号），评价可能公开展示。");
        db.exec("insert into reviews(id,order_id,author_id,target_id,score,content,created_at) values(#{p.id},#{p.o},#{p.u},#{p.target},#{p.s},#{p.c},#{p.t})",
                p("id", id(), "o", oid, "u", uid, "target",
                        uid.equals(s(o, "buyer_id")) ? o.get("seller_id") : o.get("buyer_id"), "s", score, "c",
                        content, "t", now()));
        audit(uid, "评价", oid, "");
    }

    /**
     * 卖方公开评价：读取时再次脱敏联系方式（含历史记录），治理隐藏的评价不返回；
     * 返回 author_id 便于前端区分本人评价。
     */
    public List<Map<String, Object>> reviews(String seller) {
        account(seller);
        return db.list(
                "select r.id,r.order_id,r.author_id,r.score,r.content,r.created_at,a.nickname from reviews r join accounts a on a.id=r.author_id join orders o on o.id=r.order_id where r.target_id=#{p.u} and o.seller_id=#{p.u} and r.hidden=0 order by r.created_at desc",
                p("u", seller)).stream().map(r -> {
                    r.put("content", images.mask(s(r, "content")));
                    return r;
                }).toList();
    }

    /** 当事人查看本单评价（含隐藏标记与脱敏后的内容）。 */
    public List<Map<String, Object>> orderReviews(String uid, String oid) {
        order(uid, oid, false);
        return db.list("select id,order_id,author_id,target_id,score,content,hidden,created_at from reviews where order_id=#{p.o} order by created_at",
                p("o", oid)).stream().map(r -> {
                    r.put("content", images.mask(s(r, "content")));
                    return r;
                }).toList();
    }

    @Transactional
    public void dispute(String uid, String oid, Map<String, Object> b) {
        var o = lockOrder(uid, oid, false);
        require(ACTIVE.contains(s(o, "stage")), "订单已结束，请使用一般举报。");
        require(n(o, "disputed") == 0, "已有处理中争议，请在原申请补证。");
        String did = id();
        db.exec("insert into disputes(id,order_id,author_id,reason,evidence,status,created_at,revision) values(#{p.id},#{p.o},#{p.u},#{p.r},#{p.e},'处理中',#{p.t},1)",
                p("id", did, "o", oid, "u", uid, "r", text(b, "reason", 1500), "e", evidence(b, "evidence"), "t",
                        now()));
        db.exec("update orders set disputed=1 where id=#{p.id}", p("id", oid));
        fact(uid, s(account(uid), "nickname"), "DISPUTE", did, "申请履约争议", "处理中", null, "处理中", null,
                s(b, "reason"));
    }

    /** 争议的补证记录（按提交时间顺序），供当事人列表与管理台共用；只返回展示字段。 */
    List<Map<String, Object>> evidenceLog(Object did) {
        return db.list(
                "select id,author_id,content,created_at from dispute_evidence where dispute_id=#{p.d} order by created_at,id",
                p("d", did));
    }

    Map<String, Object> withEvidenceLog(Map<String, Object> d) {
        d.put("evidence_log", evidenceLog(d.get("id")));
        return d;
    }

    public List<Map<String, Object>> disputes(String uid) {
        return db.list(
                "select d.* from disputes d join orders o on o.id=d.order_id where o.buyer_id=#{p.u} or o.seller_id=#{p.u} order by d.created_at desc",
                p("u", uid)).stream().map(this::withEvidenceLog).toList();
    }

    /**
     * 补证：先确定订单并按统一顺序加锁，锁后以 FOR UPDATE 读取争议最新状态；只有订单双方可补证，
     * 第三人拒绝，已结案拒绝。新证据追加为一条独立记录（带作者与时间），不改写原提交证据；
     * 版本号条件更新保证依据的是当前争议行，避免锁前旧快照覆盖或越过结案边界。
     */
    @Transactional
    public void supplement(String uid, String did, Map<String, Object> b) {
        var hint = db.one("select * from disputes where id=#{p.id}", p("id", did));
        require(hint != null, "争议不存在。");
        lockOrder(uid, s(hint, "order_id"), false);
        // 锁后以 SELECT ... FOR UPDATE 做真实当前读：MyBatis 一级缓存(SESSION)会用同一 SQL 的锁前结果命中，
        // 而补证自身不改争议行（@Update 不刷新缓存），所以必须由数据库锁定读，不能复用锁前快照。
        var d = db.one("select * from disputes where id=#{p.id} for update", p("id", did));
        require(d != null && s(d, "order_id").equals(s(hint, "order_id")), "争议不存在。");
        if (!s(d, "status").equals("处理中"))
            throw new ApiError("争议已结案，后续反馈请举报。");
        String content = text(b, "evidence", 1500);
        // 证据时间取锁后真实的提交时刻（此处 now()）；追加后仍由结案流程统一裁决。
        db.exec("insert into dispute_evidence(id,dispute_id,author_id,content,created_at) values(#{p.id},#{p.d},#{p.u},#{p.c},#{p.t})",
                p("id", id(), "d", did, "u", uid, "c", content, "t", now()));
        db.exec("update disputes set revision=revision+1 where id=#{p.id} and status='处理中' and revision=#{p.r}",
                p("id", did, "r", n(d, "revision")));
        fact(uid, s(account(uid), "nickname"), "DISPUTE", did, "补证", "已追加", "处理中", "处理中(补证)",
                n(d, "revision"), content);
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
        String rid = id();
        db.exec("insert into reports(id,author_id,object_type,object_id,reason,evidence,status,created_at) values(#{p.id},#{p.u},#{p.ty},#{p.o},#{p.r},#{p.e},'待处理',#{p.t})",
                p("id", rid, "u", uid, "ty", type, "o", obj, "r", text(b, "reason", 1500), "e", evidence(b, "evidence"),
                        "t", now()));
        fact(uid, s(account(uid), "nickname"), "REPORT", rid, "提交举报", "待处理", null, "待处理", null,
                s(b, "reason"));
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
        return itemPeer(uid, obj, buyer, i, writing);
    }

    /**
     * 物品咨询的参与者判定与写入可用性（物品行 i 由调用方在适当时机提供：读取不持锁，发送在锁后重新读取）。
     *
     * 咨询人（买方）的确定不依赖请求参数 peer：
     * - 非卖方：咨询人就是通过认证的本人 uid，peer 为空（前端默认）正常，传了非空且≠本人的值明确拒绝 400；
     * - 卖方：必须给出真实咨询人的 peer，且只能进入该咨询人确曾发起过的会话（空值拒绝）。
     * 因此无论谁在读或写，实际读取/写入的都是“认证本人与对方”这一条会话，不读他人会话。
     *
     * 写入可用性对买卖双方一致：先校验参与者归属，再直接按统一可用性判断（运行期校验，防止并发状态
     * 变化越过边界），不依赖角色先后。参与者关系不因物品不可访问而放松：未发起过咨询的人仍不能进入，
     * 买卖双方也不因物品进入订单而获得原咨询之外的写权限。
     * 受限账户读取本人历史消息仍然允许（受限不收回查询权），但不能写入任何会话。
     */
    String itemPeer(String uid, String obj, String buyer, Map<String, Object> i, boolean writing) {
        String seller = s(i, "seller_id"), given = buyer == null ? "" : buyer.trim();
        boolean sellerView = uid.equals(seller);
        String inquirer;
        if (sellerView) {
            require(!given.isBlank() && !uid.equals(given), "请选择咨询对象。");
            inquirer = given;
        } else {
            require(given.isBlank() || given.equals(uid), "不能访问他人的咨询会话。");
            inquirer = uid;
        }
        // 会话是否存在：按咨询人 inquirer 与物主之间的真实记录判定；键必须带咨询人（不能只用 context_id）。
        var inquiry = db.one(
                "select id from messages where context_type='ITEM' and context_id=#{p.i} and sender_id=#{p.b} and receiver_id=#{p.s}",
                p("i", obj, "b", inquirer, "s", seller));
        if (sellerView)
            // 卖方只能进入买方确曾发起过的咨询；没有任何消息的“新会话”不能由卖方凭对方 id 建立。
            require(inquiry != null, "咨询会话不存在。");
        else
            require(available(i) || inquiry != null, "会话不可访问。");
        if (writing) {
            eligible(uid);
            require(available(i), "物品已不可公开咨询，请使用对应订单会话。");
        }
        return sellerView ? inquirer : seller;
    }

    public List<Map<String, Object>> messages(String uid, String type, String obj, String buyer) {
        String other = peer(uid, type, obj, buyer, false);
        return messageList(uid, type, obj, other);
    }

    /** 会话双方之间的消息（参与者已由 {@link #peer} 校验）；即时消息按原文返回，不做脱敏。 */
    List<Map<String, Object>> messageList(String uid, String type, String obj, String other) {
        return db.list(
                "select m.*,a.nickname as sender_name from messages m join accounts a on a.id=m.sender_id where context_type=#{p.ty} and context_id=#{p.i} and ((sender_id=#{p.u} and receiver_id=#{p.o}) or (sender_id=#{p.o} and receiver_id=#{p.u})) order by created_at,id",
                p("ty", type, "i", obj, "u", uid, "o", other));
    }

    /** 会话上下文：消息 + 服务端判定的只读状态（终态订单只读，服务端仍拒绝发送）。 */
    public Map<String, Object> context(String uid, String type, String obj, String buyer) {
        String other = peer(uid, type, obj, buyer, false);
        List<Map<String, Object>> rows = messageList(uid, type, obj, other);
        boolean readOnly;
        if (type.equals("ORDER")) {
            var o = order(uid, obj, false);
            readOnly = !ACTIVE.contains(s(o, "stage"));
        } else {
            readOnly = !available(item(obj, false));
        }
        return p("messages", rows, "readOnly", readOnly, "context_type", type, "context_id", obj);
    }

    /**
     * 订单按 {@link #lockOrder} 的统一顺序加锁后判定；
     * 物品咨询先锁账户再对物品行加锁（SELECT ... FOR UPDATE），在锁后的最新读取上判定参与者与可用性，
     * 因此发起单、审核状态、隐藏、账户限制等并发变化不会让发送越过只读边界。
     */
    @Transactional
    public void message(String uid, String type, String obj, String buyer, Map<String, Object> b) {
        String other;
        if (type.equals("ORDER")) {
            lockOrder(uid, obj, false);
            other = peer(uid, type, obj, buyer, true);
        } else {
            require(type.equals("ITEM"), "会话类型无效。");
            var hint = item(obj, false);
            lockAccounts(uid, s(hint, "seller_id"));
            var i = item(obj, true);
            other = itemPeer(uid, obj, buyer, i, true);
        }
        db.exec("insert into messages(id,context_type,context_id,sender_id,receiver_id,content,created_at) values(#{p.id},#{p.ty},#{p.i},#{p.u},#{p.o},#{p.c},#{p.t})",
                p("id", id(), "ty", type, "i", obj, "u", uid, "o", other, "c", text(b, "content", 1500), "t", now()));
        notify("message:" + id(), other, "收到一条新消息。");
        afterCommit();
    }

    public List<Map<String, Object>> inbox(String uid) {
        account(uid);
        return db.list(
                "select distinct m.context_type,m.context_id,case when m.sender_id=#{p.u} then m.receiver_id else m.sender_id end as peer_id from messages m where sender_id=#{p.u} or receiver_id=#{p.u}",
                p("u", uid));
    }

    /**
     * 管理台数据：仅管理员可读。items/accounts/reports/reviews/logs/facts 为全站治理范围；
     * verifications 是整个认证队列；disputes 是整个争议队列，每条附带 evidence_log
     * （补证记录 {id,author_id,content,created_at}），与管理台核查订单依据的接口配合使用。
     */
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
                // 管理台与当事人列表使用同一补证辅助方法，争议处置时可核查锁后追加的证据记录。
                db.list("select d.*,o.title,o.stage from disputes d join orders o on o.id=d.order_id order by d.created_at desc",
                        p()).stream().map(this::withEvidenceLog).toList(),
                "reports", db.list("select * from reports order by created_at desc", p()), "reviews",
                db.list("select * from reviews order by created_at desc", p()), "logs",
                db.list("select l.*,a.nickname from audit_logs l join accounts a on a.id=l.actor_id order by l.created_at desc limit 100",
                        p()),
                "facts",
                db.list("select f.*,a.nickname as actor_name from audit_facts f join accounts a on a.id=f.actor_id order by f.created_at desc limit 200",
                        p()));
    }

    /** 解析统计时间参数：只接受带明确偏移的 ISO-8601 写法（Z、小数秒任意位数、+08:00 等），统一为 Instant。 */
    static Instant instant(String value, String label) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiError(label + "格式错误，请使用 ISO-8601 时间，例如 2026-11-04T13:55:56Z 或 2026-11-04T21:55:56+08:00。");
        }
    }

    /**
     * 成交统计：按完成时刻做 [from, to) 的真实时间比较，而不是比较 ISO 字符串。
     *
     * 旧数据的 completed_at 是 VARCHAR 且不同记录的小数秒位数不同（如 .892773700Z、.892Z、无小数秒），
     * 字符串比较会把这些写成不同形式的同一时刻判成不同结果，直接改写历史又可能截断纳秒。
     * 因此本项目（课程规模，已完成订单为千条量级）读取已完成订单的完成时刻与快照金额，
     * 在 Java 中用 Instant 精确比较完成时刻、用 BigDecimal 汇总快照金额；不落库改写旧时间，
     * 也不对原始 ISO 字符串做排序比较（包括不以字符串条件预筛，避免任何依赖书写格式的边界）。
     * 若数据量增长，可另做保留纳秒的时间类型迁移（列类型迁移，非字符串排序）。
     */
    public Map<String, Object> statistics(String uid, String from, String to) {
        admin(uid);
        Instant lower = from.isBlank() ? null : instant(from.trim(), "开始时间");
        Instant upper = to.isBlank() ? null : instant(to.trim(), "结束时间");
        String sql = "select price,completed_at,created_at from orders where stage='已完成'";
        // 只取需要的三列：旧库可能有更大列或后续新增列，避免一次读出全部宽行。
        int count = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (var row : db.list(sql, p())) {
            Object rawPrice = row.get("price");
            Instant done;
            try {
                done = Instant.parse(s(row, "completed_at"));
            } catch (DateTimeParseException e) {
                continue;
            }
            if (lower != null && done.compareTo(lower) < 0)
                continue;
            if (upper != null && done.compareTo(upper) >= 0)
                continue;
            count++;
            amount = amount.add(new BigDecimal(String.valueOf(rawPrice)));
        }
        return statsResult(count, amount);
    }

    Map<String, Object> statsResult(int count, BigDecimal amount) {
        return p("sales", p("count", count, "amount", amount),
                "items", db.one("select count(*) as count from items", p()), "reports",
                db.one("select count(*) as count from reports", p()), "basis",
                "成交按完成时刻筛选：[开始, 结束)，按解析后的时间精确比较；金额按订单快照汇总；发布/举报数量为累计值。");
    }

    /**
     * 管理员订单核查视图：仅当该订单存在关联争议或订单举报（且未对他单擅自开放）时返回
     * {order,delivery,reviews}；每次查看写入一条带管理员身份的处置事实与审计日志。
     * 管理员不能冒用买方身份确认或评价，也不因此获得无关订单的访问权。
     */
    @Transactional
    public Map<String, Object> adminOrderDetails(String uid, String oid) {
        admin(uid);
        var hint = db.one("select buyer_id,seller_id from orders where id=#{p.id}", p("id", oid));
        if (hint == null)
            throw new ApiError(404, "订单不存在。");
        // 与订单操作相同的加锁顺序：双方账户 → 订单；锁后重新确认治理关联，避免检查后关联被并发改变。
        lockAccounts(s(hint, "buyer_id"), s(hint, "seller_id"));
        var o = db.one("select * from orders where id=#{p.id} for update", p("id", oid));
        long disputes = ((Number) db.one("select count(*) as c from disputes where order_id=#{p.o}",
                p("o", oid)).get("c")).longValue();
        long reports = ((Number) db.one("select count(*) as c from reports where object_type='ORDER' and object_id=#{p.o}",
                p("o", oid)).get("c")).longValue();
        require(disputes > 0 || reports > 0, "该订单没有关联争议或举报，治理范围不开放。");
        var detail = orderDetail(o);
        fact(uid, s(account(uid), "nickname"), "ORDER", oid, "管理员查看订单核查依据", "已留痕",
                "争议=" + disputes + "/举报=" + reports, null, n(o, "item_version"), "治理核查");
        return detail;
    }

    /** 当事人订单详情：本人订单的当前进度、交付登记明细（含操作者、说明与时间）与评价。 */
    public Map<String, Object> orderDetails(String uid, String oid) {
        var o = order(uid, oid, false);
        return orderDetail(o);
    }

    Map<String, Object> orderDetail(Map<String, Object> o) {
        String oid = s(o, "id");
        var delivery = db.one(
                "select d.order_id,d.actor_id,a.nickname as actor_name,d.note,d.created_at from deliveries d left join accounts a on a.id=d.actor_id where d.order_id=#{p.o}",
                p("o", oid));
        if (delivery != null) {
            delivery.put("actor_name", s(delivery, "actor_name"));
            delivery.put("created_at", s(delivery, "created_at"));
        }
        var reviews = db.list("select id,order_id,author_id,target_id,score,content,hidden,created_at from reviews where order_id=#{p.o} order by created_at",
                p("o", oid)).stream().map(r -> {
                    r.put("content", images.mask(s(r, "content")));
                    return r;
                }).toList();
        return p("order", o, "delivery", delivery, "reviews", reviews);
    }

    @Transactional
    public void manage(String uid, String kind, String obj, Map<String, Object> b) {
        admin(uid);
        String actor = s(account(uid), "nickname");
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
                        p("s", pass ? "通过" : "退回", "r", clip(reason, 1000), "id", obj));
                fact(uid, actor, "VERIFICATION", obj, "认证审核", pass ? "通过" : "退回", "待审", pass ? "通过" : "退回",
                        null, reason);
                notify("verification:" + obj, s(v, "user_id"), "校园认证" + (pass ? "通过" : "退回：" + clip(reason, 1000)));
            }
            case "item" -> {
                var i = item(obj, true);
                require(s(i, "status").equals("待审"), "物品已非待审。");
                require(s(b, "version").equals(String.valueOf(n(i, "version"))), "审核版本已经变化，请刷新。");
                boolean pass = Boolean.TRUE.equals(b.get("pass"));
                int version = n(i, "version");
                db.exec("update items set status=#{p.s},reason=#{p.r} where id=#{p.id}",
                        p("s", pass ? "在售" : "审核退回", "r", clip(reason, 1000), "id", obj));
                fact(uid, actor, "ITEM", obj, "审核版本 v" + version, pass ? "通过" : "退回", "待审",
                        pass ? "在售" : "审核退回", version, reason);
                notify("item-review:" + obj + ":v" + version, s(i, "seller_id"),
                        "物品审核" + (pass ? "通过" : "退回：" + clip(reason, 1000)));
            }
            case "hide" -> {
                var i = item(obj, true);
                int nowValue = Boolean.TRUE.equals(b.get("hidden")) ? 1 : 0;
                int before = n(i, "hidden");
                db.exec("update items set hidden=#{p.h} where id=#{p.id}", p("h", nowValue, "id", obj));
                fact(uid, actor, "ITEM", obj, "隐藏开关", nowValue == 1 ? "已隐藏" : "已解除隐藏", before, nowValue,
                        n(i, "version"), reason);
            }
            case "restrict" -> {
                lockAccounts(obj);
                var u = account(obj);
                require(!s(u, "role").equals("ADMIN"), "不能通过此入口限制管理员。");
                int nowValue = Boolean.TRUE.equals(b.get("restricted")) ? 1 : 0;
                int before = n(u, "restricted");
                db.exec("update accounts set restricted=#{p.r} where id=#{p.id}", p("r", nowValue, "id", obj));
                String factId = (String) fact(uid, actor, "ACCOUNT", obj, "账户限制开关",
                        nowValue == 1 ? "已限制" : "已恢复", before, nowValue, null, reason).get("id");
                notify("restrict:" + factId, obj, reason);
            }
            case "relist" -> {
                var i = item(obj, true);
                require(s(i, "status").equals("交易异常终止"), "仅异常终止物品需要核实归属。");
                db.exec("update items set relist_allowed=1 where id=#{p.id}", p("id", obj));
                // 物品可多次异常终止并再次核实：每次核实是独立事件，事件键用本次核实生成的处置事实标识
                // factId（每次都不同），不能用品主或物品标识作键；同一事件的投递重试复用该记录。
                String factId = (String) fact(uid, actor, "ITEM", obj, "核实归属", "允许重新提交", n(i, "relist_allowed"), 1,
                        n(i, "version"), reason).get("id");
                notify("relist:" + factId, s(i, "seller_id"), "物品归属已核实，可修改后重新提交审核。");
            }
            case "dispute" -> {
                var hint = db.one("select * from disputes where id=#{p.id}", p("id", obj));
                require(hint != null, "争议不存在。");
                var o = lockOrder(uid, s(hint, "order_id"), true);
                var d = db.one("select * from disputes where id=#{p.id} for update", p("id", obj));
                require(s(d, "status").equals("处理中") && ACTIVE.contains(s(o, "stage")), "争议已结束。");
                String outcome = text(b, "outcome", 20);
                require(Set.of("continue", "terminate").contains(outcome), "处置方式无效。");
                String result = outcome.equals("continue") ? "继续履约" : "异常终止";
                if (outcome.equals("terminate")) {
                    require(Boolean.TRUE.equals(b.get("evidence_confirmed")), "须确认已核查无法履约依据，不能仅因沉默终止。");
                    transition(uid, o, "已异常终止", reason);
                }
                db.exec("update orders set disputed=0 where id=#{p.id}", p("id", o.get("id")));
                // 以 status='处理中' 为条件做一次真实的当前决策：并发/重复结案只有一个能改变争议行；
                // 未改变说明另一事务已结案，本次整体拒绝（不重复通知、不追加结案事实）。
                int closed = db.exec("update disputes set status='已结案',outcome=#{p.o},decision=#{p.r} where id=#{p.id} and status='处理中'",
                        p("o", result, "r", reason, "id", obj));
                require(closed == 1, "争议已结束。");
                fact(uid, actor, "DISPUTE", obj, "争议结案", result, s(o, "stage"), "已结案/" + result, n(o, "item_version"),
                        reason);
                String oid = s(o, "id");
                // 通知内容最长 1000 字（NotificationDelivery 投递时截断），而处置说明最长 1500 字：
                // 因此把订单/争议标识（短号与完整 id）与结论放在说明之前，说明只保留摘要并指向全文，
                // 保证截断后仍能定位到本次事件；完整原因仍保存在 disputes.decision 与处置事实中，不删除。
                String summary = clip(reason, 200);
                String msg = "争议已结案：" + s(o, "title") + "；结论：" + result + "；订单 " + oid + "（订单号 "
                        + oid.substring(0, 8) + "），争议 " + obj + "（争议号 " + obj.substring(0, 8) + "）；说明（摘要）："
                        + summary + (summary.length() < reason.length() ? "…" : "")
                        + "。完整说明请到“我的订单”该订单的履约争议查看。";
                // 争议只结案一次（上面的条件更新保证）；事件键是争议标识本身，双方各自一条，
                // 同一争议的重复通知登记或投递重试都落到同一条待投递记录，不会重复发送。
                notify("dispute-closed:" + obj, s(o, "buyer_id"), msg);
                notify("dispute-closed:" + obj, s(o, "seller_id"), msg);
            }
            case "report" -> {
                var r = db.one("select * from reports where id=#{p.id} for update", p("id", obj));
                require(r != null, "举报不存在。");
                String ref = optional(b, "event_ref", 100);
                db.exec("update reports set status='已处理',result=#{p.r},event_ref=#{p.e} where id=#{p.id}",
                        p("r", reason, "e", ref, "id", obj));
                // 同一举报可因新增事实再次处置：每次处置是独立事件，事件键用本次处置生成的处置事实标识
                // factId（每次都不同）；同一事件的投递重试复用该记录，不重复通知。
                String factId = (String) fact(uid, actor, "REPORT", obj, "举报处置", "已处理", s(r, "status"), "已处理",
                        null, reason).get("id");
                notify("report-result:" + factId, s(r, "author_id"), "举报处理结果：" + reason);
            }
            case "review" -> {
                var r = db.one("select * from reviews where id=#{p.id} for update", p("id", obj));
                require(r != null, "评价不存在。");
                int nowValue = Boolean.TRUE.equals(b.get("hidden")) ? 1 : 0;
                int before = n(r, "hidden");
                db.exec("update reviews set hidden=#{p.h} where id=#{p.id}", p("h", nowValue, "id", obj));
                fact(uid, actor, "REVIEW", obj, "评价可见性", nowValue == 1 ? "已隐藏" : "已恢复", before, nowValue, null,
                        reason);
            }
            default -> throw new ApiError(404, "未知管理操作。");
        }
        afterCommit();
    }
}
