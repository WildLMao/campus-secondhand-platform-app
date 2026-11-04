-- V2 查询索引：覆盖公开列表、本人订单、占用互斥、治理查询与统计区间。
-- 时间列为定长 UTC ISO-8601 字符串，按字符串比较等价于按时间比较，可直接建立索引。
-- 索引名在整个 schema 内唯一，便于 H2 本地开发库与 MySQL 使用同一脚本。

CREATE INDEX idx_accounts_role ON accounts (role);

CREATE INDEX idx_assets_owner ON assets (owner_id, kind);

CREATE INDEX idx_verifications_user ON verifications (user_id, status);

CREATE INDEX idx_items_seller_created ON items (seller_id, created_at);

CREATE INDEX idx_items_status_hidden ON items (status, hidden);

CREATE INDEX idx_items_category ON items (category);

CREATE INDEX idx_orders_buyer_created ON orders (buyer_id, created_at);

CREATE INDEX idx_orders_seller_created ON orders (seller_id, created_at);

CREATE INDEX idx_orders_item ON orders (item_id);

CREATE INDEX idx_orders_stage_completed ON orders (stage, completed_at);

CREATE INDEX idx_receipts_order ON receipts (order_id);

CREATE INDEX idx_favorites_item ON favorites (item_id);

CREATE INDEX idx_reviews_target ON reviews (target_id, hidden);

CREATE INDEX idx_reviews_order ON reviews (order_id);

CREATE INDEX idx_disputes_order ON disputes (order_id, status);

CREATE INDEX idx_reports_object ON reports (object_type, object_id);

CREATE INDEX idx_reports_status ON reports (status);

CREATE INDEX idx_messages_context ON messages (context_type, context_id, created_at);

CREATE INDEX idx_messages_receiver ON messages (receiver_id, created_at);

CREATE INDEX idx_audit_created ON audit_logs (created_at);

CREATE INDEX idx_notifications_user ON notifications (user_id, created_at);
