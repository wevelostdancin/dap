# План: устойчивый ingest данных (кодировки, диалект CSV, листы Excel, легаси .xls)

**Дата:** 23.11.2026
**Источник:** замечания пользователя по работе с 80 CSV/340 .xls в cp1251 (Краснодар).
**Ветка интеграции:** main (worktree лида), исполнители — в отдельных worktree.

## Правила

- Каждый исполнитель работает в своём worktree; общий worktree — только чтение.
- Коммиты conventional, английский: `fix(ingest): …`, `feat(ingest): …`.
- Новых зависимостей не добавлять: только JDK 21 / DuckDB 1.5.6 / stdlib.
- Точечные тесты в процессе; полный прогон делает лид.
- Документация — русский/английский, без CJK.

## Контракт

### POST /v1/datasets (запрос)

```json
{
  "file": "…", "format": "csv|excel|json|parquet",
  "header": true,
  "delimiter": ";",              // нет/""/null => автоопределение (, ; TAB |)
  "quote": "\"", "escape": "\"",
  "ignoreErrors": false, "nullPadding": false, "strictMode": true,
  "nullStrings": ["NA"],
  "encoding": "auto",            // auto|utf-8|utf-16|latin-1|cp1251|cp866|koi8-r|<java charset>
  "allVarchar": false,           // прочитать все колонки как VARCHAR
  "decimalComma": false,         // «5 449,72» -> DOUBLE (пробелы/NBSP убрать, запятую -> точку)
  "sheet": "ИП план-факт-прогноз" // Excel: имя или 0-based индекс; нет => первый лист
}
```

### POST /v1/datasets (ответ)

`{path, rows, columns, cached}` + для Excel: `sheet` (фактически прочитан), `sheets` (список имён);
для CSV: `encoding` (фактически применённая кодировка).

### MCP

- `dap_ingest`: + `encoding`, `allVarchar`, `decimalComma`, `sheet`; `delimiter` по умолчанию авто.
- `dap_analyze`, `dap_profile`, `dap_report`, `dap_explore`, `dap_estimate`: + `header`,
  `delimiter`, `encoding`, `allVarchar`, `decimalComma`, `sheet`; ingest идёт через один
  хелпер `_source`/`_csv_body` (сейчас `dap_analyze` опции теряет).
- Результаты `dap_analyze`/`dap_profile`/`dap_report`: пробрасывают `sheet`/`sheets`/`encoding`
  из ответа ingest, чтобы выбор листа не был молчаливым.

## Потоки

### A. Ядро ingest (lead)

Файлы: `io/IngestService.java`, `api/DatasetHandlers.java`, `NativeService.java`.

1. `IngestOptions` (переименование `CsvOptions`): delimiter nullable, encoding, allVarchar,
   decimalComma, sheet; старые конструкторы сохранить.
2. CSV: delimiter не задан -> не передавать `delim` в read_csv (DuckDB сам перебирает , ; TAB |).
3. CSV: `CsvEncoding.plan` + transcode во временный UTF-8, `all_varchar`, нормализация decimalComma
   (один валидирующий проход, конвертируем колонку только если ВСЕ непустые значения парсятся).
4. Excel: `sheet` в опции и в ключ кэша; ответ содержит `sheet` и `sheets`.
5. Ошибки: разворачивать `Attempting to execute an unsuccessful or closed pending query result\nError: `
   до содержательной причины; путь/строка/параметры — из сообщения DuckDB.
6. Устойчивость: семафор на параллельные rebuild (DAP_INGEST_PARALLELISM, default 2); ingest-сессии
   получают memory_limit/threads/temp_directory из Settings (иначе 6 параллельных DuckDB без лимита).

### B. CsvEncoding (worker csv-encoding)

Новый файл `io/CsvEncoding.java` + `CsvEncodingTest.java` (см. контракт в задаче).

### C. Excel-легаси (worker xlsx-legacy)

`xlsx/XlsxTableReader.java`: проверка magic bytes до ZipFile (OLE2 -> «legacy .xls не поддерживается,
конвертируйте в .xlsx»; ZIP c `xl/workbook.bin` -> «.xlsb не поддерживается»); `public static
List<String> sheetNames(Path)`. Тест `XlsxTableReaderTest.java`.

### D. MCP-адаптер (worker adapter)

`adapters/mcp/dap_mcp.py` + unit-тесты `tests/test_mcp_*.py` (моки /v1, без движка).

### E. Документация (worker docs, после слияния A–D)

`api/openapi.yaml`, `docs/API.md`, `docs/MCP.md`, `CHANGELOG.md`.

### F. Проверка (lead)

`make test-native`, `make lint`, `python3 -m unittest` профильных тестов; e2e на реальных файлах
пользователя (cp1251 CSV, 11-листовой xlsx, .xls); затем `make check` и код-ревью субагентом.

## Приёмка

- `dap_ingest` на `Охрана труда/РЕЙТИНГ.csv` без параметров отдаёт кириллические заголовки и числа.
- `dap_analyze` на `Персонал 2025-26.csv` сам находит `;`.
- `dap_analyze` на `2025-26 ИП.xlsx` с `sheet` читает нужный лист, без `sheet` — сообщает список листов.
- `dap_ingest` на `04.08..xls` отвечает «legacy .xls не поддерживается», а не «zip END header».
- 6 параллельных ingest не роняют движок; ошибка содержит причину (кодировка/строка), а не
  «closed pending query result».
