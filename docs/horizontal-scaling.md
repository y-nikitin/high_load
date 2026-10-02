# Лабораторна №3 — горизонтальне масштабування

Java 21 / Spring Boot API працює за HAProxy. Репліки використовують спільну PostgreSQL, повертають власний `X-Instance-ID` і не зберігають бізнес-стан локально. Типово запускаються два backend-контейнери; клієнту доступний тільки порт балансувальника.

**Перевірки цієї лабораторної ще не виконані.** Нижче команди самостійної перевірки; виміряних результатів поки немає.

Доменна модель, 12 endpoints, транзакції та аудит стану збережені в [описі лабораторних №1–2](labs1-2.md). Команди нижче збережено для масштабування. Для порівняння саме Lab 3 вимкніть кеш; профіль затримки вмикається через `SPRING_PROFILES_ACTIVE=lab3` із відтворенням backend.

## Запуск

Потрібен Docker Desktop із Linux containers і Compose v2. Java/Maven на хості потрібні лише для Java-тестів поза Docker.

```powershell
docker compose up --build -d --wait --remove-orphans
```

`--remove-orphans` прибирає старий `app2`; тепер використовуються репліки одного сервісу `app`. Ім'я проєкту `high-load-lab1` і volume PostgreSQL збережені. При переході зі старої гілки можливе коротке переривання доступу до порту 8080.

- Swagger: <http://localhost:8080/swagger-ui/index.html>
- Каталог: <http://localhost:8080/api/products>
- Readiness: <http://localhost:8080/health>
- OpenAPI: <http://localhost:8080/v3/api-docs>

`APP_PORT` у `.env` змінює публічний порт. У backend і БД немає `ports`; клієнт звертається тільки до HAProxy. `compose.dev.yaml` публікує БД для IDE, тому не застосовується для демонстрації ізоляції. Docker-адміністратор усе ще має доступ до внутрішньої мережі.

```powershell
docker compose ps
docker compose logs -f lb
docker compose stop
```

## Архітектура

```mermaid
flowchart LR
    C[Клієнт / Swagger / k6] -->|HTTP localhost:8080| LB[HAProxy]
    subgraph network[Docker backend network]
        LB -->|HTTP 8080| A1[Java instance 1]
        LB -->|HTTP 8080| A2[Java instance 2]
        LB -.->|HTTP 8080 після scale-out| A3[Java instance 3]
        A1 -->|JDBC 5432| DB[(PostgreSQL)]
        A2 -->|JDBC 5432| DB
        A3 -->|JDBC 5432| DB
        DB --> V[(Named volume)]
    end
```

[`infra/haproxy.cfg`](../infra/haproxy.cfg) використовує Docker DNS `127.0.0.11` та 10 слотів `server-template`. Репліки знаходяться без ручного внесення IP. `X-Instance-ID` — hostname контейнера; після відтворення він може змінитися.

`GET /health` виконує `SELECT 1`: 200/UP при доступній БД, 503/DOWN при JDBC-помилці. HAProxy опитує вузли раз на секунду, виключає після двох невдалих перевірок та повертає після двох успішних; timeout probe — 2 с. Виявлення відмови не миттєве. Надмірно повільний health теж вважається невдалим.

При помилці встановлення з'єднання дозволено retry/redispatch на інший вузол. Уже відправлений POST автоматично не повторюється: API створення замовлення ще не має idempotency key. `X-Instance-ID` проходить через proxy без підміни.

## Розподіл запитів

Після запуску виконайте в PowerShell:

```powershell
1..30 | ForEach-Object {
    $r = Invoke-WebRequest http://localhost:8080/api/products -UseBasicParsing
    [string]$r.Headers['X-Instance-ID']
} | Group-Object | Select-Object Name,Count
```

При двох здорових репліках очікуються два ID. Round Robin розподіляє запити циклічно; коротка вибірка може відхилятися від 50/50 під час зміни пулу. Для Least Connections потрібні одночасні запити: послідовний цикл не демонструє його переваг.

## Round Robin проти Least Connections

Перед експериментами на поточній версії вимкніть кеш і ввімкніть профіль затримки:

```powershell
$env:CACHE_ENABLED = 'false'
$env:SPRING_PROFILES_ACTIVE = 'lab3'
docker compose up -d --no-deps --force-recreate --wait --scale app=2 app
```

Використовуйте однаковий каталог, VUs і тривалість. Заповніть каталог через Swagger, якщо він порожній. У PowerShell:

```powershell
$env:LB_ALGORITHM = 'roundrobin'
docker compose up -d --no-deps --force-recreate --wait lb
$nodes = @(docker compose ps -q app | ForEach-Object {
    docker inspect --format '{{.Config.Hostname}}' $_
})
$env:INSTANCE_IDS = $nodes -join ','
$env:SLOW_INSTANCE = $nodes[0]
$env:VUS = '20'
$env:DURATION = '10s'
$env:RUN_NAME = 'rr-warmup'
docker compose run --rm load
$env:DURATION = '30s'
$env:RUN_NAME = 'rr-slow'
docker compose run --rm load

$env:LB_ALGORITHM = 'leastconn'
docker compose up -d --no-deps --force-recreate --wait lb
$env:DURATION = '10s'
$env:RUN_NAME = 'lc-warmup'
docker compose run --rm load
$env:DURATION = '30s'
$env:RUN_NAME = 'lc-slow'
docker compose run --rm load
```

Перемикання відтворює лише балансувальник і може коротко перервати з'єднання; backend і БД працюють далі. Значення алгоритму: `roundrobin` або `leastconn`.

