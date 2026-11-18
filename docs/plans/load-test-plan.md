# План: нагрузочное тестирование (NF08)

Основание: спека `docs/specs/load-test.md` (черновик 18.11.2026; вопросы §10 решены). Ветка `load-test` от `main`.
Один шаг — один коммит.

## 0. Исходное состояние и как проверяется

Уже есть (вне git, войдёт в шаг 1):

- `Makefile`: `K6_IMAGE := grafana/k6:2.3.0`, `LOAD_PROJECT`, `LOAD_COMPOSE` (проект `exchange-load`, порты 15432 /
  18080 / 18081 / 13000), команда `K6` (`docker run` в сети `exchange-load_default`, `load/` → `/scripts`,
  `BASE_URL`, `CARRIER_URL`), цели `load-up`, `load-down`, `load-reset`, `load-install`, `load-typecheck`,
  `load-hello`.
- `load/`: `package.json` (pnpm 10.15.1, `typescript ~6.0.3`, `@types/k6 2.3.1`), `pnpm-lock.yaml`,
  `tsconfig.json`, `hello.ts`, `README.md`, `.gitignore`.
- Код `dispatch`, эмулятора, контракты, миграции и web не меняются. Исключение — шаги по §8 спеки, если прогон не
  уложится в цель; они добавляются в план, когда станет ясна причина.

**Тесты.** Нагрузочные скрипты — инструмент стенда, а не бизнес-логика: своих тестов у них нет (спека §10 п. 2).
Правило «сначала красный тест» заменяется ожиданием на стенде, записанным в шаге **до** кода: что запустить и что
увидеть.

Проверки каждого шага, кроме docs:

- `make load-typecheck` — без ошибок;
- для `load/load-test.sh` — ShellCheck в Docker, как в `demo-seed`:
  `docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:v0.10.0 load/load-test.sh`;
- запуск на стенде, указанный в шаге. «Чистый стенд» — `make load-reset && make load-up` при остановленном
  демонстрационном (`make down`).

Порог `./gradlew build` + `make e2e` (и тесты по TDD) — только для шагов, которые меняют `dispatch` (§8 спеки).

## Шаг 1. Документы и заготовка

- Спека (статус «согласована»), этот план и уже сделанная заготовка k6 (`Makefile`, `load/`, строки в
  `CLAUDE.md`) — одним коммитом.

## Шаг 2. Набор: `make load-seed`

**Ожидание:** на чистом стенде `make load-seed PROFILE=smoke` создаёт 200 заявок, все получают итог (~90 %
`SUCCEEDED`, ~10 % `REJECTED`), печатается итог, ошибок 0. Повторный запуск — отказ с подсказкой `make
load-reset`, ничего не создано. Сейчас цели нет.

**Код:**
- `tsconfig.json` → `tsconfig.k6.json` (корень и `lib/`, типы `k6`); `typecheck` в `package.json` — по нему
  (`tsconfig.node.json` добавляется в шаге 4).
- `config.ts` — профили `full` / `smoke` и `__ENV` (спека §5.0).
- `lib/content.ts` — генератор (mulberry32 с фиксированным зерном), словари, срок от UTC-даты `serverTime`,
  ключи `load-seed-<i>` и `load-<run>-<vu>-<iter>` (спека §4).
- `lib/session.ts` — банка cookie на VU, вход (`csrf` → `login`), `X-XSRF-TOKEN` из текущей cookie.
- `lib/api.ts` — `systemInfo`, `listRequests`, `getCard`, `createRequest`, `patchRequest`; свои типы ответов в
  нужном объёме; теги `kind` / `op`; неожиданный ответ → `Counter` `errors` с тегами `op`, `status`, `code`.
- `seed.ts` — `setup()`: вход под обоими инициаторами, отказ на непустом стенде, `serverTime`; итерация —
  создание `i`-й заявки (`shared-iterations`); `teardown()`: ожидание итогов (до 15 мин), правки (комментарий у
  20 %, исполнитель у 20 % «Севера» под `north.operator`), печать итога. Порог `errors` = 0.
- `Makefile`: `load-seed` (`PROFILE` по умолчанию `full`).

**Проверка:** ожидание выше; в UI нагрузочного стенда (`:13000`) у заявок настоящая история обмена; затем
`make load-reset && make load-up && make load-seed` — 5000 заявок, время создания и до итогов записать.

## Шаг 3. Прогон: `run.ts`, `make load-smoke`

