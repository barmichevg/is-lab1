const api = 'api/organizations';
const refApi = 'api/reference';
let currentPage = 0;
let total = 0;
let size = 10;
let loadVersion = 0;
let editOriginal = null;
let editVersion = null;
let editorGeneration = 0, editorRefreshGeneration = 0;
let viewerGeneration = 0, referencesGeneration = 0;
let formSnapshot = "";
let refs = {coordinates: [], addresses: [], locations: []};
let viewedOrganizationId = null;
let viewerHasData = false;
let saveInProgress = false;
let createRequestKey = null;

let pendingCreate = null;
let pageLeaving = false;
let editor = document.getElementById('editor');
const viewer = document.getElementById('viewer');
const $ = id => document.getElementById(id);

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

async function load(page=currentPage) {
    const version=++loadVersion;
    document.querySelector('.organization-table').setAttribute('aria-busy','true');
    $('prevButton').disabled=true;$('nextButton').disabled=true;
    try {
        $('error').textContent = '';
        $('pageInfo').textContent = 'Загрузка…';
        currentPage = Math.max(0, page);
        const filterField = encodeURIComponent($('filterField').value);
        const filterValue = encodeURIComponent($('filter').value.trim());
        const sort = encodeURIComponent($('sortField').value);
        const direction = encodeURIComponent($('sortDirection').value);
        const data = await request(`${api}?page=${currentPage}&size=${size}&filterField=${filterField}&filterValue=${filterValue}&sort=${sort}&direction=${direction}`);
        if(version!==loadVersion) return;
        total = data.total;
        const last=Math.max(0,Math.ceil(total/size)-1);
        if(currentPage>last) return load(last);
        $('rows').innerHTML = data.items.map(o => `
            <tr>
                <td class="cell-id">#${o.id}</td>
                <td><button class="entity-link" type="button" onclick="viewOrg(${o.id})" title="Открыть карточку">${escapeHtml(o.name)}</button></td>
                <td><span class="numeric">${o.coordinates.x}, ${o.coordinates.y}</span><span class="cell-secondary">Координаты #${o.coordinates.id}</span></td>
                <td>${formatAddress(o.officialAddress)}</td>
                <td>${o.postalAddress ? formatAddress(o.postalAddress) : '—'}</td>
                <td class="numeric">${Lab.formatNumber(o.employeesCount)}</td>
                <td class="numeric">${Lab.formatNumber(o.annualTurnover)}</td>
                <td class="numeric">${o.rating}</td>
                <td><span class="type-badge" title="${escapeHtml(o.type)}">${escapeHtml(Lab.typeLabel(o.type))}</span></td>
                <td title="${escapeHtml(o.creationDate)}">${escapeHtml(o.creationDate ? new Date(o.creationDate).toLocaleDateString('ru-RU') : '')}<span class="cell-secondary">${escapeHtml(o.creationDate ? new Date(o.creationDate).toLocaleTimeString('ru-RU',{hour:'2-digit',minute:'2-digit'}) : '')}</span></td>
                <td><div class="row-actions">
                    <button type="button" onclick="viewOrg(${o.id})">Просмотр</button>
                    <button type="button" onclick="edit(${o.id})">Изменить</button>
                    <button class="danger-quiet" type="button" onclick="removeOrg(${o.id})" aria-label="Удалить организацию ${o.id}">Удалить</button>
                </div></td>
            </tr>`).join('');
        if(!data.items.length) $('rows').innerHTML='<tr><td colspan="11" class="empty">'+($('filter').value.trim()?'<strong>По фильтру ничего не найдено.</strong>Измените запрос или сбросьте фильтр.':'<strong>Организаций пока нет.</strong>Добавьте первую с помощью кнопки «Создать организацию».')+'</td></tr>';
        $('totalCount').textContent=Lab.formatNumber(total);
        $('pageInfo').textContent = `${total ? currentPage*size+1 : 0}–${Math.min((currentPage+1)*size,total)} из ${total}`;
        $('prevButton').disabled=currentPage===0;
        $('nextButton').disabled=(currentPage+1)*size>=total;
    } catch (e) {
        if(version!==loadVersion || pageLeaving)return;
        $('pageInfo').textContent='Ошибка загрузки';
        showError('error', e);
        throw e;
    } finally {
        if(version===loadVersion) document.querySelector('.organization-table').setAttribute('aria-busy','false');
    }
}