`LabLatencyFilter` працює тільки у профілі `lab3`. Заголовок `X-Lab-Slow-Instance` затримує GET каталогу на вибраному вузлі на 500 мс. Інші вузли, записи та `/health` не затримуються. Без заголовка затримки немає. Це повільна бізнес-операція на живому вузлі, а не відмова. Поза лабораторною профіль слід вимкнути.

Порівняйте `results/rr-slow.json` і `results/lc-slow.json`: RPS, avg/p95/p99, error rate та лічильники за ID. Warmup-файли не включайте в порівняння. Least Connections має спрямовувати менше запитів до зайнятого вузла; виграш p99 потрібно виміряти, він не гарантований. Пул реплік і ID протягом порівняння мають залишатися незмінними.

## Scale-out для 1, 2 та 3 реплік

```powershell
Remove-Item Env:SLOW_INSTANCE -ErrorAction SilentlyContinue
$env:LB_ALGORITHM = 'roundrobin'
docker compose up -d --no-deps --force-recreate --wait lb
foreach ($count in 1,2,3) {
    docker compose up -d --wait --no-deps --scale app=$count app
    $env:INSTANCE_IDS = (@(docker compose ps -q app | ForEach-Object {
        docker inspect --format '{{.Config.Hostname}}' $_
    })) -join ','
    $env:DURATION = '10s'
    $env:RUN_NAME = "scale-$count-warmup"
    docker compose run --rm load
    $env:DURATION = '30s'
    $env:RUN_NAME = "scale-$count"
    docker compose run --rm load
}
```

Warmup дає час DNS/health checks оновити пул і JVM прогрітися. При помилці будь-якої команди зупиніть порівняння. `--scale` додає JVM, а не фізичні CPU. k6 також працює на цьому хості: це навчальне порівняння, не production capacity benchmark. Залишайте однакові VUs та дані.

Заповніть таблицю реальними даними:

| Прогін | Репліки | RPS | p99, мс | Error rate | Розподіл за ID |
|---|---|---|---|---|---|
| rr-slow | 2 | — | — | — | — |
| lc-slow | 2 | — | — | — | — |
| scale-1 | 1 | — | — | — | — |
| scale-2 | 2 | — | — | — | — |
| scale-3 | 3 | — | — | — | — |

Прискорення `S(N) = RPS(N) / RPS(1)`, ефективність `E(N) = S(N) / N`. Відсутність прискорення теж є результатом, який треба пояснити.

## Демонстрація відмови

Потрібні щонайменше дві живі репліки. У першому терміналі запустіть читання:

```powershell
Remove-Item Env:SLOW_INSTANCE -ErrorAction SilentlyContinue
$env:DURATION = '60s'
$env:RUN_NAME = 'node-failure'
$env:REQUIRE_NO_ERRORS = 'true'
docker compose run --rm load
```

Поки тест працює, у другому терміналі з кореня проєкту:

```powershell
$victim = @(docker compose ps -q app)[0]
try {
    docker stop $victim
    Start-Sleep -Seconds 10
    docker compose logs --tail 30 lb
} finally {
    docker start $victim
}
```

Graceful stop, health checks і перепідключення покликані продемонструвати продовження GET без 5xx. Поріг k6 `business_errors == 0` завершить тест помилкою, якщо це не виконано. Перевірте JSON, зміни розподілу й DOWN/UP у логах. Після експерименту приберіть `REQUIRE_NO_ERRORS` із середовища для звичайних замірів.

Додатково можна застосувати `docker kill --signal KILL $victim`: активний HTTP-запит може обірватися. Розрізняйте graceful stop, SIGKILL та інтервал виявлення відмови; безумовну відсутність помилок при жорсткій аварії конфігурація не гарантує.

## Наступні вузькі місця

| Компонент | Причина | Що спостерігати |
|---|---|---|
| PostgreSQL | Усі JVM звертаються до однієї БД | DB CPU, I/O wait, SQL latency, плато RPS |
| Пули з'єднань | N × `DB_POOL_SIZE` збільшує кількість з'єднань | Hikari wait/timeouts, `pg_stat_activity`, ліміт БД |
| Популярні товари | `FOR UPDATE` серіалізує checkout між JVM | `pg_locks`, lock wait, p99 запису |
| HAProxy / мережа / хост | Спільні CPU та мережа, один proxy | CPU proxy/хосту, connection rate, навантаження генератора |

Це гіпотези. GET-тест каталогу не доводить bottleneck блокувань checkout. Висновок робіть за реальними результатами й ресурсними метриками (`docker stats`, статистика PostgreSQL).

Балансувальник залишається single point of failure. Можливе продовження — два proxy та VIP із Keepalived/VRRP або керований балансувальник. Простий DNS Round Robin сам по собі не забезпечує швидкого failover через кешування DNS і поведінку клієнтів.

Покрито вимоги: пул реплік, єдина точка входу, два алгоритми, асиметрична затримка, `/health`, вилучення/повернення вузлів, сценарії 1/2/3, метрики й аналіз обмежень. Вимірювання і заповнення таблиці ще потрібно виконати.

Java-перевірка: `.\mvnw.cmd verify` (JDK 21 + Docker). Існуючі тести API/транзакцій/stateless не замінюють HAProxy/k6 експерименти. `scripts/demo.ps1` працює через єдиний порт; старий `demo-stateless.ps1` і `docs/stateless.http` із двома прямими портами належать до попередньої гілки.

Довідка: [HAProxy 3.0 configuration manual](https://docs.haproxy.org/3.0/configuration.html).

