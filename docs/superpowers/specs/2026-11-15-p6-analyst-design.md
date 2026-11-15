# P6 «Аналитик» — дизайн

**Дата:** 2026-11-15 · **Статус:** утверждён пользователем в запросе «реализуй все пункты»
**База:** 1.1.0, ветка `phase3-native` (52 узла, 30 инструментов MCP)
**Связанные документы:** [ROADMAP.md](../../ROADMAP.md), [NODES.md](../../NODES.md),
[MCP.md](../../MCP.md), [API.md](../../API.md), [QUALITY.md](../../QUALITY.md)

## 1. Задача

Закрыть семь дефицитов, перечисленных пользователем, и заметки практического прогона:

1. Глубина анализа: p-value/частные корреляции, post-hoc и непараметрика, множественная
   регрессия/логистика, DBSCAN/LOF/изоляционный лес, кластеризация, PCA,
   bootstrap/permutation, выживаемость, A/B с мощностью, декомпозиция/ACF/прогноз/
   стационарность временного ряда.
2. Универсальность данных: мультифайл/glob/каталог, full/semi/anti/asof/cross join,
   SQL на 3+ входах, каталог датасетов с описанием/версией/кэшем профиля, нормализация
   текста, fuzzy-join, гео-расстояние, таймзоны/календарь, запись в БД.
3. Масштаб: out-of-core по умолчанию (Parquet-план без загрузки в heap, spill, потоковый
   ingest больших CSV).
4. Агентская эффективность: пагинация/проекция/фильтр/компактный формат таблиц, оценка
   стоимости до запуска, progress-нотификации, батч SQL, `dap_explore`, ресурс
   `dap://datasets`.
5. Визуализация: подписи осей/единицы/видимый заголовок, запас по Y, сортировка bar по
   значению, pie/boxplot/heatmap, facets, карта мира, несколько графиков в одном файле,
   HTML/PDF/PPTX.
6. Отчёты: `markdown_report` с несколькими входами и форматами md/html/pdf/pptx.
7. Безопасность: allowlist путей для SQL и файловых узлов, чтобы агент не читал
   `~/.ssh` и `~/.dsh/.credentials.yaml`.

## 2. Границы

**Входит:** перечисленное выше, тесты (JUnit + Python), goldens, документация,
обратная совместимость существующих вызовов.

**Не входит и почему:**
- ORC/Avro: в DuckDB 1.5.6 для darwin/arm64 нет расширения `orc` (HTTP 404), а Avro
  не поддерживается ядром. `dap.file_reader` принимает csv/parquet/json; для ORC/Avro
  возвращается ошибка с подсказкой конвертировать. Это зафиксировано в NODES.md.
- Полноценный spatial-extension (`ST_*`): не нужен для карты; расстояние считается
  haversine в SQL, карта рисуется в движке. Spatial можно добавить отдельной задачей.
- Инкрементальное обновление Parquet: каталог хранит версии (content hash, rows,
  updatedAt) и `refresh`, но не делает merge/upsert. Для файловых источников версия —
  это новый полный снимок.
- Auth/multi-tenancy/облако — вне продукта.

## 3. Совместимость

- Существующие id узлов, инструменты и аргументы сохраняются. Новые поля — опциональные,
  новые значения enum — аддитивные.
- Меняются выходные схемы `correlation`, `hypothesis_test`, `linear_regression`,
  `outlier_detect`: добавляются колонки. Это осознанное изменение P6; goldens и NODES.md
  обновляются, в CHANGELOG фиксируется.
- `dap.chart` меняет дефолт сортировки bar на «по значению (desc)»; golden/SVG-тесты
  обновляются. Явный `sort: "label"` возвращает прежнее поведение.
- `dap.markdown_report` сохраняет проходной выход порта 0 и формат md по умолчанию.

## 4. Архитектура

Движок остаётся «SQL-планы над DuckDB + Arrow», без новых зависимостей. Статистические
узлы, которым нужны матрицы (регрессия, PCA, кластеры), читают ограниченное число строк
в Java (cap по строкам) и возвращают `LazyPlan` с литералами результата — как уже делает
`hypothesis_test`. Тяжёлые сканы (profile, корреляции, группировки) остаются SQL.

Новые компоненты:

