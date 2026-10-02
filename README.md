# Система замовлень для лабораторної роботи №1

Java-проєкт для роботи «Проєктування архітектури та реалізація базового прототипу». Система керує клієнтами, каталогом і замовленнями: перевіряє залишки, обчислює вартість, атомарно списує товари та повертає їх при скасуванні. Дані зберігаються в PostgreSQL і переживають перезапуск контейнерів.

Стек: **Java 21, Spring Boot 3.5.15, Spring JDBC, PostgreSQL 17.4, Flyway, Maven 3.9.9, Docker Compose, OpenAPI/Swagger UI**. Це модульний моноліт із REST API. Бізнес-дані не зберігаються в пам'яті процесу. Усі суми — в гривнях (UAH).

## Швидкий запуск

Потрібні Docker Engine / Docker Desktop у режимі Linux containers і Docker Compose v2 із підтримкою `--wait`. Для цього способу Java та Maven на хості не потрібні. Перший запуск завантажує образи й залежності з інтернету.

```shell
docker compose up --build -d --wait
```

Команда збирає backend, запускає PostgreSQL, чекає готовності БД, застосовує Flyway-міграцію і перевіряє готовність API. Порожня база готова до використання; ручне створення таблиць не потрібне.

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Перевірка застосунку та БД: <http://localhost:8080/actuator/health>

```shell
docker compose ps
docker compose logs -f app
docker compose stop
docker compose start
```

Якщо порт 8080 зайнятий, скопіюйте `.env.example` у `.env` і змініть `APP_PORT`. Для стандартного запуску `.env` не потрібен. PostgreSQL у базовій конфігурації не публікує порт на хості. HTTP прив'язаний до `127.0.0.1`. Пароль за замовчуванням призначений для локальної лабораторної; автентифікація та публічне розгортання не входять у цей MVP.

## Демонстрація для захисту

У PowerShell із кореня проєкту:

```powershell
.\scripts\demo.ps1 -VerifyPersistence
```

Скрипт створює клієнта й товар з унікальними значеннями, читає каталог, змінює ціну, оформлює замовлення, перевіряє списання та суму, **перезапускає контейнер БД**, повторно читає замовлення, двічі скасовує його і перевіряє одноразове повернення залишків. На завершення прибирає товар із каталогу та перевіряє збереження історії. Помилка перевірки завершує скрипт винятком. Створені демонстраційні дані залишаються в БД.

Для власного порту:

```powershell
.\scripts\demo.ps1 -BaseUrl http://localhost:8081 -VerifyPersistence
```

Без автоматичного перезапуску БД використовуйте `.\scripts\demo.ps1`. Інтерактивні приклади для IntelliJ IDEA знаходяться в [`docs/api.http`](docs/api.http). Також усі endpoints доступні через Swagger `Try it out`.

План захисту:

1. Показати холодну збірку та запуск командою `docker compose up --build -d --wait` на новому checkout.
2. Відкрити Swagger і пояснити зв'язки сутностей на ER-діаграмі нижче.
3. Продемонструвати щонайменше п'ять endpoints через Swagger, HTTP Client або `demo.ps1`.
4. Показати `409` при нестачі товару і `400` при невалідній кількості.
5. Перезапустити БД та прочитати те саме замовлення за його ID.
6. Пояснити транзакцію оформлення, порядок блокувань і потенційні bottlenecks.

## Покриття вимог лабораторної

| Вимога | Реалізація |
|---|---|
| Мінімум 3 інтегровані сутності | Customer, Product, Order, OrderItem; зовнішні ключі в PostgreSQL |
| Мінімум 5 бізнесових endpoints | 12 REST endpoints: клієнти, каталог, замовлення |
| CRUD і бізнес-логіка | CRUD товарів, поповнення, атомарне оформлення та скасування замовлень |
| Валідація та обробка помилок | Jakarta Validation, обмеження БД, Problem Details, HTTP 400/404/409/503 |
| Постійне сховище | PostgreSQL, named volume `postgres-data`, без in-memory БД |
| Міграції | `src/main/resources/db/migration/V1__create_order_schema.sql`, Flyway при запуску |
| Запуск однією командою | `compose.yaml`, multi-stage `Dockerfile`, health checks, окрема Docker network |
| Архітектурна схема й опис API | Діаграми нижче, таблиця endpoints, `/v3/api-docs`, Swagger |
| High-load сценарій | Одночасне оформлення замовлень на обмежений залишок популярних товарів |
| Мінімум 3 вузькі місця | П'ять розібраних ризиків у таблиці нижче |
| Розподіл відповідальності | Таблиця модулів наприкінці README |

