# Каталог узлов, схемы и tiers

DAP не содержит собственных узлов: он публикует узлы vendored-рантайма KNIME 5.12 и
добавляет к ним машиночитаемые схемы, tier и контракт API. Этот документ объясняет, что
именно доступно, как читать tier и как перевести узел в core.

## 1. Цифры

Снимок на 2026-10-29 для `catalog/catalog.json` и `catalog/schema-report.json` версии
1.0.0. Источник истины по tier — `catalog/curated.json`; он может меняться быстрее
отчёта (в общем дереве наблюдался 141 core против 150 в отчёте). Фактическое число
отдаёт `GET /v1/health` → `coreNodes`.

| Метрика | Значение |
|---|---|
| Узлов в каталоге | **832** |
| core | **150** |
| experimental | **682** |
| Доступная схема диалога | **733** |
| — аннотированная (`annotations`) | 385 |
| — Swing (`swing`) | 348 |
| Без схемы | **99** |
| `settingsUnavailable` | **21** |
| Полей в схемах | 3189 |
| Полей с подписями | 220 |
| Уникальных JSON-файлов схем | 827 |
| Повторных регистраций фабрик | 5 |
| Бандлов-контрибьюторов | 33 |
| Категорий | 81 |
| Помечено `deprecated` | 335 |

Почему 827 файлов при 832 узлах: пять factory зарегистрированы дважды (например,
`FilterColumnNodeFactory`, `SplitNodeFactory`, `NewJoinerNodeFactory`). Каталог хранит
обе записи; файл схемы у них общий.

Проверка:

```bash
python3 - <<'PY'
import json
c=json.load(open('catalog/catalog.json'))
r=json.load(open('catalog/schema-report.json'))
print('nodes', len(c['nodes']), 'unique', len({n['id'] for n in c['nodes']}))
print('core', sum(n.get('tier')=='core' for n in c['nodes']))
print('available', r['available'], 'swing', r['swing'], 'annotations', r['annotations'])
print('no schema', r['nodes']-r['available'], 'settingsUnavailable',
      sum('settingsUnavailable' in n for n in c['nodes']))
print('reasons', r['reasons'])
PY
```

## 2. Что такое tier

Tier — обещание поддержки, а не режим исполнения.

| Tier | Значение | Количество |
|---|---|---|
| `core` | схема и поведение зафиксированы; изменения — осознанное ломающее изменение; предусмотрен golden-тест | 150 |
| `experimental` | доступен через API, но схема может быть неполной, поведение — измениться | 682 |

Оба tier можно искать, добавлять в workflow, настраивать и исполнять одинаково.
`GET /v1/nodes` и `GET /v1/nodes/{id}` возвращают `tier`; он же есть в схемах диалогов.

Tier задаётся списком `core` в `catalog/curated.json` и применяется при старте
(`Tiers.apply`). Сам `catalog.json` тоже содержит `tier` после `build-catalog`, но
источник истины — `curated.json`. Список формируется `scripts/select-curated.py` по
именам узлов (не deprecated и со схемой); спорные имена видны в его выводе
`core=N missing=M`.

## 3. Как читать схему узла

Рекомендуемый порядок для агента:

1. `GET /v1/nodes?q=…` — найти id.
2. `GET /v1/nodes/{id}` — порты, `settings`, `settingsXml`, `deprecated`, `tier`.
3. `GET /v1/nodes/{id}/dialog` — поля, типы, умолчания, choices.
4. Если узел уже в workflow — `GET /v1/workflows/{id}/nodes/{node}/dialog`: там choices
   считаются по реальным входным таблицам.

### Аннотированная схема

`schemaKind: "annotations"`. Поле содержит:

| Ключ | Смысл |
|---|---|
| `field` | имя Java-поля |
| `configKey` | ключ `@Persist` (часто совпадает с XML, но не гарантировано) |
| `title`, `description` | подпись и описание |
| `type` | `string/integer/number/boolean/enum/array/object/unknown` |
| `default` | умолчание или `null` |
| `choices` | варианты `{value,label,disabled?}` |
| `choicesSource` | провайдер и его характер (`columns/strings/enum/dynamic`) |
| `optional`, `nullable`, `advanced` | модификаторы |
| `items`, `addButtonText` | для массивов |
| `choicesNote` | почему вариантов нет (например, нет входной таблицы) |
| `choicesError` | провайдер упал; схема при этом валидна |
| `defaultNote` | значение не сериализуется без UI |

### Swing-схема

`schemaKind: "swing"`. `fields` заполняется из `DialogComponent`/`SettingsModel`.
Если диалог собран вручную, `fields` может быть пустым, а элементы — в `ui`:
`{type,label,section,source:"dialog-ui",default,choices,…}`. У таких элементов нет
выдуманного `configKey`: настройки всё равно задаются по ключам `settingsXml`.

### Если схемы нет

99 узлов возвращают `available:false` и `reason`:

> узел не отдал ни аннотированную, ни Swing-схему; используйте settingsXml из /nodes/{id}

Это не ошибка каталога. Для такого узла:

1. возьмите `settingsXml` из `GET /v1/nodes/{id}`;
2. найдите нужные ключи (`settings` — плоский список);
3. передайте JSON по структуре XML в `POST/PATCH .../nodes`.

21 узел дополнительно не сериализует настройки по умолчанию (`settingsUnavailable`) —
такие настройки через API применить нельзя, даже зная ключи.

### Динамические choices

Статическая схема не знает входных таблиц. Поэтому:

