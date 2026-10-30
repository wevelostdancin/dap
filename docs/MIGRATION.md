# MIGRATION — как добавить 11-й узел в dap-native

Статус: Фаза 3, DoD «шаблон воспроизводим». Шаблон проверен на реальном узле
[`dap.column_renamer`](../engine-native/src/main/resources/specs/dap.column_renamer.json).

## 1. Чеклист нового узла

| # | Шаг | Где |
|---|---|---|
| 1 | `NodeSpec`: `id: dap.<type>`, name, category `/...`, type `Source`/`Manipulator`, contributor `dap-native`, tier `core`, deprecated `false`, available `true`, description ≥ 20 символов, один выход `Data` | `engine-native/src/main/resources/specs/dap.<type>.json` |
| 2 | Спека перечислена в `specs/index.txt` (без строки registry её не загрузит) | `engine-native/src/main/resources/specs/index.txt` |
| 3 | `configSchema`: JSON Schema, `type: object`, `properties` с `title`/`description`, `additionalProperties: false`, `required` | та же спека |
| 4 | Executor реализует `NodeExecutor`: `nodeId()`, `execute()`; возвращает `LazyPlan`, а не данные | `engine-native/src/main/java/com/dap/nativeengine/nodes/<Type>Executor.java` |
| 5 | Executor зарегистрирован в `NativeService.defaultExecutors()` | `NativeService.java` |
| 6 | ≥ 3 unit-теста: значения/типы/порядок + пути ошибок; тест кладём рядом с другими executor-тестами | `engine-native/src/test/java/com/dap/nativeengine/nodes/<Type>ExecutorTest.java` |
| 7 | Расширение корпуса: workflow + `expect`/`expect_profile` с независимо посчитанными golden; native-only обязан иметь `oracle_skip_reason` | `tests/corpus/wf-<id>.json` |
| 8 | Логический тип добавлен в `LOGICAL_TYPES` и в валидатор конфига раннера | `tests/parity.py` |
| 9 | Обновлены пины каталога: размер/available/settingKeys, порядок имён, `nodes`/`coreNodes` в health, `/v1/nodes?limit` | `NodeRegistryTest`, `CatalogHandlersTest`, `NativeServiceTest` |
| 10 | Список workflow в corpus README (если корпус вырос) | `tests/corpus/README.md` |

Спека-скелет (поля обязательны, значения — из примера):

```json
{"id":"dap.<type>","name":"...","category":"/Manipulation/...","type":"Manipulator",
 "contributor":"dap-native","tier":"core","deprecated":false,"available":true,
 "description":"...","inPorts":[{"index":0,"name":"Data","type":"BufferedDataTable"}],
 "outPorts":[{"index":0,"name":"Data","type":"BufferedDataTable"}],
 "configSchema":{"type":"object","title":"... configuration","additionalProperties":false,
                 "properties":{...},"required":[...]}}
```

## 2. Правила именования, типов и конфигов (ROADMAP §3.3–3.5)

- **Конфиг — только native vocabulary из §3.3.** Ключи берутся из уже проверенного
  корпуса (`include`/`exclude`, `keys`/`aggregations`, `where`, ...); синонимы и
  «удобные» переименования не добавляем. Новый узел вводит ключи только под свою
  семантику, и они попадают в §3.3 при обновлении доков владельцем.
- **Имена:** агрегаты как в KNIME — `Mean(x)`, `Sum(x)`, `Count(x)`; имя узла —
  `dap.<type>`, категория начинается с `/`.
- **Типы:** храним Arrow-тип в `ColumnSpec`, наружу отдаём KNIME-имя через
  `TypeMap`: `Int64→Number (Integer)`, `Float64→Number (Float)`, `Utf8→String`,
  `Date32→Date`, `Time64→Time`, `Timestamp→Date&Time (Timestamp)`,
  `Decimal128→Number (Decimal)`, `Boolean→Boolean`; `Count` — `Number (Integer)`.
  Rename/проекция тип не меняет.
- **NULL и `""`:** NULL — только Arrow null bitmap (SQL NULL); `""` — значение.
  `csv_reader` различает пустое некавыченное поле и кавыченное `""`
  (`emptyStringIsNull`); трансформации это не переопределяют.
