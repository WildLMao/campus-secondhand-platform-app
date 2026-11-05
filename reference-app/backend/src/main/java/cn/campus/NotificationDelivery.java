package cn.campus;

import static cn.campus.Store.p;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 通知投递：业务事务只追加一行 PENDING 待投递记录；事务提交后（或以后下发时）在新事务中写入 notifications。
 *
 * 投递使用独立 REQUIRES_NEW 事务并从连接池取得新连接，不在业务事务的连接/提交之后继续写入，
 * 因此投递失败只回滚该次投递，不会撤销已成立的业务结果；业务 SQL 异常发生在业务事务内，
 * 由调用方照常抛出并整体回滚，这里只处理投递阶段的失败。
 *
 * 重试：投递失败记 FAILED（保留失败次数 attempts 与原因 last_error），每次投递先按失败次数从少到多
 * （同次数按登记时间）取一批 FAILED 记录重试，再取一批 PENDING 记录投递。失败记录不会因次数耗尽被永久丢弃
 * （课程规模不做退避队列），通知模块或 notifications 表恢复后会继续补投；已 SENT 的记录不会再投递。
 */
@Component
public class NotificationDelivery {
    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);
    static final int BATCH = 20;

    final Store db;
    final TransactionTemplate requiresNew;

    NotificationDelivery(Store db, PlatformTransactionManager transactions) {
        this.db = db;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 仅业务事务内调用：追加待投递记录。
     * eventKey 标识“一次业务事件”：同一事件重试投递始终使用同一键；不同次处置须使用不同键
     * （调用方以本次处置事实标识区分）。标识由事件键 + 接收人确定，供去重与补投识别。
     */
    void enqueue(String eventKey, String userId, String content) {
        String dedupe = eventKey + ":" + userId;
        String outboxId;
        try {
            outboxId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(dedupe.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var row = p("id", outboxId, "u", userId, "c", MarketService.clip(content, 1000), "t", MarketService.now());
        try {
            db.exec("insert into notification_outbox(id,user_id,content,created_at,status,attempts) values(#{p.id},#{p.u},#{p.c},#{p.t},'PENDING',0)",
                    row);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 同一事件在同一事务内重复登记：说明调用方没有区分处置事件，按冲突处理而不吞掉业务事实。
            log.warn("通知事件重复登记：{}", eventKey);
            throw new ApiError(409, "通知事件重复登记，请稍后重试。");
        }
    }

    /** 业务事务提交后调用；投递异常只记录，不向调用方传播。 */
    void afterCommit() {
        try {
            deliverPending();
        } catch (RuntimeException e) {
            log.warn("通知投递失败，待投递记录保留以便重试：{}", e.getMessage());
        }
    }

    /**
     * 在新事务中先重试失败的记录，再投递待发送记录；单条失败标记后继续，不影响业务数据。
     * FAILED 每批 20 条按 attempts 升序轮转：失败最少的先重试，重试仍失败会加一，排在后面，
     * 使持续失败的少数记录不会长期占住批次、遮挡其他可补投记录；不设次数上限，不做永久丢弃。
     */
    public void deliverPending() {
        List<Map<String, Object>> rows = db.list(
                "select id from notification_outbox where status='FAILED' order by attempts,created_at,id limit " + BATCH,
                p());
        deliver(rows);
        rows = db.list("select id from notification_outbox where status='PENDING' order by created_at,id limit " + BATCH,
                p());
        deliver(rows);
    }

    void deliver(List<Map<String, Object>> rows) {
        for (var row : rows) {
            String outboxId = MarketService.s(row, "id");
            try {
                requiresNew.executeWithoutResult(status -> deliverOne(outboxId));
            } catch (RuntimeException e) {
                log.warn("通知 {} 投递失败：{}", outboxId, e.getMessage());
                markFailed(outboxId, e.getMessage());
            }
        }
    }

    void deliverOne(String outboxId) {
        var row = db.one("select * from notification_outbox where id=#{p.id} for update", p("id", outboxId));
        if (row == null || "SENT".equals(MarketService.s(row, "status")))
            return;
        db.exec("insert into notifications(id,user_id,content,created_at) values(#{p.id},#{p.u},#{p.c},#{p.t})",
                p("id", outboxId, "u", row.get("user_id"), "c", row.get("content"), "t", row.get("created_at")));
        db.exec("update notification_outbox set status='SENT',delivered_at=#{p.t},attempts=attempts+1,last_error=null where id=#{p.id}",
                p("t", MarketService.now(), "id", outboxId));
    }

    void markFailed(String outboxId, String error) {
        try {
            requiresNew.executeWithoutResult(status -> db.exec(
                    "update notification_outbox set status='FAILED',attempts=attempts+1,last_error=#{p.e} where id=#{p.id} and status<>'SENT'",
                    p("e", MarketService.clip(error, 200), "id", outboxId)));
        } catch (RuntimeException e) {
            // 记录失败状态本身失败（例如数据库不可用）时保持原状态，下次投递会再次尝试。
            log.warn("记录通知失败状态时出错：{}", e.getMessage());
        }
    }
}
