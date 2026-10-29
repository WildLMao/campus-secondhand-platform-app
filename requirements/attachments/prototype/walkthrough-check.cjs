const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {Platform}=require('./core.js');
const out=path.join(__dirname,'../verification/revision-20261022');
const cases=JSON.parse(fs.readFileSync(path.join(out,'程序验证结果.json'),'utf8')).results;
const scriptPath=path.join(__dirname,'../verification/场景数据与走查脚本.md');
const markdown=fs.readFileSync(scriptPath,'utf8');
const results=[];
const state=p=>({items:p.items.map(x=>({id:x.id,state:x.state,hidden:x.hidden,occupation:x.occupation})),orders:p.orders.map(x=>({id:x.id,state:x.state,registered:x.registered,disputed:x.disputed,chatReadOnly:x.chatReadOnly})),summary:p.summary(),notifications:p.notifications.length});
for(const sample of cases){
 let error=null,count=0;
 try{
  const section=markdown.split('## '+sample.id+' ')[1].split('\n## ')[0];
  const commands=[...section.matchAll(/^\| \d+ \|[^\n]*?`([^`]+)`/gm)].map(m=>m[1].replace(/\\\|/g,'|'));
  assert.equal(commands.length,sample.steps.length,'All setup and action steps must be documented');
  const p=new Platform();
  for(const [i,command] of commands.entries()){
   const actual=vm.runInNewContext(command,{p},{timeout:1000});count++;
   assert.deepEqual(JSON.parse(JSON.stringify(actual)),sample.steps[i].result,`Step ${i+1}: actual result`);
  }
  p.assertInvariant();assert.deepEqual(state(p),sample.final,'Final state');
 }catch(e){error=e.message;}
 results.push({id:sample.id,commands:count,status:error?'失败':'通过',error});
}
const report={version:'C-prototype-v1.1',date:new Date().toISOString(),method:'逐项提取走查Markdown中的精确程序调用，在全新Platform实例执行，与本轮程序结果逐步比对响应和最终状态；不替代学生人工或页面走查',scriptSha256:crypto.createHash('sha256').update(markdown).digest('hex'),passed:results.filter(x=>!x.error).length,total:results.length,results};
fs.writeFileSync(path.join(out,'脚本复现检查.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify(report));if(report.passed!==report.total)process.exitCode=1;
