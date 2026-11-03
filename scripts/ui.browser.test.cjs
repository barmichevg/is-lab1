/* Real-browser UI regression and reproducible screenshots with deterministic, mocked HTTP data.
 * No server/database is started or modified. Not a substitute for backend integration tests.
 * Install Playwright or set PLAYWRIGHT_MODULE; optionally set CHROMIUM_PATH to a local browser.
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../src/main/webapp');
const output = path.resolve(process.env.UI_ARTIFACT_DIR || path.join(__dirname, '../review-artifacts/ui-2026-11-02'));
const base = 'http://127.0.0.1:18089/is-lab1/'; // intercepted; no listener is started
const names = ['Северная компания', 'Институт городских исследований', 'Студия «Контур»', 'Балтийская логистика', 'Центр цифровых решений', 'Новая перспектива', 'Лаборатория среды', 'Горизонт'];
const cities = ['Санкт-Петербург', 'Москва', 'Казань', 'Калининград'];
const types = ['COMMERCIAL', 'GOVERNMENT', 'PRIVATE_LIMITED_COMPANY'];
function fixture(i) {
    const id = i + 1;
    return {id, name: names[i % names.length], version:'0', employeesCount:String([240,86,32,1280,75,18,53,410][i%8]),
        annualTurnover:[12500000,3200000,840000,98500000,6500000,2100000,4780000,18400000][i%8], rating:[8,9,7,8,10,6,9,8][i%8], type:types[i%3],
        creationDate:`2026-10-${String(12+i).padStart(2,'0')}T10:24:36.123456`, coordinates:{id,x:120+i*21,y:48+i*12},
        officialAddress:{id,zipCode:['197101','101000','420111','236000'][i%4],town:{id,name:cities[i%4],x:59.93+i,y:30.31+i,z:3+i}},
        postalAddress:null};
}
(async()=>{
    await fs.mkdir(output,{recursive:true});
    const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:['--no-sandbox']});
    const context=await browser.newContext({viewport:{width:1600,height:1000},locale:'ru-RU',timezoneId:'Europe/Moscow',reducedMotion:'reduce'});
    let items=Array.from({length:8},(_,i)=>fixture(i)), nextId=20;
    const references={coordinates:items.map(o=>o.coordinates),addresses:items.map(o=>o.officialAddress),locations:items.map(o=>o.officialAddress.town)};
    const requests=[],errors=[];
    let failNextSave=false,failNextRead=false,loseNextCreateResponse=false;
    let holdNextDelete=false,heldDelete=null,onDeleteHeld=null,deleteDuringOptions=false;
    const createResults=new Map();
    await context.addInitScript(()=>{
        window.__testSockets=[];
        window.WebSocket=class {
            constructor(){this.readyState=0;window.__testSockets.push(this);setTimeout(()=>{this.readyState=1;this.onopen?.();},20);}
            close(){this.readyState=3;this.onclose?.({code:1000});}
        };
    });
    await context.route('**/*',async route=>{
        const req=route.request(),url=new URL(req.url()),method=req.method();
        if(!req.url().startsWith(base))return route.abort();
        const file=url.pathname.slice('/is-lab1/'.length);
        if(!file.startsWith('api/')) {
            if(!['index.html','special.html','references.html','login.html','app.js','app.css'].includes(file))return route.fulfill({status:404,body:''});
            return route.fulfill({status:200,contentType:file.endsWith('.html')?'text/html; charset=utf-8':file.endsWith('.css')?'text/css':'text/javascript',body:await fs.readFile(path.join(root,file))});
        }
        requests.push({url:url.pathname+url.search,method,body:req.postData(),headers:req.headers()});
        let status=200,result;
        if(file==='api/auth/session') result={username:'demo_user',csrf:'demo-csrf',wsToken:'demo-ws'};
        else if(file==='api/auth/login'||file==='api/auth/register') {status=401;result={error:'Invalid credentials'};}
        else if(file==='api/auth/logout') {status=204;}
        else if(file.startsWith('api/reference/')) {
            const [, , kind, id, action]=file.split('/');
            if(action==='usage')result={totalReferences:2};
            else if(method==='DELETE') {status=204;references[kind]=references[kind].filter(o=>o.id!==Number(id));}
            else result=references[kind];
        }
        else if(file==='api/organizations/options') {
            result=items.slice().sort((a,b)=>a.id-b.id).map(o=>({id:o.id,name:o.name,employeesCount:o.employeesCount}));
            if(deleteDuringOptions){deleteDuringOptions=false;items=items.filter(o=>o.id!==1);}
        }
        else if(file==='api/organizations' && method==='GET') {
            const filter=url.searchParams.get('filterValue')||'',field=url.searchParams.get('filterField')||'name';
            const value=o=>({name:o.name,type:o.type,officialZip:o.officialAddress.zipCode,officialTown:o.officialAddress.town.name,postalZip:o.postalAddress?.zipCode||'',postalTown:o.postalAddress?.town.name||''})[field];
            let filtered=items.filter(o=>String(value(o)).toLowerCase().includes(filter.toLowerCase()));
            const sort=url.searchParams.get('sort')||'id',direction=url.searchParams.get('direction')==='desc'?-1:1;
            filtered.sort((a,b)=>direction*(sort==='id'?a.id-b.id:String(a[sort]||a.name).localeCompare(String(b[sort]||b.name),'ru')));
            const page=Number(url.searchParams.get('page')||0),size=Number(url.searchParams.get('size')||10);
            result={items:filtered.slice(page*size,(page+1)*size),total:filtered.length,page,size};
        }
        else if(file.startsWith('api/organizations')) {
            const id=Number(file.split('/')[2]);
            if(method==='POST'||method==='PUT') {
                const key=req.headers()['x-idempotency-key'],cached=method==='POST'&&createResults.get(key);
                if(failNextSave) {failNextSave=false;status=409;result={error:'Организация изменена другим пользователем'};}
                else if(cached) {
                    if(cached.body!==req.postData()){status=400;result={error:'idempotency key: already used with another request'};}
                    else {status=201;result=cached.result;}
                } else {
                    const data=JSON.parse(req.postData());
                    result={...fixture(0),...data,id:method==='POST'?nextId++:id,
                        coordinates:data.coordinates.id?references.coordinates.find(c=>c.id===data.coordinates.id):{...data.coordinates,id:91},
                        officialAddress:data.officialAddress.id?references.addresses.find(a=>a.id===data.officialAddress.id):{...data.officialAddress,id:92,town:data.officialAddress.town.id?references.locations.find(l=>l.id===data.officialAddress.town.id):{...data.officialAddress.town,id:93}}};
                    items=items.filter(o=>o.id!==result.id).concat(result);status=method==='POST'?201:200;
                    if(method==='POST'&&key)createResults.set(key,{body:req.postData(),result:JSON.parse(JSON.stringify(result))});
                    if(method==='POST'&&loseNextCreateResponse){loseNextCreateResponse=false;return route.abort('failed');}
                }
            } else if(method==='DELETE') {
                if(holdNextDelete){holdNextDelete=false;heldDelete={route,id};onDeleteHeld?.();return;}
                items=items.filter(o=>o.id!==id);status=204;
            } else if(failNextRead) {failNextRead=false;status=503;result={error:'Временная ошибка чтения'};}
            else {result=items.find(o=>o.id===id);if(!result){status=404;result={error:'Не найдено'};}}
        }
        else if(file.includes('special-operations/')) {
            if(file.endsWith('min-employees'))result=items.reduce((a,b)=>BigInt(a.employeesCount)<BigInt(b.employeesCount)?a:b);
            else if(file.includes('name-starts-with'))result=items.filter(o=>o.name.startsWith(url.searchParams.get('prefix')||''));
            else if(file.includes('type-greater-than'))result=items.filter(o=>types.indexOf(o.type)>types.indexOf(url.searchParams.get('type')));
            else if(file.endsWith('add-employee')) {
                result=items.find(o=>o.id===Number(file.split('/')[2]));result.employeesCount=(BigInt(result.employeesCount)+1n).toString();
            } else if(file.endsWith('merge')) {
                const data=JSON.parse(req.postData()),a=items.find(o=>o.id===data.firstOrganizationId),b=items.find(o=>o.id===data.secondOrganizationId);
                result={...a,id:nextId++,name:data.name,employeesCount:(BigInt(a.employeesCount)+BigInt(b.employeesCount)).toString(),annualTurnover:a.annualTurnover+b.annualTurnover};
                items=items.filter(o=>o!==a&&o!==b).concat(result);
            }
        }
        else {status=500;result={error:'Unhandled test route '+file};}
        return route.fulfill({status,contentType:'application/json',body:status===204?'':JSON.stringify(result)});
    });
    const page=await context.newPage();
    page.on('pageerror',e=>errors.push(e.message));
    page.on('dialog',dialog=>dialog.accept());
    async function goto(name){
        await page.goto(base+name+'.html');await page.locator('main').waitFor();
        if(name!=='login')await page.waitForFunction(()=>document.querySelector('.connection-state')?.dataset.state==='online');
        assert.equal(await page.locator('.app-header .brand').textContent(),'Информационные системы · Лабораторная работа № 1');
        if(name!=='login')assert.equal(await page.locator('.app-header a[href="special.html"]').textContent(),'Операции');
    }
    async function shot(name){
        const dialogOpen=!!(await page.locator('dialog[open]').count());
        if(!dialogOpen)await page.evaluate(()=>window.scrollTo(0,0));
        await page.screenshot({path:path.join(output,name+'.png'),fullPage:!dialogOpen});
    }
    async function noPageOverflow(){assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'The page itself overflows horizontally');}
    try {
        await goto('index');await noPageOverflow();
        assert.equal(await page.locator('.organization-table th').count(),11);
        assert.equal(await page.locator('#rows tr').count(),8);
        assert.equal(await page.locator('#sessionUser').textContent(),'demo_user');
        assert.equal(await page.locator('.panel-heading .create-organization').count(),1);
        assert.equal(await page.locator('.page-heading .eyebrow,.page-description,.page-footer,.lookup-panel p').count(),0);
        assert.ok(await page.locator('#filterHint').isHidden());
        await shot('01-organizations-desktop');
        await page.locator('#filter').fill('Студия');await page.locator('#filter').press('Enter');
        await page.waitForFunction(()=>document.getElementById('totalCount').textContent==='1');
        await page.getByRole('button',{name:'Сбросить',exact:true}).click();
        await page.waitForFunction(()=>document.getElementById('totalCount').textContent==='8');
        await page.locator('#filterField').selectOption('type');assert.match(await page.locator('#filterHint').textContent(),/COMMERCIAL/);
        await page.getByRole('button',{name:'Сбросить',exact:true}).click();
        await page.locator('#rows .entity-link').first().click();
        await page.waitForFunction(()=>document.getElementById('viewDetails').textContent.includes('Северная компания'));
        assert.match(await page.locator('#viewDetails').textContent(),/59.93/);await shot('02-organization-details');
        await page.getByRole('button',{name:'Изменить организацию',exact:true}).click();
        await page.locator('#editor[open]').waitFor();
        await page.locator('#orgName').fill('Несохранённый черновик');failNextSave=true;
        await page.locator('#saveButton').click();
        await page.locator('#editWarning:not(.hidden)').waitFor();
        assert.equal(await page.locator('#orgName').inputValue(),'Несохранённый черновик');
        assert.ok(await page.locator('#editor').evaluate(e=>e.open));
        await shot('03-edit-conflict');
        await page.getByRole('button',{name:'Отмена',exact:true}).click();
        await page.getByRole('button',{name:'Создать организацию',exact:true}).click();
        await page.locator('#creator[open]').waitFor();
        await page.locator('#saveButton').click();assert.equal(await page.locator('#orgName').getAttribute('aria-invalid'),'true');
        await page.locator('#orgName').fill('Бюро «Новый маршрут»');
        await page.locator('#turnover').fill('6800000');await page.locator('#employees').fill('42');await page.locator('#rating').fill('8');
        await page.locator('#coordMode').selectOption('existing');await page.locator('#coordExisting').selectOption('1');
        await page.locator('#officialMode').selectOption('existing');await page.locator('#officialExisting').selectOption('1');
        await page.locator('#creator').evaluate(e=>e.scrollTop=0);await shot('04-create-organization');
        await page.locator('#saveButton').click();await page.waitForFunction(()=>!document.getElementById('creator').open);
        const create=requests.find(r=>r.method==='POST'&&r.url.endsWith('/organizations'));
        assert.ok(create?.headers['x-idempotency-key']);assert.equal(JSON.parse(create.body).employeesCount,'42');
        await page.locator('#lookupId').fill('20');await page.locator('#lookupId').press('Enter');
        await page.waitForFunction(()=>document.getElementById('viewDetails').textContent.includes('Новый маршрут'));
        await page.keyboard.press('Escape');
        await page.locator('#filter').fill('Заведомо отсутствует');await page.locator('#filter').press('Enter');
        await page.waitForFunction(()=>document.getElementById('totalCount').textContent==='0');await shot('05-empty-filter');
        console.log('PASS desktop registry: all columns, filter/reset, lookup, modal create, validation, exact string counters, 409 draft retained.');

        await goto('special');await noPageOverflow();assert.equal(await page.locator('.card').count(),5);
        await page.getByRole('button',{name:'Найти организацию',exact:true}).click();
        await page.locator('.operation-result table').first().waitFor();
        assert.equal(await page.locator('.card').first().locator('[role="status"]').textContent(),'');
        assert.equal(await page.locator('.card-kicker,.card-description,#mergeWarning').count(),0);
        assert.equal(await page.locator('button[onclick="mergeOrganizations()"]').evaluate(e=>getComputedStyle(e).backgroundColor),'rgb(40, 90, 214)');
        await page.locator('#mergeFirst').selectOption('1');await page.locator('#mergeSecond').selectOption('2');
        await page.locator('#mergeName').fill('Северный исследовательский центр');await page.locator('#mergeExistingAddress').selectOption('1');
        await shot('06-special-operations');
        await page.getByRole('button',{name:'Объединить организации',exact:true}).click();
        await page.waitForFunction(()=>document.querySelectorAll('.card')[4].querySelector('.success')?.textContent.includes('заменены'));
        assert.equal(items.some(o=>o.id===1||o.id===2),false);
        console.log('PASS special operations: result rendering, merge original IDs, preserved existing references, source removal.');

        await goto('references');await noPageOverflow();await shot('07-references');
        await page.locator('#referenceSearch').fill('Санкт');
        assert.match(await page.locator('#locationsCount').textContent(),/2\s*\/\s*8/);
        await page.locator('#referenceSearch').fill('');
        await page.locator('#coordinatesRows button').first().click();
        await page.locator('#replacementSelect').selectOption('2');
        assert.equal(await page.locator('#replacementHelp').count(),0);assert.equal(await page.locator('#usageInfo').textContent(),'');
        await shot('08-reference-replacement');
        assert.equal(requests.filter(r=>r.method==='DELETE').length,0);
        await page.locator('#deleteReferenceConfirm').click();
        await page.waitForFunction(()=>!document.getElementById('replacementDialog').open);
        assert.ok(requests.find(r=>r.method==='DELETE'&&r.url.includes('replacementId=2')));
        assert.equal(await page.locator('#success').count(),0);
        console.log('PASS references: counts/search and explicit replacement-before-delete dialog.');

        await goto('login');await noPageOverflow();
        assert.equal(await page.locator('.auth-story,.auth-footnote,#heading,#modeDescription,.auth-box .eyebrow').count(),0);
        await shot('09-login-desktop');
        await page.locator('#loginTab').focus();await page.keyboard.press('ArrowRight');
        assert.equal(await page.locator('#registerTab').getAttribute('aria-selected'),'true');
        await page.locator('input[name=username]').fill('new_user');await page.locator('#password').fill('one');await page.locator('#repeatPassword').fill('two');
        await page.locator('#submit').click();assert.match(await page.locator('#error').textContent(),/не совпадают/);
        await shot('10-registration-validation');
        console.log('PASS auth: keyboard tabs, registration mismatch and preserved password policy.');

        // Regression: native Escape cannot dismiss or retarget an in-flight delete.
        await goto('index');const deletionId=items[0].id,nextDeletionId=items[1].id;
        holdNextDelete=true;const deletionHeld=new Promise(resolve=>onDeleteHeld=resolve);
        await page.locator(`button[onclick="removeOrg(${deletionId})"]`).click();await page.locator('#deleteConfirm').click();await deletionHeld;
        await page.keyboard.press('Escape');assert.equal(await page.locator('#deleteDialog').evaluate(e=>e.open),true);
        await page.evaluate(id=>{document.getElementById('deleteDialog').close();removeOrg(id);},nextDeletionId);
        assert.match(await page.locator('#deleteLabel').textContent(),new RegExp('#'+deletionId+'\\b'));
        await shot('11-pending-delete');
        items=items.filter(o=>o.id!==heldDelete.id);await heldDelete.route.fulfill({status:204,body:''});
        await page.waitForFunction(()=>!document.getElementById('deleteConfirm').disabled);
        await page.locator(`button[onclick="removeOrg(${nextDeletionId})"]`).click();assert.equal(await page.locator('#deleteDialog').evaluate(e=>e.open),true);
        assert.match(await page.locator('#deleteLabel').textContent(),new RegExp('#'+nextDeletionId+'\\b'));await page.keyboard.press('Escape');
        console.log('PASS regression: native Escape/direct close/retarget blocked during DELETE; later deletion intent stays independent.');

        // Regression: an initial GET503 and a failed refresh show an in-modal error/retry.
        failNextRead=true;await page.locator('#rows .entity-link').first().click();await page.locator('#viewError').waitFor({state:'visible'});
        assert.equal(await page.locator('#viewDetails').textContent(),'');assert.equal(await page.locator('#error').textContent(),'');await shot('12-viewer-read-error');
        await page.locator('#viewRetry').click();await page.waitForFunction(()=>document.getElementById('viewDetails').textContent.length>0);
        const detailsBefore=await page.locator('#viewDetails').textContent();failNextRead=true;
        await page.evaluate(id=>viewOrg(id,{refresh:true}),items[0].id);assert.equal(await page.locator('#viewDetails').textContent(),detailsBefore);
        assert.equal(await page.locator('#viewError').isVisible(),true);await page.locator('#viewRetry').click();
        await page.waitForFunction(()=>document.getElementById('viewError').textContent==='');await page.keyboard.press('Escape');
        console.log('PASS regression: GET503 appears inside viewer, retry succeeds and refresh failure preserves loaded data.');

        // Regression: the first create commits but its response is lost at transport level.
        await page.getByRole('button',{name:'Создать организацию',exact:true}).click();await page.locator('#creator[open]').waitFor();
        await page.locator('#orgName').fill('Исходный запрос с потерянным ответом');await page.locator('#turnover').fill('100');
        await page.locator('#employees').fill('00000000000000000001');await page.locator('#rating').fill('2');
        await page.locator('#coordMode').selectOption('existing');await page.locator('#coordExisting').selectOption(String(references.coordinates[0].id));
        await page.locator('#officialMode').selectOption('existing');await page.locator('#officialExisting').selectOption(String(references.addresses[0].id));
        const countBeforeCreate=items.length,postsBefore=requests.filter(r=>r.method==='POST'&&r.url.endsWith('/organizations')).length;
        loseNextCreateResponse=true;await page.locator('#saveButton').click();await page.locator('#dialogError').waitFor({state:'visible'});
        assert.equal(items.length,countBeforeCreate+1);const recovered=items.find(o=>o.name==='Исходный запрос с потерянным ответом');assert.ok(recovered);
        await page.locator('#orgName').fill('Правки восстановленной организации');await page.locator('#saveButton').click();await page.locator('#editor[open]').waitFor();
        assert.equal(await page.locator('#editId').inputValue(),String(recovered.id));assert.equal(await page.locator('#orgName').inputValue(),'Правки восстановленной организации');
        assert.equal(items.length,countBeforeCreate+1);const createAttempts=requests.filter(r=>r.method==='POST'&&r.url.endsWith('/organizations')).slice(postsBefore);
        assert.equal(createAttempts.length,2);assert.equal(createAttempts[0].body,createAttempts[1].body);assert.equal(JSON.parse(createAttempts[0].body).employeesCount,'1');
        assert.equal(createAttempts[0].headers['x-idempotency-key'],createAttempts[1].headers['x-idempotency-key']);await shot('13-create-recovered-draft');
        await page.locator('#saveButton').click();await page.waitForFunction(()=>!document.getElementById('editor').open);
        assert.equal(items.length,countBeforeCreate+1);assert.equal(items.find(o=>o.id===recovered.id).name,'Правки восстановленной организации');
        assert.ok(requests.find(r=>r.method==='PUT'&&r.url.endsWith('/'+recovered.id)&&JSON.parse(r.body).name==='Правки восстановленной организации'));
        console.log('PASS regression: committed POST response aborted, same body/key replayed, changed draft explicitly PUT to same ID; long leading zeros canonicalized.');

        // Regression: a complete options snapshot does not skip ID101 across offset pages.
        const restoredItems=items;items=Array.from({length:201},(_,i)=>({...fixture(i%8),id:i+1,name:'Organization '+(i+1)}));
        await goto('special');await page.locator('#employeeOrg').selectOption('101');const optionsStart=requests.length;deleteDuringOptions=true;
        await page.evaluate(()=>loadData());assert.equal(await page.locator('#employeeOrg').inputValue(),'101');
        await page.evaluate(()=>loadData());assert.equal(await page.locator('#employeeOrg').inputValue(),'101');
        assert.ok(items.some(o=>o.id===101));const optionRequests=requests.slice(optionsStart).filter(r=>r.url.includes('/organizations'));
        assert.equal(optionRequests.length,2);assert.ok(optionRequests.every(r=>r.url.endsWith('/organizations/options')));
        items=items.filter(o=>o.id!==101);await page.evaluate(()=>loadData());assert.equal(await page.locator('#employeeOrg').inputValue(),'');
        assert.match(await page.locator('.card').nth(3).textContent(),/#101 удалён/);items=restoredItems;
        console.log('PASS regression:201 options use one projection snapshot; concurrent earlier deletion retains live101, actual101 deletion clears it.');

        await page.setViewportSize({width:390,height:844});
        for(const name of ['index','special','references','login']) {
            await goto(name);await noPageOverflow();await shot('mobile-'+name);
        }
        await goto('index');await page.getByRole('button',{name:'Создать организацию',exact:true}).click();
        await page.locator('#creator[open]').waitFor();
        await page.locator('#postalMode').selectOption('new');
        await page.locator('#postalTownMode').selectOption('new');
        await page.locator('#postalTownZ').fill('2');
        await page.locator('#creator').evaluate(e=>e.scrollTop=e.scrollHeight);await shot('mobile-create-bottom');
        const bounds=await page.locator('#creator').evaluate(e=>({width:e.scrollWidth,client:e.clientWidth}));
        assert.ok(bounds.width<=bounds.client+1,'Mobile dialog overflows horizontally');
        await page.keyboard.press('Escape');assert.equal(await page.locator('#creator').evaluate(e=>e.open),false);
        await page.setViewportSize({width:320,height:700});
        for(const name of ['index','special','references','login']) {await goto(name);await noPageOverflow();}
        console.log('PASS responsive: 390px/320px pages contain horizontal overflow inside tables; mobile form scroll, footer and Escape.');
        assert.deepEqual(errors,[]);
        console.log('PASS: zero browser runtime errors. Screenshots use explicitly mocked demonstration data.');
    } catch(error) {
        await shot('failure');
        console.error('Diagnostic:',JSON.stringify({items,requests:requests.slice(-12),errors,visible:await page.locator('body').innerText()},null,2));
        throw error;
    } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