## Доменна модель

```mermaid
erDiagram
    CUSTOMERS ||--o{ ORDERS : places
    ORDERS ||--|{ ORDER_ITEMS : contains
    PRODUCTS ||--o{ ORDER_ITEMS : references
    CUSTOMERS {
        uuid id PK
        varchar name
        varchar email UK
        timestamptz created_at
    }
    PRODUCTS {
        uuid id PK
        varchar sku UK
        varchar name
        numeric price
        bigint stock
        boolean active
    }
    ORDERS {
        uuid id PK
        uuid customer_id FK
        varchar status
        numeric total
        timestamptz created_at
    }
    ORDER_ITEMS {
        uuid id PK
        uuid order_id FK
        uuid product_id FK
        varchar product_name
        numeric unit_price
        integer quantity
    }
```

- **Customer**: ім'я та унікальна email-адреса; email нормалізується до нижнього регістру.
- **Product**: унікальний SKU у верхньому регістрі, опис, ціна, залишок та ознака доступності.
- **Order**: замовлення одного клієнта, статус `PLACED` або `CANCELLED`, загальна вартість і час.
- **OrderItem**: товар, кількість, зафіксовані назва й ціна на момент оформлення. Зміни каталогу не змінюють історію.

Обмеження: 1–50 різних товарів у замовленні, 1–10000 одиниць кожного товару; повторення одного товару в запиті заборонено. Ціна додатна, максимум 10 цифр до коми та 2 після; розрахунок через `BigDecimal` і PostgreSQL `NUMERIC`. Початковий залишок 0–1 000 000 000; одне поповнення 1–1 000 000 000. У БД залишок має тип `BIGINT` і не може бути від'ємним.

## Архітектура

Схема контейнерів у стилі C4 показує межу системи, протоколи та напрямки запитів:

```mermaid
flowchart LR
    client["Користувач / Swagger / HTTP Client"]
    subgraph system["Система замовлень — Docker Compose"]
        app["Backend container\nJava 21 / Spring Boot\nREST API та бізнес-логіка"]
        db[("Database container\nPostgreSQL 17\nКлієнти, товари, замовлення")]
        volume[("Named volume\npostgres-data")]
        app -->|"JDBC / PostgreSQL TCP 5432\nSQL, транзакції, Flyway"| db
        db -->|"Файлові операції / durable storage"| volume
    end
    client -->|"HTTP / JSON TCP 8080"| app
```

Компоненти backend:

```mermaid
flowchart TD
    controllers["CustomerController / ProductController / OrderController"]
    validation["DTO + Jakarta Validation"]
    services["CustomerService / ProductService / OrderService"]
    repositories["CustomerRepository / ProductRepository / OrderRepository"]
    db[(PostgreSQL)]
    errors["ApiExceptionHandler\nProblem Details"]
    controllers --> validation
    controllers --> services
    services -->|"Транзакції Spring"| repositories
    repositories -->|"Параметризований SQL / JdbcClient"| db
    controllers -.-> errors
```

Модульний моноліт достатній для поточного домену: дозволяє оформити замовлення і списати залишки однією транзакцією БД. Пакети розділені за предметними модулями, всередині яких є controller → service → repository. JDBC обраний для явних SQL-запитів і блокувань; ORM та прихованого lazy loading немає. Зовнішніх інтеграцій і черг у першій лабораторній немає.

### Транзакція оформлення