function prevPage(){ if(currentPage > 0) load(currentPage-1).catch(()=>{}); }
function nextPage(){ if((currentPage+1)*size < total) load(currentPage+1).catch(()=>{}); }

async function loadReferences(showMessage=false) {
    const generation=++referencesGeneration;
    const [coordinates, addresses, locations] = await Promise.all([
        request(`${refApi}/coordinates`),
        request(`${refApi}/addresses`),
        request(`${refApi}/locations`)
    ]);
    if(generation!==referencesGeneration || pageLeaving)return;
    refs = {coordinates, addresses, locations};
    refillReferenceSelects();
    if (showMessage) $('error').textContent = 'Справочники обновлены.';
}

function refillReferenceSelects() {
    fillSelect('coordExisting', refs.coordinates, c => `#${c.id}: (${c.x}, ${c.y})`);
    fillSelect('officialExisting', refs.addresses, addressLabel);
    fillSelect('postalExisting', refs.addresses, addressLabel);
    fillSelect('officialTownExisting', refs.locations, locationLabel);
    fillSelect('postalTownExisting', refs.locations, locationLabel);

    $('coordMode').querySelector('option[value="existing"]').disabled = refs.coordinates.length === 0;
    $('officialMode').querySelector('option[value="existing"]').disabled = refs.addresses.length === 0;
    $('postalMode').querySelector('option[value="existing"]').disabled = refs.addresses.length === 0;
    $('officialTownMode').querySelector('option[value="existing"]').disabled = refs.locations.length === 0;
    $('postalTownMode').querySelector('option[value="existing"]').disabled = refs.locations.length === 0;
}

function fillSelect(id, items, labelFn) {
    Lab.fillSelect($(id), items, labelFn, message => {
        if(editor.open) { $('editWarning').textContent=message; $('editWarning').classList.remove('hidden'); }
    });
}

async function openCreate(){
    if(saveInProgress)return;
    const generation=activateEditor('creator');
    try {
        await loadReferences();
        if(generation!==editorGeneration || pageLeaving)return;
        editVersion=null; editOriginal=null;
        if(pendingCreate) {
            restoreCreateDraft(pendingCreate.draft);
            $('dialogTitle').textContent='Восстановление создания';
            $('dialogError').textContent='';clearValidationErrors();syncAllModes();
            showPendingCreateWarning();
            formSnapshot=pendingCreate.sentSnapshot;
            $('saveButton').textContent='Повторить исходный запрос';
            editor.showModal();return;
        }
        createRequestKey = newCreateRequestKey();
        $('saveButton').textContent='Сохранить организацию';
        $('form').reset();
        $('editId').value = '';
        $('dialogTitle').textContent = 'Создание организации';
        $('dialogError').textContent = '';
        clearValidationErrors();
        $('coordMode').value = 'new';
        $('officialMode').value = 'new';
        $('officialTownMode').value = 'new';
        $('postalMode').value = 'none';
        $('postalTownMode').value = 'new';
        syncAllModes();
        $('editWarning').classList.add('hidden');
        formSnapshot=snapshot();
        editor.showModal();
    } catch(e) {
        if(generation!==editorGeneration || pageLeaving)return;
        showError('error', e);
    }
}



const nativeViewerClose=viewer.close.bind(viewer);
viewer.close=()=>{++viewerGeneration;viewedOrganizationId=null;nativeViewerClose();};
function closeViewer() { viewer.close(); }
viewer.addEventListener('cancel',e=>{e.preventDefault();closeViewer();});