| Компонент | Пакет | Ответственность |
|---|---|---|
| `PathGuard` | `io` | allowlist/denylist путей, проверка SQL-литералов |
| `DatasetCatalog` | `io` | persist-каталог датасетов (JSON, atomic write), версии, кэш профиля |
| `ExploreService` | `explore` | профиль + аномалии + связи + гипотезы одним проходом |
| `ReportWriter` | `report` | md/html/pdf/pptx из секций и таблиц |
| `ChartRenderer` | `viz` | общий рендер SVG/PNG для chart и chart_grid |
| `Matrix`/`Stats` | `engine` | линейная алгебра, квантили, p-value, studentized range |
| `ProgressListener` | `exec` | прогресс узлов job → `/v1/jobs` → MCP notifications |

## 5. Контракты движка (`/v1`)

### 5.1 `GET /v1/workflows/{wf}/nodes/{node}/table`

Новые query-параметры (все опциональные):

| Параметр | Тип | Смысл |
|---|---|---|
| `offset` | int ≥ 0 | пропустить N строк после фильтра |
| `columns` | csv | проекция: имена через запятую; неизвестное имя — 400 `unknown_column` |
| `where` | string | SQL-предикат над выходными колонками; запрещены `;`, `--`, `/*`, `SELECT`, `FROM`, `read_`; ошибка — 400 `invalid_filter` |

Ответ дополняется `offset`, `nextOffset` (null если страница последняя), `truncated`.
`rows` — число строк после фильтра. Проекция меняет `columns`/порядок значений.

### 5.2 `GET /v1/workflows/{wf}/nodes/{node}/estimate`

Выполняет `EXPLAIN (FORMAT JSON)` над планом узла. Ответ:
`{node, rows, operators:[{name, rows}], plan}`. `rows` — оценка корневого оператора;
для reader — оценка сканирования. 409 `not_materialized`, если у узла нет плана.

### 5.3 `GET /v1/jobs/{id}` — прогресс

В ответ добавлен `progress`: `{nodesTotal, nodesDone, currentNode, percent}`.
Job обновляется Dag-ом на старте/финише каждого узла.

### 5.4 `POST /v1/explore`

Тело: `{file?|dataset?|format?, sql?, maxColumns?, sampleRows?}` (ровно один источник).
Ответ:

```json
{
  "source": "...", "rows": 150,
  "columns": [{"name":"...","type":"Float64","missing":0,"distinct":35,
               "min":0.1,"max":7.9,"mean":3.7,"stddev":1.2}],
  "quality": {"score": 92, "issues": [{"severity":"medium","kind":"missing",
               "column":"...","count":3,"detail":"..."}]},
  "relationships": [{"left":"a","right":"b","method":"pearson","value":0.96,
                     "p_value":1e-20,"n":150,"strength":"very strong"}],
  "group_differences": [{"value":"petal_length","group":"species","test":"anova",
                         "statistic":1180.0,"p_value":1e-90,"effect_size":0.94}],
  "time": {"column":"date","granularity":"day","from":"...","to":"..."},
  "hypotheses": [{"id":"h1","question":"...","test":"anova","columns":["..."],
                  "rationale":"...","priority":"high"}],
  "recommendations": ["..."]
}
```

Детерминированный порядок: relationships по убыванию |value|, затем по именам; hypotheses
по priority, затем id. Не более 20 связей, 10 групповых различий, 5 гипотез.

### 5.5 Каталог датасетов

```
GET    /v1/catalog/datasets
POST   /v1/catalog/datasets          {name, file|dataset, format?, description?, tags?, owner?}
GET    /v1/catalog/datasets/{name}
PATCH  /v1/catalog/datasets/{name}   {description?, tags?, owner?}
DELETE /v1/catalog/datasets/{name}
POST   /v1/catalog/datasets/{name}/refresh
GET    /v1/catalog/datasets/{name}/profile?refresh=false
```

Запись: `{name, description, owner, tags, source, format, path, rows, columns, version,
contentHash, createdAt, updatedAt, profile?}`. `name` — `[A-Za-z0-9._-]{1,64}`.
Файл: `DAP_DATASETS_FILE` или `$DAP_HOME/build/datasets.json`; запись атомарная
(tmp + move), чтение под lock. `refresh` переингестит источник и увеличивает `version`,
сохраняя прежнюю версию в `history` (последние 10).

### 5.6 Ошибки

