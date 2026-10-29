const {chromium}=require(process.env.C_NODE_MODULES?process.env.C_NODE_MODULES+'/playwright':'playwright');
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict'),{pathToFileURL}=require('node:url');
(async()=>{
 const out=path.resolve(__dirname,'../verification/revision-20261022');fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.C_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--disable-extensions']});
 const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[],checks=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);
 async function test(name,fn){try{await fn();checks.push({name,status:'通过'});}catch(e){checks.push({name,status:'失败',error:e.message});throw e;}}
 async function role(who){await page.locator('#actor').selectOption(who);}
 async function action(op,options={}){const selector=`[data-op="${op}"]${options.target?`[data-target="${options.target}"]`:''}`;await page.locator(selector).first().click();if(options.reason!==undefined)await page.locator('#reason').fill(options.reason);if(options.declaration)await page.locator('#declaration').check();if(options.outcome)await page.locator('#outcome').selectOption(options.outcome);if(options.delivered)await page.locator('#delivered').check();if(options.evidence)await page.locator('#evidence').check();await page.locator('#actionForm button[type="submit"]').click();}
 async function reset(){await page.locator('#lab').evaluate(el=>el.open=true);await page.locator('#reset').click();}
 try{
 await test('UI-C-01 正常下单与核心闭环（SC-C-01）',async()=>{
   await page.screenshot({path:path.join(out,'01-发现物品.png'),fullPage:true});
   await action('place');assert.match(await page.locator('#content').innerText(),/待卖方确认/);
   await role('seller');await action('accept');await action('register',{reason:'模拟：当面交接教材',declaration:true});
   assert.match(await page.locator('#stateSummary').textContent(),/完成 0 笔/);await role('buyer');await action('confirm',{declaration:true});
   assert.match(await page.locator('#content').innerText(),/已完成/);assert.match(await page.locator('#stateSummary').textContent(),/完成 1 笔/);
   await page.screenshot({path:path.join(out,'02-买方确认完成.png'),fullPage:true});
 });
 await test('UI-C-02 对象归属与治理入口（SC-C-06/20）',async()=>{await role('buyer2');assert.match(await page.locator('#content').innerText(),/还没有本人的订单/);await page.locator('[data-tab="manage"]').click();assert.match(await page.locator('#content').innerText(),/无管理权限/);});
 await test('UI-C-03 取消声明校验及取消后登记不可达（SC-C-07/08）',async()=>{await reset();await action('place');await role('seller');await action('accept');await role('buyer');await action('cancel',{reason:'模拟未交付取消'});assert.equal(await page.locator('#actionDialog').isVisible(),true);assert.match(await page.locator('#dialogFeedback').innerText(),/声明/);await page.locator('#declaration').check();await page.locator('#actionForm button[type="submit"]').click();assert.match(await page.locator('#content').innerText(),/已取消/);await role('seller');assert.equal(await page.locator('[data-op="register"]').count(),0);});
 await test('UI-C-04 争议受理与异常终止（SC-C-15）',async()=>{await reset();await action('place');await role('seller');await action('accept');await action('register',{reason:'模拟已交付',declaration:true});await role('buyer');await action('dispute',{reason:'模拟质量分歧，申请核查'});await role('admin');await page.locator('[data-tab="manage"]').click();await action('resolve',{reason:'模拟核查证明不能继续履约',outcome:'terminate',evidence:true});await role('buyer');await page.locator('[data-tab="orders"]').click();assert.match(await page.locator('#content').innerText(),/已异常终止/);assert.match(await page.locator('#stateSummary').textContent(),/物品 交易异常终止/);assert.match(await page.locator('#content').innerText(),/原交付登记保留；订单已异常终止/);await page.screenshot({path:path.join(out,'03-异常终止.png'),fullPage:true});});
 await test('UI-C-05 响应丢失后原标识查询（SC-C-02）',async()=>{await reset();await page.locator('#mode').selectOption('lost');await action('place');assert.match(await page.locator('#feedback').innerText(),/响应丢失/);await page.locator('[data-tab="orders"]').click();assert.match(await page.locator('#content').innerText(),/O001/);await page.locator('#mode').selectOption('normal');await page.locator('#retry').click();assert.match(await page.locator('#feedback').innerText(),/原操作回执/);assert.match(await page.locator('#stateSummary').textContent(),/有效订单 1/);});
 await test('UI-C-06 受限旧单与通知失败（SC-C-11/17）',async()=>{await reset();await page.locator('#notifyFail').check();await action('place');await role('admin');await page.locator('[data-tab="manage"]').click();await action('restrict',{target:'seller',reason:'模拟账户限制'});await role('seller');await page.locator('[data-tab="orders"]').click();await action('accept');await action('message',{reason:'模拟旧单交接沟通'});assert.match(await page.locator('#content').innerText(),/模拟旧单交接沟通/);await action('register',{reason:'模拟旧单交付',declaration:true});await role('buyer');await action('confirm',{declaration:true});assert.match(await page.locator('#content').innerText(),/已完成/);});
 await test('UI-C-07 窄屏与导出记录',async()=>{await page.setViewportSize({width:390,height:844});await page.locator('#lab').evaluate(el=>el.open=false);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);await page.screenshot({path:path.join(out,'04-手机宽度.png'),fullPage:true});await page.locator('#lab').evaluate(el=>el.open=true);const download=page.waitForEvent('download');await page.locator('#export').click();const d=await download;await d.saveAs(path.join(out,'页面导出记录.json'));const data=JSON.parse(fs.readFileSync(path.join(out,'页面导出记录.json')));assert.equal(data.summary.completed,1);assert.equal(data.version,'C-prototype-v1.1');});
 await test('UI-C-08 未登记交付的异常终止（SC-C-15补充）',async()=>{
   await page.setViewportSize({width:1280,height:900});
   for(const [stage,accepted,reported] of [['待卖方确认',false,false],['待交付',true,false],['报告交付但未登记',true,true]]){
     await reset();await action('place');
     if(accepted){await role('seller');await action('accept');}
     await role(reported?'seller':'buyer');await action('dispute',{reason:'模拟无法履约，未完成交付登记',delivered:reported});
     await role('admin');await page.locator('[data-tab="manage"]').click();await action('resolve',{reason:'模拟核查后无法继续履约',outcome:'terminate',evidence:true});
     for(const who of ['buyer','seller']){await role(who);await page.locator('[data-tab="orders"]').click();const content=await page.locator('#content').innerText();assert.match(content,/未登记交付；订单已异常终止/,stage+' / '+who);assert.doesNotMatch(content,/原交付登记保留/);assert.equal(await page.locator('[data-op="confirm"]').count(),0);assert.equal(await page.locator('[data-op="register"]').count(),0);}
     assert.match(await page.locator('#stateSummary').textContent(),/有效订单 0；完成 0 笔/);
     const snapshot=await page.evaluate(()=>app.snapshot());assert.equal(snapshot.orders[0].registered,false);assert.equal(snapshot.orders[0].chatReadOnly,true);assert.equal(snapshot.items[0].occupation,null);assert.equal(snapshot.items[0].state,'交易异常终止');assert.equal(snapshot.orders[0].actualDeliveryReported,reported);
     await page.screenshot({path:path.join(out,'05-未登记异常终止-'+stage+'.png'),fullPage:true});
     await page.locator('[data-tab="market"]').click();assert.equal(await page.locator('[data-op="place"]').count(),0);
   }
 });
 assert.deepEqual(errors,[]);
 }catch(e){console.error(e);await page.screenshot({path:path.join(out,'browser-failure.png'),fullPage:true});process.exitCode=1;}
 finally{fs.writeFileSync(path.join(out,'页面验证结果.json'),JSON.stringify({version:'C-prototype-v1.1',files:Object.fromEntries(['core.js','ui.js','browser-check.cjs'].map(f=>[f,require('node:crypto').createHash('sha256').update(fs.readFileSync(path.join(__dirname,f))).digest('hex')])),date:new Date().toISOString(),browser:await browser.version(),scope:'agent无窗口浏览器实际点击；未代替学生人工走查或真实后台验证',checks,errors},null,2));await browser.close();console.log(JSON.stringify({checks,errors}));}
})();

