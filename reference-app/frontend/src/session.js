// 匿名状态的完整视图与各私有表单初值：所有“私有的东西”集中定义，退出/换号统一取回。
export const EMPTY_LOGIN = { username: '', password: '', nickname: '' };
export const EMPTY_VERIFY = { student_no: '', real_name: '', asset_id: '' };
export const EMPTY_ITEM = { id: '', title: '', category: '教材书籍', description: '', price: '', image_id: '' };
export const EMPTY_FIELDS = {};
export const EMPTY_DIALOG = { dialogue: null, publicReviews: null, chat: null, messages: [], messageText: '', modal: null, fields: {} };
export const EMPTY_COLLECTIONS = {
  orders: [], mine: [], favorites: [], verifications: [], disputes: [], reports: [],
  notifications: [], inbox: [], management: null, statistics: null,
};

export function emptyPrivate() {
  return {
    orders: [], mine: [], favorites: [], verifications: [], disputes: [], reports: [],
    notifications: [], inbox: [], management: null, statistics: null,
    chat: null, messages: [], messageText: '', dialog: null, fields: {},
    publicReviews: null,
    verifyForm: { ...EMPTY_VERIFY },
    itemForm: { ...EMPTY_ITEM },
  };
}

// 只比较身份字段；同一账号刷新资料不触发清理。
export function accountChanged(before, after) {
  const a = before && before.id, b = after && after.id;
  return a !== b;
}

// 异步响应是否仍属于当前账户（响应返回前换号则丢弃，不回填）。
export function sameAccount(before, current) {
  return (before && before.id || null) === (current && current.id || null);
}

// 旧请求在会话失效（401/403）后经 /me 获得的身份与发起时不同 → 丢弃旧响应。
export function staleFor(requestActor, resolvedActor) {
  return (requestActor && requestActor.id || null) !== (resolvedActor && resolvedActor.id || null);
}

// 登录框显示身份提示；提交成功后只接受当前对话框的结果。
export function loginSubmitSucceeded(user) {
  return !!user;
}

export function displayName(user) {
  return user && user.nickname ? user.nickname : '';
}

// 待认证标记跳转个人中心（已登录）或打开登录（未登录）。
export function verifyAction(user) {
  return user ? 'account' : 'login';
}
