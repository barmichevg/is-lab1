const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {JSDOM}=require('jsdom');
const root=path.join(__dirname,'../src/main/webapp');
const pause=ms=>new Promise(r=>setTimeout(r,ms));
// Hold actual HTTP responses, not UI functions, to reproduce asynchronous races.
function holdRequests(p,predicate){
 const ready=[],waiters=[];
 p.state.intercept=(call,response)=>predicate(call)?new Promise(resolve=>{
  const held={call,release:(replacement=response)=>resolve(replacement)};
  if(waiters.length)waiters.shift()(held);else ready.push(held);
 }):response;
 return ()=>ready.length?Promise.resolve(ready.shift()):new Promise(resolve=>waiters.push(resolve));
}
const submitEvent=()=>({preventDefault(){},stopImmediatePropagation(){}});
function org(id=1){return {id,version:'0',name:'Org '+id,employeesCount:'9223372036854775807',annualTurnover:10,rating:1,type:'COMMERCIAL',creationDate:'2026-01-01T12:00:00',coordinates:{id,x:1,y:2},officialAddress:{id,zipCode:'123',town:{id,name:'Town',x:1,y:2,z:3}},postalAddress:null};}
async function page(name){
 const dom=new JSDOM(fs.readFileSync(path.join(root,name+'.html'),'utf8'),{url:'http://localhost/is-lab1/'+name+'.html',runScripts:'outside-only'}),w=dom.window;
 const state={items:[org(1),org(2)],calls:[],sockets:[],usage:0,delay:0};
 w.HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
 w.HTMLDialogElement.prototype.close=function(){if(this.open){this.removeAttribute('open');w.setTimeout(()=>this.dispatchEvent(new w.Event('close')),0);}};
 w.confirm=()=>true;
 w.WebSocket=class{constructor(){this.readyState=0;state.sockets.push(this);setTimeout(()=>{this.readyState=1;this.onopen?.();},0);}close(){this.readyState=3;this.onclose?.({code:1000});}};
 w.fetch=async(url,options={})=>{
  const u=new URL(url,w.location.href),method=options.method||'GET',call={path:u.pathname,query:u.search,method,body:options.body,headers:options.headers};state.calls.push(call);
  let status=200,body;
  if(u.pathname.endsWith('/auth/session'))body={csrf:'token',wsToken:'ws',username:'test'};
  else if(u.pathname.includes('/reference/')){
   if(method==='DELETE'){status=204;body=null;}
   else if(u.pathname.endsWith('/usage'))body={totalReferences:state.usage};
   else{const kind=u.pathname.split('/').pop();body=state.items.map(o=>kind==='coordinates'?o.coordinates:kind==='addresses'?o.officialAddress:o.officialAddress.town);}
  }else if(u.pathname.includes('/special-operations/')){
   if(method==='POST'){if(state.delay)await pause(state.delay);body=org(99);}
   else if(u.pathname.endsWith('min-employees'))body=state.items[0];else body=state.items;
  }else if(u.pathname.endsWith('/organizations/options')){
    body=state.items.slice().sort((a,b)=>a.id-b.id).map(o=>({id:o.id,name:o.name,employeesCount:o.employeesCount}));
   }else if(u.pathname.endsWith('/organizations')){
   if(method==='POST'){if(state.delay)await pause(state.delay);body=org(99);}
   else{const page=+(u.searchParams.get('page')||0),size=+(u.searchParams.get('size')||10);body={items:state.items.slice(page*size,(page+1)*size),total:state.items.length,page,size};}
  }else if(/\/organizations\/\d+$/.test(u.pathname)){
   if(method==='DELETE'){state.items=state.items.filter(o=>o.id!==+u.pathname.split('/').pop());status=204;}
   else if(method==='PUT' && state.putStatus){status=state.putStatus;body={error:'Organization changed; reload before saving'};}
    else {body=state.items.find(o=>o.id===+u.pathname.split('/').pop());if(!body){status=404;body={error:'Not found'};}}
  }else throw Error('Unhandled '+url);
  if(state.mergeReplacesSources && method==='POST' && u.pathname.endsWith('/special-operations/merge')){
   const input=JSON.parse(options.body);state.items=state.items.filter(o=>![input.firstOrganizationId,input.secondOrganizationId].includes(o.id));state.items.push(body);
  }
  const snapshot=body===undefined?null:JSON.parse(JSON.stringify(body));
   const response={ok:status>=200&&status<300,status,json:async()=>snapshot};
   return state.intercept?state.intercept(call,response):response;
 };
 const inline=[...w.document.querySelectorAll('script:not([src])')].map(s=>s.textContent).join('\n');
 w.eval(fs.readFileSync(path.join(root,'app.js'),'utf8')+'\n'+inline+'\nwindow.testApi={Lab,'+(name==='index'?'load,loadReferences,openCreate,edit,viewOrg,closeViewer,closeEditor,editViewedOrganization,retryViewer,refreshOpenObjects,payload,save,removeOrg,confirmDelete,validateOrganizationForm':name==='special'?'loadData,refreshResults,mergeOrganizations,addEmployee,minEmployees':'loadData,deleteReference,replaceAndDelete')+'};');
 await pause(40);
 return {w,state,api:w.testApi,close:()=>{w.dispatchEvent(new w.Event('pagehide'));w.close();}};
}
test('numeric validation preserves long and rejects fractions/ranges',async()=>{const p=await page('index');try{
 const input=p.w.document.getElementById('employees');input.value='9223372036854775807';assert.equal(p.api.Lab.number(input,{long:true,positive:true}),'9223372036854775807');
 for(const value of ['9223372036854775808','1.5','1e3']){input.value=value;assert.throws(()=>p.api.Lab.number(input,{long:true,positive:true}));}
}finally{p.close();}});
test('pagination returns from an empty final page',async()=>{const p=await page('index');try{
 p.state.items=Array.from({length:11},(_,i)=>org(i+1));await p.api.load(1);p.state.items.pop();await p.api.load(1);
 assert.match(p.w.document.getElementById('pageInfo').textContent,/1–10 из 10/);assert.equal(p.w.document.getElementById('nextButton').disabled,true);
}finally{p.close();}});
test('creation and editing have distinct dialogs; create double submit is one POST',async()=>{const p=await page('index');try{
 await p.api.openCreate();assert.equal(p.w.document.getElementById('creator').open,true);
 p.w.eval('fillRandomData()');p.w.document.getElementById('employees').value='9223372036854775807';assert.equal(p.api.payload().employeesCount,'9223372036854775807');
 p.state.delay=40;const event={preventDefault(){},stopImmediatePropagation(){}};await Promise.all([p.api.save(event),p.api.save(event)]);
 assert.equal(p.state.calls.filter(c=>c.method==='POST'&&c.path.endsWith('/organizations')).length,1);
 await p.api.edit(1);assert.equal(p.w.document.getElementById('editor').open,true);assert.equal(p.w.document.getElementById('creator').open,false);
}finally{p.close();}});
test('viewer refreshes but unsaved edit text is preserved with warning',async()=>{const p=await page('index');try{
 await p.api.viewOrg(1);p.state.items[0].name='Changed';await p.api.refreshOpenObjects(null);assert.match(p.w.document.getElementById('viewDetails').textContent,/Changed/);
 p.w.document.getElementById('viewer').close();await p.api.edit(1);p.w.document.getElementById('orgName').value='My draft';
 p.state.items[0].name='Changed again';await p.api.refreshOpenObjects(null);assert.equal(p.w.document.getElementById('orgName').value,'My draft');assert.equal(p.w.document.getElementById('editWarning').classList.contains('hidden'),false);
}finally{p.close();}});
test('organization deletion waits for confirmation',async()=>{const p=await page('index');try{
 p.api.removeOrg(1);assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,0);await p.api.confirmDelete();assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,1);
}finally{p.close();}});
test('special operation selections survive refresh and deleted IDs become empty',async()=>{const p=await page('special');try{
 const select=p.w.document.getElementById('mergeSecond');select.value='2';await p.api.loadData();assert.equal(select.value,'2');
 p.state.items=p.state.items.filter(o=>o.id!==2);await p.api.loadData();assert.equal(select.value,'');assert.match(p.w.document.querySelectorAll('.card')[4].textContent,/объект #2 удалён/);
}finally{p.close();}});
test('successful merge removes source choices and reports replacement without false deletion error',async()=>{const p=await page('special');try{
 p.state.mergeReplacesSources=true;
 for(const [id,value]of Object.entries({mergeFirst:'1',mergeSecond:'2',mergeExistingAddress:'1',mergeName:'Merged'}))p.w.document.getElementById(id).value=value;
 await p.api.mergeOrganizations();
 for(const id of ['mergeFirst','mergeSecond']){
  const select=p.w.document.getElementById(id);assert.equal(select.value,'');
  assert.deepEqual([...select.options].map(o=>o.value),['','99']);
 }
 const card=p.w.document.querySelectorAll('.card')[4];
 assert.match(card.querySelector('.success').textContent,/заменены.*#99/);
 assert.match(card.querySelector('.operation-result').textContent,/Org 99/);
}finally{p.close();}});

test('merge double click sends one request and results refresh by GET',async()=>{const p=await page('special');try{
 for(const [id,value] of Object.entries({mergeFirst:'1',mergeSecond:'2',mergeExistingAddress:'1',mergeName:'Merged'}))p.w.document.getElementById(id).value=value;
 p.state.delay=40;await Promise.all([p.api.mergeOrganizations(),p.api.mergeOrganizations()]);assert.equal(p.state.calls.filter(c=>c.path.endsWith('/merge')&&c.method==='POST').length,1);
 await pause(420);await p.api.loadData();assert.equal(p.state.calls.filter(c=>c.path.endsWith('/merge')&&c.method==='POST').length,1);
}finally{p.close();}});
test('reference deletion checks usage without explanatory copy and requires confirmation even without links',async()=>{const p=await page('references');try{
 await p.api.deleteReference('coordinates',1);assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,0);assert.ok(p.state.calls.some(c=>c.path.endsWith('/coordinates/1/usage')));assert.equal(p.w.document.getElementById('usageInfo').textContent,'');assert.equal(p.w.document.getElementById('replacementLabel').hidden,true);assert.equal(p.w.document.getElementById('deleteReferenceConfirm').disabled,false);
 await p.api.replaceAndDelete();assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,1);
}finally{p.close();}});
test('open replacement list keeps selection and clears removed selection',async()=>{const p=await page('references');try{
 p.state.usage=1;await p.api.deleteReference('coordinates',1);const select=p.w.document.getElementById('replacementSelect');select.value='2';await p.api.loadData();assert.equal(select.value,'2');
 p.state.items=[org(1)];await p.api.loadData();assert.equal(select.value,'');assert.match(p.w.document.getElementById('replacementError').textContent,/#2 удалён/);
}finally{p.close();}});
test('reconnect performs a full refresh',async()=>{const p=await page('special');try{
 const before=p.state.calls.length;p.state.sockets[0].close();await pause(1600);assert.equal(p.state.sockets.length,2);assert.ok(p.state.calls.length>before);assert.equal(p.w.document.querySelector('.connection-state').textContent,'Синхронизировано');assert.equal(p.w.document.querySelector('.connection-state').dataset.state,'online');
}finally{p.close();}});
test('registration tab validates repeated password and shows duplicate login error',async()=>{
 const dom=new JSDOM(fs.readFileSync(path.join(root,'login.html'),'utf8'),{url:'http://localhost/is-lab1/login.html',runScripts:'outside-only'}),w=dom.window,calls=[];
 try{
  w.fetch=async(url,options)=>{calls.push({url,body:JSON.parse(options.body)});return {ok:false,status:409};};
  w.eval(w.document.querySelector('script').textContent);
  w.document.getElementById('registerTab').click();
  assert.equal(w.document.getElementById('repeatLabel').hidden,false);
  const password=w.document.getElementById('password'),repeat=w.document.getElementById('repeatPassword');
  assert.equal(password.hasAttribute('maxlength'),false);assert.equal(password.required,false);
  password.value='x';repeat.value='y';
  w.document.getElementById('login').dispatchEvent(new w.Event('submit',{cancelable:true}));await pause(0);
  assert.equal(calls.length,0);assert.match(w.document.getElementById('error').textContent,/не совпадают/);
  password.value='a'.repeat(300);repeat.value=password.value;w.document.querySelector('[name=username]').value='student';
  w.document.getElementById('login').dispatchEvent(new w.Event('submit',{cancelable:true}));await pause(0);
  assert.equal(calls[0].url,'api/auth/register');assert.equal(calls[0].body.password.length,300);assert.match(w.document.getElementById('error').textContent,/уже занят/);
 }finally{w.close();}
});

test('closed viewer background response cannot reopen it or interfere with editing',async()=>{const p=await page('index');try{
 await p.api.viewOrg(1);const next=holdRequests(p,c=>c.path.endsWith('/organizations/1'));
 const refresh=p.api.refreshOpenObjects(null),held=await next();
 p.w.document.querySelector('#viewer button:last-child').click();p.state.intercept=null;await p.api.edit(2);
 p.w.document.getElementById('orgName').value='Unsaved draft';held.release();await refresh;
 assert.equal(p.w.document.getElementById('viewer').open,false);assert.equal(p.w.document.getElementById('editor').open,true);
 assert.equal(p.w.document.getElementById('orgName').value,'Unsaved draft');
}finally{p.close();}});

test('viewer ignores out-of-order responses and stale 404 errors',async()=>{const p=await page('index');try{
 const next=holdRequests(p,c=>/\/organizations\/\d+$/.test(c.path));
 const first=p.api.viewOrg(1),a=await next(),second=p.api.viewOrg(2),b=await next();
 b.release();await second;a.release();await first;
 const third=p.api.viewOrg(1),c=await next(),fourth=p.api.viewOrg(2),d=await next();
 d.release();await fourth;c.release({ok:false,status:404,json:async()=>({error:'Stale missing object'})});await third;
 assert.equal(p.w.document.getElementById('viewTitle').textContent,'Организация #2');
 assert.match(p.w.document.getElementById('viewDetails').textContent,/Org 2/);
 assert.equal(p.w.document.getElementById('viewer').open,true);assert.equal(p.w.document.getElementById('error').textContent,'');
}finally{p.close();}});

test('viewer Escape and direct close invalidate pending opens and refreshes',async()=>{const p=await page('index');try{
 const next=holdRequests(p,c=>c.path.endsWith('/organizations/1'));
 const opening=p.api.viewOrg(1),a=await next();
 p.w.document.getElementById('viewer').dispatchEvent(new p.w.Event('cancel',{cancelable:true}));a.release();await opening;
 assert.equal(p.w.document.getElementById('viewer').open,false);p.state.intercept=null;await p.api.viewOrg(1);
 const nextRefresh=holdRequests(p,c=>c.path.endsWith('/organizations/1'));
 const refresh=p.api.refreshOpenObjects(null),b=await nextRefresh();p.w.document.getElementById('viewer').close();b.release();await refresh;
 assert.equal(p.w.document.getElementById('viewer').open,false);
}finally{p.close();}});

test('editing viewed object invalidates pending viewer refresh',async()=>{const p=await page('index');try{
 await p.api.viewOrg(1);const next=holdRequests(p,c=>c.path.endsWith('/organizations/1'));
 const refresh=p.api.refreshOpenObjects(null),held=await next();p.state.intercept=null;await p.api.editViewedOrganization();held.release();await refresh;
 assert.equal(p.w.document.getElementById('viewer').open,false);assert.equal(p.w.document.getElementById('editor').open,true);
}finally{p.close();}});

test('pending forms obey latest intent, cancellation and shared-form ownership',async()=>{const p=await page('index');try{
 const next=holdRequests(p,c=>/\/organizations\/\d+$/.test(c.path));
 const first=p.api.edit(1),a=await next(),second=p.api.edit(2),b=await next();b.release();await second;a.release();await first;
 assert.equal(p.w.document.getElementById('editId').value,'2');p.state.intercept=null;await p.api.openCreate();
 assert.equal(p.w.document.getElementById('creator').open,true);assert.equal(p.w.document.getElementById('editor').open,false);
 assert.equal(p.w.document.getElementById('form').parentElement.id,'creator');
 const pendingNext=holdRequests(p,c=>c.path.endsWith('/organizations/1'));
 const pending=p.api.edit(1),c=await pendingNext();p.api.closeEditor();c.release();await pending;
 assert.equal(p.w.document.getElementById('creator').open,false);assert.equal(p.w.document.getElementById('editor').open,false);
 assert.equal(p.w.document.querySelectorAll('#form').length,1);
}finally{p.close();}});

test('special invalidations during POST and 400ms guard replay only GET results',async()=>{const p=await page('special');try{
 for(const [id,value]of Object.entries({mergeFirst:'1',mergeSecond:'2',mergeExistingAddress:'1',mergeName:'Merged'}))p.w.document.getElementById(id).value=value;
 const next=holdRequests(p,c=>c.method==='POST'&&c.path.endsWith('/merge'));
 const running=p.api.mergeOrganizations(),held=await next();p.state.items.push(org(99));await p.api.loadData();held.release();await running;
 p.state.intercept=null;p.state.items[2].name='Updated during guard';await p.api.loadData();await p.api.mergeOrganizations();await pause(450);
 assert.match(p.w.document.querySelectorAll('.operation-result')[4].textContent,/Updated during guard/);
 assert.equal(p.state.calls.filter(c=>c.method==='POST').length,1);
 assert.ok(p.state.calls.some(c=>c.method==='GET'&&c.path.endsWith('/organizations/99')));
}finally{p.close();}});

test('special refresh discards obsolete response and drains newer invalidation',async()=>{const p=await page('special');try{
 await p.api.minEmployees();await pause(450);const next=holdRequests(p,c=>c.path.endsWith('/min-employees'));
 const first=p.api.refreshResults(),a=await next();p.state.items[0].name='Latest result';const second=p.api.refreshResults();
 a.release();const b=await next();assert.doesNotMatch(p.w.document.querySelector('.operation-result').textContent,/Latest result/);
 b.release();await Promise.all([first,second]);assert.match(p.w.document.querySelector('.operation-result').textContent,/Latest result/);
 assert.equal(p.state.calls.filter(c=>c.method==='POST').length,0);
}finally{p.close();}});

test('formatting keeps tiny float and double values and exact long digits',async()=>{const p=await page('index');try{
 for(const n of [1e-8,Math.fround(1e-8),Number.MIN_VALUE,1.2345678901234567]){
  const formatted=p.api.Lab.formatNumber(n),parsed=Number(formatted.replace(/[\s\u00a0\u202f]/g,'').replace(',','.'));assert.equal(parsed,n,formatted);
 }
 assert.equal(p.api.Lab.formatNumber('9223372036854775807').replace(/\s/g,''),'9223372036854775807');
 p.state.items[0].annualTurnover=1e-8;await p.api.load();assert.match(p.w.document.getElementById('rows').textContent,/0,00000001/);
 await p.api.edit(1);assert.equal(p.api.validateOrganizationForm(),true);assert.equal(p.api.payload().annualTurnover,1e-8);
}finally{p.close();}});

test('draft retains original opaque version after refresh and 409; POST omits version',async()=>{const p=await page('index');try{
 p.state.items[0].version='9223372036854775806';await p.api.edit(1);p.w.document.getElementById('orgName').value='My versioned draft';
 p.state.items[0].version='9223372036854775807';p.state.items[0].name='Concurrent change';await p.api.refreshOpenObjects(null);await p.api.loadReferences();
 assert.equal(p.api.payload().version,'9223372036854775806');p.state.putStatus=409;await p.api.save(submitEvent());
 assert.equal(JSON.parse(p.state.calls.find(c=>c.method==='PUT').body).version,'9223372036854775806');
 assert.equal(p.w.document.getElementById('orgName').value,'My versioned draft');assert.equal(p.w.document.getElementById('editor').open,true);
 assert.match(p.w.document.getElementById('dialogError').textContent,/Конфликт.*черновик сохранён/s);assert.equal(p.api.payload().version,'9223372036854775806');
 await p.api.openCreate();p.w.eval('fillRandomData()');assert.equal(Object.hasOwn(p.api.payload(),'version'),false);await p.api.save(submitEvent());
 const post=p.state.calls.find(c=>c.method==='POST'&&c.path.endsWith('/organizations'));assert.equal(Object.hasOwn(JSON.parse(post.body),'version'),false);
}finally{p.close();}});

test('merge rejects fractional int Z and each selected ID without POST',async()=>{const p=await page('special');try{
 const d=p.w.document;
 for(const [id,value]of Object.entries({mergeFirst:'1',mergeSecond:'2',mergeExistingAddress:'1',mergeExistingTown:'1',mergeName:'Merged',mergeAddressMode:'new',mergeTownMode:'new',mergeTownName:'Town',mergeTownX:'1',mergeTownY:'2',mergeTownZ:'3.5'}))d.getElementById(id).value=value;
 await p.api.mergeOrganizations();assert.match(d.getElementById('mergeTownZ').parentElement.textContent,/целое/);
 for(const id of ['mergeFirst','mergeSecond','mergeExistingAddress','mergeExistingTown']){
  await pause(410);d.getElementById('mergeTownZ').value='3';
  d.getElementById('mergeAddressMode').value=id==='mergeExistingAddress'?'existing':'new';d.getElementById('mergeTownMode').value=id==='mergeExistingTown'?'existing':'new';
  const select=d.getElementById(id),previous=select.value;select.add(new p.w.Option('Invalid fractional ID','1.5'));select.value='1.5';
  await p.api.mergeOrganizations();assert.match(select.parentElement.textContent,/целое/);select.value=previous;
 }
 assert.equal(p.state.calls.filter(c=>c.method==='POST').length,0);
}finally{p.close();}});

test('all organization and related selectors preserve IDs, clear missing ones and warn',async()=>{const p=await page('index');try{
 await p.api.edit(2);const ids=['coordExisting','officialExisting','postalExisting','officialTownExisting','postalTownExisting'];
 for(const id of ids)p.w.document.getElementById(id).value='2';await p.api.loadReferences();
 for(const id of ids)assert.equal(p.w.document.getElementById(id).value,'2',id);
 p.state.items=[org(1)];await p.api.loadReferences();for(const id of ids)assert.equal(p.w.document.getElementById(id).value,'',id);
 assert.match(p.w.document.getElementById('editWarning').textContent,/#2 удалён/);assert.equal(p.api.validateOrganizationForm(),false);
}finally{p.close();}
 const s=await page('special');try{
 const ids=['employeeOrg','mergeFirst','mergeSecond','mergeExistingAddress','mergeExistingTown'];
 for(const id of ids)s.w.document.getElementById(id).value='2';await s.api.loadData();for(const id of ids)assert.equal(s.w.document.getElementById(id).value,'2',id);
 s.state.items=[org(1)];await s.api.loadData();for(const id of ids)assert.equal(s.w.document.getElementById(id).value,'',id);
 assert.match(s.w.document.querySelectorAll('.card')[3].textContent,/#2 удалён/);assert.match(s.w.document.querySelectorAll('.card')[4].textContent,/#2 удалён/);
}finally{s.close();}});

test('deleting only row on last page navigates to an existing page, including empty dataset',async()=>{const p=await page('index');try{
 p.state.items=Array.from({length:11},(_,i)=>org(i+1));await p.api.load(1);p.api.removeOrg(11);await p.api.confirmDelete();
 assert.match(p.w.document.getElementById('pageInfo').textContent,/1–10 из 10/);
 p.state.items=[org(1)];await p.api.load(0);p.api.removeOrg(1);await p.api.confirmDelete();
 assert.equal(p.w.document.getElementById('pageInfo').textContent,'0–0 из 0');
 assert.equal(p.w.document.getElementById('prevButton').disabled,true);assert.equal(p.w.document.getElementById('nextButton').disabled,true);
}finally{p.close();}});

test('stale list and edit errors cannot overwrite a newer successful intent',async()=>{const p=await page('index');try{
 const next=holdRequests(p,c=>c.path.endsWith('/organizations'));
 const first=p.api.load(0),a=await next(),second=p.api.load(0),b=await next();
 b.release();await second;a.release({ok:false,status:500,json:async()=>({error:'Obsolete list error'})});await first;
 assert.equal(p.w.document.getElementById('error').textContent,'');assert.match(p.w.document.getElementById('pageInfo').textContent,/1–2 из 2/);
 const nextEdit=holdRequests(p,c=>/\/organizations\/\d+$/.test(c.path));
 const old=p.api.edit(1),c=await nextEdit(),latest=p.api.edit(2),d=await nextEdit();
 d.release();await latest;c.release({ok:false,status:404,json:async()=>({error:'Obsolete edit error'})});await old;
 assert.equal(p.w.document.getElementById('editId').value,'2');assert.equal(p.w.document.getElementById('error').textContent,'');
}finally{p.close();}});

test('reference list ignores stale failures after a newer successful refresh',async()=>{const p=await page('references');try{
 const next=holdRequests(p,c=>c.path.includes('/reference/'));
 const first=p.api.loadData().catch(()=>{}),old=[await next(),await next(),await next()];
 p.state.items=[org(2)];const second=p.api.loadData(),latest=[await next(),await next(),await next()];
 latest.forEach(r=>r.release());await second;
 old[0].release({ok:false,status:500,json:async()=>({error:'Obsolete reference failure'})});old.slice(1).forEach(r=>r.release());await first;
 assert.equal(p.w.document.getElementById('error').textContent,'');assert.equal(p.w.document.getElementById('loading').textContent,'');
 assert.match(p.w.document.getElementById('coordinatesRows').textContent,/2/);
}finally{p.close();}});

test('cancelled reference deletion cannot reopen from pending usage response',async()=>{const p=await page('references');try{
 const next=holdRequests(p,c=>c.path.endsWith('/usage'));
 const opening=p.api.deleteReference('coordinates',1),held=await next();
 const dialog=p.w.document.getElementById('replacementDialog');
 dialog.close();held.release();await opening;
 assert.equal(dialog.open,false);await p.api.replaceAndDelete();
 assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,0);
}finally{p.close();}});

test('Escape cancels pending usage and deletion waits for successful usage',async()=>{const p=await page('references');try{
 const next=holdRequests(p,c=>c.path.endsWith('/usage'));
 const opening=p.api.deleteReference('coordinates',1),held=await next();
 const dialog=p.w.document.getElementById('replacementDialog');assert.equal(dialog.open,true);
 assert.equal(p.w.document.getElementById('deleteReferenceConfirm').disabled,true);
 await p.api.replaceAndDelete();assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,0);
 dialog.dispatchEvent(new p.w.Event('cancel',{cancelable:true}));held.release();await opening;
 assert.equal(dialog.open,false);await p.api.replaceAndDelete();assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,0);
}finally{p.close();}});