1. Перевірити клієнта та унікальність позицій запиту.
2. Одним `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE` заблокувати потрібні товари в узгодженому порядку.
3. Перевірити існування, доступність і залишки всіх товарів.
4. Розрахувати суму за актуальними цінами БД, створити замовлення, списати залишки й записати позиції.
5. Зробити commit; при помилці відкотити всі зміни через `@Transactional`.

Конкурентний покупець чекає блокування та після його отримання бачить актуальний залишок. Оновлення товару й поповнення використовують ті самі блокування. Для скасування спочатку блокується замовлення, потім товари в тому самому порядку; повторне скасування вже `CANCELLED` не повертає товар вдруге. Блокування реалізовані в PostgreSQL, а не через `synchronized` у JVM.

POST створення замовлення не має idempotency key: повторно надісланий успішний запит створить інше замовлення. Якщо клієнт втратив відповідь після commit, автоматичний повтор не гарантує відсутності дубля. Це задокументоване обмеження MVP. Скасування за конкретним ID повторювати безпечно.

## Специфікація API

Базовий шлях `/api`. Формат запитів і відповідей — JSON. Успішний POST створення повертає `201 Created` та заголовок `Location`.

| Метод | Шлях | Операція | Успіх |
|---|---|---|---|
| POST | `/api/customers` | Створити клієнта | 201 |
| GET | `/api/customers/{id}` | Прочитати клієнта | 200 |
| POST | `/api/products` | Створити товар | 201 |
| GET | `/api/products?page=0&size=20` | Каталог активних товарів | 200 |
| GET | `/api/products/{id}` | Прочитати товар | 200 |
| PUT | `/api/products/{id}` | Замінити редаговані поля: назву, опис, ціну | 200 |
| DELETE | `/api/products/{id}` | Деактивувати товар | 204 |
| POST | `/api/products/{id}/restock` | Поповнити залишок | 200 |
| POST | `/api/orders` | Оформити замовлення | 201 |
| GET | `/api/orders/{id}` | Замовлення з позиціями | 200 |
| GET | `/api/orders?customerId={id}&page=0&size=20` | Історія замовлень клієнта | 200 |
| POST | `/api/orders/{id}/cancel` | Скасувати замовлення | 200 |

`DELETE` є логічним видаленням: товар зникає з каталогу, GET товару повертає 404, нове замовлення на нього відхиляється, але посилання та історія залишаються. Повторний DELETE існуючого деактивованого товару повертає 204. SKU залишається зарезервованим. PUT не змінює SKU або залишок; для залишку є окрема бізнес-операція.

Списки мають форму `{"items": [], "page": 0, "size": 20, "hasNext": false}`. Межі: `page` 0–10000, `size` 1–100. Виконується вибірка `size + 1` рядків без дорогого `COUNT(*)`. Порядок — час створення за спаданням, потім UUID; offset-пагінація не гарантує незмінності сторінок при конкурентних вставках.

### Приклади cURL

Наведений синтаксис — для Bash; у Windows використовуйте `demo.ps1`, IntelliJ HTTP Client або Swagger. Після перших двох запитів підставте отримані UUID замість `CUSTOMER_ID` і `PRODUCT_ID`.

```bash
curl -i -X POST http://localhost:8080/api/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Olena","email":"olena@example.com"}'

curl -i -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"sku":"BOOK-001","name":"System Design","description":"Book","price":800.00,"stock":10}'

curl 'http://localhost:8080/api/products?page=0&size=20'

curl -i -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUSTOMER_ID","items":[{"productId":"PRODUCT_ID","quantity":2}]}'

curl http://localhost:8080/api/orders/ORDER_ID
curl -X POST http://localhost:8080/api/orders/ORDER_ID/cancel
```

### Помилки

| Код | Приклади |
|---|---|
| 400 | Невалідний JSON/UUID/email, порожнє замовлення, від'ємна чи дробова кількість, дубль позиції, невідоме поле |
| 404 | Відсутній клієнт/товар/замовлення; деактивований товар при GET |
| 409 | Зайнятий email/SKU, недостатній залишок, оформлення деактивованого товару, порушення обмежень даних |
| 503 | Таймаут блокування, недоступна операція JDBC або перевантаження БД |

