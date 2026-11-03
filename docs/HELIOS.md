# Запуск на Helios

Требуются Java 17/21 и PostgreSQL `pg/studs`. Приложение слушает только
`127.0.0.1:46514`; доступ с компьютера — через SSH-туннель. При другом логине
замените `s465142` в командах и задайте `DB_USER`, `DB_SCHEMA`, `HTTP_PORT`.

## Сборка и копирование

Из каталога проекта:

```bash
mvn clean package
curl -fL https://repo.maven.apache.org/maven2/fish/payara/extras/payara-micro/6.2025.11/payara-micro-6.2025.11.jar -o target/payara-micro.jar
curl -fL https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.7/postgresql-42.7.7.jar -o target/postgresql.jar
ssh -p 2222 s465142@helios.cs.ifmo.ru 'mkdir -p ~/is-lab1'
scp -P 2222 target/is-lab1.war target/payara-micro.jar target/postgresql.jar db/schema.sql scripts/helios-start.sh s465142@helios.cs.ifmo.ru:~/is-lab1/
```

Пароль SSH вводится в терминале. Не переносите секреты в репозиторий.

## На сервере

В `~/.pgpass` нужна подходящая запись для `pg:5432:studs:s465142`.
Не выводите содержимое файла. Скрипт читает пароль в окружение процесса,
не передавая его в аргументах Java.

```bash
ssh -p 2222 s465142@helios.cs.ifmo.ru
sh
cd "$HOME/is-lab1"
chmod 600 "$HOME/.pgpass"
umask 077
nohup sh ./helios-start.sh > payara.log 2>&1 < /dev/null &
echo $! > payara.pid
```

Скрипт проверяет права на личную схему, применяет SQL без удаления данных,
настраивает JDBC и сохраняет аккаунты в `accounts/users.properties`.
Существующей схеме нужны USAGE/CREATE; если схемы нет и нет права создать её,
задайте свою разрешённую `DB_SCHEMA` или обратитесь к администратору.
Не переключайтесь вслепую на public или чужую схему.

Payara запускается с `--nohazelcast`. Не добавляйте `--nocluster`:
в используемой версии этот флаг мешает отключению Hazelcast.

## Туннель и проверка

На своём компьютере в отдельном терминале:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:18080:127.0.0.1:46514 -p 2222 s465142@helios.cs.ifmo.ru
```

Откройте http://localhost:18080/is-lab1/login.html. Проверьте вход, создание,
изменение и удаление организации; двумя браузерами — автоматическое обновление.
Проверку сборки и основной smoke-тест локально выполняет `mvn test`.

## Обновление и остановка

Перед заменой WAR проверьте PID и команду собственного процесса:

```bash
cd "$HOME/is-lab1"
ps -p "$(cat payara.pid)" -o pid,args
```

Только после проверки остановите именно этот процесс:

```bash
kill "$(cat payara.pid)"
```

Не используйте `killall java` на общем сервере. После остановки копируйте вместе
новый WAR, SQL и скрипт, затем запускайте приложение снова. Не удаляйте
`accounts/users.properties` и таблицы БД. После обновления перезагрузите вкладки
с обходом кэша: frontend и backend должны соответствовать одной версии.
