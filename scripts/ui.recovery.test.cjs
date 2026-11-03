/* Real browser + real isolated Payara/DB regression. Creates/deletes owned fixtures only.
 * Explicit opt-in required; never run on a shared/production server.
 */
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs/promises'),crypto=require('node:crypto');
const base=(process.env.TEST_BASE_URL||'').replace(/\/$/,'');
if(process.env.TEST_UI_ISOLATED!=='1'||!base||!['127.0.0.1','localhost','[::1]'].includes(new URL(base).hostname))throw new Error('Set TEST_UI_ISOLATED=1 and a loopback TEST_BASE_URL for an isolated test app.');
const out=path.resolve(process.env.UI_ARTIFACT_DIR||'review-artifacts/fixes-2026-11-03');
(async()=>{
 await fs.mkdir(out,{recursive:true});
 const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:1400,height:1000},locale:'ru-RU'});
 let csrf,seed;const owned=new Set(),requests=[],errors=[];let saved=null;
 async function call(method,route,data,expected=200,extra={}){
  const response=await context.request.fetch(base+route,{method,headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{}),...extra},...(data===undefined?{}:{data}),timeout:20000});
  const text=await response.text();assert.equal(response.status(),expected,route+' '+text);return text?JSON.parse(text):null;
 }
 try{
  const session=await call('POST','/api/auth/login',{username:process.env.TEST_AUTH_USER,password:process.env.TEST_AUTH_PASSWORD});csrf=session.csrf;
  const marker='recovery-'+crypto.randomUUID();
  seed=await call('POST','/api/organizations',{name:'seed-'+marker,coordinates:{x:1,y:2},officialAddress:{zipCode:'',town:{name:'Recovery town',x:1,y:2,z:3}},annualTurnover:10,employeesCount:'1',rating:1,type:'COMMERCIAL'},201);owned.add(seed.id);
  const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));page.on('dialog',dialog=>dialog.accept());
  await page.route(base+'/api/organizations',async route=>{
   if(route.request().method()!=='POST')return route.continue();
   requests.push({body:route.request().postData(),key:route.request().headers()['x-idempotency-key']});
   if(saved)return route.continue();
   const response=await route.fetch();assert.equal(response.status(),201,await response.text());saved=await response.json();owned.add(saved.id);
   // The database transaction has committed; the browser intentionally never receives this response.
   await route.abort('failed');
  });
  await page.goto(base+'/index.html');await page.waitForFunction(()=>document.querySelector('.connection-state')?.dataset.state==='online');
  await page.getByRole('button',{name:'Создать организацию',exact:true}).click();await page.locator('#creator[open]').waitFor();
  await page.locator('#orgName').fill(marker);await page.locator('#turnover').fill('25');await page.locator('#employees').fill('00000000000000000001');await page.locator('#rating').fill('3');
  await page.locator('#coordMode').selectOption('existing');await page.locator('#coordExisting').selectOption(String(seed.coordinates.id));
  await page.locator('#officialMode').selectOption('existing');await page.locator('#officialExisting').selectOption(String(seed.officialAddress.id));
  await page.locator('#saveButton').click();await page.locator('#dialogError').waitFor({state:'visible'});assert.ok(saved);assert.equal(saved.employeesCount,'1');
  assert.equal((await call('GET','/api/organizations?filterValue='+encodeURIComponent(marker))).total,2); // seed + one committed create
  await page.locator('#orgName').fill(marker+'-edited');await page.locator('#saveButton').click();await page.locator('#editor[open]').waitFor();
  assert.equal(requests.length,2);assert.equal(requests[0].body,requests[1].body);assert.equal(requests[0].key,requests[1].key);
  assert.equal(await page.locator('#editId').inputValue(),String(saved.id));assert.equal(await page.locator('#orgName').inputValue(),marker+'-edited');
  assert.equal((await call('GET','/api/organizations/'+saved.id)).name,marker,'Replay does not silently apply changed draft');
  await page.screenshot({path:path.join(out,'real-recovered-create.png')});
  await page.locator('#saveButton').click();await page.waitForFunction(()=>!document.getElementById('editor').open);
  const updated=await call('GET','/api/organizations/'+saved.id);assert.equal(updated.name,marker+'-edited');assert.equal(updated.employeesCount,'1');
  const listing=await call('GET','/api/organizations?filterValue='+encodeURIComponent(marker));assert.equal(listing.total,2,'No second organization may be created by recovery');
  const original=await call('POST','/api/organizations',requests[0].body,201,{'X-Idempotency-Key':requests[0].key});
  assert.deepEqual(original,saved,'Durable replay still returns the original snapshot after PUT');
  assert.deepEqual(errors,[]);await page.close();
  console.log('PASS real Chrome/Payara/PostgreSQL: committed response dropped, immutable body/key replayed, changed draft PUT to same ID, no duplicate organization.');
  console.log('PASS: leading-zero long transmitted as1; original durable response snapshot remains unchanged after PUT; zero browser runtime errors.');
 }finally{
  try{
   for(const id of [...owned].reverse())await call('DELETE','/api/organizations/'+id,undefined,204);
   if(seed)for(const [kind,id]of [['addresses',seed.officialAddress.id],['coordinates',seed.coordinates.id],['locations',seed.officialAddress.town.id]])await call('DELETE',`/api/reference/${kind}/${id}`,undefined,204);
   if(csrf)await call('POST','/api/auth/logout',undefined,204);
   console.log('PASS: real-browser recovery fixtures removed.');
  }finally{await browser.close();}
 }
})().catch(error=>{console.error(error);process.exitCode=1;});
