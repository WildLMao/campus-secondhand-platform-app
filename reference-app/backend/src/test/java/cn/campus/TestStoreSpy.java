package cn.campus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 测试层 Store 的 spy：生产 Store 的真实 @SpyBean，只用来观察/改写 SQL 的发起时机与结果，
 * 产品代码中不存在任何测试开关。所有方法默认调用真实实现。
 *
 * <ul>
 * <li>failNextNotificationInserts(n)：只让接下来 n 次写 notifications 的 SQL 抛
 * DataAccessResourceFailureException（模拟通知存储不可写），其余 SQL（账户、物品、订单、占用、
 * 待投递记录等）保持真实；抛出的异常从真实 DB 调用之前发生，业务事务与投递用的 REQUIRES_NEW 事务都是真的。</li>
 * <li>pauseAfterDisputeHintRead(did, arrived, release)：让指定争议的第一次普通读取返回真实结果后挂起，
 * 用于确定性构造“补证已读旧状态→管理员结案→补证继续加锁”的并发时序。</li>
 * </ul>
 *
 * 所有线程共享同一个 spy（Spring 注入的 @SpyBean），因此每个测试都不重启应用上下文。
 */
final class TestStoreSpy {
    private TestStoreSpy() {
    }

    private static final AtomicInteger notificationFaults = new AtomicInteger();
    private static final ConcurrentHashMap<String, Object[]> pausedReads = new ConcurrentHashMap<>();
    private static final java.util.List<String> pauseHits = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static final AtomicInteger notificationInsertFailures = new AtomicInteger();

    static void install(Store spy) {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            // 只拦截“写入 notifications”的成功路径：标记 FAILED 是另一条 update，不受影响。
            // 生产写法为 "insert into notifications(...)" 前缀，这里两种写法都识别。
            if (sql != null && sql.startsWith("insert into notifications")) {
                if (notificationFaults.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    notificationInsertFailures.incrementAndGet();
                    throw new DataAccessResourceFailureException("测试层故障：通知存储不可写");
                }
                @SuppressWarnings("unchecked")
                var params = (java.util.Map<String, Object>) inv.getArgument(1);
                if (params != null && params.get("c") != null)
                    notificationInserts.add(String.valueOf(params.get("c")));
            }
            return inv.callRealMethod();
        }).when(spy).exec(anyString(), any());

        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            var params = (java.util.Map<String, Object>) inv.getArgument(1);
            String did = params == null ? null : String.valueOf(params.get("id"));
            var pause = did == null ? null : pausedReads.get(did);
            // 只拦“按主键普通读取”这一种（有 FOR UPDATE 的锁后读取不经过它）
            if (pause != null && "select * from disputes where id=#{p.id}".equals(sql) && pausedReads.remove(did, pause)) {
                Object real = inv.callRealMethod();
                pauseHits.add(did);
                ((CountDownLatch) pause[1]).countDown();
                if (!((CountDownLatch) pause[2]).await(20, TimeUnit.SECONDS))
                    throw new IllegalStateException("测试协调超时：补证线程未被放行");
                return real;
            }
            return inv.callRealMethod();
        }).when(spy).one(anyString(), any());
    }

    /** 成功写入 notifications 的内容快照（供断言“真实写库只发生一次”）。 */
    static final java.util.List<String> notificationInserts = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static void resetProbe() {
        notificationInsertFailures.set(0);
        notificationInserts.clear();
    }

    static int notificationInsertFailures() {
        return notificationInsertFailures.get();
    }

    static long notificationInsertsOf(String content) {
        return notificationInserts.stream().filter(content::equals).count();
    }

    /** 直接在真实 Store 上写入一条 PENDING 待投递记录（等价于业务事务已登记、尚未投递）。 */
    static void insertPendingOutbox(Store store, String userId, String content) {
        store.exec("insert into notification_outbox(id,user_id,content,created_at,status,attempts) values(#{p.id},#{p.u},#{p.c},#{p.t},'PENDING',0)",
                Store.p("id", MarketService.id(), "u", userId, "c", content, "t", MarketService.now()));
    }

    static void failNextNotificationInserts(int n) {
        notificationFaults.set(n);
    }

    static void clearNotificationFaults() {
        notificationFaults.set(0);
    }

    static int remainingNotificationFaults() {
        return notificationFaults.get();
    }

    static void pauseAfterDisputeHintRead(String did, CountDownLatch arrived, CountDownLatch release) {
        pausedReads.put(did, new Object[] { did, arrived, release });
    }

    static void clearPauses() {
        pausedReads.clear();
        pauseHits.clear();
    }

    static boolean paused(String did) {
        return pauseHits.contains(did);
    }
}
