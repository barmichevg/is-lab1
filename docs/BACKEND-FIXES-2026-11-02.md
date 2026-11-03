# Backend: идемпотентность, авторизация, даты и пагинация — 2 ноября 2026

Исправлены пять согласованных недостатков. Предыдущие изменения конкурентности/merge сохранены. Helios и рабочие контейнеры проекта не обновлялись; проверки проводились на отдельном PostgreSQL и временных Payara.

## 1–2. Нет общего монитора; идемпотентность хранится в PostgreSQL

[OrganizationCreationGuard](../src/main/java/ru/itmo/is/service/OrganizationCreationGuard.java) больше не имеет `synchronized` или JVM-кэша результатов. Без заголовка `X-Idempotency-Key` операция выполняется без записи/блокировки retry-ключа. С ключом координация идёт через [IdempotencyRepository](../src/main/java/ru/itmo/is/repository/IdempotencyRepository.java) и новую таблицу `api_idempotency` в [схеме](../db/schema.sql).

Ключ записи: **аутентифицированный username + операция + ключ запроса**. Используется username из сессии, не Session ID и не переданный клиентом owner. Поэтому новый вход после перезапуска приложения не изменяет область ключа. Операции create, merge и add-employee имеют разные области; для add-employee ID организации входит в fingerprint запроса.

Алгоритм:

1. В JTA-транзакции выполняется `INSERT … ON CONFLICT DO NOTHING` по уникальному ключу.
2. Первый запрос владеет новой строкой. Он вызывает обычный CDI-сервис, чья REQUIRED-транзакция **присоединяется к транзакции guard**, а не коммитится отдельно.
3. После изменения сохраняется полный снимок DTO-ответа. **Claim + изменение + ответ коммитятся вместе.** При ошибке выполнения, кодирования, записи ответа или commit откатываются все три.
4. Конкурирующий запрос с тем же ключом ждёт именно эту строку, затем получает сохранённый ответ. Если первый откатился, конкурент может сам получить claim и выполнить операцию.
5. Другие ключи и запросы без ключа не ждут общего JVM-монитора. Доменные shared/exclusive guards предыдущего исправления по-прежнему обеспечивают нужную сериализацию merge/rebind.

Порядок блокировок: **idempotency claim → domain mutation guard → строки доменных объектов**. Нельзя брать новый claim после уже захваченных доменных блокировок или менять порядок в будущих entry points.

### Контракт повторов

- Ключ — непустая строка до 256 символов; отсутствие заголовка означает обычную операцию без защиты от повтора.
- SHA-256 связывает ключ с точным текстом тела запроса; для add-employee — с ID. Изменение payload с тем же ключом возвращает **400** и не выполняет новую операцию. Изменённое форматирование JSON также считается другим payload.
- Повтор возвращает **первоначальный снимок ответа**, а не текущую карточку. Это верно и после дальнейшего изменения/удаления организации. Для актуального состояния нужен GET; успешный replay не означает, что организация до сих пор существует.
- Снимок сериализуется [версионированным codec](../src/main/java/ru/itmo/is/dto/OrganizationResponseCodec.java): формат 1, точные строковые long/version, вложенные адреса/координаты, дата. Статус и Location восстанавливаются существующим обработчиком соответствующего endpoint.
- Внешний API сохраняет прежние DTO и заголовок; клиент переписывать не требуется.
- Нет автоматического TTL/очистки: ключи переживают restart и не становятся исполняемыми повторно через десять минут. **Удаление записей администратором завершает гарантию повторов для этих ключей.** Нужна осмысленная политика retention/квот при неучебной нагрузке; молча удалять живую историю нельзя.
- Таблица не имеет FK на Organization: удаление/merge не должны удалять историю результата.
- Гарантия работает и между процессами Payara, использующими одну схему PostgreSQL. Это не делает файловый UserStore распределённым: в тесте оба процесса использовали уже существующий аккаунт, а регистрацию выполнял один экземпляр.

## 3. creationDate одинакова в POST и GET

[Organization.prePersist](../src/main/java/ru/itmo/is/model/Organization.java) приводит дату к микросекундам до INSERT, как PostgreSQL TIMESTAMP. Нормализуется и автоматически созданное, и заранее присвоенное серверным кодом значение. Поле остаётся неизменяемым при UPDATE. Проверяется равенство **полных DTO** POST/GET с creationDate/version, без исключения даты из assertions.