- `ColumnChoicesProvider` без таблицы → `choicesNote` «варианты появятся, когда…»;
- провайдеры, зависящие от других полей, получают no-op initializer → `choicesError`;
- в контексте workflow (`.../workflows/{id}/nodes/{node}/dialog`) подставляются реальные
  spec-ы входных портов, и choices по колонкам работают.

### Ключи настроек

`SettingsMerge` принимает JSON по XML-структуре `settingsXml`, а не по именам полей
диалога. `configKey` — это `@Persist`, он может отличаться от XML-ключа. При ошибке
ответ 400 содержит список доступных ключей:

```json
{"error":"unknown setting 'file_location' in 'settings'; available: [stddeviation, noise, patcount, seed, clustercount, unisize]"}
```

Практика: сначала прочитайте `settings`/`settingsXml`, затем шлите минимальную дельту;
для вложенных структур используйте объекты, для повторяющихся — массивы.

**Массивы.** Пустые плоские списки (`array-size=0`) поддерживаются: движок создаёт
`entry` с типом по значению (xstring/xint/xdouble/xboolean). Так настраиваются
`Column Filter.exclude` и `GroupBy.grouByColumns.InclList`. Ограничение остаётся для
вложенных списков, сериализованных как numeric `config` без шаблона (например,
`Missing Value.columnSettings`) — для них возможен 400 `has no template to extend`.

## 4. Как добавить узел в core

Перевод в core — это обязательство, а не флаг в UI. Минимальная процедура:

1. **Проверить пригодность.** Узел есть в каталоге, имеет `tier: experimental`, стабильный
   id, исполняется в минимальном workflow, его схема доступна или сознательно
   документирован fallback на `settingsXml`.
2. **Зафиксировать контракт.** Выбрать сценарий: обязательные настройки, входные данные,
   ожидаемая схема результата. Если схема Swing без `configKey` — зафиксировать XML-ключи.
3. **Добавить id в `catalog/curated.json`** в массив `core`. Формат:

   ```json
   {
     "version": 1,
     "generatedAt": "2026-10-29",
     "description": "...",
     "core": ["org.knime...Factory", "..."]
   }
   ```

   Список — множество, порядок не важен; дубликаты безвредны, но нежелательны.
4. **Добавить golden-тест** `tests/golden/<имя>.py` с функцией `run(api, sc)`;
   `tests/run_all.py` подхватит его автоматически. Тест должен проверять:
   - создание узла и применение настроек (200);
   - исполнение (200, `ok:true`);
   - схему/строки/профиль с точностью до типов и `null`;
   - отсутствие `ERROR` у узлов;
   - прогон: `python3 tests/run_all.py` (порт 7810, не трогает 7800).
5. **Пересобрать каталог и схемы:** `make build-catalog`. `schema-report.json` обновится,
   `tier` в схемах станет `core`.
6. **Перезапустить сервис** и проверить `GET /v1/health`: `coreNodes` должен вырасти на 1.
7. **Обновить документацию:** цифры в этом файле и README, если изменилось общее число.

Критерии отказа:

- узел нестабилен или требует UI/интерактивности;
- настройки несериализуемы (`settingsUnavailable`);
- нет способа детерминированно проверить результат;
- схема пуста и XML-ключи не документированы.

Удаление из core — обратная операция: убрать id из `curated.json`, перезапустить сервис,
удалить/пометить golden-тест. Пересборка каталога не обязательна для применения tier, но
`schema-report.json` и `tier` в `schemas/*.json` обновятся только после `build-catalog`.

## 5. Добавление нового узла (experimental)

Специальных действий нет: `make build-catalog` обходит extension point
`org.knime.workbench.repository.nodes` и добавляет всё, что зарегистрировано в runtime.
Чтобы узел появился, он должен быть в одном из бандлов `runtime/knime/plugins` и
зарегистрирован в `plugin.xml`. После пересборки — перезапуск сервиса.

## 6. Ограничения каталога

- **99 без схемы** — см. раздел 3; fallback на `settingsXml`.
- **21 `settingsUnavailable`** — настройки нельзя применить через API.
- **Зависимые choices** неполны без workflow-контекста.
- **`configKey` ≠ XML-ключ** в общем случае; источник истины — `settingsXml`.
- **Вложенные списки без шаблона** (numeric `config`) не синтезируются: 400
  `has no template to extend` (например, `Missing Value.columnSettings`).
  Пустые плоские `entry`-массивы уже поддерживаются (Column Filter, GroupBy).
- **5 дубликатов** регистраций: в поиске узел может встретиться дважды.
- **335 `deprecated`** — доступны, но не рекомендуются.
- **Каталог — снимок runtime.** Обновили runtime/бандлы — пересоберите каталог и схемы;
  старые `schemas/*.json` могут описывать исчезнувший узел.
- **Схемы предрасчитаны.** После `build-catalog` нужен перезапуск: `dialogCache` и файлы
  читаются на старте/по запросу.

## 7. Полезные срезы

Все узлы без схемы:

```bash
python3 - <<'PY'
import json
r=json.load(open('catalog/schema-report.json'))
for d in r['details']:
    if not d.get('available'):
        print(d['id'])
PY
```

Все `settingsUnavailable`:

```bash
python3 - <<'PY'
import json
for n in json.load(open('catalog/catalog.json'))['nodes']:
    if 'settingsUnavailable' in n:
        print(n['id'], '->', n['settingsUnavailable'])
PY
```

Core-узлы по категориям:

```bash
python3 - <<'PY'
import json, collections
c=json.load(open('catalog/catalog.json'))['nodes']
print(collections.Counter(n['category'] for n in c if n.get('tier')=='core'))
PY
```
