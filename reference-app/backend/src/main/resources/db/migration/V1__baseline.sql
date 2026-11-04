-- V1 空库基线：创建业务表。
-- 执行者：cn.campus.SchemaMigrator，按版本号顺序执行一次并记录到 schema_migrations。
-- 时间列统一保存 UTC 的 ISO-8601 字符串（Instant.toString()，以 Z 结尾，长度 <= 35），
-- 不使用数据库会话时区做隐式换算；金额统一 DECIMAL(12,2)，单位为人民币元。
-- 说明：CREATE TABLE IF NOT EXISTS 只用于兼容首版已有的本地 H2 文件库；
-- 后续结构升级必须新增 V2、V3…，不得修改已执行的脚本内容。

CREATE TABLE IF NOT EXISTS accounts (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 username VARCHAR(50) NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 nickname VARCHAR(40) NOT NULL,
 role VARCHAR(10) NOT NULL DEFAULT 'USER',
 verified INT NOT NULL DEFAULT 0,
 restricted INT NOT NULL DEFAULT 0,
 created_at VARCHAR(35) NOT NULL,
 CONSTRAINT uq_accounts_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS assets (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 owner_id VARCHAR(40) NOT NULL,
 kind VARCHAR(15) NOT NULL,
 media_type VARCHAR(40) NOT NULL,
 created_at VARCHAR(35) NOT NULL
);

CREATE TABLE IF NOT EXISTS verifications (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 user_id VARCHAR(40) NOT NULL,
 student_no VARCHAR(50) NOT NULL,
 real_name VARCHAR(50) NOT NULL,
 asset_id VARCHAR(40) NOT NULL,
 status VARCHAR(20) NOT NULL,
 reason VARCHAR(1000),
 created_at VARCHAR(35) NOT NULL
);

CREATE TABLE IF NOT EXISTS identities (
 student_no VARCHAR(50) NOT NULL PRIMARY KEY,
 user_id VARCHAR(40) NOT NULL,
 CONSTRAINT uq_identities_user UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS items (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 seller_id VARCHAR(40) NOT NULL,
 title VARCHAR(120) NOT NULL,
 category VARCHAR(30) NOT NULL,
 description TEXT NOT NULL,
 price DECIMAL(12,2) NOT NULL,
 image_id VARCHAR(40),
 version INT NOT NULL,
 status VARCHAR(30) NOT NULL,
 hidden INT NOT NULL DEFAULT 0,
 reason VARCHAR(1000),
 relist_allowed INT NOT NULL DEFAULT 0,
 created_at VARCHAR(35) NOT NULL
);

CREATE TABLE IF NOT EXISTS item_versions (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 item_id VARCHAR(40) NOT NULL,
 version INT NOT NULL,
 title VARCHAR(120) NOT NULL,
 description TEXT NOT NULL,
 price DECIMAL(12,2) NOT NULL,
 image_id VARCHAR(40),
 category VARCHAR(30),
 created_at VARCHAR(35) NOT NULL,
 CONSTRAINT uq_item_versions UNIQUE (item_id, version)
);

CREATE TABLE IF NOT EXISTS orders (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 item_id VARCHAR(40) NOT NULL,
 buyer_id VARCHAR(40) NOT NULL,
 seller_id VARCHAR(40) NOT NULL,
 title VARCHAR(120) NOT NULL,
 description TEXT NOT NULL,
 price DECIMAL(12,2) NOT NULL,
 image_id VARCHAR(40),
 buyer_name VARCHAR(40) NOT NULL,
 seller_name VARCHAR(40) NOT NULL,
 item_version INT NOT NULL,
 stage VARCHAR(30) NOT NULL,
 disputed INT NOT NULL DEFAULT 0,
 reminded INT NOT NULL DEFAULT 0,
 reason VARCHAR(1000),
 created_at VARCHAR(35) NOT NULL,
 completed_at VARCHAR(35)
);

CREATE TABLE IF NOT EXISTS reservations (
 item_id VARCHAR(40) NOT NULL PRIMARY KEY,
 order_id VARCHAR(40) NOT NULL,
 CONSTRAINT uq_reservations_order UNIQUE (order_id)
);

CREATE TABLE IF NOT EXISTS deliveries (
 order_id VARCHAR(40) NOT NULL PRIMARY KEY,
 actor_id VARCHAR(40) NOT NULL,
 note VARCHAR(1000) NOT NULL,
 created_at VARCHAR(35) NOT NULL
);

CREATE TABLE IF NOT EXISTS receipts (
 actor_id VARCHAR(40) NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 operation VARCHAR(150) NOT NULL,
 payload_hash VARCHAR(100) NOT NULL,
 order_id VARCHAR(40) NOT NULL,
 created_at VARCHAR(35) NOT NULL,
 PRIMARY KEY (actor_id, request_key)
);

CREATE TABLE IF NOT EXISTS favorites (
 user_id VARCHAR(40) NOT NULL,
 item_id VARCHAR(40) NOT NULL,
 PRIMARY KEY (user_id, item_id)
);

CREATE TABLE IF NOT EXISTS reviews (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 order_id VARCHAR(40) NOT NULL,
 author_id VARCHAR(40) NOT NULL,
 target_id VARCHAR(40) NOT NULL,
 score INT NOT NULL,
 content VARCHAR(1000) NOT NULL,
 hidden INT NOT NULL DEFAULT 0,
 created_at VARCHAR(35) NOT NULL,
 CONSTRAINT uq_reviews_author UNIQUE (order_id, author_id)
);

CREATE TABLE IF NOT EXISTS disputes (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 order_id VARCHAR(40) NOT NULL,
 author_id VARCHAR(40) NOT NULL,
 reason VARCHAR(1500) NOT NULL,
 evidence TEXT NOT NULL,
 status VARCHAR(20) NOT NULL,
 outcome VARCHAR(30),
 decision VARCHAR(1500),
 created_at VARCHAR(35) NOT NULL
);

CREATE TABLE IF NOT EXISTS reports (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 author_id VARCHAR(40) NOT NULL,
 object_type VARCHAR(20) NOT NULL,
 object_id VARCHAR(40) NOT NULL,
 reason VARCHAR(1500) NOT NULL,
 evidence TEXT NOT NULL,
 status VARCHAR(20) NOT NULL,
 result VARCHAR(1500),
 event_ref VARCHAR(100),
 created_at VARCHAR(35) NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 context_type VARCHAR(10) NOT NULL,
 context_id VARCHAR(40) NOT NULL,
 sender_id VARCHAR(40) NOT NULL,
 receiver_id VARCHAR(40) NOT NULL,
 content VARCHAR(1500) NOT NULL,
 created_at VARCHAR(35) NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 actor_id VARCHAR(40) NOT NULL,
 action VARCHAR(100) NOT NULL,
 object_id VARCHAR(40) NOT NULL,
 reason VARCHAR(1500),
 created_at VARCHAR(35) NOT NULL
);

CREATE TABLE IF NOT EXISTS notifications (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 user_id VARCHAR(40) NOT NULL,
 content VARCHAR(1000) NOT NULL,
 created_at VARCHAR(35) NOT NULL
);