## 4. Изолированный и ограниченный rate limiter

[LoginRateLimiter](../src/main/java/ru/itmo/is/security/LoginRateLimiter.java) заменяет общий лимит 30 попыток на весь сервер:

| Область | Лимит за минуту |
|---|---:|
| Один username, отдельно LOGIN и REGISTER | 30 |
| Фактический IP клиента, обе операции вместе | 120 |
| Высокий общий защитный предел одного процесса | 1000 |

[AuthServlet](../src/main/java/ru/itmo/is/security/AuthServlet.java) делает **двухэтапную** проверку:

1. До чтения тела/Content-Type/JSON — дешёвый peer/global admission. Поэтому malformed JSON, неподдерживаемый Content-Type и слишком длинные тела тоже ограничены. Уже заблокированный IP не расходует оставшийся global budget остальных IP.
2. После ограничения тела 65536 байт и проверки bounded username/password shape — user admission, затем PBKDF2 и файловое IO. Недопустимый username не создаёт пользовательские buckets.

Используется только `getRemoteAddr()`, а не недоверенный X-Forwarded-For. При отказе — HTTP 429 с Retry-After. Монотонные часы, не более 4096 user-operation и 2048 IP buckets, очистка истёкших окон; при переполнении памяти buckets живые записи не вытесняются для обхода квоты. Синхронизация есть только на короткой проверке счётчиков, а не вокруг хеширования/работы приложения.

Оговорки: лимиты process-local и суммируются по числу экземпляров; клиенты за одним SSH-туннелем/NAT делят IP-предел. Общий высокий предел — намеренный safety circuit breaker, не обещание неограниченной авторизации при распределённой атаке. Политика паролей не менялась: пустой пароль всё ещё допускается текущим вариантом/тестами.

## 5. items и total из одного снимка

[OrganizationPageService](../src/main/java/ru/itmo/is/service/OrganizationPageService.java) — отдельный CDI-компонент с **REQUIRES_NEW**. Первый DB statement — `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY`; затем count, выборка страницы и полное построение вложенных DTO выполняются в этой транзакции.

Это именно общий PostgreSQL snapshot, а не просто аннотация @Transactional при READ COMMITTED. Параллельные записи не блокируются advisory guard/row locks. SET TRANSACTION действует только на текущую транзакцию, не меняет постоянные настройки пула. Результат — [материализованный DTO](../src/main/java/ru/itmo/is/dto/OrganizationPageDto.java), без JPA-сущностей и отложенных загрузок после commit.

JSON-контракт остался `items/total/page/size`. Пустая страница за пределами диапазона сохраняет корректный total; пустая выборка возвращает 0. Существующие фильтры, экранирование `%/_/!`, enum, left joins и сортировка сохранены.

## Проверки и доказательства

Использованы PostgreSQL 16 без пользовательских томов и два независимых Payara Micro 6.2025.11/EclipseLink, слушающие только loopback. Рабочий Docker Compose не запускался; реквизиты стенда одноразовые и не относятся к Helios.

- [GuardTest](../src/test/java/ru/itmo/is/GuardTest.java): независимые ключи/нет ключа, отсутствие monitor, хранение/восстановление ответа, конфликт payload, границы транзакций, точность codec.
- [IdempotencyIT](../src/test/java/ru/itmo/is/IdempotencyIT.java): конкурентные запросы из двух JVM с одним ключом → один результат; новый login/session; разные области операций; неизменяемый replay после удаления; rollback ошибки; искусственный отказ сохранения response откатывает employeesCount/version **и claim**; заблокированный ключ не мешает другим ключам/запросу без ключа.
- [CreationDateIT](../src/test/java/ru/itmo/is/CreationDateIT.java): POST == GET со всеми полями.
- [PaginationIT](../src/test/java/ru/itmo/is/PaginationIT.java): детерминированный JDBC writer commit между count/items/nested loads; пустые/вне диапазона страницы; read-only/отсутствие утечки isolation; 60 реальных GET одновременно с 60 PUT, total/items/filter/link согласованы.
- [Unit-проверки страницы](../src/test/java/ru/itmo/is/service/OrganizationPageServiceTest.java): новая транзакция, snapshot первым, mapping внутри неё, resource делегирует единый вызов.
- [Limiter tests](../src/test/java/ru/itmo/is/security/LoginRateLimiterTest.java) и [Servlet tests](../src/test/java/ru/itmo/is/security/AuthServletRateLimitTest.java): independent users/IPs, bounded memory, fake monotonic clock, concurrent admission, spoofed headers, malformed floods, peer denial до чтения тела, защита global budget от уже заблокированного IP.
- [Двухфазный restart-тест](../scripts/idempotency-restart.test.py): prepare создаёт две организации, добавляет сотрудника, объединяет; **оба JVM полностью останавливаются**; после нового процесса/входа те же четыре запроса возвращают исходные ответы, не создавая копий и не увеличивая счётчик повторно. Скрипт ограничен loopback и предназначен только для отдельного тестового экземпляра.