async function viewOrg(id, {refresh=false}={}) {
    if(refresh && (!viewer.open || viewedOrganizationId!==id))return;
    const generation=++viewerGeneration;
    if(!refresh) {
        ++editorGeneration;
        viewedOrganizationId=id;
        viewerHasData=false;
        $('viewTitle').textContent=`Организация #${id}`;
        $('viewDetails').textContent='Загрузка…';
        $('viewError').textContent='';$('viewRetry').classList.add('hidden');
        if(!viewer.open)viewer.showModal();
    }
    try {
        const o = await request(`${api}/${id}`);
        if(generation!==viewerGeneration || !viewer.open || viewedOrganizationId!==id || pageLeaving)return;
        $('viewTitle').textContent = `Организация #${o.id}`;
        const entries = [
            ['Название', o.name],
            ['Тип организации', Lab.typeLabel(o.type)],
            ['Дата создания', o.creationDate],
            ['Годовой оборот', Lab.formatNumber(o.annualTurnover)],
            ['Сотрудники', Lab.formatNumber(o.employeesCount)],
            ['Рейтинг', o.rating],
            ['Координаты', `#${o.coordinates.id}: (${o.coordinates.x}, ${o.coordinates.y})`],
            ['Официальный адрес', plainAddress(o.officialAddress)],
            ['Почтовый адрес', o.postalAddress ? plainAddress(o.postalAddress) : '—']
        ];
        $('viewDetails').innerHTML = entries.map(([k,v]) => `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(v)}</dd>`).join('');
        viewerHasData=true;
        $('viewError').textContent='';$('viewRetry').classList.add('hidden');
    } catch(e) {
        if(generation!==viewerGeneration || !viewer.open || viewedOrganizationId!==id || pageLeaving)return;
        if(e.status===404) {closeViewer();showError('error',e);return;}
        if(!viewerHasData) $('viewDetails').textContent='';
        showError('viewError', e);
        $('viewRetry').classList.remove('hidden');
    }
}
function retryViewer() {
    if(viewer.open && viewedOrganizationId!=null) return viewOrg(viewedOrganizationId,{refresh:true});
}

async function editViewedOrganization() {
    if (viewedOrganizationId == null) return;
    const id = viewedOrganizationId;
    closeViewer();
    await edit(id);
}

async function edit(id){
    if(saveInProgress)return;
    const generation=activateEditor('editor');
    try {
        createRequestKey = null;
        $('saveButton').textContent='Сохранить организацию';
        const [o] = await Promise.all([request(`${api}/${id}`), loadReferences()]);
        if(generation!==editorGeneration || pageLeaving)return;
        $('form').reset();
        editOriginal=JSON.stringify(o);
        editVersion=o.version;
        $('editId').value = o.id;
        $('orgName').value = o.name;
        $('orgType').value = o.type;
        $('turnover').value = o.annualTurnover;
        $('employees').value = o.employeesCount;
        $('rating').value = o.rating;

        $('coordMode').value = 'existing';
        $('coordExisting').value = String(o.coordinates.id);
        $('coordX').value = o.coordinates.x;
        $('coordY').value = o.coordinates.y;

        $('officialMode').value = 'existing';
        $('officialExisting').value = String(o.officialAddress.id);
        prefillAddress('official', o.officialAddress);

        if (o.postalAddress) {
            $('postalMode').value = 'existing';
            $('postalExisting').value = String(o.postalAddress.id);
            prefillAddress('postal', o.postalAddress);
        } else {
            $('postalMode').value = 'none';
        }

        $('dialogTitle').textContent = `Изменение организации #${id}`;
        $('dialogError').textContent = '';
        clearValidationErrors();
        syncAllModes();
        $('editWarning').classList.add('hidden');
        formSnapshot=snapshot();
        editor.showModal();
    } catch(e) {
        if(generation!==editorGeneration || pageLeaving)return;
        showError('error', e);
    }
}

function prefillAddress(prefix, address) {
    $(`${prefix}Zip`).value = address.zipCode;
    $(`${prefix}TownMode`).value = 'existing';
    $(`${prefix}TownExisting`).value = String(address.town.id);
    $(`${prefix}TownName`).value = address.town.name;
    $(`${prefix}TownX`).value = address.town.x;
    $(`${prefix}TownY`).value = address.town.y;
    $(`${prefix}TownZ`).value = address.town.z;
}

