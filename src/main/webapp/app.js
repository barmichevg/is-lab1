


/* Shared session, HTTP, WebSocket and small UI helpers. No framework needed. */
const Lab = (() => {
    let auth=null, pendingAuth=null, leaving=false;
    const status=document.createElement('div');
    status.className='connection-state'; status.setAttribute('role','status');
    (document.getElementById('connectionSlot') || document.querySelector('nav'))?.append(status);
    function connection(message,state='pending') { status.textContent=message; status.dataset.state=state; }
    connection('Подключение…');
    const exit=document.createElement('button'); exit.textContent='Выйти'; exit.type='button';
    (document.querySelector('.session-tools') || document.querySelector('nav'))?.append(exit);
    async function session() {
        if(auth) return auth;
        if(!pendingAuth) pendingAuth=fetch('api/auth/session').then(async r => {
            if(!r.ok) { location.replace('login.html'); throw new Error('Войдите в систему'); }
            auth=await r.json();
            const user=document.getElementById('sessionUser');
            if(user) {user.textContent=auth.username;user.title='Вы вошли как '+auth.username;}
            return auth;
        }).finally(()=>pendingAuth=null);
        return pendingAuth;
    }
    async function request(url,options={}) {
        const a=await session();
        const r=await fetch(url,{...options,headers:{...(options.body?{'Content-Type':'application/json'}:{}),
                'X-CSRF-Token':a.csrf,...options.headers}});
        if(r.status===401) { auth=null; location.replace('login.html'); throw new Error('Сессия завершена. Войдите снова.'); }
        if(!r.ok) {
            const body=await r.json().catch(()=>({}));
            const error=new Error((body.error||`Ошибка HTTP ${r.status}`)+(Array.isArray(body.details)?'\n'+body.details.join('\n'):''));
            error.status=r.status; error.details=body.details; throw error;
        }
        return r.status===204?null:r.json();
    }
    exit.onclick=async()=> { exit.disabled=true; try { await request('api/auth/logout',{method:'POST'}); location.replace('login.html'); } catch(e) { connection(e.message,'error'); exit.disabled=false; } };
    function connect(sync) {
        let socket=null,timer=null,refreshTimer=null,running=false,queued=false;
        async function refresh(event) {
            if(leaving) return;
            if(running) { queued=true; return; }
            running=true;
            connection('Синхронизация…');
            try {
                do { queued=false; await sync(event); event=null; } while(queued && !leaving);
                if(socket?.readyState===1) connection('Синхронизировано','online');
                else connection('Нет соединения — переподключение…');
            } catch(e) {
                connection('Ошибка синхронизации: '+e.message,'error');
                clearTimeout(refreshTimer); refreshTimer=setTimeout(()=>refresh(null),3000);
            } finally { running=false; }
        }
        async function open() {
            if(leaving) return;
            try {
                const a=await session();
                const base=new URL('.',location.href);
                socket=new WebSocket(`${base.protocol==='https:'?'wss:':'ws:'}//${base.host}${base.pathname}ws/organizations?token=${encodeURIComponent(a.wsToken)}`);
                socket.onopen=()=>refresh(null); // Full resync after every reconnect.
                socket.onmessage=e=> { let event=null; try {event=JSON.parse(e.data);} catch(_) {} refresh(event); };
                socket.onclose=e=> {
                    if(e.code===1008) { auth=null; location.replace('login.html'); return; }
                    if(!leaving) { connection('Нет соединения — повторное подключение…'); timer=setTimeout(open,1500); }
                };
                socket.onerror=()=>socket.close();
            } catch(e) { connection(e.message,'error'); if(!leaving) timer=setTimeout(open,3000); }
        }
        window.addEventListener('pagehide',()=>{ leaving=true; clearTimeout(timer); clearTimeout(refreshTimer); socket?.close(); });
        window.addEventListener('pageshow',e=>{if(e.persisted) location.reload();});
        open();
    }
    function fillSelect(select,items,label,onMissing=()=>{}) {
        const previous=select.value;
        select.replaceChildren(new Option(items.length?'Выберите объект':'Нет объектов',''));
        for(const item of items) select.add(new Option(label(item),String(item.id)));
        select.value=previous;
        if(previous && !items.some(item=>String(item.id)===previous)) {
            select.value=''; onMissing(`Выбранный объект #${previous} удалён. Выберите другой объект.`);
        }
    }
    function number(input,{integer=false,long=false,positive=false,float=false}={}) {
        const raw=input.value.trim();
        if(!raw) throw new Error('Введите число.');
        if(integer || long) {
            if(!/^-?\d+$/.test(raw)) throw new Error('Введите целое число без дробной части.');
            const n=BigInt(raw), min=positive?1n:(long?-9223372036854775808n:-2147483648n), max=long?9223372036854775807n:2147483647n;
            if(n<min || n>max) throw new Error(`Диапазон: ${min} … ${max}.`);
            return long?n.toString():Number(n);
        }
        const n=Number(raw);
        if((n===0 && /[1-9]/.test(raw.split(/[eE]/)[0])) || !Number.isFinite(n) || (positive && n<=0) || (float && (!Number.isFinite(Math.fround(n)) || Math.fround(n)<=0)))
            throw new Error('Число вне допустимого диапазона.');
        return n;
    }
    function fieldError(input,message) {
        let node=input.parentElement.querySelector('.field-error');
        if(!node) {node=document.createElement('div');node.className='field-error';input.after(node);}
        node.textContent=message;
        input.classList.toggle('input-error',Boolean(message));
        input.setAttribute('aria-invalid',String(Boolean(message)));
        if(input.id) {
            node.id=input.id+'Error';
            const ids=new Set((input.getAttribute('aria-describedby')||'').split(/\s+/).filter(Boolean));
            ids.add(node.id);input.setAttribute('aria-describedby',[...ids].join(' '));
        }
    }
    function typeLabel(type) { return ({COMMERCIAL:'Коммерческая',GOVERNMENT:'Государственная',PRIVATE_LIMITED_COMPANY:'ООО'})[type] || String(type??''); }
    function formatNumber(n) {
        try {
            // Integer strings carry Java long values: never round them through Number.
            if(typeof n==='string' && /^-?\d+$/.test(n)) return BigInt(n).toLocaleString('ru-RU');
            return Number(n).toLocaleString('ru-RU',{maximumSignificantDigits:17});
        } catch(_) { return String(n??''); }
    }
    return {request,connect,fillSelect,number,fieldError,formatNumber,typeLabel};
})();

