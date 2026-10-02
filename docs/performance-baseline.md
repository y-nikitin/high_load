# Лабораторна №5 — Load Performance Baseline

Реалізацію підготовлено без запуску застосунку, Docker, збірки та тестів. Виміряних результатів поки немає. Скрипти генерують таблиці та графіки після запуску користувачем.

## Запуск

Потрібні Docker Desktop із Linux containers, Docker Compose v2, PowerShell і вільний порт 18080. Java та k6 на хості не потрібні: образ застосунку збирається Dockerfile, генератор працює в контейнері.

Із кореня репозиторію:

```powershell
# Основні сценарії: 10, 25, 50, 100, 200 одночасних користувачів
.\scripts\run-baseline.ps1 -Scenario read -Instances 1 -CacheMode disabled
.\scripts\run-baseline.ps1 -Scenario write -Instances 1
.\scripts\run-baseline.ps1 -Scenario workflow -Instances 1

# Horizontal scaling: ті ж сценарії та параметри на двох репліках
.\scripts\run-baseline.ps1 -Scenario read -Instances 2 -CacheMode disabled
.\scripts\run-baseline.ps1 -Scenario write -Instances 2
.\scripts\run-baseline.ps1 -Scenario workflow -Instances 2

# Кеш: порівнювати з read / 1 replica / disabled
.\scripts\run-baseline.ps1 -Scenario read -Instances 1 -CacheMode cold
.\scripts\run-baseline.ps1 -Scenario read -Instances 1 -CacheMode warm

# Перевірка відкритою моделлю: рівні тут означають iterations/s
.\scripts\run-baseline.ps1 -Scenario read -Mode arrival -Levels 100,200,400,800 -Seconds 60 -Repeats 3

# Повторна генерація з усіх наявних вимірювань
.\scripts\report-baseline.ps1
Start-Process .\results\baseline.html
```

Кожен виклик створює окремий Compose project `baseline-...`, власну БД і Redis. Існуючі дані інших Compose projects не змінюються. Після завершення контейнери видаляються через `down`, том БД залишається для аналізу. Назва project виводиться в консолі й зберігається у metadata. Не запускайте кілька прогонів одночасно: це спотворює результати та спричиняє конфлікт порту. Нову серію експериментів зберігайте в окремій копії каталогу results; звіт читає всі наявні файли.

## Методика

Детерміновані fixtures: один покупець і 100 товарів по 10.00 із великим запасом. Read читає п'ять сторінок по 20 товарів. Write створює замовлення на один товар. Workflow читає товар, створює замовлення, перевіряє його і скасовує (чотири HTTP запити). Перевіряються статуси HTTP, ціна/сума та бізнес-статуси. Повторів POST після помилок немає.

Перед кожним вимірюванням виконується окремий 10-секундний прогрів, потім 30 секунд вимірювання. Warmup не входить у custom latency/RPS. Новий процес k6 відновлює клієнтські з'єднання; JVM залишається прогрітою. Для стійкіших висновків використовуйте `-WarmupSeconds 60 -Seconds 120 -Repeats 3`. Повтори не усереднюють percentiles. У write/workflow БД росте між рівнями та повторами; для незалежних точок запускайте окремий виклик із одним `-Levels`.

У режимі cold Redis очищується безпосередньо перед вимірюванням; далі кеш природно прогрівається. Це cold-start, а не постійні misses. Warm попередньо заповнює всі п'ять сторінок. TTL 3600 с виключає звичайне протухання протягом короткого прогону. Disabled повністю вимикає кеш. Перевіряйте cacheHits/Misses/Bypasses: Redis failure не можна інтерпретувати як нормальний warm cache.

Фіксовані ліміти: кожна Java replica — 1 CPU/512 MiB, PostgreSQL — 2 CPU/1 GiB, Redis — 0.5 CPU/192 MiB, HAProxy — 0.5 CPU/128 MiB, k6 — 1 CPU/1 GiB. Пул — 10 connections на replica (`-PoolSize`), тому сумарний пул збільшується з кількістю реплік. Round-robin, без штучної затримки lab3. Metadata фіксує revision, кількість реплік, Docker CPU/RAM та параметри. Cgroup limits не резервують ядра: забезпечте Docker достатні ресурси, закрийте сторонні навантаження. Для точнішого вимірювання генератор варто винести на окремий хост.

