// 履约争议补充证据：GET /disputes 每条返回 evidence_log:[{id,author_id,content,created_at}]，
// 原始 evidence 保持不变。两者分开渲染；补充人只按可核实的名字映射，匹配不到显示编号。

const LOG_ID = /^[A-Za-z0-9_-]{1,40}$/;

function text(v) {
  if (v == null) return '';
  try { return String(v); } catch (e) { return ''; }
}

// 只接受数组形式的 evidence_log；缺字段或非数组时视为没有补充，不伪造。
export function evidenceLog(dispute) {
  const rows = dispute && Array.isArray(dispute.evidence_log) ? dispute.evidence_log : [];
  return rows
    .filter(r => r && typeof r === 'object')
    .map(r => ({
      id: text(r.id),
      author_id: text(r.author_id),
      content: text(r.content),
      created_at: text(r.created_at),
    }))
    .filter(r => r.content.trim() !== '');
}

// 可核实名字来源，按优先级：订单快照里的买家/卖家名、当前登录用户本人。
// otherUsers 只用于已由服务端返回过的公开昵称（例如同一响应内），本函数不会编造。
export function knownNames(order, me) {
  const names = new Map();
  if (me && me.id && me.nickname) names.set(text(me.id), text(me.nickname));
  if (order) {
    if (order.buyer_id && order.buyer_name && !names.has(text(order.buyer_id))) names.set(text(order.buyer_id), text(order.buyer_name));
    if (order.seller_id && order.seller_name && !names.has(text(order.seller_id))) names.set(text(order.seller_id), text(order.seller_name));
  }
  return names;
}

// 补充人展示名：命中已知名字则显示名字；否则显示“用户·<编号>”，绝不冒用他人姓名。
export function authorLabel(authorId, known) {
  const id = text(authorId);
  if (!id) return '提交人未知';
  const map = known instanceof Map ? known : new Map(Object.entries(known || {}));
  if (map.get(id)) return map.get(id);
  return '用户·' + (LOG_ID.test(id) ? id.slice(0, 8) : '未知编号' + (id ? '·' + id.slice(0, 8) : ''));
}

// 原始证据与补充证据的组合视图：原始 evidence 原文保留，补充按时间升序编号，内容原样返回（不解析 HTML）。
export function evidenceView(dispute, order, me) {
  const known = knownNames(order, me);
  const original = text(dispute && dispute.evidence);
  const supplements = evidenceLog(dispute).map((r, i) => ({
    ...r,
    index: i + 1,
    author: authorLabel(r.author_id, known),
    isMine: !!(me && me.id && r.author_id === text(me.id)),
  }));
  return {
    original,
    hasOriginal: original.trim() !== '',
    supplements,
    count: supplements.length,
  };
}
