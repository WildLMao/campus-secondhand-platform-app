<script setup>
import { reactive, computed, onMounted, onUnmounted } from 'vue';
import { api, setCsrf, setFavorite, orderDetails, adminOrderDetails } from './api';
import { acquire, settle, settleIfApplied, clearScope, storage, peek, fingerprint } from './intent';
import { enterAction, composing, composeAction, chatEditable, messageList } from './iam';
import { orderDetails as shapeDetails, reviewRows, canReview, alreadyReviewed, addReview, actionsFor, governanceOrderId } from './orders';
import { itemError, itemBody, CATEGORIES } from './identity';
import { emptyPrivate, accountChanged, sameAccount, staleFor, verifyAction, favoriteView } from './session';
import { statsQuery } from './stats';
import { evidenceView } from './evidence';
const start=emptyPrivate();
const user=reactive({value:null});
const ui=reactive({tab:'market',feedback:'准备好了。从一件闲置物品，开始一次校园循环。',error:false,busy:false});
const items=reactive({value:[]}),mine=reactive({value:[]}),orders=reactive({value:[]}),favorites=reactive({value:[]}),verifications=reactive({value:[]}),disputes=reactive({value:[]}),reports=reactive({value:[]}),notifications=reactive({value:[]}),inbox=reactive({value:[]}),management=reactive({value:null}),statistics=reactive({value:null});
const ui2=reactive({query:'',category:'',page:1,adminTab:'items'});
const categories=CATEGORIES;
const dialog=reactive({value:null}),fields=reactive({}),chatState=reactive({value:null}),msgs=reactive({value:[]}),draft=reactive({text:''}),publicReviews=reactive({value:null});
const registerMode=reactive({value:false});
const login=reactive({username:'',password:'',nickname:''});
// 每个账户独立的私有表单；换号/退出时重置为初值。
const itemForm=reactive({...start.itemForm});
const verifyForm=reactive({...start.verifyForm});
const dateFilter=reactive({from:'',to:''});
// 已完成订单评价：服务器评价列表 + 本会话“本人已评”标记，不在重新打开后丢失。
const orderData=reactive({});
const reviewed=reactive({});
const detailError=reactive({});
// 管理台治理订单详情：按订单 id 缓存，纯查询。
const govDetail=reactive({});
let msgPending=false,acting=false,epoch=0;
// 当前身份世代：换号/退出/会话失效后递增，旧响应与旧错误不得回填、不得展示。
function bumpEpoch(){epoch++;}
// 操作所有权：ui.busy 只由“持有锁的那一次 safe”释放。token 与身份世代 epoch 相互独立——
// 换号/退出/登录引起的 epoch 变化不得把正在结束的当前操作卡在 busy；旧操作（token 已失效）也不得清掉新操作的忙态。
let uiOwner=0,uiDone=0;
const isAdmin=computed(()=>user.value?.role==='ADMIN');
const active=computed(()=>orders.value.filter(o=>['待卖方确认','待交付','待买方确认'].includes(o.stage)));
const done=computed(()=>orders.value.filter(o=>o.stage==='已完成'));
const money=n=>Number(n||0).toFixed(2);
const date=v=>v?new Date(v).toLocaleString('zh-CN',{hour12:false}):'—';
const short=v=>v?.slice(0,8);
function say(text,failed=false){ui.feedback=text;ui.error=failed;}
// 先记下旧 scope（旧账户或 anon），切号后清其意图表/输入指纹，再清内存状态；只清 campus: 前缀键。
function resetPrivate(oldId){bumpEpoch();clearScope([oldId===undefined?'anon':(oldId||'anon')]);const s=emptyPrivate();orders.value=s.orders;mine.value=s.mine;favorites.value=s.favorites;verifications.value=s.verifications;disputes.value=s.disputes;reports.value=s.reports;notifications.value=s.notifications;inbox.value=s.inbox;management.value=s.management;statistics.value=s.statistics;chatState.value=s.chat;msgs.value=s.messages;draft.text=s.messageText;dialog.value=s.dialog;for(const k in fields)delete fields[k];publicReviews.value=s.publicReviews;Object.assign(verifyForm,s.verifyForm);Object.assign(itemForm,s.itemForm);}
function clearGov(){for(const k in govDetail)delete govDetail[k];}
function clearAccount(){clearGov();for(const k in orderData)delete orderData[k];for(const k in reviewed)delete reviewed[k];for(const k in detailError)delete detailError[k];}
function setUser(next){user.value=next||null;}
// 账户变化（含退出）时清空所有私有状态；同一账户仅刷新资料。
function applyUser(next,force=false){const old=user.value;setUser(next);if(force||accountChanged(old,next)){resetPrivate(old&&old.id);clearAccount();Object.assign(login,{username:'',password:'',nickname:''});ui.tab='market';return true;}return false;}
const scope=()=>[user.value?.id||'anon'];
// 先占锁再执行；返回 runPromise（供 submitDialog 等调用方判断失败），未占锁返回 undefined。
function safe(fn){
 if(ui.busy)return;
 const token=++uiOwner;ui.busy=true;const actor=user.value;const gen=epoch;
 // 错误只记在本次操作自己的 token 上；调用方（submitDialog）据此判断失败，不会误读别的操作的错误。
 const op={token,failed:false,auth:false};
 return (async()=>{
  try{await fn(actor);}catch(e){
   op.failed=true;op.auth=e.status===401||e.status===403;
   if(epoch!==gen)return;
   if(op.auth){
    let me=null;try{me=await api('/me');}catch(err){}
    if(staleFor(actor,me?.user)||epoch!==gen)return;
    setCsrf(me?.csrf||'');
    if(!me?.user){applyUser(null);return say('登录已失效，请重新登录。',true);}
    applyUser(me.user);
   }
   if(epoch===gen)say(e.message,true);
  }finally{
   // 只有仍持有锁的本次操作能解锁：与 epoch 无关，旧操作不得误清新操作。
   if(uiOwner===token){ui.busy=false;uiDone=token;}
  }
 })();
}
let searchVersion=0;
async function market(){const version=++searchVersion;const who=user.value?.id||null;const rows=await api(`/items?q=${encodeURIComponent(ui2.query)}&category=${encodeURIComponent(ui2.category)}&page=${ui2.page}`);if(version!==searchVersion||(user.value?.id||null)!==who)return;items.value=rows;}
async function refresh(actor=user.value){
 if(!actor)return;
 const me=await api('/me');setCsrf(me.csrf);if(accountChanged(actor,me.user)||!me.user){applyUser(me.user);return;}
 applyUser(me.user);
 const [m,o,f,v,d,r,n,i]=await Promise.all(['/items/mine','/orders','/favorites','/verifications','/disputes','/reports','/notifications','/inbox'].map(p=>api(p)));
 if(!sameAccount(actor,user.value))return;
 mine.value=m;orders.value=o;favorites.value=f;verifications.value=v;disputes.value=d;reports.value=r;notifications.value=n;inbox.value=i;
 if(isAdmin.value)await loadAdmin(actor);
}
function statsParams(){const q=statsQuery(dateFilter.from,dateFilter.to);const parts=[];if(q.from)parts.push('from='+encodeURIComponent(q.from));if(q.to)parts.push('to='+encodeURIComponent(q.to));return {query:parts.length?'?'+parts.join('&'):'',errors:q.errors,ok:q.ok};}
async function loadAdmin(actor=user.value){
 const p=statsParams();if(!p.ok)throw new Error(p.errors.join(' '));
 const [m,s]=await Promise.all([api('/admin/dashboard'),api('/admin/stats'+p.query)]);
 if(!sameAccount(actor,user.value))return;management.value=m;statistics.value=s;
}
async function loadStats(){await safe(async()=>{const p=statsParams();if(!p.ok)return say(p.errors.join(' '),true);statistics.value=await api('/admin/stats'+p.query);});}
async function navigate(next){if(next!=='market'&&!user.value){openLogin();return;}ui.tab=next;if(user.value)await safe(async()=>{await refresh();});}
function openLogin(){ui.error=false;const type=registerMode.value?'register':'login';dialog.value={type,title:type==='register'?'注册校园账户':'欢迎回到校园循环'};}
async function authenticate(){
 // 登录弹窗有自己的 safe（不是 submitDialog 的 open() 表单）；已在处理中则忽略重复提交。
 if(ui.busy)return;
 const mode=registerMode.value?'register':'login';const body={...login};
 await safe(async()=>{
   // 先登录成功并切换身份，再清空输入；登录失败时保留已填账号等输入以便重试。
   const data=await api('/auth/'+mode,'POST',body);
   Object.assign(login,{username:'',password:'',nickname:''});
   if(dialog.value?.type!==mode)return;
   setCsrf(data.csrf);applyUser(data.user);dialog.value=null;
   // 登录后的资料刷新失败不回滚登录态：已认证身份保持，并允许重新进入登录弹窗。
   try{await refresh(data.user);}catch(e){bumpEpoch();say('登录成功，资料加载失败：'+e.message,true);return;}
   say('你好，'+(data.user.nickname||'')+'。');
 });
}
async function logout(){
 await safe(async()=>{
  await api('/auth/logout','POST',{});
  applyUser(null);Object.assign(login,{username:'',password:'',nickname:''});dialog.value=null;
  let me=null;try{me=await api('/me');setCsrf(me.csrf);}catch(e){}
  items.value=[];await market();say('已退出登录。');
 });
}
function goVerify(){if(verifyAction(user.value)==='login'){registerMode.value=false;openLogin();return;}ui.tab='account';if(user.value)safe(async()=>{await refresh();});}
function open(title,form,submit,help=''){ui.error=false;for(const k in fields)delete fields[k];form.forEach(f=>fields[f.key]=f.value??(f.type==='check'?false:''));dialog.value={title,form,submit,help};}
async function submitDialog(){
 const current=dialog.value;if(!current)return;
 // 唯一的 safe：open() 的 submit 回调只做请求与返回结果，内部不得再进 safe（否则看 ui.busy 立即返回、真实请求被跳过）。
 const gen=epoch;
 const submit=async(actor)=>{
  const result=await current.submit({...fields},actor);
  // 验证未通过（false）/请求异常（在 safe 内抛出前到此为止）：保留弹窗与输入，不显示成功。
  if(result===false)return;
  if(dialog.value!==current)return;
  dialog.value=null;
  if(result!==undefined&&result!==null)say(typeof result==='string'?result:'操作已保存。');
  await refresh(actor);
 };
 const run=safe(submit);
 if(!run)return;
 await run;
 // 接口失败时 safe 已把后端错误原因显示在弹窗/提示里，这里不再用通用文案覆盖；
 // 仅当身份世代已变、safe 按 epoch 隔离没有显示原因时，补一句（弹窗、输入与未决 key 仍保留）。
 if(dialog.value===current&&gen!==epoch&&!ui.error)say('操作未完成，请核对后重试。',true);
}
// 未决意图的事实来源只有 intent map：每个槽位自带 fp/key（acquire 按 (账户, 槽位) 保存，多槽位并存）。
// 携带操作标识执行请求：同内容（fp 相同）沿用原 key；5xx/网络失败保留原 key；成功或可证明未生效的 4xx 释放。
// 槽位里已有未决记录且内容变了：必须用户确认作为新操作，才 acquire 覆盖为新 key；不同意则不发送、旧 key 原样保留。
async function idempotent(path,body,slot,method='POST'){
 const actor=user.value,gen=epoch,store=storage(),scopes=scope();
 const prior=peek(scopes,slot,store);
 if(prior&&prior.fp!==fingerprint(actor&&actor.id,path,body)){
  if(!confirm('上次相同操作的结果未知，而本次内容已经变化。若上次已生效，本次会作为新的操作再执行一次。确定继续吗？'))return {cancelled:true};
 }
 const met=acquire(scopes,slot,actor&&actor.id,path,body,store);
 try{const result=await api(path,method,body,met.key);settleIfApplied(scopes,slot,met.key,'ok',store);return {result,gen:genId(gen),key:met.key};}
 catch(e){settleIfApplied(scopes,slot,met.key,e&&e.status?{status:e.status}:{},store);throw e;}
}
function genId(gen){return gen===epoch?'current':'stale';}
const TITLES={accept:'接受订单',reject:'拒绝订单',deliver:'登记交付',confirm:'确认收到',cancel:'取消订单',remind:'提醒买方'};
function orderAction(o,action){
 const form=[];if(['reject','deliver','cancel'].includes(action))form.push({key:'reason',label:action==='deliver'?'交付记录（时间、地点、说明）':'原因',type:'textarea',required:true,max:1000});
 if(['deliver','confirm','cancel'].includes(action))form.push({key:'declared',label:action==='deliver'?'我确认已经实际交付物品':action==='confirm'?'我本人已经收到物品':'我声明尚未实际交付；若已交付或有分歧，应申请争议处理',type:'check',required:true});
 const body=b=>({reason:(b.reason||'').trim(),declared:!!b.declared});
 open(TITLES[action],form,async (b,actor)=>{
  if(form.some(f=>f.required&&!b[f.key]))return false;
  const path=`/orders/${o.id}/${action}`,payload=body(b),gen=epoch;
  if(acting)return '上一项订单操作仍在处理中，请稍候。';
  acting=true;
  let out;try{out=await idempotent(path,payload,'order:'+o.id+':'+action);}finally{acting=false;}
  // 未确认作为新操作：不发送、不显示成功，保留弹窗与输入。
  if(out.cancelled)return false;
  if(!out.result||gen!==epoch||!sameAccount(actor,user.value))return '订单已提交，请以订单列表结果为准。';
  const result=out.result;
  orders.value=orders.value.map(x=>x.id===result.id?{...x,...result}:x);
  await refreshOrders(actor);
  return '订单状态已更新。';
 },action==='deliver'?'登记交付后仍须买方本人确认，才计入成交。':'操作会按当前订单阶段和本人权限进行检查。');
}
async function refreshOrders(actor=user.value){await refresh(actor);}
// 买入：内容固定为 item_id + 版本；同内容重试携带原 key，改选其他物品不同槽位。
function buy(item){
 if(!user.value)return openLogin();
 const body={item_id:item.id,version:item.version};
 open('确认下单',[{key:'declared',type:'check',label:`我已确认「${item.title}」及价格 ¥ ${money(item.price)}`,required:true}],async (b,actor)=>{
  if(!b.declared)return false;
  if(acting)return '上一项下单请求仍在处理中，请稍候。';
  acting=true;
  let out;try{out=await idempotent('/orders',body,'buy:'+item.id);}finally{acting=false;}
  // 未确认作为新操作：不发送、不导航、不显示成功，保留弹窗与勾选。
  if(out.cancelled)return false;
  if(!sameAccount(actor,user.value))return '下单请求已完成，请以订单列表结果为准。';
  await refresh(actor);
  if(sameAccount(actor,user.value)){ui.tab='orders';}
  return '下单请求已完成，请以订单列表结果为准。';
 },'下单会预占这件物品。款项由双方线下结算，平台不收款。');
}
async function upload(event,kind,target,key){
 if(!user.value)return openLogin();
 const file=event.target.files&&event.target.files[0];if(!file)return;
 const actor=user.value;
 await safe(async()=>{const form=new FormData();form.append('file',file);form.append('kind',kind);const result=await api('/assets','POST',form);
  if(!sameAccount(actor,user.value))return;target[key]=result.id;say('图片上传完成。');});
 event.target.value='';
}
function editItem(item){Object.assign(itemForm,{id:item.id,title:item.title,category:item.category,description:item.description,price:item.price,image_id:item.image_id});ui.tab='publish';}
function newItem(){Object.assign(itemForm,{id:'',title:'',category:'教材书籍',description:'',price:'',image_id:''});navigate('publish');}
async function publish(){
 const bad=itemError(itemForm);if(bad)return say(bad,true);
 const actor=user.value;const id=itemForm.id;const path=id?'/items/'+id:'/items';const body=itemBody({...itemForm});
 const slot='item:'+(id||'new')+JSON.stringify(body);
 await safe(async()=>{
  const out=await idempotent(path,body,slot,id?'PUT':'POST');
  // 未确认作为新操作：不清空表单、不跳转、不显示成功。
  if(out.cancelled)return say('未提交：本次内容与上次未决的提交不同，已取消。',true);
  Object.assign(itemForm,{id:'',title:'',category:'教材书籍',description:'',price:'',image_id:''});
  await refresh(actor);if(!sameAccount(actor,user.value))return;
  ui.tab='mine';say('物品已提交，当前版本审核通过后才会公开展示。');
 });
}
async function verify(){await safe(async()=>{await api('/verifications','POST',verifyForm);Object.assign(verifyForm,{student_no:'',real_name:'',asset_id:''});await refresh();say('校园认证已提交，请等待管理员审核。');});}
// 以下 open() 回调都在 submitDialog 的唯一 safe 内执行：只发请求并返回结果，内部不再进 safe。
function withdraw(item){open('撤下物品',[{key:'confirm',type:'check',label:'确认撤下，保留历史记录',required:true}],async b=>{if(!b.confirm)return false;await api('/items/'+item.id+'/withdraw','POST',{});return '物品已撤下。';});}
function isFavorite(id){return favorites.value.some(i=>i.id===id);}
// 收藏请求表达用户想要达到的目标状态，而不是“切换”。
async function favorite(item,on){if(!user.value)return openLogin();
 const target=on===undefined?!isFavorite(item.id):!!on;
 await safe(async()=>{await setFavorite(item.id,target);await refresh();say(target?'已加入收藏。':'已取消收藏。');});}
