# Исправления конкурентности, merge и SQL-ошибок — 1 ноября 2026

> Дополнение от 2 ноября: retry-cache заменён PostgreSQL-идемпотентностью, добавлены
> snapshot-пагинация и новый auth limiter. Теперь порядок locks при наличии ключа:
> idempotency claim → domain guard → domain rows. Актуальный контракт/тесты — в
> [отчёте backend](BACKEND-FIXES-2026-11-02.md). Числа проверок ниже — исторический результат.

## Результат

По уточнённому требованию владельца **объединение заменяет обе исходные организации новой**, а не копирует численность с сохранением исходников. Также исправлены оба конкурентных сценария из [первоначального аудита](AUDIT-2026-11-01.md) и уточнены сообщения SQL-ошибок. Пользовательские изменения сохранены. Helios и основной Docker-проект не изменялись.

## 1. Location-rebind против create/update Organization

Добавлены [MutationLock](../src/main/java/ru/itmo/is/repository/MutationLock.java) и функция `lab1_lock_writes(boolean)` в [SQL-схеме](../db/schema.sql).

| Вход в изменяющий сервис | Режим |
|---|---|
| Organization create / update / delete | shared |
| addEmployee | shared |
| Удаление Coordinates / Address / Location с переназначением | exclusive |
| merge | exclusive |

Блокировка берётся **первым действием до чтения сущностей**, в той же JTA-транзакции. Компонент требует `TxType.MANDATORY`; transaction-scoped advisory lock освобождается PostgreSQL при commit/rollback. Ключ — `(1364, hashtext(current_schema()))`, поэтому разные схемы студентов не делят один глобальный lock. Коллизия хеша может лишь излишне сериализовать две схемы, а не разрешить небезопасную запись.

При этом протоколе:

- если create/update завершается первым, exclusive-rebind дожидается commit и повышает version у всех уже связанных организаций, включая новую;
- если rebind начинается первым, create/update ждёт его commit **до resolveAddress**, затем получает новый town;
- между version UPDATE и Address.town UPDATE больше не может вклиниться API-writer;
- обычные изменения могут идти параллельно друг с другом; существующая защита `@Version`/409 сохранена;
- merge и удаление справочников сериализованы между собой и исключают обычных writers.

В [persistence.xml](../src/main/resources/META-INF/persistence.xml) задан `shared-cache-mode=NONE`: после ожидания guard адрес/город не может быть восстановлен из устаревшего общего JPA-кэша. Контекст хранения транзакционный, чтения в изменяющих сервисах идут после guard.

**Границы договора:** все произвольные прямые SQL-writers должны сначала вызывать `SELECT lab1_lock_writes(FALSE)` или `TRUE` в явной транзакции и той же схеме. Нельзя взять shared, а затем повышать его до exclusive. Новые функции merge/addEmployee берут нужный guard и самостоятельно. Не следует вызывать несколько разных бизнес-операций с upgrade в одной внешней транзакции. Старый WAR протокол не соблюдает: смешанное развёртывание запрещено.

## 2. Merge читает согласованные источники и удаляет их

`lab1_merge_organizations`:

1. Получает exclusive guard **до чтения**.
2. Блокирует обе исходные строки `FOR UPDATE` в порядке ID, затем читает значения. Источники остаются заблокированы до конца транзакции, поэтому две раздельные загрузки уже не могут увидеть несовместимые значения.
3. Проверяет сумму employeesCount и диапазон суммы annualTurnover.
4. Создаёт выбранные новые Address/Location при необходимости и новую Organization.
5. Удаляет обе исходные Organization. Справочники не удаляются и остаются доступны для повторного использования.
6. Возвращает ID новой организации. Любая ошибка либо rollback вызывающей транзакции откатывает **все** изменения, включая удаление исходников и новые справочники.

Остальные поля определены так же, как раньше: coordinates первой организации; сумма annualTurnover; max rating и type в порядке enum; postalAddress = null. ID и дата — новые, version новой SQL-организации = 1.

Два одновременных merge с общим исходником или обратным порядком тех же ID не создают дубликат сотрудников: один завершается, второй после ожидания видит отсутствие исходника и возвращает 404. Повтор с тем же idempotency key в действующем retry-cache возвращает первоначальный результат; новый ключ с удалёнными исходниками — 404.