test('obsolete usage 404 cannot close a newer reference deletion dialog',async()=>{const p=await page('references');try{
 const next=holdRequests(p,c=>c.path.endsWith('/usage'));
 const first=p.api.deleteReference('coordinates',1),a=await next(),second=p.api.deleteReference('coordinates',2),b=await next();
 b.release();await second;a.release({ok:false,status:404,json:async()=>({error:'Old missing reference'})});await first;
 assert.equal(p.w.document.getElementById('replacementDialog').open,true);
 assert.match(p.w.document.getElementById('replacementTitle').textContent,/#2/);assert.equal(p.w.document.getElementById('error').textContent,'');
 await p.api.replaceAndDelete();assert.equal(p.state.calls.find(c=>c.method==='DELETE').path,'/is-lab1/api/reference/coordinates/2');
}finally{p.close();}});

test('pending organization delete rejects Escape, direct close and target changes',async()=>{const p=await page('index');try{
 const d=p.w.document,next=holdRequests(p,c=>c.method==='DELETE');
 p.api.removeOrg(1);const deleting=p.api.confirmDelete(),held=await next();
 const cancel=new p.w.Event('cancel',{cancelable:true});d.getElementById('deleteDialog').dispatchEvent(cancel);
 assert.equal(cancel.defaultPrevented,true);assert.equal(d.getElementById('deleteDialog').open,true);
 d.getElementById('deleteDialog').close();p.api.removeOrg(2);await p.api.confirmDelete();
 assert.equal(d.getElementById('deleteDialog').open,true);assert.match(d.getElementById('deleteLabel').textContent,/#1/);
 assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,1);
 held.release();await deleting;assert.equal(d.getElementById('deleteDialog').open,false);
 p.api.removeOrg(2);assert.equal(d.getElementById('deleteDialog').open,true);assert.match(d.getElementById('deleteLabel').textContent,/#2/);
 await pause(0);assert.equal(d.getElementById('deleteDialog').open,true);
}finally{p.close();}});

test('viewer displays local read errors, retries and retains data on failed refresh',async()=>{const p=await page('index');try{
 const d=p.w.document;
 p.state.intercept=(call,response)=>call.path.endsWith('/organizations/1')?{ok:false,status:503,json:async()=>({error:'Temporary read failure'})}:response;
 await p.api.viewOrg(1);assert.equal(d.getElementById('viewer').open,true);assert.equal(d.getElementById('viewDetails').textContent,'');
 assert.match(d.getElementById('viewError').textContent,/Temporary read failure/);assert.equal(d.getElementById('error').textContent,'');
 assert.equal(d.getElementById('viewer').contains(d.getElementById('viewError')),true);assert.equal(d.getElementById('viewRetry').classList.contains('hidden'),false);
 p.state.intercept=null;await p.api.retryViewer();assert.match(d.getElementById('viewDetails').textContent,/Org 1/);
 const before=d.getElementById('viewDetails').textContent;assert.equal(d.getElementById('viewRetry').classList.contains('hidden'),true);
 p.state.intercept=(call,response)=>call.path.endsWith('/organizations/1')?{ok:false,status:503,json:async()=>({error:'Refresh failure'})}:response;
 await p.api.refreshOpenObjects(null);assert.equal(d.getElementById('viewDetails').textContent,before);assert.match(d.getElementById('viewError').textContent,/Refresh failure/);
 p.state.intercept=null;await p.api.retryViewer();assert.equal(d.getElementById('viewError').textContent,'');
}finally{p.close();}});

test('employees payload canonicalizes leading zeros without losing long precision',async()=>{const p=await page('index');try{
 await p.api.openCreate();p.w.eval('fillRandomData()');const input=p.w.document.getElementById('employees');
 for(const [raw,expected]of [['00000000000000000001','1'],['0009223372036854775807','9223372036854775807']]){
  input.value=raw;assert.equal(p.api.validateOrganizationForm(),true);assert.equal(p.api.payload().employeesCount,expected);
 }
 input.value='000';assert.equal(p.api.validateOrganizationForm(),false);
}finally{p.close();}});

test('lost committed create response retries immutable body/key and converts edited draft to PUT',async()=>{const p=await page('index');try{
 await p.api.openCreate();p.w.eval('fillRandomData()');let committed=null;const posts=[];
 p.state.intercept=(call,response)=>{
  if(call.method!=='POST'||!call.path.endsWith('/organizations'))return response;
  posts.push(call);
  if(!committed){committed={body:call.body,key:call.headers['X-Idempotency-Key']};p.state.items.push(org(99));throw new Error('Lost after commit');}
  assert.equal(call.body,committed.body);assert.equal(call.headers['X-Idempotency-Key'],committed.key);return response;
 };
 await p.api.save(submitEvent());const d=p.w.document;assert.equal(d.getElementById('creator').open,true);
 d.getElementById('orgName').value='Edited after uncertain result';await p.api.save(submitEvent());
 assert.equal(posts.length,2);assert.equal(p.state.items.filter(o=>o.id===99).length,1);
 assert.equal(d.getElementById('creator').open,false);assert.equal(d.getElementById('editor').open,true);assert.equal(d.getElementById('editId').value,'99');
 assert.equal(d.getElementById('orgName').value,'Edited after uncertain result');assert.match(d.getElementById('editWarning').textContent,/#99/);
 assert.equal(p.state.calls.filter(c=>c.method==='PUT').length,0);
 await p.api.save(submitEvent());const put=p.state.calls.find(c=>c.method==='PUT');assert.equal(put.path,'/is-lab1/api/organizations/99');
 assert.equal(JSON.parse(put.body).name,'Edited after uncertain result');assert.equal(JSON.parse(put.body).version,'0');assert.equal(posts.length,2);
}finally{p.close();}});

test('uncertain create can be reconciled even when the newer draft is invalid',async()=>{const p=await page('index');try{
 await p.api.openCreate();p.w.eval('fillRandomData()');let firstBody=null,count=0;
 p.state.intercept=(call,response)=>{
  if(call.method!=='POST')return response;
  count++;if(count===1){firstBody=call.body;throw new Error('Network lost before response');}
  assert.equal(call.body,firstBody);return response;
 };
 await p.api.save(submitEvent());const d=p.w.document;d.getElementById('employees').value='';
 await p.api.save(submitEvent());assert.equal(count,2);assert.equal(d.getElementById('editor').open,true);assert.equal(d.getElementById('editId').value,'99');
 assert.equal(d.getElementById('employees').value,'');await p.api.save(submitEvent());assert.equal(p.state.calls.filter(c=>c.method==='PUT').length,0);
}finally{p.close();}});

test('unchanged uncertain create closes on replay and never allocates another key',async()=>{const p=await page('index');try{
 await p.api.openCreate();p.w.eval('fillRandomData()');const posts=[];
 p.state.intercept=(call,response)=>{if(call.method==='POST'){posts.push(call);if(posts.length===1)throw new Error('Lost response');}return response;};
 await p.api.save(submitEvent());await p.api.save(submitEvent());assert.equal(posts.length,2);
 assert.equal(posts[0].body,posts[1].body);assert.equal(posts[0].headers['X-Idempotency-Key'],posts[1].headers['X-Idempotency-Key']);
 assert.equal(p.w.document.getElementById('creator').open,false);assert.equal(p.w.document.getElementById('editor').open,false);
}finally{p.close();}});

test('closing and reopening a pending create restores both draft and original request',async()=>{const p=await page('index');try{
 await p.api.openCreate();p.w.eval('fillRandomData()');const posts=[];
 p.state.intercept=(call,response)=>{if(call.method==='POST'){posts.push(call);if(posts.length===1)throw new Error('Lost response');}return response;};
 await p.api.save(submitEvent());const d=p.w.document;d.getElementById('orgName').value='Preserved pending draft';p.api.closeEditor();
 await p.api.edit(1);assert.equal(d.getElementById('editId').value,'1');p.api.closeEditor();
 await p.api.openCreate();assert.equal(d.getElementById('orgName').value,'Preserved pending draft');assert.match(d.getElementById('saveButton').textContent,/Повторить/);
 await p.api.save(submitEvent());assert.equal(posts[0].body,posts[1].body);assert.equal(posts[0].headers['X-Idempotency-Key'],posts[1].headers['X-Idempotency-Key']);
 assert.equal(d.getElementById('editId').value,'99');assert.equal(d.getElementById('orgName').value,'Preserved pending draft');
}finally{p.close();}});

test('definitive create rejection permits corrected body with a fresh key',async()=>{const p=await page('index');try{
 await p.api.openCreate();p.w.eval('fillRandomData()');const posts=[];
 p.state.intercept=(call,response)=>{if(call.method==='POST'){posts.push(call);if(posts.length===1)return {ok:false,status:400,json:async()=>({error:'Rejected input'})};}return response;};
 await p.api.save(submitEvent());p.w.document.getElementById('orgName').value='Corrected rejected request';await p.api.save(submitEvent());
 assert.equal(posts.length,2);assert.notEqual(posts[0].body,posts[1].body);assert.notEqual(posts[0].headers['X-Idempotency-Key'],posts[1].headers['X-Idempotency-Key']);
 assert.equal(p.w.document.getElementById('creator').open,false);
}finally{p.close();}});

test('organization options use one complete snapshot and retain selected IDs beyond100',async()=>{const p=await page('special');try{
 p.state.items=Array.from({length:201},(_,i)=>org(i+1));await p.api.loadData();const d=p.w.document,selected=d.getElementById('employeeOrg');selected.value='101';
 const before=p.state.calls.length;let changed=false;
 p.state.intercept=(call,response)=>{if(!changed&&call.path.endsWith('/organizations/options')){changed=true;p.state.items=p.state.items.filter(o=>o.id!==1);}return response;};
 await p.api.loadData();assert.equal(selected.value,'101');assert.equal(selected.options.length,202);await p.api.loadData();
 assert.equal(selected.value,'101');assert.equal(selected.options.length,201);assert.equal(p.state.items.some(o=>o.id===101),true);
 const loads=p.state.calls.slice(before).filter(c=>c.path.includes('/organizations'));
 assert.equal(loads.length,2);assert.ok(loads.every(c=>c.path.endsWith('/options')&&!c.query));
 p.state.items=p.state.items.filter(o=>o.id!==101);await p.api.loadData();assert.equal(selected.value,'');assert.match(d.querySelectorAll('.card')[3].textContent,/#101 удалён/);
}finally{p.close();}});

test('redesign shares navigation, active route, safe account identity and online status',async()=>{
 for(const name of ['index','special','references']){const p=await page(name);try{
  const d=p.w.document;
  assert.equal(d.querySelector('main').id,'main');assert.equal(d.querySelector('.skip-link').getAttribute('href'),'#main');
  assert.equal(d.querySelectorAll('.app-header nav a').length,3);assert.equal(d.querySelector('nav a[aria-current="page"]').getAttribute('href'),name+'.html');
  assert.equal(d.querySelector('#sessionUser').textContent,'test');assert.equal(d.querySelector('.session-tools button').textContent,'Выйти');
  assert.equal(d.querySelector('#connectionSlot .connection-state').dataset.state,'online');
  assert.equal(d.querySelectorAll('style').length,0);
 }finally{p.close();}}
});

test('simplified shell uses the lab title, operations label and relocated create button',async()=>{
 for(const name of ['index','special','references']){const p=await page(name);try{
  const d=p.w.document;assert.equal(d.querySelector('.app-header .brand').textContent,'Информационные системы · Лабораторная работа № 1');
  assert.equal(d.querySelector('.app-header a[href="special.html"]').textContent,'Операции');
  assert.equal(d.querySelector('.brand-mark'),null);assert.equal(d.querySelector('.page-heading .eyebrow,.page-heading .page-description'),null);
  if(name==='index'){
   assert.equal(d.querySelectorAll('.create-organization').length,1);assert.ok(d.querySelector('.panel-heading .create-organization'));
   assert.equal(d.getElementById('filterHint').hidden,true);assert.equal(d.querySelector('.lookup-panel p,.page-footer'),null);
  }
 }finally{p.close();}}
});

test('login has the shared header without the split-screen panel or explanatory text',()=>{
 const dom=new JSDOM(fs.readFileSync(path.join(root,'login.html'),'utf8'));
 try{
  const d=dom.window.document;assert.equal(d.querySelector('.app-header .brand').textContent,'Информационные системы · Лабораторная работа № 1');
  assert.equal(d.querySelector('.auth-story,.auth-footnote,#heading,#modeDescription,.auth-box .eyebrow'),null);
  assert.ok(d.getElementById('login'));assert.ok(d.getElementById('registerTab'));assert.equal(d.querySelectorAll('[role="tab"]').length,2);
 }finally{dom.window.close();}
});

test('operations have one concise heading each, blue merge and no read-success message',async()=>{const p=await page('special');try{
 const d=p.w.document;assert.equal(d.querySelector('h1').textContent,'Операции');
 assert.deepEqual([...d.querySelectorAll('.card > h2')].map(e=>e.textContent),['Минимум сотрудников','Поиск по началу названия','Поиск по типу выше заданного','Добавление сотрудника','Объединение организаций']);
 assert.equal(d.querySelector('.card-kicker,.card-description,#mergeWarning'),null);
 assert.equal(d.querySelector('button[onclick="mergeOrganizations()"]').className,'primary');
 await p.api.minEmployees();assert.ok(d.querySelector('.operation-result table'));assert.equal(d.querySelector('.card > [role="status"]').textContent,'');
}finally{p.close();}});

test('used reference still requires replacement without usage paragraphs or success banner',async()=>{const p=await page('references');try{
 p.state.usage=1;const d=p.w.document;await p.api.deleteReference('coordinates',1);
 assert.equal(d.getElementById('replacementHelp'),null);assert.equal(d.getElementById('usageInfo').textContent,'');assert.equal(d.getElementById('replacementLabel').hidden,false);
 await p.api.replaceAndDelete();assert.equal(p.state.calls.filter(c=>c.method==='DELETE').length,0);assert.match(d.getElementById('replacementError').textContent,/Выберите объект/);
 d.getElementById('replacementSelect').value='2';await p.api.replaceAndDelete();
 const deletion=p.state.calls.find(c=>c.method==='DELETE');assert.equal(deletion.query,'?replacementId=2');assert.equal(d.getElementById('replacementDialog').open,false);assert.equal(d.getElementById('success'),null);
}finally{p.close();}});

test('registry retains every attribute column and translated labels retain enum wire values',async()=>{const p=await page('index');try{
 const d=p.w.document,headers=[...d.querySelectorAll('.organization-table th')].map(e=>e.textContent);
 assert.deepEqual(headers,['ID','Название','Координаты','Официальный адрес','Почтовый адрес','Сотрудники','Годовой оборот','Рейтинг','Тип','Дата создания','Действия']);
 assert.equal(d.querySelector('.table-wrap').tabIndex,0);assert.equal(d.getElementById('totalCount').textContent,'2');
 assert.deepEqual([...d.querySelectorAll('#orgType option')].map(o=>o.value),['COMMERCIAL','GOVERNMENT','PRIVATE_LIMITED_COMPANY']);
 assert.equal(p.api.Lab.typeLabel('GOVERNMENT'),'Государственная');
 d.getElementById('filterField').value='type';d.getElementById('filterField').dispatchEvent(new p.w.Event('change'));
 assert.equal(d.getElementById('filter').getAttribute('list'),'typeSuggestions');assert.match(d.getElementById('filterHint').textContent,/PRIVATE_LIMITED_COMPANY/);
 d.getElementById('filter').value='COMM';await p.api.load(0);
 assert.ok(p.state.calls.some(c=>c.query.includes('filterField=type')&&c.query.includes('filterValue=COMM')));
}finally{p.close();}});

test('field errors are associated with inputs and removed when corrected',async()=>{const p=await page('index');try{
 await p.api.openCreate();assert.equal(p.api.validateOrganizationForm(),false);
 assert.equal(p.w.document.getElementById('officialZip').required,false,'Blank zip is allowed, including for assistive technology');
 const d=p.w.document,input=d.getElementById('orgName');assert.equal(input.getAttribute('aria-invalid'),'true');
 const error=d.getElementById(input.getAttribute('aria-describedby'));assert.ok(error.textContent);
 input.value='Исправлено';input.dispatchEvent(new p.w.Event('input'));assert.equal(input.hasAttribute('aria-invalid'),false);assert.equal(error.textContent,'');
 p.api.Lab.fieldError(d.getElementById('rating'),'Проверьте рейтинг');assert.equal(d.getElementById('rating').getAttribute('aria-invalid'),'true');
 p.api.Lab.fieldError(d.getElementById('rating'),'');assert.equal(d.getElementById('rating').getAttribute('aria-invalid'),'false');
}finally{p.close();}});

test('reference counters reflect filtered and total rows; empty cells match table columns',async()=>{const p=await page('references');try{
 const d=p.w.document;assert.equal(d.getElementById('coordinatesCount').textContent,'2');
 d.getElementById('referenceSearch').value='OrgDoesNotExist';p.w.eval('render()');
 for(const kind of ['coordinates','addresses','locations']){
  assert.match(d.getElementById(kind+'Count').textContent,/0\s*\/\s*2/);
  const body=d.getElementById(kind+'Rows');assert.equal(body.querySelector('td').colSpan,body.closest('table').querySelectorAll('th').length);
 }
}finally{p.close();}});

test('untrusted organization and reference text remains text in redesigned tables',async()=>{const p=await page('index');try{
 p.state.items[0].name='<img src=x onerror=alert(1)>';p.state.items[0].officialAddress.town.name='<svg onload=alert(1)>';
 await p.api.load();const rows=p.w.document.getElementById('rows');assert.equal(rows.querySelector('img,svg'),null);assert.match(rows.textContent,/<img src=x/);
 await p.api.viewOrg(1);assert.equal(p.w.document.getElementById('viewDetails').querySelector('img,svg'),null);
}finally{p.close();}
 const s=await page('special');try{s.state.items[0].name='<img src=x onerror=alert(1)>';await s.api.minEmployees();assert.equal(s.w.document.querySelector('.operation-result img'),null);assert.match(s.w.document.querySelector('.operation-result').textContent,/<img src=x/);}finally{s.close();}
 const r=await page('references');try{r.state.items[0].officialAddress.town.name='<svg onload=alert(1)>';await r.api.loadData();assert.equal(r.w.document.querySelector('#locationsRows svg'),null);assert.match(r.w.document.getElementById('locationsRows').textContent,/<svg/);}finally{r.close();}
});

test('out-of-order reference loads keep newest lists and the current selected ID',async()=>{const p=await page('index');try{
 await p.api.edit(2);const next=holdRequests(p,c=>c.path.includes('/reference/'));
 const first=p.api.loadReferences(),old=[await next(),await next(),await next()];
 p.state.items=[org(2)];const second=p.api.loadReferences(),latest=[await next(),await next(),await next()];
 latest.forEach(r=>r.release());await second;old.forEach(r=>r.release());await first;
 for(const id of ['coordExisting','officialExisting','officialTownExisting']){
  const select=p.w.document.getElementById(id);assert.equal(select.value,'2');assert.equal(select.options.length,2);assert.equal(select.options[1].value,'2');
 }
}finally{p.close();}});