function isOwn(item){return !!user.value&&item?.seller_id===user.value.id;}
function reportObject(type,id){if(!user.value)return openLogin();open('提交举报',[{key:'reason',label:'举报事实与理由',type:'textarea',required:true,max:1500},{key:'evidence',label:'证据说明',type:'textarea',max:1500}],async b=>{await api('/reports','POST',{...b,object_type:type,object_id:id});return true;},'同事件可关联核查；每条举报保留独立回执，举报数量不直接判定违规。');}
function startDispute(o){open('申请履约争议处理',[{key:'reason',label:'履约分歧或已交付未登记情况',type:'textarea',required:true,max:1500},{key:'evidence',label:'相关证据、时间和情况',type:'textarea',max:1500}],async b=>{await api('/orders/'+o.id+'/disputes','POST',b);return true;},'受理后保留订单原阶段和占用，暂停接单、拒单、登记和普通取消。已登记时买方仍可确认收到。');}
function supplement(d){open('补充争议证据',[{key:'evidence',label:'新增证据与事实',type:'textarea',required:true,max:1500}],async b=>{await api('/disputes/'+d.id+'/evidence','POST',b);return true;});}
// 订单详情：GET /orders/{id}/details → {order,delivery,reviews}；本人已评以服务端评价为准。
async function loadDetails(o,actor=user.value){
 try{const data=await orderDetails(o.id);if(!sameAccount(actor,user.value))return;
  const d=shapeDetails(data);orderData[o.id]={...d,order:d.order||o,reviews:d.reviews};delete detailError[o.id];
 }catch(e){
  if(e.status===401||e.status===403)throw e;
  // 后端尚未提供 /details 时，已完成订单退避到既有 GET /orders/{id}/reviews。
  if(o.stage!=='已完成'){detailError[o.id]='该订单详情暂不可用。';return;}
  try{const list=await api('/orders/'+o.id+'/reviews');if(!sameAccount(actor,user.value))return;
   const prev=orderData[o.id]||{};
   orderData[o.id]={order:prev.order||o,reviews:Array.isArray(list)?list:[],delivery:prev.delivery||null};
  }catch(err){detailError[o.id]=err.message;}
 }
}
async function openDetails(o){await safe(async(actor)=>{await loadDetails(o,actor);});}
const orderFor=o=>(orderData[o.id]&&orderData[o.id].order)||o;
const myReviews=o=>reviewRows(orderData[o.id]?.reviews,orderFor(o));
const govReviews=id=>reviewRows(govDetail[id]?.reviews,govDetail[id]?.order||{});
// 履约争议证据：原始证据与补证日志分开；补证人只按可核实名字显示，匹配不到显示编号。
function evidenceFor(d,order){return evidenceView(d,order||null,user.value);}
function disputeOrder(d){return orders.value.find(o=>o.id===d.order_id)||null;}
// 管理台可见的昵称：管理台返回的账户列表 + 当前管理员本人；只用于可核实展示。
function userLabel(id){const acct=management.value?.accounts?.find(a=>a.id===id);return acct&&acct.nickname?acct.nickname:'用户·'+(id?id.slice(0,8):'未知编号');}
const statusLabel=o=>o&&o.title?o.title+' · '+(o.stage||''):'';
const hasReviewed=o=>alreadyReviewed(o,orderData[o.id]?.reviews,user.value,reviewed);
const mayReview=o=>canReview(o,orderData[o.id]?.reviews,user.value)&&hasReviewed(o)===false;
function review(o){
 if(hasReviewed(o))return say('本单已评价，可在订单详情查看。',false);
 open('评价本次交易',[{key:'score',label:'评分',type:'select',options:['5','4','3','2','1'],value:'5',required:true},{key:'content',label:'评价内容（请勿包含联系方式和个人身份信息）',type:'textarea',required:true,max:1000}],async (b,actor)=>{
  if(!String(b.content||'').trim())return false;
  await api('/orders/'+o.id+'/reviews','POST',b);
  if(!sameAccount(actor,user.value))return '';
  const rec={author_id:actor.id,score:Number(b.score),content:b.content,created_at:new Date().toISOString(),hidden:0};
  reviewed[o.id]=actor.id;const cur=orderData[o.id];if(cur)cur.reviews=addReview(cur.reviews,rec);
  await refresh(actor);
  return '评价已提交，感谢你的反馈。';
 },'完成订单双方各可评价一次。');
}
async function viewReviews(seller){const actor=user.value;await safe(async()=>{const rows=await api('/sellers/'+seller+'/reviews');if(!sameAccount(actor,user.value))return;publicReviews.value=rows;});}
// 聊天：以上下文对象标识当前会话，旧会话的迟到响应按对象丢弃。
function chatQuery(c){return '?'+new URLSearchParams({type:c.type,object:c.object,peer:c.peer||''}).toString();}
async function openChat(type,object,peer='',title='交易沟通'){
 if(!user.value)return openLogin();
 if(peer===''&&type==='ITEM'){
  const hinted=items.value.find(i=>i.id===object)||mine.value.find(i=>i.id===object);
  if(hinted&&isOwn(hinted))return say('这是你自己发布的物品，不能咨询自己。',true);
 }
 chatState.value={type,object,peer,title,messages:[],readOnly:true,loaded:false};
 msgs.value=[];draft.text='';
 await safe(async(actor)=>{await loadMessages(actor);});
}
async function loadMessages(actor=user.value){
 const c=chatState.value;if(!c)return;
 const data=await api('/messages/context'+chatQuery(c));
 if(chatState.value!==c||!sameAccount(actor,user.value))return;
 c.messages=messageList(data);c.readOnly=data.readOnly!==false;c.loaded=true;
 msgs.value=c.messages;
}
async function send(){
 const c=chatState.value;if(!c)return;if(!c.loaded)return say('正在读取会话状态，请稍候再发送。',true);
 if(c.readOnly)return say('订单已结束，会话只读。',true);
 const text=draft.text.trim();if(!text)return;if(msgPending)return;msgPending=true;
 try{await safe(async(actor)=>{await api('/messages'+chatQuery(c),'POST',{content:text});if(chatState.value!==c||!sameAccount(actor,user.value))return;draft.text='';await loadMessages(actor);});}finally{msgPending=false;}
}
// Enter 发送；Shift+Enter 由 textarea 插入换行；IME Enter 交给输入法（不发送）。
function onEnter(e){
 const act=composeAction(e);
 if(act.preventDefault)e.preventDefault();
 if(act.action!=='send')return;
 if(chatState.value&&!chatState.value.readOnly)send();
}
function manage(kind,obj,extra={},title='管理处置'){
 const form=[{key:'reason',label:'审核或核查依据与原因',type:'textarea',required:true,max:1500}];
 if(kind==='dispute'){form.push({key:'outcome',label:'处置结果',type:'select',options:['continue','terminate'],labels:['继续履约，保留原阶段','有依据异常终止'],value:'continue',required:true},{key:'evidence_confirmed',label:'若异常终止，我已核查无法履约依据；不以沉默作为唯一依据',type:'check'});}
 if(kind==='report')form.push({key:'event_ref',label:'同事件关联编号（可选）',type:'text',max:100});
 open(title,form,async b=>{
  const body={...extra,...b};
  if(!String(b.reason||'').trim())return false;
  const actor=user.value;
  await api(`/admin/${kind}/${obj}`,'POST',body);
  if(!sameAccount(actor,user.value))return '处置已记录，请以管理台结果为准。';
  management.value=await api('/admin/dashboard');await refresh(actor);
  return '处置已记录，相关通知与结果已刷新。';
 }, '审批或核查结论会记录到操作日志。');
}
// #6 管理台：从争议 order_id / 订单类举报 object_id 只读打开治理订单详情（无代收货、代评价）。
async function openGovOrder(kind,row){
 const id=governanceOrderId(kind,row);if(!id)return say('该记录不关联订单。',true);
 await safe(async(actor)=>{const data=await adminOrderDetails(id);if(!sameAccount(actor,user.value))return;
  const d=shapeDetails(data);govDetail[id]={...d,order:d.order||{id}};});
}
let poll;
onMounted(async()=>{await safe(async()=>{const me=await api('/me');setCsrf(me.csrf);setUser(me.user);if(me.user)await refresh(me.user);else await market();});
 poll=setInterval(()=>{if(chatState.value&&!ui.busy)loadMessages().catch(e=>say(e.message,true));},5000);});