function syncAllModes() {
    syncCoordinateMode();
    syncOfficialMode();
    syncPostalMode();
}

function setSection(id, visible) {
    const section = $(id);
    section.classList.toggle('hidden', !visible);
    section.querySelectorAll('input, select').forEach(el => el.disabled = !visible);
}

function syncCoordinateMode() {
    const existing = $('coordMode').value === 'existing';
    setSection('coordExistingSection', existing);
    setSection('coordNewSection', !existing);
}

function syncOfficialMode() {
    const existing = $('officialMode').value === 'existing';
    setSection('officialExistingSection', existing);
    setSection('officialNewSection', !existing);
    if (!existing) syncTownMode('official');
}

function syncPostalMode() {
    const mode = $('postalMode').value;
    setSection('postalExistingSection', mode === 'existing');
    setSection('postalNewSection', mode === 'new');
    if (mode === 'new') syncTownMode('postal');
}

function syncTownMode(prefix) {
    const existing = $(`${prefix}TownMode`).value === 'existing';
    setSection(`${prefix}TownExistingSection`, existing);
    setSection(`${prefix}TownNewSection`, !existing);
    setRequired(`${prefix}TownName`, !existing);
    setRequired(`${prefix}TownX`, !existing);
    setRequired(`${prefix}TownY`, !existing);
    setRequired(`${prefix}TownZ`, !existing);

    setRequired(`${prefix}Zip`, false);
}

function setRequired(id, required) {
    const el = $(id);
    if (el) el.required = required && !el.disabled;
}

function coordinatesPayload() {
    if ($('coordMode').value === 'existing') {
        return {id: requiredSelectedId('coordExisting', 'Координаты')};
    }
    return {x: numberValue('coordX'), y: numberValue('coordY')};
}

function addressPayload(prefix, mode) {
    if (mode === 'existing') {
        return {id: requiredSelectedId(`${prefix}Existing`, 'Адрес')};
    }
    if (mode === 'none') return null;

    const townMode = $(`${prefix}TownMode`).value;
    const town = townMode === 'existing'
        ? {id: requiredSelectedId(`${prefix}TownExisting`, 'Город')}
        : {
            x: numberValue(`${prefix}TownX`),
            y: numberValue(`${prefix}TownY`),
            z: numberValue(`${prefix}TownZ`),
            name: $(`${prefix}TownName`).value.trim()
        };

    return {
        zipCode: $(`${prefix}Zip`).value,
        town
    };
}

function clearFieldError(id) {
    const el = $(id);
    if (!el) return;
    el.classList.remove('input-error');
    el.removeAttribute('aria-invalid');
    const node = document.querySelector(`[data-error-for="${id}"]`);
    if (node) node.textContent = '';
}

function setFieldError(id, message) {
    const el = $(id);
    if (!el) return;
    el.classList.add('input-error');
    el.setAttribute('aria-invalid','true');
    let node = document.querySelector(`[data-error-for="${id}"]`);
    if (!node) {
        node = document.createElement('div');
        node.className = 'field-error';
        node.dataset.errorFor = id;
        const label = el.closest('label');
        if (label) label.appendChild(node);
        else el.insertAdjacentElement('afterend', node);
    }
    node.id=id+'Error';
    node.textContent = message;
    const descriptions=new Set((el.getAttribute('aria-describedby')||'').split(/\s+/).filter(Boolean));
    descriptions.add(node.id);el.setAttribute('aria-describedby',[...descriptions].join(' '));
}

function clearValidationErrors() {
    document.querySelectorAll('.field-error').forEach(node => node.textContent = '');
    document.querySelectorAll('.input-error').forEach(el => {el.classList.remove('input-error');el.removeAttribute('aria-invalid');});
}

function validateRequiredText(id, emptyMessage = 'Строка не может быть пустой.') {
    const value = $(id).value.trim();
    if (!value) {
        setFieldError(id, emptyMessage);
        return false;
    }
    return true;
}

function validateNumber(id, options={}) {
    try { Lab.number($(id),{integer:options.integer,long:id==='employees',positive:options.positive,float:id==='turnover'}); return true; }
    catch(e) { setFieldError(id,e.message); return false; }
}