Итоговые проверки:

| Проверка | Результат |
|---|---:|
| Java unit | **106/106** |
| Реальная интеграция PostgreSQL + два Payara | **45/45** |
| DOM/UI | **30/30** |
| Скрипт Helios с заглушками | **19/19** |
| Replay create/addEmployee/merge после остановки обоих JVM и нового входа | Успех |
| HTTP: 30 неверных паролей одного username не блокируют другой username того же IP | Успех |
| HTTP: malformed flood ограничен; 1000 отказов одному IP не блокируют другой фактический IP | Успех |
| WAR совпадает с исходниками/compiled classes и проверенным приложением после рестарта | Побайтово совпадает |
| Shell syntax и git diff --check | Успех |

Доказательства: [Maven verify](../review-artifacts/backend-2026-11-02/verify-final.log),
[restart replay](../review-artifacts/backend-2026-11-02/restart-final-verify.log),
[HTTP auth isolation](../review-artifacts/backend-2026-11-02/auth-final.log),
[сверка WAR](../review-artifacts/backend-2026-11-02/artifact-check.log),
[UI](../review-artifacts/backend-2026-11-02/ui-tests.log),
[Helios script](../review-artifacts/backend-2026-11-02/helios-tests.log).

[Проверка очистки БД](../review-artifacts/backend-2026-11-02/database-cleanup.log):
0 организаций/адресов/координат/городов, 0 незавершённых claims, 0 временных схем
и тестовых CHECK-ограничений. Завершённые retry-снимки намеренно сохранены до удаления
отдельной тестовой БД — это часть новой гарантии, не забытые доменные объекты.
После проверок все временные JVM остановлены и PostgreSQL-контейнер удалён;
рабочие контейнеры/Helios не затронуты. Одноразовые runtime/accounts/state остались
только в игнорируемом локальном target; исходные аккаунты проекта не менялись.
 Скрипт restart использует TEST_BASE_URL/TEST_AUTH_USER/TEST_AUTH_PASSWORD и путь state, который содержит только тестовые payload/ответы, **без пароля или cookies**. TEST_SECOND_BASE_URL позволяет направить вторую группу клиентов IdempotencyIT в другой JVM; без переменной используется второй HTTP-клиент того же экземпляра.

Полный `mvn -Pintegration verify` — **только на отдельном тестовом стенде**, где TEST_DB_* указывает на ту же схему, что HTTP. В IdempotencyIT есть временное CHECK-ограничение для моделирования отказа записи ответа; PaginationIT/DatabaseIT создают собственные схемы. Не направлять весь набор на Helios/рабочую БД.

## Обновление Helios

Сначала остановить старую версию, передать **новый WAR и schema.sql вместе**, затем запустить существующий [helios-start.sh](../scripts/helios-start.sh). Скрипт создаст новую таблицу в уже разрешённой схеме `s465142`; права создания новых схем не нужны. Без таблицы `api_idempotency` запросы с ключами не заработают.

Старая in-memory история запросов не мигрируется: для запросов, завершённых до обновления, восстановить гарантию повторов задним числом нельзя. Не смешивать старые/новые writers. Данные организаций и существующие аккаунты сохраняются. После обновления перезагрузить вкладки и проверить отдельными тестовыми записями POST==GET, повтор ключа после выхода/входа, merge и pagination.

На Helios изменения помощником не применялись. Команды копирования/туннель — в [HELIOS](HELIOS.md).