**Ожидание:** на чистом стенде `make load-smoke` (набор `smoke` + прогон `smoke`) завершается с кодом 0, ошибок 0;
в сводке есть `http_req_duration{phase:run,kind:read}` / `{…kind:write}` и подметрики четырёх операций; число
операций каждого вида у клиента отличается не больше чем на 1; запросы входа и `setup()` не в `phase:run`. Сейчас
цели нет.

**Код:**
- `lib/shares.ts` — номер VU → пользователь и доля (спека §5.1).
- `run.ts` — `options` (спека §5.0, §5.4), `http.setResponseCallback(http.expectedStatuses(200, 201))`;
  `setup()`: время начала, заявки обоих инициаторов (`size=100`, `sort=number,asc`), число страниц по
  `SUCCEEDED` / `REJECTED`, `serverTime`, падение при пустой доле; `default`: вход в первой итерации, тег
  `phase`, четыре операции (спека §5.2); `handleSummary` — `out/summary.json` (с окном прогона) и вывод в консоль.
- `Makefile`: `load-smoke` (`load-seed` и `run.ts` с `PROFILE=smoke`, `RUN_ID`, `--out json=/scripts/out/requests.json`).
- `hello.ts` и `load-hello` удаляются.

**Проверка:** ожидание выше; один раз — `LOAD_PASSWORD=wrong make load-smoke`: k6 падает, ошибки посчитаны с
причиной `401 UNAUTHENTICATED`, а не пропали.

## Шаг 4. Отчёт и `make load-test`

**Ожидание:** после `make load-smoke` команда `make load-test PROFILE=smoke` пишет `load/out/report.md` со всеми
восемью разделами спеки §7, числа совпадают со сводкой k6; при запущенном демо-стенде — отказ с понятным
сообщением; включённый заранее режим сбоя (`PUT :18081/admin/faults`) выключен; фоновый `docker stats` после
выхода (и после `Ctrl+C`) не работает. Сейчас цели нет.

**Код:**
- `report/` (Node, `tsconfig.node.json`, `@types/node` как в `web/`; `typecheck` проверяет обе конфигурации):
  разбор `requests.json` (только `phase:run`; группы, операции, ошибки по `op` / `status` / `code`), процентили,
  время до исполнения из CSV (три интервала спеки §6), `docker stats` (среднее и пик), лимиты из `docker compose
  config`, сборка `report.md`; нет необязательной части — раздел «нет данных».
- `load/load-test.sh`: отказ при запущенном проекте `exchange`; здоровье стенда; режимы сбоев выключены; нет
  открытых проблем у набора; `out/env.json` (коммит, хост, ВМ Docker, версии, `docker compose config`); `docker
  stats` раз в 5 с в фоне под `trap`; k6 `run.ts` (код выхода запоминается); ожидание исполнения созданного (до
  15 мин); выгрузка `out/exec-times.csv` (`created_at`, `acknowledged_at`, `result_at`, `processed_at` через
  `applied_inbox_id`); сборщик; выход с кодом k6.
- `Makefile`: `load-test` (`PROFILE` по умолчанию `full`).

**Проверка:** ожидание выше; ShellCheck без предупреждений.

## Шаг 5. Полный прогон и отчёт

- `make down; make load-reset && make load-up && make load-seed && make load-test`.
- Пороги выполнены — `load/out/report.md` копируется в `docs/load-report.md`, дописываются выводы: запас до
  порогов, что ограничивает (по `docker stats`), очередь отправки (спека §6).
- Не выполнены — работа по §8 спеки: причина по замерам (`EXPLAIN (ANALYZE, BUFFERS)` на наборе, CPU по
  контейнерам, пул). Каждое исправление — отдельный шаг, вставляемый в план, по TDD (например, миграция с индексом
  + тест в `SchemaMigrationTests`; порог `./gradlew build` и `make e2e`); затем прогон заново, в отчёт —
  «до / после».
- Два прогона подряд на свежем стенде дают близкие результаты.

## Шаг 6. Документация

- README: раздел «Нагрузка» — команды, профиль, API и поле фильтра, параметры среды и лимиты, требования к машине
  (ВМ Docker ≥ 4 CPU, Node ≥ 22.6 и pnpm), ссылка на `docs/load-report.md`.
- `CLAUDE.md`: строка про `load/` и команды; `load/README.md` — по итогам.
- `docs/PLAN.md` этап 2 п. 8 — отметка о готовности со ссылками.
- Спека — статус «реализовано», отличия реализации, если есть.