## Метрики та обмеження

- `*-measure.json`: completed HTTP RPS, avg/p50/p95/p99, request/workflow error rates, workflow p95 і throughput, dropped iterations, cache counters, розподіл запитів по репліках. Setup не входить у custom metrics.
- `*.resources.jsonl`: CPU/RAM/network/block I/O контейнерів застосунку, БД, Redis, HAProxy та активного генератора приблизно кожні 2–4 с. Ліміт 1 CPU відповідає приблизно 100% Docker CPU. Короткий запуск генератора може не потрапити в окремі samples.
- `*.database.json`: PostgreSQL counters before/after, зокрема product tuples read. Це proxy обсягу роботи, не кількість SQL запитів; health checks та затримка оновлення статистики впливають на значення.
- `baseline.csv` та `baseline.html`: таблиці й графіки RPS/p95 для кожного сценарію, режиму, кількості реплік і повтору.

SLO прикладу: HTTP p95 ≤ 500 мс, p99 ≤ 1000 мс, request/workflow errors ≤ 1%, dropped iterations = 0. Незавершені запити/ітерації на межі вимірювання зараховуються в errors. Їхня latency не потрапляє в percentiles, тому завжди аналізуйте її разом із errors. Workflow p95 наведено окремо: HTTP SLO не є SLO всього workflow.

Closed model (`vus`) зменшує темп нових запитів, коли сервер сповільнюється — це може приховувати overload/coordinated omission. Arrival model задає незалежну частоту ітерацій; для workflow вона не дорівнює HTTP RPS. `dropped_iterations` означає, що навантаження не доставлено: перевірте generator CPU, preAllocatedVUs/maxVUs (50/500 у скрипті) та latency сервера перед висновком про bottleneck. Див. [open/closed models](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/) і [dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/).

JIT, GC, connection pools і прогрів кешу впливають на перші запити. p95 означає, що 95% завершених запитів швидші за це значення; avg приховує хвіст. Не усереднюйте p95/p99 між прогонами. GC паузи не можна довести лише Docker CPU — потрібні JVM GC logs/JFR у додатковому діагностичному прогоні.

## Аналіз і картка baseline

Перший рівень, що порушив SLO, є кандидатом на межу допустимого навантаження. Насичення підтверджується плато RPS при збільшенні навантаження разом зі зростанням latency/черги; якщо цього немає, збільште рівні. Найвищий спостережений RPS у межах SLO не є гарантованою production capacity.

Порівнюйте 1/2 replicas за однаковими scenario, mode, level, cache, duration та environment: `factor = RPS₂/RPS₁`, `efficiency = factor/2`. Для cache порівнюйте disabled/cold/warm: `latency reduction = 1 - p95_warm/p95_disabled`; аналогічно для product tuples read, додатково нормалізуючи на кількість requests. Нульовий знаменник означає, що відношення не визначене.

Primary bottleneck визначайте за збігом сигналів: Java CPU біля ліміту, DB CPU/I/O, конкуренція за product rows у write/workflow, connection pool або генератор. Саме лише погіршення p95 не доводить жодну з цих причин. Якщо counters недостатньо, зафіксуйте гіпотезу та потрібний діагностичний прогін, а не вигаданий висновок.

Після прогонів заповніть картку у звіті лабораторної:

| Поле | Виміряне значення / доказ |
|---|---|
| CPU/RAM, Docker environment, revision | З environment.json |
| Replicas / pool / cache / dataset | З metadata та методики |
| SLO та тривалість / повтори | З JSON |
| Максимальний спостережений стабільний RPS | Лише точки, що пройшли SLO в повторах |
| Межа насичення | Рівень і плато RPS / latency, або «не досягнута» |
| p95 / p99 / errors | Для вибраної точки |
| Критичний сценарій | Найменша допустима capacity за порівнюваних умов |
| Основний bottleneck | Висновок + конкретні resource/DB докази |
| Scaling factor / efficiency | 1 vs 2 replicas |
| Виграш кешу | Latency і DB work, disabled/cold/warm |
