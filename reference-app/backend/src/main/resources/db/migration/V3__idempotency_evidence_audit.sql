-- V3 业务事实新增表（只做 CREATE，不改写 V1/V2 中的既有记录）。
-- 已执行脚本不得修改；H2(MODE=MySQL) 与 MySQL 8 共用本脚本。

-- 发布/修改的提交意图：同一操作者、同一 Idempotency-Key 只对应一个物品版本。
CREATE TABLE IF NOT EXISTS item_receipts (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 actor_id VARCHAR(40) NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 operation VARCHAR(60) NOT NULL,
 payload_hash VARCHAR(100) NOT NULL,
 item_id VARCHAR(40) NOT NULL,
 item_version INT NOT NULL,
 created_at VARCHAR(35) NOT NULL,
 CONSTRAINT uq_item_receipts UNIQUE (actor_id, request_key),
 CONSTRAINT uq_item_receipts_version UNIQUE (item_id, item_version)
);

-- 争议补证：追加记录，不改写已据以结案的证据集合。
CREATE TABLE IF NOT EXISTS dispute_evidence (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 dispute_id VARCHAR(40) NOT NULL,
 author_id VARCHAR(40) NOT NULL,
 content TEXT NOT NULL,
 created_at VARCHAR(35) NOT NULL
);

CREATE INDEX idx_dispute_evidence_dispute ON dispute_evidence (dispute_id, created_at);

-- 处置事实：明确对象类型、动作、结果及其前后值；业务行只保留当前状态，历史在本表追加且不改写。
CREATE TABLE IF NOT EXISTS audit_facts (
 id VARCHAR(40) NOT NULL PRIMARY KEY,
 actor_id VARCHAR(40) NOT NULL,
 object_type VARCHAR(20) NOT NULL,
 object_id VARCHAR(40) NOT NULL,
 action VARCHAR(60) NOT NULL,
 result VARCHAR(40) NOT NULL,
 before_value VARCHAR(100),
 after_value VARCHAR(100),
 version INT,
 reason VARCHAR(1500),
 created_at VARCHAR(35) NOT NULL
);

CREATE INDEX idx_audit_facts_object ON audit_facts (object_type, object_id, created_at);