[SpecialOperationsService](../src/main/java/ru/itmo/is/service/SpecialOperationsService.java) очищает persistence context после native merge и отправляет **одно after-commit REFRESH**, покрывающее и новую организацию, и исчезновение двух старых. На [странице спецопераций](../src/main/webapp/special.html) явно сказано об удалении исходников. После успеха выбор источников очищается без ложного сообщения об неожиданном удалении; показывается ID результата.

## 3. Безопасные SQL-ошибки

[ApiExceptionMapper](../src/main/java/ru/itmo/is/exception/ApiExceptionMapper.java) выдаёт фиксированные сообщения по whitelist, не использует текст SQLException, SQL, detail/hint и неизвестные имена ограничений в ответе.

| SQLSTATE | HTTP | Причина |
|---|---:|---|
| P1001 | 409 | Нельзя добавить сотрудника: employeesCount уже Long.MAX_VALUE. |
| P1002 | 409 | Сумма employeesCount при merge превышает Long.MAX_VALUE. |
| P1003 | 409 | Сумма annualTurnover при merge вне диапазона float. |
| P1004 | 409 | Исчерпан счётчик version при addEmployee. |
| P1005 | 404 | Организация/источник уже отсутствует. |
| P1006 | 409 | Выбранный Address/Location отсутствует. |
| P1007 | 400 | Некорректные параметры merge. |

Известные CHECK/FK отображаются на сообщения о соответствующих полях/связях через структурированный идентификатор ограничения PostgreSQL. Для неизвестного ограничения — безопасное общее сообщение. Отдельно распознаются numeric overflow, слишком длинная строка, NOT NULL/unique/FK/CHECK, deadlock, serialization failure и недоступная блокировка.

Обход вложенных causes и `SQLException.nextException` защищён от циклов. OptimisticLockException имеет приоритет и сохраняет существующий контракт 409. Неизвестная внутренняя ошибка возвращает 500 без деталей; mapper не пишет произвольное исключение/секретный message в собственный журнал. Сам ORM/контейнер может независимо вести серверный диагностический лог: клиенту он не возвращается.

## 4. Проверки

Проверка выполнена не на Helios, а на отдельном разрешённом владельцем PostgreSQL 16 в Docker **без пользовательских томов**, плюс отдельный Payara Micro 6.2025.11 на loopback. Использован EclipseLink 4.0.7.payara-p1 из Payara. Основные контейнеры проекта не запускались. Тестовые реквизиты не связаны с SSH/БД пользователя.

| Проверка | Итог |
|---|---:|
| Java unit, включая порядок locks, кэш и безопасные ошибки | **68/68** |
| DatabaseIT: SQL-функции, миграция, ошибки, rollback | **9/9** |
| DatabaseConcurrencyIT: настоящие конкурирующие JDBC-транзакции | **10/10** |
| ApiIT: HTTP, ORM/JTA, merge replacement, overflow, версии, WebSocket | **10/10** |
| MutationApiIT: реальные HTTP/JPA create/update, ожидающие rebind | **2/2** |
| RegistrationIT на временном экземпляре | **2/2** |
| Итого интеграционных | **33/33** |
| DOM/UI, включая исчезновение исходников merge | **30/30** |
| Скрипт Helios с заглушками | **19/19** |
| Shell syntax, diff whitespace, сборка WAR | Успех |

### Что доказывают конкурентные тесты

[DatabaseConcurrencyIT](../src/test/java/ru/itmo/is/DatabaseConcurrencyIT.java) устанавливает расписание через latch/barrier и проверку реального ожидания в `pg_locks`, а не через случайные задержки:

- create и update связей **после** exclusive-rebind возвращают новый town;
- create и update **до** rebind включаются в повышение версии; старый version не может обновить строку;
- shared-writers совместимы, exclusive исключает их, commit/rollback освобождает guard;
- merge удерживает exclusive до commit;
- прямой SQL-вызов addEmployee тоже соблюдает shared guard;
- конкурирующие merge с общим исходником/обратным порядком имеют одного победителя, без дубликатов;
- ожидающий merge видит согласованные изменения обеих исходных организаций.