onUnmounted(()=>clearInterval(poll));
</script>

<template>
 <header><a class="brand" href="#" @click.prevent="navigate('market')"><span class="mark">循</span><span><strong>校园循环</strong><small>让闲置物品，遇见下一位同学</small></span></a><div class="identity" v-if="user.value"><span>{{ user.value.nickname }} <button type="button" class="tag" :class="{warn:user.value.restricted||!user.value.verified}" @click="goVerify">{{ user.value.restricted?'账户受限':user.value.verified?'校园已认证':'待认证' }}</button></span><button @click="logout" :disabled="ui.busy">退出</button></div><button v-else class="primary" @click="openLogin">登录 / 注册</button></header>
 <div class="notice">校园单件二手物品交易 · 校内线下交付与结算 · 卖方登记交付，买方本人确认收到</div>
 <main>
  <nav aria-label="主导航"><button :class="{active:ui.tab==='market'}" @click="navigate('market')">发现物品</button><button :class="{active:ui.tab==='mine'||ui.tab==='publish'}" @click="navigate('mine')">我的发布</button><button :class="{active:ui.tab==='orders'}" @click="navigate('orders')">我的订单</button><button :class="{active:ui.tab==='messages'}" @click="navigate('messages')">消息</button><button :class="{active:ui.tab==='account'}" @click="navigate('account')">个人中心</button><button v-if="isAdmin" :class="{active:ui.tab==='admin'}" @click="navigate('admin')">管理台</button></nav>
  <div id="feedback" role="status" aria-live="polite" :class="{error:ui.error}">{{ ui.busy?'正在处理，请稍候…':ui.feedback }}<button class="refresh" :disabled="ui.busy" @click="safe(()=>refresh())">刷新</button></div>

  <section v-if="ui.tab==='market'">
   <div class="intro"><div><span class="eyebrow">CAMPUS CIRCULATION</span><h1>旧物有新去处，好物继续发光。</h1><p class="muted">找一本需要的书，也让不再需要的物品继续被珍惜。</p></div><button class="primary" @click="newItem">＋ 发布闲置</button></div>
   <form class="search" @submit.prevent="ui2.page=1;safe(market)"><input v-model="ui2.query" maxlength="120" placeholder="搜索教材、台灯、数码配件…" aria-label="搜索物品"><button class="primary" :disabled="ui.busy">搜索物品</button></form>
   <div class="chips"><button v-for="c in ['',...categories]" :key="c" :class="{active:ui2.category===c}" :aria-pressed="ui2.category===c" @click="ui2.category=c;ui2.page=1;safe(market)">{{ c||'全部物品' }}</button></div>
   <div class="grid"><article class="card item-card" v-for="i in items.value" :key="i.id"><img :src="'/api/assets/'+i.image_id" :alt="i.title" class="item-image"><div class="row"><span class="tag">{{ i.category }}</span><span class="muted small">{{ i.seller_name }}{{ isOwn(i)?' · 我发布的':'' }}</span></div><h2>{{ i.title }}</h2><p class="description clip">{{ i.description }}</p><div class="row"><div class="price">¥ {{ money(i.price) }}</div><button v-if="!isOwn(i)" class="primary" :disabled="ui.busy" @click="buy(i)">确认下单</button></div><div class="actions compact"><template v-if="!isOwn(i)"><button @click="favorite(i,true)">{{ isFavorite(i.id)?'已收藏':'收藏' }}</button><button @click="openChat('ITEM',i.id,'',i.title)">咨询卖方</button></template><template v-else><span class="muted small">自己的物品：可用“我的发布”管理</span></template><button @click="viewReviews(i.seller_id)">卖方评价</button><button @click="reportObject('ITEM',i.id)">举报</button></div></article></div>
   <div class="empty" v-if="!items.value.length"><strong>暂时没有找到物品</strong><p>换个关键词，或等待新物品审核上架。</p></div><div class="pagination"><button :disabled="ui2.page<=1||ui.busy" @click="ui2.page--;safe(market)">上一页</button><span>第 {{ ui2.page }} 页</span><button :disabled="items.value.length<20||ui.busy" @click="ui2.page++;safe(market)">下一页</button></div>
  </section>
  <section v-if="ui.tab==='mine'"><div class="intro"><div><h1>我的发布</h1><p class="muted">每次关键修改都生成新版本，重新审核。</p></div><button class="primary" @click="newItem">＋ 发布闲置</button></div><div class="grid"><article class="card" v-for="i in mine.value" :key="i.id"><div class="row"><span class="tag">{{ i.status }}{{ i.hidden?' · 已隐藏':'' }}</span><span class="muted">版本 {{ i.version }}</span></div><h2>{{ i.title }}</h2><p class="price">¥ {{ money(i.price) }}</p><p>{{ i.reason||'暂无审核意见' }}</p><div class="actions"><button v-if="['在售','待审','审核退回','已撤下','交易异常终止'].includes(i.status)" @click="editItem(i)">修改 / 重新提交</button><button v-if="['在售','待审','审核退回'].includes(i.status)" @click="withdraw(i)">撤下</button></div></article></div><div class="empty" v-if="!mine.value.length">还没有发布物品，试着发布第一件闲置吧。</div></section>
  <section v-if="ui.tab==='publish'"><div class="intro"><div><h1>{{ itemForm.id?'修改并重新审核':'发布一件闲置' }}</h1><p class="muted">单件实物、正数价格，审核通过后公开。有效订单期间不能修改。</p></div></div><form class="card form" @submit.prevent="publish"><label>物品名称<input v-model="itemForm.title" required maxlength="120"></label><div class="form-grid"><label>分类<select v-model="itemForm.category"><option v-for="c in categories" :key="c">{{ c }}</option></select></label><label>价格 / 元<input type="number" min="0.01" max="9999999999.99" step="0.01" v-model="itemForm.price" required></label></div><label>物品描述<textarea v-model="itemForm.description" rows="5" required maxlength="5000" placeholder="成色、使用情况、瑕疵等，请如实说明。不要填写公开联系方式。"></textarea></label><label>物品图片（JPEG / PNG，最大 5MB）<input type="file" accept="image/png,image/jpeg" @change="upload($event,'ITEM',itemForm,'image_id')"></label><img class="preview" v-if="itemForm.image_id" :src="'/api/assets/'+itemForm.image_id" alt="物品图片预览"><div class="actions"><button class="primary" :disabled="ui.busy||!itemForm.image_id">提交审核</button><button type="button" @click="navigate('mine')">返回</button></div></form></section>

  <section v-if="ui.tab==='orders'"><div class="intro"><div><h1>我的订单</h1><p class="muted">订单保留成立时的物品与价格快照。交付登记不等于成交。</p></div><span class="tag">进行中 {{ active.length }} · 已完成 {{ done.length }}</span></div><article class="card" v-for="o in orders.value" :key="o.id"><div class="row"><span class="tag">{{ o.stage }}{{ o.disputed?' · 争议处理中':'' }}</span><span class="muted small">{{ date(o.created_at) }} · {{ short(o.id) }}</span></div><h2>{{ o.title }}</h2><p class="description">{{ orderFor(o).description }}</p><div class="row"><span class="price">¥ {{ money(o.price) }}</span><span class="muted">买方 {{ orderFor(o).buyer_name }} ／ 卖方 {{ orderFor(o).seller_name }}</span></div><div class="steps"><span v-for="stage in ['待卖方确认','待交付','待买方确认','已完成']" :key="stage" :class="{current:o.stage===stage}">{{ stage }}</span></div><p v-if="o.reason" class="muted">操作原因：{{ o.reason }}</p><div class="actions"><button class="primary" :disabled="ui.busy" v-for="key in actionsFor(o,user.value)" :key="key" @click="orderAction(o,key)">{{ TITLES[key] }}</button><button @click="openChat('ORDER',o.id,'',o.title)">{{ ['已完成','已取消','已拒绝','已异常终止'].includes(o.stage)?'查看历史沟通':'订单沟通' }}</button><button v-if="['待卖方确认','待交付','待买方确认'].includes(o.stage)&&!o.disputed" @click="startDispute(o)">申请争议处理</button><button @click="openDetails(o)">查看详情</button><button v-if="o.stage==='已完成'&&!hasReviewed(o)" @click="review(o)">评价交易</button><span v-else-if="o.stage==='已完成'" class="tag">本人已评价</span><button @click="reportObject('ORDER',o.id)">举报反馈</button></div>
   <div v-if="orderData[o.id]" class="detail"><h2>订单依据与评价</h2><p class="muted small">交付登记</p><div v-if="orderData[o.id].delivery"><p>登记人：{{ orderData[o.id].delivery.actor_id===orderFor(o).seller_id?orderFor(o).seller_name:orderData[o.id].delivery.actor_id===orderFor(o).buyer_id?orderFor(o).buyer_name:'交易方' }} · 登记时间 {{ date(orderData[o.id].delivery.created_at) }}</p><pre class="evidence">{{ orderData[o.id].delivery.note||'（未填写说明）' }}</pre></div><p v-else class="muted">{{ o.stage==='已完成'?'未返回交付登记明细。':'尚未登记交付。' }}</p><p class="muted small">本单评价（双方各一次）</p><article class="log" v-for="r in myReviews(o)" :key="r.id"><strong>{{ r.authorName }} → {{ r.targetName }} · {{ r.score }} 分<span v-if="r.author_id===user.value?.id" class="tag">我的评价</span><span v-if="r.hidden" class="tag">已被治理隐藏</span></strong><p>{{ r.content }}</p><small>{{ date(r.created_at) }}</small></article><p v-if="!orderData[o.id].reviews.length" class="muted">双方还没有评价。</p><button v-if="o.stage==='已完成'&&!hasReviewed(o)&&mayReview(o)" class="primary" @click="review(o)">评价交易</button></div>
  </article><div class="empty" v-if="!orders.value.length">还没有订单，去发现物品逛逛吧。</div><h2 v-if="disputes.value.length">我的履约争议</h2><article class="card" v-for="d in disputes.value" :key="d.id"><div class="row"><strong>订单 {{ short(d.order_id) }}</strong><span class="tag">{{ d.status }}</span></div><p>{{ d.reason }}</p><h3 class='evidence-title'>原始证据</h3><pre v-if="evidenceFor(d,disputeOrder(d)).hasOriginal" class="evidence">{{ evidenceFor(d,disputeOrder(d)).original }}</pre><p v-else class='muted'>申请时未填写证据说明。</p><h3 class='evidence-title'>补充证据（{{ evidenceFor(d,disputeOrder(d)).count }} 条）</h3><ol v-if="evidenceFor(d,disputeOrder(d)).count" class='evidence-list'><li v-for="e in evidenceFor(d,disputeOrder(d)).supplements" :key="e.id||e.index"><div class="row"><strong>{{ e.author }}<span v-if="e.isMine" class="tag">我</span></strong><small class="muted">{{ date(e.created_at) }}</small></div><pre class="evidence">{{ e.content }}</pre></li></ol><p v-else class='muted'>尚无补充证据。</p><p v-if="d.decision">{{ d.outcome }}：{{ d.decision }}</p><button v-if="d.status==='处理中'" @click="supplement(d)">补充证据</button></article></section>
  <section v-if="ui.tab==='messages'"><div class="intro"><div><h1>消息与通知</h1><p class="muted">咨询与订单沟通分别保存，终态订单会话只读。</p></div><button :disabled="ui.busy" @click="safe(()=>refresh())">刷新通知</button></div><div class="grid"><div><h2>会话</h2><article class="card row" v-for="c in inbox.value" :key="c.context_type+c.context_id+c.peer_id"><span>{{ c.context_type==='ORDER'?'订单沟通':'物品咨询' }} · {{ short(c.context_id) }}</span><button @click="openChat(c.context_type,c.context_id,c.peer_id)">打开</button></article><div class="empty" v-if="!inbox.value.length">暂无会话，可从物品或订单发起沟通。</div></div><div><h2>站内通知</h2><article class="card" v-for="n in notifications.value" :key="n.id"><p>{{ n.content }}</p><small class="muted">{{ date(n.created_at) }}</small></article><div class="empty" v-if="!notifications.value.length">暂无通知。</div></div></div></section>
  <section v-if="ui.tab==='account'&&user.value"><div class="intro"><div><h1>{{ user.value.nickname }}的个人中心</h1><p class="muted">账号 {{ user.value.username }} · {{ user.value.verified?'校园认证已通过':'尚未通过校园认证' }}</p></div></div><div class="grid"><div><form v-if="!user.value.verified" class="card form" @submit.prevent="verify"><h2>校园身份认证</h2><p class="muted">材料只供本人及授权审核人员查看。</p><label>真实姓名<input v-model="verifyForm.real_name" required maxlength="50"></label><label>学号<input v-model="verifyForm.student_no" required maxlength="50"></label><label>认证材料图片<input type="file" accept="image/jpeg,image/png" @change="upload($event,'VERIFY',verifyForm,'asset_id')"></label><p v-if="verifyForm.asset_id" class="tag">材料已上传</p><button class="primary" :disabled="ui.busy||!verifyForm.asset_id">提交认证</button></form><article class="card" v-for="v in verifications.value" :key="v.id"><div class="row"><strong>认证申请</strong><span class="tag">{{ v.status }}</span></div><p>{{ v.reason||'等待审核' }}</p><small class="muted">{{ date(v.created_at) }}</small></article><h2>我的举报回执</h2><article class="card" v-for="r in reports.value" :key="r.id"><span class="tag">{{ r.status }}</span><p>{{ r.reason }}</p><p class="muted">{{ r.result||'等待核查' }}</p><small>回执 {{ short(r.id) }}</small></article><div v-if="!reports.value.length" class="empty">暂无举报记录。</div></div><div><h2>我的收藏</h2><article class="card row" :class="{unavailable:favoriteView(i).unavailable}" v-for="i in favorites.value" :key="i.id"><div><strong>{{ favoriteView(i).title }}</strong><p class="muted">{{ favoriteView(i).detail }}</p></div><button @click="favorite(i,false)">取消收藏</button></article><div class="empty" v-if="!favorites.value.length">收藏喜欢的物品，方便下次寻找。</div></div></div></section>

  <section v-if="ui.tab==='admin'&&isAdmin&&management.value"><div class="intro"><div><h1>校园管理台</h1><p class="muted">审核当前版本，处置保留依据；管理员不能代买方确认收货。</p></div></div><div class="stats" v-if="statistics.value"><div><strong>{{ statistics.value.sales.count }}</strong><span>完成订单</span></div><div><strong>¥ {{ money(statistics.value.sales.amount) }}</strong><span>快照成交额</span></div><div><strong>{{ statistics.value.items.count }}</strong><span>累计发布</span></div><div><strong>{{ statistics.value.reports.count }}</strong><span>累计举报</span></div></div><form class="search range" @submit.prevent="loadStats"><label>成交开始<input type="datetime-local" v-model="dateFilter.from"></label><label>成交截止（不含）<input type="datetime-local" v-model="dateFilter.to"></label><button :disabled="ui.busy">筛选成交统计</button></form><p class="muted small" v-if="statistics.value">{{ statistics.value.basis }}</p><div class="chips"><button v-for="[key,label] in [['items','物品审核'],['verifications','校园认证'],['accounts','账户治理'],['disputes','履约争议'],['reports','举报处理'],['reviews','评价治理'],['logs','操作记录']]" :key="key" :class="{active:ui2.adminTab===key}" @click="ui2.adminTab=key">{{ label }}</button></div>
    <template v-if="ui2.adminTab==='items'"><article class="card" v-for="i in management.value.items" :key="i.id"><div class="row"><h2>{{ i.title }}</h2><span class="tag">{{ i.status }}{{ i.hidden?' · 隐藏':'' }}</span></div><p>{{ i.description }}</p><p class="muted">{{ i.seller_name }} · ¥ {{ money(i.price) }} · 版本 {{ i.version }}</p><a :href="'/api/assets/'+i.image_id" target="_blank" rel="noopener">查看图片</a><div class="actions"><template v-if="i.status==='待审'"><button class="primary" @click="manage('item',i.id,{pass:true,version:i.version},'审核通过当前版本')">通过</button><button @click="manage('item',i.id,{pass:false,version:i.version},'退回当前版本')">退回</button></template><button @click="manage('hide',i.id,{hidden:!i.hidden},i.hidden?'解除隐藏':'隐藏物品')">{{ i.hidden?'解除隐藏':'隐藏物品' }}</button><button v-if="i.status==='交易异常终止'&&!i.relist_allowed" @click="manage('relist',i.id,{},'核实归属，允许重新提交')">核实归属</button></div></article></template>
    <template v-if="ui2.adminTab==='verifications'"><article class="card" v-for="v in management.value.verifications" :key="v.id"><div class="row"><h2>{{ v.nickname }} · {{ v.real_name }}</h2><span class="tag">{{ v.status }}</span></div><p>学号：{{ v.student_no }}</p><a :href="'/api/assets/'+v.asset_id" target="_blank" rel="noopener">查看认证材料（私有）</a><p>{{ v.reason }}</p><div class="actions" v-if="v.status==='待审'"><button class="primary" @click="manage('verification',v.id,{pass:true},'通过校园认证')">通过</button><button @click="manage('verification',v.id,{pass:false},'退回校园认证')">退回</button></div></article></template>
    <template v-if="ui2.adminTab==='accounts'"><article class="card row" v-for="u in management.value.accounts" :key="u.id"><div><strong>{{ u.nickname }}</strong><p class="muted">{{ u.username }} · {{ u.verified?'已认证':'未认证' }} · {{ u.restricted?'受限':'正常' }}</p></div><button v-if="u.role!=='ADMIN'" @click="manage('restrict',u.id,{restricted:!u.restricted},u.restricted?'恢复账户':'限制账户')">{{ u.restricted?'恢复':'限制' }}</button><span v-else class="tag">管理员</span></article></template>
    <template v-if="ui2.adminTab==='disputes'"><article class="card" v-for="d in management.value.disputes" :key="d.id"><div class="row"><h2>{{ d.title }}</h2><span class="tag">{{ d.status }} · {{ d.stage }}</span></div><p>{{ d.reason }}</p><h3 class='evidence-title'>原始证据</h3><pre v-if="d.evidence" class="evidence">{{ d.evidence }}</pre><p v-else class='muted'>申请时未填写证据说明。</p><h3 class='evidence-title'>补充证据（{{ d.evidence_log?.length||0 }} 条）</h3><ol v-if="d.evidence_log?.length" class='evidence-list'><li v-for="e in d.evidence_log" :key="e.id"><div class="row"><strong>{{ userLabel(e.author_id) }}</strong><small class="muted">{{ date(e.created_at) }}</small></div><pre class="evidence">{{ e.content }}</pre></li></ol><p v-else class='muted'>尚无补充证据。</p><p v-if="d.decision">{{ d.outcome }}：{{ d.decision }}</p><div class="actions"><button v-if="governanceOrderId('dispute',d)" @click="openGovOrder('dispute',d)">查看治理订单详情</button><button v-if="d.status==='处理中'" @click="manage('dispute',d.id,{},'核查并结案履约争议')">核查结案</button></div><div v-if="govDetail[governanceOrderId('dispute',d)]" class="detail gov"><h2>订单依据（只读）</h2><p class="muted small">{{ statusLabel(govDetail[governanceOrderId('dispute',d)].order) }}</p><div v-if="govDetail[governanceOrderId('dispute',d)].delivery"><p>登记人：{{ govDetail[governanceOrderId('dispute',d)].delivery.actor_name||govDetail[governanceOrderId('dispute',d)].delivery.actor_id }} · {{ date(govDetail[governanceOrderId('dispute',d)].delivery.created_at) }}</p><pre class="evidence">{{ govDetail[governanceOrderId('dispute',d)].delivery.note||'（未填写说明）' }}</pre></div><p v-else class="muted">该订单尚无交付登记。</p><p class="muted small">双方评价</p><article class="log" v-for="r in govReviews(governanceOrderId('dispute',d))" :key="r.id"><strong>{{ r.authorName }} → {{ r.targetName }} · {{ r.score }} 分<span v-if="r.hidden" class="tag">已隐藏</span></strong><p>{{ r.content }}</p><small>{{ date(r.created_at) }}</small></article><p v-if="!govDetail[governanceOrderId('dispute',d)].reviews.length" class="muted">暂无评价。</p><p class="muted small">仅展示核查依据，管理员不能代买方确认收货或代写评价。</p></div></article></template>
    <template v-if="ui2.adminTab==='reports'"><article class="card" v-for="r in management.value.reports" :key="r.id"><div class="row"><strong>{{ r.object_type }} · {{ short(r.object_id) }}</strong><span class="tag">{{ r.status }}</span></div><p>{{ r.reason }}</p><pre class="evidence">{{ r.evidence }}</pre><p>{{ r.result }}</p><p v-if="r.event_ref" class="muted">事件关联：{{ r.event_ref }}</p><div class="actions"><button v-if="governanceOrderId('report',r)" @click="openGovOrder('report',r)">查看关联订单详情</button><button @click="manage('report',r.id,{},'记录举报核查结果')">处理 / 复核新增事实</button></div><div v-if="govDetail[governanceOrderId('report',r)]" class="detail gov"><h2>关联订单依据（只读）</h2><div v-if="govDetail[governanceOrderId('report',r)].delivery"><p>登记人：{{ govDetail[governanceOrderId('report',r)].delivery.actor_name||govDetail[governanceOrderId('report',r)].delivery.actor_id }} · {{ date(govDetail[governanceOrderId('report',r)].delivery.created_at) }}</p><pre class="evidence">{{ govDetail[governanceOrderId('report',r)].delivery.note||'（未填写说明）' }}</pre></div><p v-else class="muted">该订单尚无交付登记。</p><article class="log" v-for="x in govReviews(governanceOrderId('report',r))" :key="x.id"><strong>{{ x.authorName }} → {{ x.targetName }} · {{ x.score }} 分<span v-if="x.hidden" class="tag">已隐藏</span></strong><p>{{ x.content }}</p><small>{{ date(x.created_at) }}</small></article><p v-if="!govDetail[governanceOrderId('report',r)].reviews.length" class="muted">暂无评价。</p></div></article></template>
    <template v-if="ui2.adminTab==='reviews'"><article class="card" v-for="r in management.value.reviews" :key="r.id"><span class="tag">{{ r.score }} 分 · {{ r.hidden?'已隐藏':'公开' }}</span><p>{{ r.content }}</p><button @click="manage('review',r.id,{hidden:!r.hidden},'评价展示治理')">{{ r.hidden?'恢复公开':'隐藏评价' }}</button></article></template>
    <template v-if="ui2.adminTab==='logs'"><h2 class="evidence-title">处置事实</h2><article class="log" v-for="(f,i) in (management.value.facts||[])" :key="f.id||i"><strong>{{ f.action }}<span v-if="f.result" class="tag">{{ f.result }}</span></strong><span>{{ f.actor_name||'操作人未知' }} · {{ f.object_type||'对象' }}{{ f.object_id?' '+short(f.object_id):'' }} · {{ date(f.created_at) }}<template v-if="f.version!==null&&f.version!==undefined&&f.version!==''"> · 版本 {{ f.version }}</template></span><p v-if="(f.before_value!==null&&f.before_value!==undefined)||(f.after_value!==null&&f.after_value!==undefined)"><span class="muted">前值：{{ f.before_value!==null&&f.before_value!==undefined?f.before_value:'—' }}</span> → <span>后值：{{ f.after_value!==null&&f.after_value!==undefined?f.after_value:'—' }}</span></p><p v-if="f.reason">{{ f.reason }}</p></article><h2 class="evidence-title">其他操作记录</h2><article class="log" v-for="l in management.value.logs" :key="l.id"><strong>{{ l.action }}</strong><span>{{ l.nickname }} · {{ date(l.created_at) }} · {{ short(l.object_id) }}</span><p>{{ l.reason }}</p></article></template><div v-if="!management.value[ui2.adminTab]?.length&&!(ui2.adminTab==='logs'&&management.value.facts?.length)" class="empty">暂无相关记录。</div>
  </section>
  <footer>校园循环 · 交易款项由当事人线下结算。平台记录审核、交易与治理过程。<br>公开信息不包含真实姓名、学号、认证材料或私人联系方式。</footer>
 </main>
 <div v-if="dialog.value" class="backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><h2 id="modal-title">{{ dialog.value.title }}</h2><template v-if="dialog.value.type==='login'||dialog.value.type==='register'"><form class="form" @submit.prevent="authenticate"><label>账号<input v-model="login.username" autocomplete="username" required minlength="3" maxlength="40"></label><label>密码<input v-model="login.password" type="password" :autocomplete="registerMode.value?'new-password':'current-password'" required minlength="8" maxlength="72"></label><p v-if="registerMode.value" class="muted small">密码按原文保存，首尾空格也算密码的一部分；至少 8 个字符且不超过 72 字节（中文字符占 3 字节）。</p><label v-if="registerMode.value">昵称<input v-model="login.nickname" required maxlength="30"></label><p class="muted small">首次本地演示账号：admin / seller / buyer / buyer2<br>演示密码：Campus123!</p><p v-if="ui.error" role="alert" class="inline-error">{{ ui.feedback }}</p><div class="actions"><button class="primary" :disabled="ui.busy">{{ registerMode.value?'注册':'登录' }}</button><button type="button" :disabled="ui.busy" @click="registerMode.value=!registerMode.value;openLogin()">{{ registerMode.value?'已有账号':'注册新账号' }}</button><button type="button" :disabled="ui.busy" @click="dialog.value=null">返回</button></div></form></template><form v-else class="form" @submit.prevent="submitDialog"><p class="muted">{{ dialog.value.help }}</p><label v-for="f in dialog.value.form" :key="f.key" :class="{check:f.type==='check'}"><template v-if="f.type==='check'"><input type="checkbox" v-model="fields[f.key]" :required="f.required">{{ f.label }}</template><template v-else>{{ f.label }}<textarea v-if="f.type==='textarea'" v-model="fields[f.key]" rows="4" :required="f.required" :maxlength="f.max||1500"></textarea><select v-else-if="f.type==='select'" v-model="fields[f.key]" :required="f.required"><option v-for="(o,i) in f.options" :key="o" :value="o">{{ f.labels?.[i]||o }}</option></select><input v-else v-model="fields[f.key]" :required="f.required" :maxlength="f.max||200"></template></label><p v-if="ui.error" role="alert" class="inline-error">{{ ui.feedback }}</p><div class="actions end"><button type="button" :disabled="ui.busy" @click="dialog.value=null">返回</button><button class="primary" :disabled="ui.busy">确认提交</button></div></form></section></div>
 <div v-if="chatState.value" class="backdrop" @click.self="chatState.value=null"><section class="modal chat-modal" role="dialog" aria-modal="true" aria-labelledby="chat-title"><div class="row"><h2 id="chat-title">{{ chatState.value.title }}</h2><button @click="chatState.value=null">关闭</button></div><p class="muted small">仅会话双方可见 · 订单结束后只读 · 每 5 秒更新</p><div class="messages"><article v-for="m in msgs.value" :key="m.id" :class="{self:m.sender_id===user.value?.id}"><small>{{ m.sender_name }} · {{ date(m.created_at) }}</small><p>{{ m.content }}</p></article><p v-if="!msgs.value.length" class="muted">暂无消息。</p></div><p v-if="chatState.value.readOnly" class="tag">该会话为只读（订单已结束或暂未取得可写状态），不能发送新消息。</p><form v-else class="search chat-compose" @submit.prevent="send"><textarea v-model="draft.text" rows="2" required maxlength="1500" aria-label="消息内容" placeholder="约定校内交接或补充情况…（Enter 发送，Shift+Enter 换行）" @keydown.enter="onEnter"></textarea><button class="primary" :disabled="ui.busy">发送</button></form><p v-if="ui.error" class="inline-error">{{ ui.feedback }}</p></section></div>
 <div v-if="publicReviews.value!==null" class="backdrop" @click.self="publicReviews.value=null"><section class="modal" role="dialog" aria-modal="true" aria-label="卖方公开评价"><div class="row"><h2>卖方公开评价</h2><button @click="publicReviews.value=null">关闭</button></div><article class="log" v-for="(r,i) in publicReviews.value" :key="i"><strong>{{ r.score }} 分 · {{ r.nickname }}</strong><p>{{ r.content }}</p><small>{{ date(r.created_at) }}</small></article><p v-if="!publicReviews.value.length" class="empty">暂无公开评价。</p></section></div>
</template>
