-- V4 根除并发丢更新。
-- 争议与交付此前用「锁前读取 + 旧值拼接/条件判断」，MySQL READ COMMITTED 下锁后读到的是最新行；
-- 显式版本列让服务端在锁后重读并核对该次修改依据的版本，避免旧快照覆盖新事实。
-- 存量记录以 1 为基线；新建争议/交付从 1 开始，每次成功修改 +1。

ALTER TABLE disputes ADD COLUMN revision INT NOT NULL DEFAULT 1;

ALTER TABLE deliveries ADD COLUMN revision INT NOT NULL DEFAULT 1;