function validateExistingSelection(id) {
    if (!$(id).value) {
        setFieldError(id, 'Выберите существующий объект.');
        return false;
    }
    return true;
}

function validateAddressFields(prefix, mode) {
    if (mode === 'none') return true;
    if (mode === 'existing') return validateExistingSelection(`${prefix}Existing`);

    let valid = true;
    const townMode = $(`${prefix}TownMode`).value;
    if (townMode === 'existing') {
        valid = validateExistingSelection(`${prefix}TownExisting`) && valid;
    } else {
        valid = validateRequiredText(`${prefix}TownName`) && valid;
        valid = validateNumber(`${prefix}TownX`, {
            expectedMessage: 'Введите число (Double).'
        }) && valid;
        valid = validateNumber(`${prefix}TownY`, {
            expectedMessage: 'Введите число (Double).'
        }) && valid;
        valid = validateNumber(`${prefix}TownZ`, {
            integer:true,
            int32:true,
            expectedMessage: 'Введите целое число (int).'
        }) && valid;
    }
    return valid;
}

function validateOrganizationForm() {
    clearValidationErrors();
    let valid = true;

    valid = validateRequiredText('orgName') && valid;
    valid = validateNumber('turnover', {
        positive:true,
        expectedMessage: 'Введите число (float) больше 0.'
    }) && valid;
    valid = validateNumber('employees', {
        integer:true,
        positive:true,
        expectedMessage: 'Введите целое число (long) больше 0.'
    }) && valid;
    valid = validateNumber('rating', {
        integer:true,
        positive:true,
        int32:true,
        expectedMessage: 'Введите целое число (int) больше 0.'
    }) && valid;

    if ($('coordMode').value === 'existing') {
        valid = validateExistingSelection('coordExisting') && valid;
    } else {
        valid = validateNumber('coordX', {
            integer:true,
            int32:true,
            expectedMessage: 'Введите целое число (int).'
        }) && valid;
        valid = validateNumber('coordY', {
            integer:true,
            int32:true,
            expectedMessage: 'Введите целое число (int).'
        }) && valid;
    }

    valid = validateAddressFields('official', $('officialMode').value) && valid;
    valid = validateAddressFields('postal', $('postalMode').value) && valid;

    if (!valid) {
        const first = document.querySelector('.input-error');
        if (first) first.focus();
    }
    return valid;
}

function payload(){
    return {
        ...($('editId').value ? {version:editVersion} : {}),
        name: $('orgName').value.trim(),
        coordinates: coordinatesPayload(),
        officialAddress: addressPayload('official', $('officialMode').value),
        annualTurnover: numberValue('turnover'),
        employeesCount: Lab.number($('employees'),{long:true,positive:true}),
        rating: numberValue('rating'),
        type: $('orgType').value,
        postalAddress: addressPayload('postal', $('postalMode').value)
    };
}

