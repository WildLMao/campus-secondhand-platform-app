// 订单详情视图模型：兼容新 GET /orders/{id}/details 的 {order,delivery,reviews}，
// 以及仅 {order,reviews} 或缺字段的返回；缺失不伪造。
const TERMINAL = ['已完成', '已取消', '已拒绝', '已异常终止'];

export function orderDetails(data) {
  const order = (data && data.order) || null;
  return {
    order,
    delivery: (data && data.delivery) || null,
    reviews: data && Array.isArray(data.reviews) ? data.reviews : [],
  };
}

// 治理详情：争议 d.order_id / 订单类型举报 r.object_id。仅当事人订单才可打开；纯查询。
export function governanceOrderId(kind, row) {
  if (!row) return '';
  if (kind === 'dispute') return row.order_id || '';
  if (kind === 'report') return row.object_type === 'ORDER' ? (row.object_id || '') : '';
  return '';
}

export function isParty(order, me) {
  return !!order && !!me && (order.buyer_id === me.id || order.seller_id === me.id);
}

export function isTerminal(stage) {
  return TERMINAL.includes(stage);
}

// 按 author_id 映射买卖双方，评价附显示名；不认识的作者退化为“交易方”。
export function reviewRows(reviews, order) {
  return (reviews || []).map(r => ({
    ...r,
    authorName: order && r.author_id === order.seller_id ? order.seller_name
      : order && r.author_id === order.buyer_id ? order.buyer_name : '交易方',
    targetName: order && r.author_id === order.seller_id ? order.buyer_name
      : order && r.author_id === order.buyer_id ? order.seller_name : '',
  }));
}

// 本人是否已评：以服务端评价列表为准（含隐藏评价），不依赖仅详情页的本地缓存。
export function myReview(reviews, me) {
  if (!me) return null;
  return (reviews || []).find(r => r.author_id === me.id) || null;
}

// 本人尚无可见评价记录 → 提交入口可以填写；新增成功后由 addReview 置为已评。
export function canReview(order, reviews, me) {
  if (!order || !me) return false;
  if (order.stage !== '已完成') return false;
  return !alreadyReviewed(order, reviews, me);
}

// 本地标记合并：重新打开列表后服务器记录可能延迟，标记仍生效，但不倒填他人。
export function alreadyReviewed(order, reviews, me, marked) {
  if (!me || !order) return false;
  if (myReview(reviews, me)) return true;
  return !!(marked && marked[order.id] === me.id);
}

export function addReview(reviews, review) {
  const next = (reviews || []).slice();
  if (!next.some(r => r.author_id === review.author_id)) next.push(review);
  return next;
}

// 当前可执行动作，仅由阶段、争议与卖家身份决定；登记交付后不显示任何“完成”改字入口。
export function actionsFor(order, me) {
  if (!order || !me || !isParty(order, me)) return [];
  const seller = order.seller_id === me.id;
  if (order.disputed) return [];
  if (order.stage === '待卖方确认') return seller ? ['accept', 'reject'] : ['cancel'];
  if (order.stage === '待交付') return seller ? ['deliver', 'cancel'] : ['cancel'];
  if (order.stage === '待买方确认') return seller ? (order.reminded ? [] : ['remind']) : ['confirm'];
  return [];
}

export function showConfirmReceived(order, me) {
  return actionsFor(order, me).includes('confirm');
}

// 新 GET /details 失败（如后端尚未上线）时，仅对已完成订单退避到既有的 /orders/{id}/reviews。
export function fallbackReviews(load, id) {
  return load(id).then(list => ({ order: null, delivery: null, reviews: Array.isArray(list) ? list : [] }));
}
