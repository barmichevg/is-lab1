# Проверка ЛР1

## 1. Пагинация

Создайте минимум 12 организаций (для этого можно использовать кнопку случайного заполнения), затем:

```bash
./scripts/verify-api.sh
```

В UI при размере страницы 10 первая страница должна содержать 10 строк, вторая — остальные. Фильтрация и сортировка должны сохраняться при переходе между страницами.

## 2. Удаление вспомогательного объекта с заменой

Найти Coordinates, используемые несколькими организациями:

```sql
SELECT coordinates_id, count(*)
FROM organizations
GROUP BY coordinates_id
HAVING count(*) > 1;
```

Откройте `references.html`, удалите найденные Coordinates и выберите другой объект в качестве замены. Затем проверьте:

```sql
SELECT id, name, coordinates_id
FROM organizations
ORDER BY id;

SELECT * FROM coordinates ORDER BY id;
```

Все ранее связанные Organization должны ссылаться на replacement, а исходная строка Coordinates должна исчезнуть. Аналогично проверяются Address и Location.

## 3. WebSocket

1. Откройте `index.html` в двух вкладках A и B.
2. В A создайте Organization — она должна появиться в B без F5.
3. В A измените Organization — новые значения должны появиться в B без F5.
4. В A удалите Organization — она должна исчезнуть в B без F5.
5. Откройте `references.html` или `special.html` во второй вкладке и повторите изменяющую операцию: эти страницы тоже должны обновиться.

Событие отправляется сервером только после успешного commit JTA-транзакции.
