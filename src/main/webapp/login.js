let registering=false,busy=false;
function setMode(value){
 if(busy)return;registering=value;
 document.getElementById('submit').textContent=value?'Зарегистрироваться':'Войти';
 document.getElementById('loginTab').setAttribute('aria-selected',String(!value));
 document.getElementById('registerTab').setAttribute('aria-selected',String(value));
 document.getElementById('loginTab').tabIndex=value?-1:0;
 document.getElementById('registerTab').tabIndex=value?0:-1;
 document.getElementById('authPanel').setAttribute('aria-labelledby',value?'registerTab':'loginTab');
 document.getElementById('usernameHint').hidden=!value;
 document.getElementById('repeatLabel').hidden=!value;
 document.getElementById('repeatPassword').disabled=!value;
 document.getElementById('password').autocomplete=value?'new-password':'current-password';
 document.getElementById('error').textContent='';
}
document.getElementById('loginTab').onclick=()=>setMode(false);
document.getElementById('registerTab').onclick=()=>setMode(true);
document.querySelector('.tabs').addEventListener('keydown',e=>{
 if(!['ArrowLeft','ArrowRight','Home','End'].includes(e.key)||busy)return;
 e.preventDefault();setMode(e.key==='Home'?false:e.key==='End'?true:!registering);
 document.getElementById(registering?'registerTab':'loginTab').focus();
});
document.getElementById('login').addEventListener('submit',async e=>{
 e.preventDefault();if(busy)return;
 const form=e.currentTarget,error=document.getElementById('error');error.textContent='';
 if(registering && document.getElementById('password').value!==document.getElementById('repeatPassword').value){error.textContent='Пароли не совпадают.';document.getElementById('repeatPassword').focus();return;}
 busy=true;form.setAttribute('aria-busy','true');document.querySelectorAll('button').forEach(b=>b.disabled=true);
 document.getElementById('submit').textContent=registering?'Создаём аккаунт…':'Входим…';
 try{
  const response=await fetch(registering?'api/auth/register':'api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(Object.fromEntries(new FormData(form)))});
  if(!response.ok){
   const messages={400:'Проверьте логин: до 64 латинских букв, цифр, точек, дефисов или подчёркиваний.',401:'Неверный логин или пароль.',409:'Этот логин уже занят.',413:'Слишком большой запрос.',429:'Слишком много попыток. Подождите минуту.',503:'Не удалось сохранить пользователя. Проверьте доступ сервера к файлу учётных записей.'};
   throw new Error(messages[response.status]||'Не удалось выполнить запрос. Попробуйте снова.');
  }
  location.replace('index.html');
 }catch(e){error.textContent=e.message;}finally{
  busy=false;form.setAttribute('aria-busy','false');document.querySelectorAll('button').forEach(b=>b.disabled=false);
  document.getElementById('submit').textContent=registering?'Зарегистрироваться':'Войти';
 }
});