- **Ленивость:** узел-трансформация возвращает `new LazyPlan(...)` с SQL поверх
  `input.asTable()`; данные читают только `/table`, `/profile` и job. Копировать
  таблицу в узле нельзя (исключение — source-узлы на run).
- **Ошибки:** `IllegalArgumentException` с именем проблемного поля/колонки; HTTP-коды
  и тексты — забота `Dag`/`JobManager`, узел их не формирует.
- **Изоляция:** `grep -r "org.knime" engine-native/` должен оставаться пустым; новые
  зависимости — только с явного разрешения.

## 3. Рабочий пример: `dap.column_renamer`

Чистая проекция `SELECT "old" AS "new", ... FROM (<input>) AS dap_input`: порядок и типы
колонок сохраняются, данные не копируются, пустой `renames` — passthrough. Валидация:
неизвестный `from`, дубликат `from`/`to`, коллизия `to` с остающейся колонкой, пустые
имена; перестановка двух колонок допустима (одновременная проекция).

- спека: [`dap.column_renamer.json`](../engine-native/src/main/resources/specs/dap.column_renamer.json)
- executor: [`ColumnRenamerExecutor.java`](../engine-native/src/main/java/com/dap/nativeengine/nodes/ColumnRenamerExecutor.java)
- тесты (6): [`ColumnRenamerExecutorTest.java`](../engine-native/src/test/java/com/dap/nativeengine/nodes/ColumnRenamerExecutorTest.java)
- корпус: [`wf-n3-iris-renamer.json`](../tests/corpus/wf-n3-iris-renamer.json) — IRIS →
  renames `sepal_length→sl`, `sepal_width→sw` → `Mean(sl)` by species → sorter.

## 4. Команды проверки

```bash
export JAVA_HOME="$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
cd engine-native
./mvnw -q -o test                          # 203 теста, 0 failures
./mvnw -q -o -DskipTests package           # target/dap-native.jar
DAP_PORT=7924 "$JAVA_HOME/bin/java" -jar target/dap-native.jar &
cd ..
python3 tests/parity.py --validate          # corpus: 13 workflows, 0 problem(s)
python3 tests/parity.py --engine native --base http://127.0.0.1:7924 --only wf-n3
# ok wf-n3-iris-renamer ... checks=2
# matching cells 0/0 (0.0%): native-only проверки не входят в baseline-ячейки
kill %1
make lint                                   # OK: 0 errors
```

Полный корпус: `python3 tests/parity.py --engine native --base http://127.0.0.1:7924` →
`13 workflows ok ... matching cells 1563/1563 (100.0%)`. На чистом worktree Maven-кэш
`.tools/` сидируется из основного checkout и не коммитится.

Цели Makefile для проверки уже есть: `make build-native` (jar), `make test-native`
(`mvnw test`), `make bench` (100k), `make bench-full` (10M + `build/bench.json`),
`make mcp-e2e` (`tests/mcp_e2e.py`), `make image-native` / `make run-native-image`.
Отдельные `test-native-offline`, `parity-validate` и `parity-native` в Makefile не
добавлены: используйте команды выше.

## 5. Чего автор узла не трогает

- `api/openapi-v1-frozen.yaml`, `.sha256`, `CHANGELOG.md` — только при изменении HTTP-контракта;
  новый узел контракт не меняет.
- `tests/baseline.json` — пишет только oracle (`--write-baseline`); native-only workflow
  вручную в baseline не добавляется.
- `docs/NATIVE.md`, `docs/PARITY.md`, `docs/BENCH.md`, `docs/ROADMAP-NATIVE.md` — у них свои
  владельцы; числа корпуса (12→13) и приёмку обновляют они.
- `Makefile` — менять не нужно: `build-native`, `test-native`, `bench`, `mcp-e2e`,
  `image-native` уже есть.
- `engine-native/.tools/` — gitignored кэш Maven.
- Чужие executor'ы, спеки и тесты — не рефакторим; правим только пины каталога, которые
  сломались из-за нового узла.
