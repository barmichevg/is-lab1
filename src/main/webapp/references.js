const refApi = 'api/reference';
const $ = id => document.getElementById(id);
const replacementDialog = $('replacementDialog');
let refs = {coordinates:[], addresses:[], locations:[]};
let pendingDelete = null;
let deleting=false,dataVersion=0,usageGeneration=0;
let pageLeaving = false;

function beginPageLeave() {
    if (pageLeaving) return;
    pageLeaving = true;
}

function showError(targetId, error) {
    if (pageLeaving || error?.name === 'AbortError') return;
    $(targetId).textContent = error?.message || String(error);
}


window.addEventListener('pagehide', beginPageLeave);

async function request(url, options={}) { return Lab.request(url, options); }

async function loadData() {
    const version=++dataVersion;
    try {
        $('error').textContent = '';
        $('loading').textContent='Загрузка…';
        const [coordinates, addresses, locations] = await Promise.all([
            request(`${refApi}/coordinates`),
            request(`${refApi}/addresses`),
            request(`${refApi}/locations`)
        ]);
        if(version!==dataVersion || pageLeaving)return;
        refs={coordinates,addresses,locations};
        $('loading').textContent='';
        render();
        if(replacementDialog.open && pendingDelete && !deleting) await refreshDelete();
    } catch (e) {
        if(version!==dataVersion || pageLeaving)return;
        $('loading').textContent='Ошибка загрузки';
        showError('error', e);
        throw e;
    }
}

function render() {
    const query=$('referenceSearch').value.toLowerCase();
    const matches=item=>JSON.stringify(item).toLowerCase().includes(query);
    const columns={coordinates:c=>[c.id,c.x,c.y],addresses:a=>[a.id,a.zipCode,locationLabel(a.town)],locations:l=>[l.id,l.name,l.x,l.y,l.z]};
    for(const kind of ['coordinates','addresses','locations']){
        const items=refs[kind].filter(matches),body=$(kind+'Rows');
        $(kind+'Count').textContent=query?`${items.length} / ${refs[kind].length}`:String(refs[kind].length);
        $(kind+'Count').setAttribute('aria-label',query?`Найдено ${items.length} из ${refs[kind].length}`:`Всего объектов: ${refs[kind].length}`);
        body.replaceChildren();
        for(const item of items){
            const row=body.insertRow();
            for(const value of columns[kind](item))row.insertCell().textContent=String(value??'');
            const button=document.createElement('button');button.type='button';button.className='danger-quiet';button.textContent='Удалить';
            button.setAttribute('aria-label',`Удалить ${kindLabel(kind)} #${item.id}`);
            button.addEventListener('click',()=>deleteReference(kind,item.id));row.insertCell().append(button);
        }
        if(!items.length){
            const cell=body.insertRow().insertCell();cell.colSpan=kind==='locations'?6:4;cell.className='empty';cell.textContent=query?'Ничего не найдено.':'Объектов пока нет.';
        }
    }
}



const nativeReplacementClose=replacementDialog.close.bind(replacementDialog);
replacementDialog.close=()=>{pendingDelete=null;++usageGeneration;nativeReplacementClose();};
async function deleteReference(kind,id) {
    if(deleting)return;
    const target=pendingDelete={kind,id}; $('replacementError').textContent='';
    $('replacementTitle').textContent=`Удалить ${kindLabel(kind)} #${id}?`;
    $('usageInfo').textContent='Загрузка связей…';
    $('replacementSelect').replaceChildren();$('replacementLabel').hidden=true;
    $('deleteReferenceConfirm').disabled=true;
    if(!replacementDialog.open)replacementDialog.showModal();
    try { await refreshDelete(); }
    catch(e) {if(pendingDelete===target && !pageLeaving)showError('replacementError',e);}
}
async function refreshDelete() {
    const target=pendingDelete;if(!target)return;
    const generation=++usageGeneration;
    const active=()=>pendingDelete===target && generation===usageGeneration && !pageLeaving;
    target.ready=false;$('deleteReferenceConfirm').disabled=true;
    try {
        const usage=await request(`${refApi}/${target.kind}/${target.id}/usage`);
        if(!active())return;
        target.used=Number(usage.totalReferences)>0;
        $('replacementTitle').textContent=`Удалить ${kindLabel(target.kind)} #${target.id}?`;
        $('usageInfo').textContent='';
        $('replacementLabel').hidden=!target.used;
        $('deleteReferenceConfirm').textContent=target.used?'Перепривязать и удалить':'Удалить';
        Lab.fillSelect($('replacementSelect'),refs[target.kind].filter(item=>item.id!==target.id),item=>referenceLabel(target.kind,item),message=>$('replacementError').textContent=message);
        target.ready=true;$('deleteReferenceConfirm').disabled=false;
    } catch(e) {
        if(!active())return;
        if(e.status===404){replacementDialog.close();$('error').textContent='Объект уже удалён.';}
        else throw e;
    }
}
async function replaceAndDelete() {
    if(!pendingDelete || !pendingDelete.ready || deleting)return;
    deleting=true;$('deleteReferenceConfirm').disabled=true;
    try {
        $('replacementError').textContent='';
        const {kind,id,used}=pendingDelete,replacement=$('replacementSelect').value;
        if(used&&!replacement)throw new Error('Выберите объект для замены.');
        await request(`${refApi}/${kind}/${id}`+(used?`?replacementId=${replacement}`:''),{method:'DELETE'});
        pendingDelete=null;replacementDialog.close();await loadData();
    }catch(e){showError('replacementError',e);}
    finally{deleting=false;$('deleteReferenceConfirm').disabled=false;}
}
replacementDialog.addEventListener('cancel',e=>{e.preventDefault();if(!deleting)replacementDialog.close();});

function kindLabel(kind) {
    if (kind === 'coordinates') return 'координаты';
    if (kind === 'addresses') return 'адрес';
    return 'город';
}

function referenceLabel(kind, item) {
    if (kind === 'coordinates') return `#${item.id}: (${item.x}, ${item.y})`;
    if (kind === 'addresses') return `#${item.id}: ${item.zipCode} → ${item.town.name}`;
    return locationLabel(item);
}

function locationLabel(l) {
    return `#${l.id}: ${l.name} (${l.x}, ${l.y}, ${l.z})`;
}

function escapeHtml(v) {
    return String(v ?? '').replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
}

function connectWebSocket() { Lab.connect(async event => { await loadData(); }); }

loadData().catch(()=>{});
connectWebSocket();
