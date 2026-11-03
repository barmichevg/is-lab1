const specialApi='api/special-operations', organizationsApi='api/organizations', refApi='api/reference';
const $=id=>document.getElementById(id);
let organizations=[],refs={addresses:[],locations:[]},dataVersion=0;
const cards=[...document.querySelectorAll('.card')];
const states=cards.map(card=>{
    const message=document.createElement('div'),result=document.createElement('div');
    message.setAttribute('role','status'); result.className='operation-result';
    card.append(message,result);
    return {card,message,result,busy:false,query:null,ids:null,key:null,body:null,dirty:false,generation:0,refreshing:null};
});
const request=Lab.request;
function escapeHtml(v){return String(v??'').replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));}
function orgLabel(o){return `#${o.id}: ${o.name} — сотрудников: ${Lab.formatNumber(o.employeesCount)}`;}
function addressLabel(a){return `#${a.id}: ${a.zipCode} → ${a.town.name}`;}
function locationLabel(l){return `#${l.id}: ${l.name} (${l.x}, ${l.y}, ${l.z})`;}
async function loadAllOrganizations(){

    return request(`${organizationsApi}/options`);
}
async function loadData(){
    const version=++dataVersion;
    const [org,addresses,locations]=await Promise.all([loadAllOrganizations(),request(`${refApi}/addresses`),request(`${refApi}/locations`)]);
    if(version!==dataVersion)return;
    organizations=org;refs={addresses,locations};
    for(const id of ['mergeFirst','mergeSecond','employeeOrg']) fillSelect(id,organizations,orgLabel);
    fillSelect('mergeExistingAddress',addresses,addressLabel);fillSelect('mergeExistingTown',locations,locationLabel);
    await refreshResults();
}
function fillSelect(id,items,label){Lab.fillSelect($(id),items,label,message=>{
    const state=states[id==='employeeOrg'?3:4];state.message.className='error';state.message.textContent=message;
});}
function syncAddressMode(){const isNew=$('mergeAddressMode').value==='new';$('existingAddressBlock').classList.toggle('hidden',isNew);$('newAddressBlock').classList.toggle('hidden',!isNew);syncTownMode();}
function syncTownMode(){const isNew=$('mergeTownMode').value==='new';$('existingTownBlock').classList.toggle('hidden',isNew);$('newTownBlock').classList.toggle('hidden',!isNew);}
function checked(id,options){const input=$(id);try{const result=Lab.number(input,options);Lab.fieldError(input,'');return result;}catch(e){Lab.fieldError(input,e.message);throw e;}}
function selected(id){return checked(id,{integer:true,positive:true});}
function textValue(id,blank=false){const input=$(id),value=input.value.trim();if((!blank&&!value)||value.length>255){Lab.fieldError(input,'Введите строку до 255 символов.');throw new Error('Проверьте выделенные поля.');}Lab.fieldError(input,'');return value;}
function mergeAddressPayload(){
    if($('mergeAddressMode').value==='existing')return {id:selected('mergeExistingAddress')};
    const town=$('mergeTownMode').value==='existing'?{id:selected('mergeExistingTown')}:{name:textValue('mergeTownName'),x:checked('mergeTownX'),y:checked('mergeTownY'),z:checked('mergeTownZ',{integer:true})};
    return {zipCode:textValue('mergeZip',true),town};
}
async function run(index,prepare,mutating=false){
    const state=states[index];if(state.busy)return;
    state.busy=true;state.dirty=true;++state.generation;
    state.card.querySelectorAll('button').forEach(b=>b.disabled=true);
    state.message.className='';state.message.textContent='Выполняется…';
    try{
        const {url,body}=prepare();
        let options={};
        if(mutating){
            const fingerprint=url+'|'+(body||'');
            if(state.body!==fingerprint){state.body=fingerprint;state.key=crypto.randomUUID();}
            options={method:'POST',headers:{'X-Idempotency-Key':state.key},...(body?{body}:{})};
        }
        const result=await request(url,options);
        state.query=mutating?null:url;state.ids=mutating?[result.id]:null;
        renderResult(state,Array.isArray(result)?result:[result]);
        state.message.className=mutating?'success':'';state.message.textContent=mutating?'Операция выполнена.':'';
        if(mutating){
            state.body=null;


            if(index===4){$('mergeFirst').value='';$('mergeSecond').value='';}
            await loadData();
            state.message.className='success';
            state.message.textContent=index===4?`Исходные организации заменены новой #${result.id}.`:'Операция выполнена.';
        }
    }catch(e){state.message.className='error';state.message.textContent=e.message;applyErrors(index,e);}
    finally{

        setTimeout(()=>{
            state.busy=false;state.card.querySelectorAll('button').forEach(b=>b.disabled=false);
            refreshState(state).catch(e=>$('globalError').textContent=e.message);
        },400);
    }
}
function minEmployees(){return run(0,()=>({url:`${specialApi}/min-employees`}));}
function nameStartsWith(){return run(1,()=>({url:`${specialApi}/name-starts-with?prefix=${encodeURIComponent($('prefix').value)}`}));}
function typeGreaterThan(){return run(2,()=>({url:`${specialApi}/type-greater-than?type=${encodeURIComponent($('typeValue').value)}`}));}
function addEmployee(){return run(3,()=>({url:`${specialApi}/${selected('employeeOrg')}/add-employee`}),true);}
function mergeOrganizations(){return run(4,()=>{
    const first=selected('mergeFirst'),second=selected('mergeSecond');
    if(first===second){Lab.fieldError($('mergeSecond'),'Выберите другую организацию.');throw new Error('Организации должны различаться.');}
    return {url:`${specialApi}/merge`,body:JSON.stringify({firstOrganizationId:first,secondOrganizationId:second,name:textValue('mergeName'),officialAddress:mergeAddressPayload()})};
},true);}
async function refreshResults(){
    await Promise.all(states.map(state=>{
        state.dirty=true;++state.generation;
        return refreshState(state);
    }));
}
function refreshState(state){
    if(state.refreshing)return state.refreshing;
    if(state.busy || !state.dirty || (!state.query&&!state.ids))return Promise.resolve();
    state.refreshing=(async()=>{
        while(state.dirty && !state.busy && (state.query||state.ids)){
            state.dirty=false;
            const generation=state.generation;
            try{
                let items;
                if(state.query){const data=await request(state.query);items=Array.isArray(data)?data:[data];}
                else items=await Promise.all(state.ids.map(id=>request(`${organizationsApi}/${id}`)));
                if(generation!==state.generation || state.busy)continue;
                renderResult(state,items);
            }catch(e){
                if(generation!==state.generation || state.busy)continue;
                if(e.status===404){renderResult(state,[]);state.message.className='';state.message.textContent='Объект удалён или результат пуст.';}
                else {state.message.className='error';state.message.textContent='Не удалось обновить результат: '+e.message;throw e;}
            }
        }
    })().finally(()=>{state.refreshing=null;});
    return state.refreshing;
}
function renderResult(state,items){
    if(!items.length){state.result.innerHTML='<p class="empty">Результат пуст.</p>';return;}
    const title='Результат: '+state.card.querySelector('h2').textContent;
    const region=document.createElement('div');region.className='table-wrap';region.setAttribute('role','region');region.setAttribute('aria-label',title);region.tabIndex=0;
    const table=document.createElement('table');table.title=title;table.createCaption().textContent=title;
    const header=table.createTHead().insertRow();
    for(const label of ['ID','Название','Сотрудники','Годовой оборот','Рейтинг','Тип','Официальный адрес']){
        const th=document.createElement('th');th.scope='col';th.textContent=label;header.append(th);
    }
    const body=table.createTBody();
    for(const o of items){
        const row=body.insertRow(),link=document.createElement('a');link.href='index.html?id='+encodeURIComponent(o.id);link.textContent=String(o.id);row.insertCell().append(link);
        for(const value of [o.name,Lab.formatNumber(o.employeesCount),Lab.formatNumber(o.annualTurnover),o.rating,Lab.typeLabel(o.type),addressLabel(o.officialAddress)])row.insertCell().textContent=String(value??'');
    }
    region.append(table);state.result.replaceChildren(region);
}
function applyErrors(index,error){
    const map={name:'mergeName',firstOrganizationId:'mergeFirst',secondOrganizationId:'mergeSecond','officialAddress.id':'mergeExistingAddress','officialAddress.zipCode':'mergeZip','officialAddress.town.id':'mergeExistingTown','officialAddress.town.name':'mergeTownName','officialAddress.town.x':'mergeTownX','officialAddress.town.y':'mergeTownY','officialAddress.town.z':'mergeTownZ'};
    if(index===4)for(const detail of error.details||[]){const i=detail.indexOf(':');if(map[detail.slice(0,i)])Lab.fieldError($(map[detail.slice(0,i)]),detail.slice(i+1));}
}
cards.forEach(card=>card.querySelectorAll('input,select').forEach(input=>input.addEventListener('input',()=>Lab.fieldError(input,''))));
loadData().catch(e=>$('globalError').textContent=e.message);
Lab.connect(loadData);