Новые коды: `path_not_allowed` (400), `unknown_column` (400), `invalid_filter` (400),
`dataset_not_found` (404), `dataset_exists` (409), `estimate_unavailable` (409).

## 6. Контракты узлов

### 6.1 Новые IO/текст/гео/БД

| Узел | Входы | Ключевые настройки | Выход |
|---|---|---|---|
| `dap.file_reader` | 0 | `path` или `paths`, `format` csv/parquet/json, `recursive`, `union_by_name`, `filename`, CSV: `delimiter`,`header`,`quote`,`escape`,`nullstr`,`ignore_errors` | таблица; потоковый DuckDB scan |
| `dap.db_writer` | 1 | `driver` duckdb/sqlite/postgres/mysql, `connection`, `table`, `mode` create/replace/append | pass-through |
| `dap.text_normalize` | 1 | `columns`, `mode` nfc/nfd/nfkc/nfkd, `case`, `strip_accents`, `collapse_whitespace`, `trim`, `remove_punctuation`, `suffix`, `replace` | исходные + нормализованные колонки |
| `dap.fuzzy_join` | 2 | `left_key`,`right_key`,`method` jaro_winkler/levenshtein/damerau,`threshold`,`how` inner/left,`max_matches`,`suffix` | join + `similarity` |
| `dap.geo_distance` | 1 | `lat`,`lng`,`lat2`,`lng2`,`unit` km/mi,`output` | + distance |
| `dap.calendar` | 1 | `column`,`timezone`,`features`,`holidays`,`holiday_column` | + calendar columns |
| `dap.chart_grid` | 8 | `file`,`format` svg/png/html,`title`,`columns`,`charts`[{input,type,x,y,series,title,...}] | pass-through port 0 |

`dap.joiner`: `how` += `asof`, `cross`; `left_time`,`right_time`,`asof_inequality`.
`dap.sql_query`: 8 входных портов.
`dap.markdown_report`: 8 входов, `format` md/html/pdf/pptx, секции `input`,
`image`, `page_break`, `title/subtitle/author/toc/theme/page_size/orientation`.

### 6.2 Статистика

| Узел | Изменение |
|---|---|
| `dap.correlation` | `method` += kendall; `partial` (контрольные колонки); `output` long/matrix; выход `method,n,correlation,p_value,ci_low,ci_high,partial` |
| `dap.hypothesis_test` | += mann_whitney, kruskal_wallis, wilcoxon_signed_rank, tukey_hsd; `correction` none/bonferroni/holm/bh; выход `comparison,p_adjusted,effect_size,effect_size_name,ci_low,ci_high`; tukey — строка на пару |
| `dap.linear_regression` | `x` — массив; `robust` none/hc1; `mode` += diagnostics; выход `r2,adj_r2,f_statistic,f_p_value,rmse,df_residual,vif,ci_low,ci_high`; predict += `prediction_lower/upper` |
| `dap.logistic_regression` | новый: `x[]`,`y`,`mode` coefficients/predict,`robust`,`max_iterations`,`tolerance`; выход odds_ratio, ci, p, pseudo_r2, auc |
| `dap.outlier_detect` | `method` += dbscan,lof,isolation_forest; `eps`,`min_points`,`k`,`contamination`,`seed`; выход `outlier,score` |
| `dap.cluster` | новый: kmeans/dbscan/hierarchical, `columns`,`k`,`standardize`,`seed`; выход `cluster,silhouette` |
| `dap.pca` | новый: `columns`,`components`,`standardize`; выход scores + loadings + explained variance |
| `dap.resample_test` | новый: mode bootstrap/permutation, statistic mean/median/sd/correlation/difference, resamples, confidence, seed |
| `dap.survival` | новый: Kaplan-Meier + log-rank, `time`,`event`,`group` |
| `dap.ab_test` | новый: `group`,`value`,`alpha`,`power`,`mde`; p, lift, power, required n |
| `dap.time_series` | новый: mode decompose/acf/lag/forecast/stationarity; `time`,`value`,`period`,`max_lag`,`horizon`,`method`,`group_by` |

### 6.3 Визуализация