[MutationApiIT](../src/test/java/ru/itmo/is/MutationApiIT.java) дополнительно прогревает ORM-чтения, удерживает JDBC exclusive guard и native-rebind незакоммиченным, запускает настоящий HTTP create/PUT, подтверждает ожидание **соединения Payara** в PostgreSQL, затем делает commit. Проверяются новый town в ответе и последующем GET, актуальная version и 409 при сохранении старого черновика. Это проверка реальных CDI/JTA/EclipseLink-путей, не только SQL-модель сервиса.

[MutationLockOrderingTest](../src/test/java/ru/itmo/is/service/MutationLockOrderingTest.java) проверяет ранний захват правильного режима во всех восьми изменяющих service-методах. [ErrorTest](../src/test/java/ru/itmo/is/ErrorTest.java) проверяет custom states, whitelist ограничений, неизвестные/поддельные имена, chains/cycles и отсутствие утечек в ответах/mapper-логах.

Первый интеграционный прогон выявил не откат данных, а ошибку новых сравнений теста: POST-дата содержит наносекунды, PostgreSQL хранит микросекунды. Baseline для проверки неизменности теперь берётся GET-запросом **до** операции, затем сравнивается полный DTO с датой и version; assertions не ослаблены. Следующие полные прогоны проходят.

Доказательства: [Maven verify](../review-artifacts/concurrency-2026-11-01/maven-verify.log), [повторный прогон окончательного WAR](../review-artifacts/concurrency-2026-11-01/runtime-verify.log), [побайтовая сверка WAR и проверенного приложения](../review-artifacts/concurrency-2026-11-01/artifact-check.log), [UI](../review-artifacts/concurrency-2026-11-01/ui-tests.log), [Helios script](../review-artifacts/concurrency-2026-11-01/helios-tests.log). Полный прогон запускается `mvn -Pintegration verify` с TEST_DB_* и TEST_BASE_URL/TEST_AUTH_* **только на отдельном тестовом экземпляре**. MutationApiIT требует, чтобы JDBC и HTTP указывали на одну тестовую схему. DatabaseIT/DatabaseConcurrencyIT создают временные схемы и делают TRUNCATE только внутри них — не перенаправлять их в рабочую схему Helios.

После финального прогона [проверена очистка](../review-artifacts/concurrency-2026-11-01/database-cleanup.log):
0 организаций, адресов, координат и городов; временных тестовых схем не осталось.
Оба временных процесса Payara остановлены, отдельный PostgreSQL-контейнер удалён.
Тестовые аккаунты и runtime-артефакты находятся только в игнорируемом локальном target;
рабочее хранилище аккаунтов и данные пользователя не затронуты.

## 5. Обновление Helios

**На Helios эти изменения ещё не развёрнуты.** Для применения нужно обновить и приложение, и SQL, не только WAR:

1. Остановить свой старый Payara (Ctrl+C в его терминале, не `killall java`).
2. Локально из каталога проекта:

```bash
mvn package
scp -P 2222 target/is-lab1.war db/schema.sql scripts/helios-start.sh \
  s465142@helios.cs.ifmo.ru:~/is-lab1/
```

3. На Helios:

```bash
cd "$HOME/is-lab1"
sh ./helios-start.sh
```

Скрипт повторно применяет функции в существующей схеме `s465142`, сохраняет данные и запускает новый WAR. Без `lab1_lock_writes` новый WAR не выполнит изменяющие операции. Нельзя одновременно оставлять старые writers, не соблюдающие guard. Новая миграция **сама не объединяет и не удаляет существующие организации**; удаление двух исходников происходит только при явном вызове merge пользователем.

4. Перезагрузить открытые вкладки и проверить на отдельных тестовых записях: merge оставляет одну новую организацию, обе старые по ID дают 404, справочники сохранены; rebind обновляет второй клиент; старый PUT конфликтует. Для addEmployee Long.MAX_VALUE и merge overflow — информативный 409 без изменения исходников.

Подробности туннеля и разрешённого API-only прогона: [HELIOS](HELIOS.md). Полный интеграционный набор на Helios не запускать.