function newCreateRequestKey() {
    if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
    return `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

function captureCreateDraft() {
    return [...$('form').querySelectorAll('input,select')].map(input=>[input.id,input.value]);
}
function restoreCreateDraft(draft) {
    for(const [id,value] of draft) if($(id)) $(id).value=value;
}
function rememberCreateDraft() {
    if(pendingCreate && !$('editId').value) pendingCreate.draft=captureCreateDraft();
}
function showPendingCreateWarning() {
    $('editWarning').textContent='Результат создания ещё не подтверждён. Повторите исходный запрос: его тело и ключ сохранены. Новые правки останутся в форме.';
    $('editWarning').classList.remove('hidden');
}
async function save(e){
    e.preventDefault();
    e.stopImmediatePropagation();
    if (saveInProgress) return;

    const id=$('editId').value, isCreate=!id;
    $('dialogError').textContent = '';

    if (!(isCreate && pendingCreate) && !validateOrganizationForm()) return;

    saveInProgress = true;
    const saveButton = $('saveButton');
    saveButton.disabled = true;
    saveButton.textContent = isCreate && pendingCreate ? 'Проверка результата…' : 'Сохранение…';
    let attempt=null;
    try {
        if(isCreate) {
            if(!pendingCreate) {
                createRequestKey ||= newCreateRequestKey();
                pendingCreate={key:createRequestKey,body:JSON.stringify(payload()),sentSnapshot:snapshot(),draft:captureCreateDraft()};
            }
            attempt=pendingCreate;
            rememberCreateDraft();
            const created=await request(api,{method:'POST',body:attempt.body,headers:{'X-Idempotency-Key':attempt.key}});
            if(!created || !Number.isInteger(created.id) || created.id<=0 || created.version==null) throw new Error('Не удалось подтвердить результат создания. Повторите исходный запрос.');
            const draftChanged=snapshot()!==attempt.sentSnapshot;
            pendingCreate=null;createRequestKey=null;
            if(draftChanged) {


                activateEditor('editor');
                $('editId').value=String(created.id);editVersion=created.version;editOriginal=JSON.stringify(created);
                $('dialogTitle').textContent=`Изменение организации #${created.id}`;
                $('editWarning').textContent=`Создание организации #${created.id} подтверждено. Новые правки сохранены в форме; нажмите «Сохранить организацию», чтобы обновить эту запись.`;
                $('editWarning').classList.remove('hidden');
                formSnapshot=attempt.sentSnapshot;
                editor.showModal();
            } else editor.close();
            $('success').textContent=`Организация #${created.id} создана.`;
        } else {
            await request(`${api}/${id}`,{method:'PUT',body:JSON.stringify(payload())});
            editor.close();$('success').textContent='Организация сохранена.';
        }
        await Promise.all([load(currentPage), loadReferences()]);
    } catch(e) {
        if(isCreate && attempt && pendingCreate===attempt) {
            rememberCreateDraft();


            if([400,404,409,422].includes(e.status)) {pendingCreate=null;createRequestKey=null;$('editWarning').classList.add('hidden');}
            else showPendingCreateWarning();
        }
        applyServerErrors(e);
        if(e.status===409 && !isCreate) {
            const message='Конфликт изменений: организация уже изменена другим пользователем. Ваш черновик сохранён. Откройте актуальную карточку и сравните данные; для загрузки новой версии отмените редактирование и откройте его снова.';
            $('editWarning').textContent=message;
            $('editWarning').classList.remove('hidden');
            $('dialogError').textContent=message;
        } else showError('dialogError', e);
    } finally {
        saveInProgress = false;
        saveButton.disabled = false;
        saveButton.textContent = pendingCreate && !$('editId').value ? 'Повторить исходный запрос' : 'Сохранить организацию';
    }
}

let deleteId=null, deleting=false, deleteGeneration=0;
const deleteDialog=$('deleteDialog'),nativeDeleteClose=deleteDialog.close.bind(deleteDialog);
deleteDialog.close=()=>{
    if(deleting)return;
    ++deleteGeneration;deleteId=null;nativeDeleteClose();
};
deleteDialog.addEventListener('cancel',e=>{e.preventDefault();if(!deleting)deleteDialog.close();});
function removeOrg(id) {
    if(deleting)return;
    ++deleteGeneration;deleteId=id;
    $('deleteLabel').textContent=`Удалить организацию #${id}? Справочники сохранятся.`;
    $('deleteError').textContent='';if(!deleteDialog.open)deleteDialog.showModal();
}
async function confirmDelete() {
    if(deleting || deleteId===null)return;
    const target=deleteId,generation=deleteGeneration;
    const active=()=>generation===deleteGeneration && deleteId===target && !pageLeaving;
    deleting=true; $('deleteConfirm').disabled=true;
    try {
        await request(`${api}/${target}`,{method:'DELETE'});
        if(!active())return;
        ++deleteGeneration;deleteId=null;nativeDeleteClose();
        $('success').textContent='Организация удалена.'; await load(currentPage);
    } catch(e) {if(active())showError('deleteError',e);}
    finally {deleting=false;$('deleteConfirm').disabled=false;}
}


function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

function randomFloat(min, max, digits = 2) {
    return Number((Math.random() * (max - min) + min).toFixed(digits));
}

function randomItem(items) {
    return items[randomInt(0, items.length - 1)];
}