Приклад `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Insufficient stock for product: BOOK-001",
  "instance": "/api/orders"
}
```

Помилки Bean Validation додатково містять масив `errors` із `field` та `message`. Внутрішній SQL не включається у відповіді API.

## Збереження даних

Flyway створює схему та індекси. Ключі UUID, зовнішні ключі, `UNIQUE` і `CHECK` захищають цілісність на рівні БД. Складені індекси підтримують каталог та історію замовлень, індекс унікальності позицій — пошук позицій замовлення. Файли PostgreSQL знаходяться в named volume, окремому від контейнера.

```shell
docker compose restart db
docker compose ps
```

Після відновлення `health` той самий GET замовлення повертає збережені дані. Під час самого перезапуску є коротка недоступність: цей прототип має одну БД і не обіцяє high availability. `docker compose down` залишає named volume; **`docker compose down -v` видаляє дані**, тому для перевірки persistence його не використовуйте. Зміна `DB_PASSWORD` у `.env` не змінює пароль уже ініціалізованої БД.

## High-load сценарій

Пікова ситуація — старт розпродажу: багато клієнтів одночасно викликають `POST /api/orders`, купуючи 1–50 позицій, зокрема один популярний товар з обмеженим запасом. Це навантажує запис у БД, WAL, пул з'єднань і row-level locks. До одного замовлення входять читання клієнта, блокування товарів, запис замовлення та по одному оновленню залишку і вставці позиції на кожен товар.

Для `N` позицій поточний checkout виконує приблизно **3 + 2N SQL statements**, не враховуючи керування транзакцією. Сценарій навмисно має зрозумілу реалізацію для першої лабораторної; пакетні записи й оптимізація запитів можуть бути наступними кроками після вимірювання.

## Аналіз потенційних вузьких місць

Наведені висновки є теоретичними гіпотезами, а не результатами навантажувального тесту. Числові RPS/p99 у першій лабораторній не вигадуються.

| Компонент / операція | Причина | Очікувані симптоми та метрики | Як перевірити / напрям оптимізації |
|---|---|---|---|
| PostgreSQL, checkout популярного товару | Конкуренція за `FOR UPDATE` одного рядка; запити на цей товар серіалізуються | Зростання p95/p99, lock wait, HTTP 503 при timeout, RPS перестає зростати | `pg_stat_activity`, `pg_locks`; скоротити транзакцію, зменшити SQL round trips |
| HikariCP, увесь API | Максимум 10 з'єднань; повільні транзакції утримують пул | Очікування з'єднання, connection timeout, висока tail latency навіть простих GET | Метрики active/pending connections; спершу знайти повільні SQL, потім узгодити pool із лімітом БД |
| PostgreSQL, запис замовлень | WAL/fsync та оновлення індексів створюють I/O-навантаження | Високий disk latency/I/O wait, commit latency, плато write RPS | Метрики диска й БД; швидший диск, batch inserts, контроль кількості індексів |
| Backend ↔ DB, багатопозиційне замовлення | `3 + 2N` послідовних SQL statements усередині транзакції | Latency зростає з кількістю позицій; довше утримуються locks і connections | Порівняти 1/10/50 позицій; batch для вставок, переглянути повернення даних після UPDATE |
| Каталог, великі обсяги читань та глибокі сторінки | Кожен GET читає БД; OFFSET пропускає багато рядків | DB CPU/read I/O, дорожчі пізні сторінки, зростання p99 | `EXPLAIN (ANALYZE, BUFFERS)`; keyset pagination та кеш read-intensive запитів у наступних роботах |

Єдині backend і PostgreSQL також є точками відмови. У цій роботі це прийняте спрощення; горизонтальне масштабування, балансувальник і Redis належать до наступних лабораторних.

## Тести та локальна розробка

Для запуску поза контейнером потрібні JDK 21 та Docker. Maven завантажується через Wrapper.

```powershell
# Швидкі тести обчислень без Docker
.\mvnw.cmd test

# Повна збірка та інтеграційні тести з реальною PostgreSQL у Testcontainers
.\mvnw.cmd verify
```

