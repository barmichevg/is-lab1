/* Targeted read-only-to-app repro. Runs fixture prefix only; does not register/run existing suite.
 * Uses the app's current HTML/JS in JSDOM and explicitly modeled HTTP failures.
 * Native Escape effect is represented by dialog.close(): JSDOM has no native modal keyboard behavior.
 */
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const scripts=path.resolve(__dirname,'../../scripts');
const source=fs.readFileSync(path.join(scripts,'ui.test.cjs'),'utf8');
const prefix=source.slice(0,source.indexOf("\ntest("));
const {page,org}=new Function('require','__dirname',prefix+'\nreturn {page,org};')(require,scripts);
const event=()=>({preventDefault(){},stopImmediatePropagation(){}});
function holdDelete(p){let ready;const next=new Promise(resolve=>ready=resolve);p.state.intercept=(call,response)=>call.method==='DELETE'?new Promise(resolve=>ready({release:()=>resolve(response)})):response;return next;}
(async()=>{
 let p=await page('index');try{
  const next=holdDelete(p);p.api.removeOrg(1);const deleting=p.api.confirmDelete(),held=await next;
  const d=p.w.document,dialog=d.getElementById('deleteDialog');
  let canceled=false;const cancel=new p.w.Event('cancel',{cancelable:true});dialog.dispatchEvent(cancel);canceled=cancel.defaultPrevented;
  assert.equal(canceled,false,'There must be no cancel guard for this reproduction');
  dialog.close(); // Native HTML dialog Escape would do this when cancel was NOT prevented.
  p.api.removeOrg(2);assert.equal(dialog.open,true);assert.match(d.getElementById('deleteLabel').textContent,/#2/);
  held.release();await deleting;
  assert.equal(dialog.open,false);
  console.log('CONFIRMED P2 delete intent race: pending DELETE#1 -> unprevented native-cancel effect -> open delete#2 -> response#1 closes dialog#2. (JSDOM, native close explicitly modeled)');
 }finally{p.close();}

 p=await page('index');try{
  await p.api.openCreate();p.w.eval('fillRandomData()');
  let committed=null;const posts=[];
  p.state.intercept=(call,response)=>{
   if(call.method!=='POST'||!call.path.endsWith('/organizations'))return response;
   posts.push(call);
   if(!committed){committed={body:call.body,key:call.headers['X-Idempotency-Key']};p.state.items.push(org(99));throw new Error('Network response lost AFTER database commit (modeled)');}
   if(call.headers['X-Idempotency-Key']===committed.key&&call.body!==committed.body)return {ok:false,status:400,json:async()=>({error:'idempotency key: already used with another request'})};
   return response;
  };
  await p.api.save(event());assert.equal(p.w.document.getElementById('creator').open,true);
  p.w.document.getElementById('orgName').value='Corrected after unknown outcome';await p.api.save(event());
  assert.equal(posts.length,2);assert.equal(posts[0].headers['X-Idempotency-Key'],posts[1].headers['X-Idempotency-Key']);assert.notEqual(posts[0].body,posts[1].body);
  assert.match(p.w.document.getElementById('dialogError').textContent,/already used with another request/);
  console.log('CONFIRMED P2 create key/body divergence: committed+lost first response, edit name, retry -> SAME key with DIFFERENT body -> modeled backend fingerprint rejection400. No automatic reconciliation/rekey.');
 }finally{p.close();}

 p=await page('index');try{
  p.state.intercept=(call,response)=>call.method==='GET'&&call.path.endsWith('/organizations/1')?{ok:false,status:503,json:async()=>({error:'Read temporarily unavailable'})}:response;
  await p.api.viewOrg(1);const d=p.w.document;
  assert.equal(d.getElementById('viewer').open,true);assert.equal(d.getElementById('viewDetails').textContent,'Загрузка…');assert.match(d.getElementById('error').textContent,/temporarily unavailable/);
  assert.equal(d.getElementById('viewer').contains(d.getElementById('error')),false);
  console.log('CONFIRMED P2 viewer error location: GET503 leaves open modal stuck at Loading; error is only outside modal under backdrop. (JSDOM DOM state; visual visibility follows native modal semantics)');
 }finally{p.close();}

 p=await page('index');try{
  await p.api.openCreate();p.w.eval('fillRandomData()');p.w.document.getElementById('employees').value='00000000000000000001';
  assert.equal(p.api.validateOrganizationForm(),true);assert.equal(p.api.payload().employeesCount,'00000000000000000001');assert.equal(/^-?[0-9]{1,19}$/.test(p.api.payload().employeesCount),false);
  console.log('CONFIRMED P3 long normalization mismatch: UI accepts positive BigInt with20 leading-zero digits but sends raw string; RequestParser decimal allowString regex rejects >19 digits.');
 }finally{p.close();}
 p=await page('special');try{
  p.state.items=Array.from({length:201},(_,i)=>org(i+1));await p.api.loadData();
  const selected=p.w.document.getElementById('employeeOrg');selected.value='101';assert.equal(selected.value,'101');
  let injected=false;
  p.state.intercept=(call,response)=>{
   if(!injected&&call.method==='GET'&&call.path.endsWith('/organizations')&&!new URLSearchParams(call.query).has('page')){
    injected=true;p.state.items=p.state.items.filter(o=>o.id!==1); // deletion commits AFTER first page snapshot, BEFORE offset100 read
   }
   return response;
  };
  await p.api.loadData();assert.equal(injected,true);assert.equal(p.state.items.some(o=>o.id===101),true);
  assert.equal([...selected.options].some(o=>o.value==='101'),false);assert.equal(selected.value,'');
  assert.match(p.w.document.querySelectorAll('.card')[3].textContent,/#101 удалён/);
  console.log('CONFIRMED P2 cross-page snapshot gap: with201 rows, delete#1 after page0; offset page1 skips live#101; employee selector drops its chosen101 and falsely reports it deleted.');
 }finally{p.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