function randomToken(length = 6) {
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let result = '';
    for (let i = 0; i < length; i++) result += alphabet[randomInt(0, alphabet.length - 1)];
    return result;
}

function fillRandomData() {
    $('orgName').value = `${randomItem(['Orion', 'Nova', 'Vertex', 'Atlas', 'Nimbus', 'Quantum', 'Aurora', 'Vector'])}-${randomToken(4)}`;
    $('orgType').value = randomItem(['COMMERCIAL', 'GOVERNMENT', 'PRIVATE_LIMITED_COMPANY']);
    $('turnover').value = randomFloat(1, 10_000_000, 2);
    $('employees').value = randomInt(1, 50_000);
    $('rating').value = randomInt(1, 100);

    $('coordMode').value = 'new';
    $('coordX').value = randomInt(-10_000, 10_000);
    $('coordY').value = randomInt(-10_000, 10_000);

    $('officialMode').value = 'new';
    $('officialZip').value = String(randomInt(100000, 999999));
    $('officialTownMode').value = 'new';
    $('officialTownName').value = `${randomItem(['North', 'South', 'East', 'West', 'Central', 'Lake', 'Hill', 'River'])}-${randomToken(3)}`;
    $('officialTownX').value = randomFloat(-1000, 1000, 3);
    $('officialTownY').value = randomFloat(-1000, 1000, 3);
    $('officialTownZ').value = randomInt(-10_000, 10_000);

    $('postalMode').value = 'new';
    $('postalZip').value = String(randomInt(100000, 999999));
    $('postalTownMode').value = 'new';
    $('postalTownName').value = `${randomItem(['Pine', 'Oak', 'Stone', 'Green', 'Silver', 'Golden', 'Red', 'Blue'])}-${randomToken(3)}`;
    $('postalTownX').value = randomFloat(-1000, 1000, 3);
    $('postalTownY').value = randomFloat(-1000, 1000, 3);
    $('postalTownZ').value = randomInt(-10_000, 10_000);

    syncAllModes();
    $('dialogError').textContent = '';
    clearValidationErrors();
}

function requiredSelectedId(id, label) {
    const value = $(id).value;
    if (!value) throw new Error(`${label}: выберите существующий объект`);
    return Number(value);
}

function numberValue(id) {
    const value = $(id).value;
    return value === '' ? null : Number(value);
}

function addressLabel(a) {
    return `#${a.id}: ${a.zipCode} → ${a.town.name} (#${a.town.id})`;
}

function locationLabel(l) {
    return `#${l.id}: ${l.name} (${l.x}, ${l.y}, ${l.z})`;
}

function plainAddress(a) {
    return `#${a.id}: ${a.zipCode}; ${a.town.name} (#${a.town.id}, ${a.town.x}, ${a.town.y}, ${a.town.z})`;
}

function formatAddress(a) {
    return `<span title="${escapeHtml(plainAddress(a))}">${escapeHtml(a.town.name)}<span class="cell-secondary">${escapeHtml(a.zipCode || 'Без индекса')} · адрес #${a.id}</span></span>`;
}

function escapeHtml(v){
    return String(v ?? '').replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
}

function connectWebSocket() { Lab.connect(async event => { await Promise.all([load(currentPage), loadReferences()]); await refreshOpenObjects(event); }); }