У Linux/macOS: `sh mvnw test` і `sh mvnw verify`. Інтеграційні тести названі `*IT` і запускаються Maven Failsafe на фазі `verify`. Docker потрібен для повної перевірки; тести не пропускаються мовчки за його відсутності. Testcontainers створює окрему тимчасову БД, не використовує дані Compose і видаляє свої контейнери після тестів.

Перевіряються CRUD/пагінація, Location headers, валідація й коди помилок, конфлікти email/SKU, історичні ціни, транзакційний rollback після примусової помилки БД, конкурентна купівля останніх одиниць, одноразове повернення складу при конкурентних скасуваннях, Flyway, health і OpenAPI. Persistence після рестарту перевіряє `demo.ps1 -VerifyPersistence`.

Фактична перевірка 02.10.2026: `mvnw.cmd verify` — **14 тестів успішно, 0 помилок, 0 пропусків** (2 unit + 12 integration); `docker compose up --build -d --wait` — обидва сервіси healthy; `demo.ps1 -VerifyPersistence` — PASS, замовлення, позиції та залишок збережено після перезапуску PostgreSQL. Це функціональна перевірка, не вимірювання продуктивності.

Локальний backend з БД у Docker:

```powershell
docker compose stop app
docker compose -f compose.yaml -f compose.dev.yaml up -d --wait db
.\mvnw.cmd spring-boot:run
```

Стандартний JDBC URL — `jdbc:postgresql://localhost:5432/orders`. Якщо змінено пароль або порт БД, перед запуском Java задайте `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` у середовищі IDE/термінала. Spring Boot самостійно не читає `.env`, його читає Compose.

| Змінна | Типове значення | Призначення |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/orders` | Адреса БД; Compose використовує host `db` |
| `DB_USERNAME` | `orders` | Користувач БД |
| `DB_PASSWORD` | `lab1-local-password` | Пароль локальної БД |
| `DB_POOL_SIZE` | `10` | Ліміт з'єднань застосунку |
| `PORT` | `8080` | HTTP-порт Java-процесу |
| `APP_PORT` | `8080` | Опублікований порт Compose |

## Структура проєкту

```text
src/main/java/ua/edu/highload/
  OrderApplication.java
  customer/       контролер, сервіс, repository, DTO клієнта
  catalog/        каталог, редагування та складські операції
  order/          оформлення, історія і скасування замовлень
  common/         помилки API, пагінація, OpenAPI
src/main/resources/
  application.yaml
  db/migration/V1__create_order_schema.sql
src/test/java/ua/edu/highload/
  OrderApiIT.java
  order/OrderItemTest.java
docs/api.http
scripts/demo.ps1
compose.yaml
compose.dev.yaml
Dockerfile
pom.xml
mvnw / mvnw.cmd
```

## Розподіл відповідальності

Для індивідуального виконання всі модулі належать виконавцю лабораторної. Перед поданням впишіть своє ПІБ; якщо робота командна — замініть ролі фактичними іменами та реальним розподілом. Авторство інших учасників тут не припускається.

| Модуль / результат | Відповідальний при індивідуальному виконанні |
|---|---|
| Доменна модель, архітектура, аналіз bottlenecks | Виконавець лабораторної |
| Customers, catalog, API та валідація | Виконавець лабораторної |
| Замовлення, транзакції та робота зі складом | Виконавець лабораторної |
| PostgreSQL, міграції, Docker Compose | Виконавець лабораторної |
| Тести, документація та демонстрація | Виконавець лабораторної |

## Технічні джерела

- [Spring Boot 3.5 reference](https://docs.spring.io/spring-boot/3.5/reference/index.html)
- [springdoc OpenAPI для Spring Boot 3](https://springdoc.org/v2/)
- [PostgreSQL explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html)

Версії Java-залежностей зафіксовані у `pom.xml` і Spring Boot BOM, Maven — у Wrapper, базові образи — тегами у Dockerfile/Compose. Теги образів не закріплені digest, тому це відтворюваний навчальний запуск, а не побітово ідентична збірка.
