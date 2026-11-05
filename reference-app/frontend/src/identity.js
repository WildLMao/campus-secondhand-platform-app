// 与后端 MarketService.saveItem 一致的前端可预检校验；不是授权，服务端仍校验归属与版本。
export const CATEGORIES = ['教材书籍', '数码配件', '宿舍生活', '运动户外', '其他'];

export function priceError(v) {
  const s = String(v == null ? '' : v).trim();
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return '价格必须是最多两位小数的正数。';
  const n = Number(s);
  if (!(n > 0) || n > 9999999999.99) return '价格超出范围。';
  return '';
}

export function itemError(f) {
  const t = String(f.title || '').trim(), d = String(f.description || '').trim(), c = String(f.category || '');
  if (!t) return '请填写物品名称。';
  if (t.length > 120) return '物品名称不能超过 120 字。';
  if (!CATEGORIES.includes(c)) return '请选择有效分类。';
  if (!d) return '请填写物品描述。';
  if (d.length > 5000) return '物品描述不能超过 5000 字。';
  const p = priceError(f.price);
  if (p) return p;
  if (!String(f.image_id || '').trim()) return '请先上传本人的物品图片。';
  return '';
}

// 发布请求体与静态提交完全一致：保留最后一次提交的内容再附带操作标识。
export function itemBody(f) {
  return { title: f.title, category: f.category, description: f.description, price: f.price, image_id: f.image_id };
}
