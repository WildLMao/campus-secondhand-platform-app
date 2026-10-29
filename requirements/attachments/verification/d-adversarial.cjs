// D authored checks derived from A BR06/08/10/12/14/19 and D AC05/07/09/11.
// Local in-memory results only. Direct user flag changes are test fixtures.
const {Platform}=require('../prototype/core.js');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const results=[];
function run(id,name,refs,expected,fn){
  const p=new Platform(),steps=[],checks=[];let n=0,error=null;
  const state=()=>({items:p.items.map(({id,state,hidden,occupation})=>({id,state,hidden,occupation})),orders:p.orders.map(({id,state,registered,disputed,chatReadOnly})=>({id,state,registered,disputed,chatReadOnly})),summary:p.summary(),notifications:p.notifications.length});
  const act=(actor,op,target='O001',data={},intent,mode='normal')=>{const before=state(),result=p.request(actor,op,target,data,intent||'D'+(++n),mode);steps.push({actor,op,target,data,intent:intent||'D'+n,mode,result,before,after:state()});return result;};
  const eq=(actual,want,description)=>{checks.push({description,expected:want,actual});assert.deepEqual(actual,want,description);};
  const fixture=(description,fn)=>{fn();steps.push({testFixture:description,after:state()});};
  const place=()=>act('buyer','place','I01',{},'purchase').orderId;
  const stage=which=>{place();if(which!=='待卖方确认')act('seller','accept');if(which==='待买方确认')act('seller','register','O001',{delivered:true,reason:'D测试夹具：已交付声明'});};
  try{fn({p,act,eq,fixture,place,stage,state});p.assertInvariant();}catch(e){error=e.message;}
  results.push({id,name,refs,expected,method:'D侧AI新写检查，Node顺序内存模拟；非学生观察、真实认证或后台并发',status:error?'失败':'通过',error,steps,checks,final:state()});
}
run('PV-D-01','新交易资格拒绝保持原物品与订单数据','BR06/12；AC-D-05/11','买卖双方任一方受限均不建单、不留占用',({p,act,eq,fixture})=>{
  fixture('将买方标为受限',()=>p.users.buyer.restricted=true);eq(act('buyer','place','I01').ok,false,'受限买方新单拒绝');
  fixture('恢复买方、限制卖方',()=>{p.users.buyer.restricted=false;p.users.seller.restricted=true;});eq(act('buyer','place','I01').ok,false,'受限卖方的物品拒绝新单');
  eq(p.orders.length,0,'无孤立订单');eq(p.item('I01').occupation,null,'无占用');eq(p.item('I01').state,'在售','不擅自修改生命周期');
});
run('PV-D-02','完成后的旧下单回执与当前进度','BR08/10/12；AC-D-05/07','买方后来受限仍可取本人原回执，当前进度为已完成',({p,act,eq,stage})=>{
  stage('待买方确认');act('buyer','confirm','O001',{received:true});act('admin','restrict','buyer',{restricted:true,reason:'D模拟治理'});
  const r=act('buyer','place','I01',{},'purchase');eq(r.orderId,'O001','返回原单');eq(r.currentState,'已完成','返回当前终态');eq(p.orders.length,1,'不重建');eq(p.summary().completed,1,'成交仅一次');
});
run('PV-D-03','三个未结束阶段继续履约均恢复受理时阶段','BR15/19；AC-D-09','恢复不强制登记、不自动完成，保留占用',({p,act,eq,fixture,stage})=>{
  for(const s of ['待卖方确认','待交付','待买方确认']){
    fixture('每阶段重置隔离夹具：'+s,()=>p.reset());stage(s);act('buyer','dispute','O001',{reason:'D模拟需核查'});act('admin','resolve','O001',{outcome:'continue',reason:'D模拟核查后继续'});
    eq(p.order('O001').state,s,s+'恢复原阶段');eq(p.order('O001').disputed,false,'解除标记');eq(p.item('I01').occupation,'O001','保留占用');eq(p.summary().completed,0,'不自动成交');
  }
});
run('PV-D-04','异常终止不补造交付登记','BR08/19；AC-D-09','两个未登记阶段终止，登记事实仍false、占用释放且不可重售',({p,act,eq,fixture,stage})=>{
  for(const s of ['待卖方确认','待交付']){
    fixture('每阶段重置隔离夹具：'+s,()=>p.reset());stage(s);act('seller','dispute','O001',{reason:'D模拟无法继续'});act('admin','resolve','O001',{outcome:'terminate',reason:'D模拟核查材料齐全',evidence:'verified'});
    eq(p.order('O001').registered,false,'不补造登记');eq(p.order('O001').state,'已异常终止','订单终态');eq(p.item('I01').occupation,null,'释放占用');eq(p.publicItems().length,0,'不自动重售');eq(p.summary().completed,0,'不计成交');
  }
});
run('PV-D-05','旧取消回执不解除后一笔新单占用','BR06/09/10；AC-D-05/08','先取消再由另一买方下单，重试旧取消只能回原结果',({p,act,eq,place})=>{
  place();act('buyer','cancel','O001',{undelivered:true,reason:'D模拟未交付取消'},'cancel-old');act('buyer2','place','I01',{},'purchase-new');
  const r=act('buyer','cancel','O001',{undelivered:true,reason:'D模拟未交付取消'},'cancel-old');eq(r.replay,true,'回原结果');eq(p.item('I01').occupation,'O002','不释放新单占用');eq(p.order('O002').state,'待卖方确认','不改变后一笔订单');eq(p.summary().active,1,'仅一笔有效单');
});
run('PV-D-06','其他买方不能借相同意图读取私有回执','BR10/14；AC-D-05/07','非当事人确认被拒，错误响应不含私有订单回执',({p,act,eq,stage})=>{
  stage('待买方确认');act('buyer','confirm','O001',{received:true},'private-confirm');const r=act('buyer2','confirm','O001',{received:true},'private-confirm');
  eq(r.ok,false,'无归属拒绝');eq(Object.hasOwn(r,'orderId'),false,'不泄露私有回执');eq(p.summary().completed,1,'原成交保持');
});
run('PV-D-07','通知失败与同次完成重试不改变业务结果','BR08/10/16/18；AC-D-07/12','完成与通知发送分离，同次重试不重复计数或通知',({p,act,eq,fixture,stage})=>{
  stage('待买方确认');fixture('仅注入模拟通知发送故障',()=>p.notificationFailure=true);act('buyer','confirm','O001',{received:true},'finish');const count=p.notifications.length;
  act('buyer','confirm','O001',{received:true},'finish');eq(p.notifications.length,count,'重试不新增通知');eq(p.notifications.at(-1).status,'发送失败','记录故障');eq(p.summary().completed,1,'完成不撤销也不重复');eq(p.item('I01').state,'已成交','物品成交保持');
});
run('PV-D-08','处理中资格变化后不建立半成订单','BR06/10/12；AC-D-05','挂起新请求在实际完成前资格失效，应拒绝并保持零占用',({p,act,eq,fixture})=>{
  act('buyer','place','I01',{},'pending-eligibility','pending');fixture('处理期间将买方设为受限',()=>p.users.buyer.restricted=true);
  const r=p.finish('buyer|pending-eligibility');eq(r.ok,false,'处理完成时资格拒绝');eq(p.orders.length,0,'无订单');eq(p.item('I01').occupation,null,'无半个占用');
});
const out={version:'D-independent-v1',date:new Date().toISOString(),runtime:process.version,scope:'独立补充8组反例；顺序内存与夹具注入，不证明后台并发、身份认证或人工观察',files:Object.fromEntries(['../prototype/core.js','d-adversarial.cjs'].map(f=>[f,crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname,f))).digest('hex')])),passed:results.filter(r=>r.status==='通过').length,total:results.length,checks:results.reduce((n,r)=>n+r.checks.length,0),results};
fs.writeFileSync(path.join(__dirname,'D独立反例结果.json'),JSON.stringify(out,null,2));
console.log(JSON.stringify({passed:out.passed,total:out.total,checks:out.checks,failures:results.filter(r=>r.error).map(r=>({id:r.id,error:r.error}))}));if(out.passed!==out.total)process.exitCode=1;