`dap.chart` += типы pie/boxplot/heatmap/map; `x_label`,`y_label`,`x_unit`,`y_unit`,
`subtitle`,`sort` value/label/none + `sort_direction`,`log_x`,`log_y`,`data_labels`,
`grid`,`annotations`,`facet`,`facet_columns`,`lat`,`lng`,`size`,`color`,`label`.
Заголовок видимый (не только `<title>`), запас Y 5 % (bar от 0), bar сортируется по
агрегированному значению. Карта — equirectangular, контуры мира из ресурса
`viz/world-110m.json` (public domain Natural Earth), точки-пузыри.

## 7. MCP-контракты

### 7.1 Инструменты

| Инструмент | Изменение |
|---|---|
| `dap_get_table` | += `offset`,`columns[]`,`where`,`format` json/objects/csv/markdown,`maxCellChars`,`includeColumns`; ответ `nextOffset/truncated` |
| `dap_sql` | += `queries[]` (батч), `explain`; `sql` остаётся |
| `dap_estimate` | новый: file/dataset + sql → rows, operators, plan |
| `dap_dataset` | новый: action list/add/show/refresh/remove |
| `dap_explore` | новый: профиль+аномалии+связи+гипотезы |
| `dap_report` | += `format` md/html/pdf/pptx, `queries[]` (несколько таблиц) |
| `dap_analyze`/`dap_profile` | `dataset` принимает имя из каталога |

Прогресс: `tools/call` c `_meta.progressToken` получает
`notifications/progress` (progress/total/message). `dap_run` идёт через async job и
транслирует `progress` движка; остальные длинные инструменты — по стадиям.

### 7.2 Ресурсы

`dap://datasets` — список с name/description/rows/columns/version; `dap://datasets/<name>`
— запись + кэш профиля. Существующие `dap://catalog`, `dap://recipes` не меняются.

## 8. Безопасность и масштаб

- `DAP_ALLOWED_ROOTS` (path-separator separated) — строгий allowlist. Если не задан:
  `user.home`, `java.io.tmpdir`, `DAP_HOME`, cache dir. Denylist всегда:
  `~/.ssh`, `~/.gnupg`, `~/.dsh`, `~/.aws`, `~/.kube`, `~/.config/gcloud`,
  `~/Library/Keychains`, `~/.netrc`, `~/.docker/config.json`.
- Проверяются входные и выходные пути всех файловых узлов, ingest, SQL-литералы
  `read_*`/`FROM '...'`; URL `http(s)://`/`s3://` разрешены (httpfs).
- Out-of-core: `DAP_DUCKDB_MEMORY_LIMIT` по умолчанию `512MB`, `DAP_DUCKDB_TEMP_DIR`
  по умолчанию `$DAP_HOME/build/duckdb-tmp`, `preserve_insertion_order=false`;
  `parquet_reader` возвращает ленивый план `read_parquet(...)` без загрузки в heap;
  ingest CSV > `DAP_INGEST_STREAM_MB` (64) идёт через `read_csv` DuckDB; `file_reader`
  всегда потоковый.

## 9. Тестирование и приёмка

- JUnit: на каждый новый узел ≥3 теста (валидация, успех, граница); для расширенных —
  новые тесты на добавленные режимы и обратную совместимость.
- Python: unit-тесты адаптера на новые аргументы/форматы/прогресс/ресурсы; e2e на
  `dap_explore`, `dap_get_table` paging и `dap_dataset`.
- Goldens: новые `wf-p6-*` для file_reader, joiner asof, report multi-input,
  correlation p-value, multiple regression, cluster, time_series; обновить изменившиеся
  ожидания.
- `make check` зелёный; `make lint`; `make goldens-validate`.
- Документы: NODES.md (каждый узел), MCP.md (инструменты/ресурсы/прогресс), API.md
  (эндпоинты/параметры), QUALITY.md (счётчики), ROADMAP.md (P6), README (счётчики).

## 10. Риски

| Риск | Мера |
|---|---|
| Статистика с ошибками | независимые ожидания в тестах (известные формулы/таблицы), сверка с R/Python вручную |
| Изменение схем ломает goldens | обновлять ожидания отдельным коммитом с причиной |
| Java-статистика на больших данных | cap строк (по умолчанию 100k), ошибка с подсказкой |
| PDF/PPTX без библиотек | минимальные детерминированные writer-ы, тесты на структуру файла |
| Карта без spatial | контуры из ресурса, haversine в SQL; spatial — отдельная задача |
| Конфликты веток | отдельные worktree, интеграция по одной, финальный полный прогон |
