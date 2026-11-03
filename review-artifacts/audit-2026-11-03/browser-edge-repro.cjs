/* Audit only: actual native dialogs in Chrome, intercepted API, no server writes. */
const {chromium}=require('/home/username/.vscode/extensions/vscjava.migrate-java-to-azure-1.24.0-linux-x64/node_modules/playwright');
const fs=require('node:fs/promises'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve('src/main/webapp'),base='http://127.0.0.1:18090/is-lab1/';
const out=path.resolve('review-artifacts/audit-2026-11-03');
const org=id=>({id,version:'0',name:'Audit '+id,coordinates:{id,x:1,y:2},officialAddress:{id,zipCode:'123',town:{id,name:'Town',x:1,y:2,z:3}},postalAddress:null,employeesCount:'4',annualTurnover:10,rating:1,type:'COMMERCIAL',creationDate:'2026-11-01T12:00:00'});
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'/opt/google/chrome/chrome',args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:1300,height:900},locale:'ru-RU'});
 let items=[org(1),org(2)],heldDelete,deleteStarted,failGet=false;
 let started=new Promise(resolve=>deleteStarted=resolve);
 await context.addInitScript(()=>{window.WebSocket=class {constructor(){this.readyState=0;setTimeout(()=>{this.readyState=1;this.onopen?.();},0);}close(){this.readyState=3;}};});
 await context.route('**/*',async route=>{
  const req=route.request(),u=new URL(req.url()),file=u.pathname.replace('/is-lab1/','');
  if(!req.url().startsWith(base))return route.abort();
  if(!file.startsWith('api/')){
   if(!['index.html','app.css','app.js'].includes(file))return route.fulfill({status:404,body:''});
   return route.fulfill({contentType:file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':'text/javascript',body:await fs.readFile(path.join(root,file))});
  }
  let result,status=200;
  if(file==='api/auth/session')result={username:'audit',csrf:'token',wsToken:'ws'};
  else if(file.startsWith('api/reference/')){const kind=file.split('/')[2];result=items.map(o=>kind==='coordinates'?o.coordinates:kind==='addresses'?o.officialAddress:o.officialAddress.town);}
  else if(req.method()==='DELETE'){heldDelete=route;deleteStarted();return;}
  else if(file==='api/organizations')result={items,total:items.length,page:0,size:10};
  else if(failGet){status=503;result={error:'Audit simulated unavailable DB'};}
  else result=items.find(o=>o.id===Number(file.split('/')[2]));
  return route.fulfill({status,contentType:'application/json',body:JSON.stringify(result)});
 });
 const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.goto(base+'index.html');await page.waitForFunction(()=>document.querySelector('#rows tr'));
  await page.locator('button[onclick="removeOrg(1)"]').click();await page.locator('#deleteConfirm').click();await started;
  await page.keyboard.press('Escape');assert.equal(await page.locator('#deleteDialog').evaluate(e=>e.open),false,'Native Escape should close unguarded pending delete');
  await page.locator('button[onclick="removeOrg(2)"]').click();assert.match(await page.locator('#deleteLabel').textContent(),/#2/);
  items=items.filter(o=>o.id!==1);await heldDelete.fulfill({status:204,body:''});
  await page.waitForFunction(()=>!document.getElementById('deleteDialog').open);
  assert.equal(items[0].id,2);
  console.log('CONFIRMED in real Chrome: DELETE#1 pending -> native Escape -> delete dialog#2 -> old response closes dialog#2 (object#2 still exists).');
  items=[org(1),org(2)];failGet=true;
  await page.goto(base+'index.html');await page.locator('#rows .entity-link').first().click();
  await page.waitForFunction(()=>document.getElementById('error').textContent.includes('Audit simulated'));
  assert.equal(await page.locator('#viewer').evaluate(e=>e.open),true);assert.equal(await page.locator('#viewDetails').textContent(),'Загрузка…');
  assert.equal(await page.locator('#viewer .error').count(),0);
  await page.screenshot({path:path.join(out,'viewer-get503.png')});
  console.log('CONFIRMED in real Chrome: GET503 leaves modal Loading; error is outside the modal under its backdrop. Screenshot saved.');
  assert.deepEqual(errors,[]);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
