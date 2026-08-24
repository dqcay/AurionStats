# AurionStats

Плагин для Leaf/Paper 1.21.11, который автоматически сохраняет статистику игроков в MySQL.

Готовая сборка находится в `release/AurionStats-1.0.0.jar`.

## Что сохраняется

- UUID и последний ник игрока;
- время последнего входа и последней активности;
- текущий статус `ONLINE` / `OFFLINE`;
- общее игровое время;
- каждая игровая сессия;
- игровое время по дням UTC, включая активность за последние 7 дней.

Плагин создаёт таблицы `player_stats`, `player_sessions` и `player_daily_activity` автоматически. Сама база должна существовать до первого запуска. Если её ещё нет, замените шаблонные значения и выполните `database-bootstrap.sql` от пользователя MySQL с правом создавать базы и пользователей.

## Сборка и установка

Нужны JDK 21 и Maven 3.9+.

```bash
mvn clean package
```

Готовый файл `target/AurionStats-1.0.0.jar` перенесите в папку `plugins` сервера и перезапустите сервер. Настройки подключения появятся в `plugins/AurionStats/config.yml`. Перед запуском замените `YOUR_DB_HOST`, `YOUR_DATABASE_NAME`, `YOUR_DATABASE_USER` и `CHANGE_ME` на реальные значения. Никогда не публикуйте заполненный `config.yml`.

## Полезные запросы

Общее время игроков в часах:

```sql
SELECT last_known_name,
       status,
       last_join_at,
       last_seen_at,
       ROUND(total_playtime_millis / 3600000, 2) AS total_hours
FROM player_stats
ORDER BY total_playtime_millis DESC;
```

Активность за последние 7 календарных дней UTC:

```sql
SELECT p.last_known_name,
       ROUND(SUM(a.playtime_millis) / 3600000, 2) AS hours_last_7_days
FROM player_daily_activity a
JOIN player_stats p ON p.player_uuid = a.player_uuid
WHERE a.activity_date >= UTC_DATE() - INTERVAL 6 DAY
GROUP BY a.player_uuid, p.last_known_name
ORDER BY hours_last_7_days DESC;
```

Запись выполняется асинхронно и не задерживает основной игровой поток. Каждые 60 секунд сохраняется контрольная точка; интервал можно изменить в `config.yml`.
