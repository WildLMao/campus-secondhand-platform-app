-- V5 待投递通知（事务性发件箱记录）。
-- 业务事务只写一行待投递记录，事务提交后由独立 REQUIRES_NEW 事务写入 notifications；
-- 投递失败只影响该行状态，不回滚业务；进程中断留下的 PENDING 行在下次投递或下发时补投。

CREATE TABLE IF NOT EXISTS notification_outbox (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 user_id VARCHAR(40) NOT NULL,
 content VARCHAR(1000) NOT NULL,
 created_at VARCHAR(35) NOT NULL,
 status VARCHAR(20) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(200),
 delivered_at VARCHAR(35)
);

CREATE INDEX idx_outbox_status ON notification_outbox (status, created_at);