function activateEditor(id) {
    rememberCreateDraft();
    closeViewer();
    const target=$(id);

    if(editor.open)editor.close();
    if(editor!==target) { target.append(...editor.childNodes); editor=target; }
    return ++editorGeneration;
}
function snapshot() { return JSON.stringify([...$('form').querySelectorAll('input,select')].map(e=>[e.id,e.value])); }
function closeEditor() {
    if(saveInProgress)return;
    if(snapshot()!==formSnapshot && !confirm(pendingCreate && !$('editId').value ? 'Результат создания не подтверждён. Закрыть форму? При следующем создании будет восстановлен исходный запрос и ваш черновик.' : 'Закрыть форму и потерять несохранённые изменения?'))return;
    rememberCreateDraft();
    ++editorGeneration;
    editor.close();
}
for(const id of ['creator','editor']) {
    const dialog=$(id),nativeClose=dialog.close.bind(dialog);
    dialog.close=()=>{if(editor===dialog){rememberCreateDraft();++editorGeneration;}nativeClose();};
    dialog.addEventListener('cancel',e=>{e.preventDefault();closeEditor();});
}
window.addEventListener('beforeunload',e=>{if(pendingCreate || (editor.open && snapshot()!==formSnapshot)){e.preventDefault();e.returnValue='';}});
function syncFilterHint() {
    const type=$('filterField').value==='type';
    if(type) $('filter').setAttribute('list','typeSuggestions'); else $('filter').removeAttribute('list');
    $('filter').placeholder=type?'Выберите код типа или его часть…':'Введите часть значения…';
    $('filterHint').hidden=!type;
    $('filterHint').textContent=type?'Коды типов: COMMERCIAL — коммерческая · GOVERNMENT — государственная · PRIVATE_LIMITED_COMPANY — ООО. Можно вводить часть кода.':'';
}
$('filterField').addEventListener('change',syncFilterHint);
function resetFilters() { $('filter').value=''; $('filterField').value='name'; $('sortField').value='id'; $('sortDirection').value='asc'; syncFilterHint();load(0).catch(()=>{}); }
function lookup() { try {const id=Lab.number($('lookupId'),{integer:true,positive:true});viewOrg(id);} catch(e){showError('error',e);} }
$('filter').addEventListener('keydown',e=>{if(e.key==='Enter')load(0).catch(()=>{});});
$('lookupId').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();lookup();}});
async function refreshOpenObjects(event) {
    const tasks=[];
    if(viewer.open && viewedOrganizationId) tasks.push(viewOrg(viewedOrganizationId,{refresh:true}));
    if(editor.open && $('editId').value && !saveInProgress) {
        const generation=editorGeneration, refresh=++editorRefreshGeneration, id=$('editId').value;
        const active=()=>generation===editorGeneration && refresh===editorRefreshGeneration && editor.open && $('editId').value===id && !pageLeaving;
        tasks.push((async()=>{
            try {
                const latest=await request(`${api}/${id}`);
                if(!active())return;
                if(JSON.stringify(latest)!==editOriginal) {
                    $('editWarning').textContent='Объект изменён другим пользователем. Введённые данные сохранены; перед сохранением проверьте актуальную карточку.';
                    $('editWarning').classList.remove('hidden');
                }
            } catch(e) {
                if(!active())return;
                if(e.status!==404)throw e;
                $('editWarning').textContent='Организация удалена другим пользователем. Изменения сохранить нельзя.';
                $('editWarning').classList.remove('hidden');
            }
        })());
    }
    await Promise.all(tasks);
}
function applyServerErrors(error) {
    const map={name:'orgName',annualTurnover:'turnover',employeesCount:'employees',rating:'rating',type:'orgType','coordinates.x':'coordX','coordinates.y':'coordY','coordinates.id':'coordExisting'};
    for(const prefix of ['official','postal']) {
        map[prefix+'Address.id']=prefix+'Existing';map[prefix+'Address.zipCode']=prefix+'Zip';
        for(const field of ['x','y','z','name']) map[prefix+'Address.town.'+field]=prefix+'Town'+field[0].toUpperCase()+field.slice(1);
        map[prefix+'Address.town.id']=prefix+'TownExisting';
    }
    for(const detail of error.details||[]) {const i=detail.indexOf(':');if(map[detail.slice(0,i)])setFieldError(map[detail.slice(0,i)],detail.slice(i+1));}
}

$('form').addEventListener('submit', save);

$('form').querySelectorAll('input, select').forEach(el => {
    el.addEventListener('input', () => clearFieldError(el.id));
    el.addEventListener('change', () => clearFieldError(el.id));
});

loadReferences().catch(e => showError('error', e));
load().catch(()=>{});
const initialId=new URLSearchParams(location.search).get('id');
if(initialId && /^[1-9][0-9]*$/.test(initialId)) viewOrg(Number(initialId));
connectWebSocket();
