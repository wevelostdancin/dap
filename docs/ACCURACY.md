# ACCURACY — как доказано, что DAP считает верно

Документ фиксирует методику эталонной проверки статистики и текущие границы
применимости. Правило простое: **каждая формула, которую DAP отдаёт пользователю,
сверена с независимой реализацией** (scipy, statsmodels, scikit-learn, lifelines,
numpy), а расхождение закреплено тестом.

## 1. Методика

1. Эталонные значения считает отдельный Python-скрипт в `tests/reference/`
   (venv `build/refvenv`, scipy 1.18.1, statsmodels 0.15.0, scikit-learn 1.9.1,
   lifelines 0.30.3, numpy 2.5.5). Скрипты детерминированы, сеть не нужна,
   данные зафиксированы в самом скрипте.
2. JUnit-тест берёт те же входные данные и сравнивает результат узла с выводом
   скрипта: допуск `1e-9` для статистик и p-value, `1e-12` для тождеств
   (например, `|z| = |tau| / SE`).
3. Сквозная проверка — `tests/reference/validate_dap.py`: поднимает настоящий
   движок, прогоняет узлы через `/v1` и сравнивает с эталоном на 200 000 строк,
   на реальных данных (`adult.csv`) и на пограничных входах.

Воспроизведение:

```bash
# эталонное окружение (один раз)
python3 -m venv build/refvenv
build/refvenv/bin/pip install scipy statsmodels scikit-learn lifelines

# отдельные доказательства
build/refvenv/bin/python tests/reference/kendall_pvalue.py
build/refvenv/bin/python tests/reference/chi_square.py
build/refvenv/bin/python tests/reference/logrank.py
build/refvenv/bin/python tests/reference/silhouette.py
build/refvenv/bin/python tests/reference/correction.py
build/refvenv/bin/python tests/reference/explore_stats.py

# сквозная проверка движка (нужен собранный jar)
make build-native
build/refvenv/bin/python tests/reference/validate_dap.py
```

## 2. Что сверено и чем

| Что | Эталон | Скрипт / тест | Статус |
|---|---|---|---|
| Kendall tau-b (с ties и без) | `scipy.stats.kendalltau` | `kendall_pvalue.py`, `CorrelationExecutorTest` | 1e-9 |
| Kendall p-value, tie-corrected асимптотика | `kendalltau(method='asymptotic')` | то же | 1e-9 |
| Pearson / Spearman (r, p, CI) | `scipy.stats.pearsonr/spearmanr` | `validate_dap.py`, `CorrelationExecutorTest` | 1e-9 |
| Partial correlation | `scipy` + обратная корреляционная матрица | `CorrelationExecutorTest` | 1e-9 |
| Поправки на множественные сравнения (bonferroni/holm/BH) | `statsmodels.multipletests` | `correction.py`, `CorrelationExecutorTest` | 1e-9 |
| Линейная регрессия: coef, SE, t, p, R², F | `statsmodels.OLS` | `validate_dap.py`, `LinearRegressionExecutorTest` | 1e-9 |
| Логистическая регрессия (коэффициенты) | `statsmodels.Logit` | `LogisticRegressionExecutorTest` | 1e-6 |
| Welch t-test, ANOVA | `scipy.stats.ttest_ind(equal_var=False)`, `f_oneway` | `validate_dap.py` | 1e-9 |
| χ² независимости (включая пустые клетки) | `scipy.stats.chi2_contingency(correction=False)` | `chi_square.py`, `HypothesisTestExecutorTest` | 1e-9 |
| Log-rank (2 и 3 группы, цензурирование) | `lifelines.statistics.logrank_test`, `multivariate_logrank_test` | `logrank.py`, `SurvivalExecutorTest` | 1e-9 |
| Силуэт (включая синглтон = 0) | `sklearn.metrics.silhouette_samples` | `silhouette.py`, `ClusterExecutorTest` | 1e-9 |
| PCA: explained variance / loadings | `numpy.linalg.eigvalsh` по ковариационной матрице | `validate_dap.py`, `PcaExecutorTest` | 1e-9 |
| Квантили | `numpy.quantile(method='linear')` | `validate_dap.py`, `QuantileExecutorTest` | 1e-6 |
| Гистограмма, бины | `numpy.histogram` | `validate_dap.py`, `HistogramExecutorTest` | счётчики |
| IQR-выбросы | `numpy.percentile` | `validate_dap.py`, `OutlierDetectExecutorTest` | флаги |
| Min-max нормализация | `numpy` | `validate_dap.py` | 1e-9 |
| Describe / profile: count, missing, distinct, mean, min, max | `numpy` / `pandas` на `adult.csv` (15 колонок, 32 561 строка) | `validate_dap.py` | 1e-9 |
| Explore: доля выбросов по сэмплу, BH по парам | `statsmodels` + расчёт по просканированным строкам | `explore_stats.py`, `ExploreRegressionsTest` | 1e-9 |

Контрольные значения (вывод эталонных скриптов):

- Kendall, n=4, ties: `tau=0.9128709291752769`, `p=0.07095149242730564` (scipy
  asymptotic; точное permutation p для малых n DAP не считает — см. §4).
- χ², разреженная 2×2 (`[[10,0],[0,10]]`): `χ²=20`, `p=7.744216431044084e-06`,
  Cramér's V = 1.0. До исправления было `χ²=10` — пустые клетки не учитывались.
- Log-rank, 3 группы с цензурированием: `statistic=4.1432119205298`,
  `p=0.12598329498995398` (lifelines), независимая numpy-реализация Мантеля–Хензеля
  совпадает до `4.4e-15`.
- Силуэт, 4 точки с синглтоном: `s(singleton)=0`, среднее `0.6675378514771508`.
- BH на 10 p-value: `0.001, 0.0025, 0.0066667, 0.00875, 0.022, 0.04, 0.052857, 0.06, 0.233333, 0.93`.

## 3. Сквозная валидация

`validate_dap.py` (27 кейсов, все проходят):

- математика: describe, pearson/spearman/kendall (с ties), регрессия, Welch/ANOVA,
  χ² (плотная и разреженная), log-rank, силуэт, PCA, квантили, гистограмма,
  IQR-выбросы, нормализация — на данных до 200 000 строк;
- реальные данные: `tests/goldens-native/data/adult.csv` — profile по всем 15
  колонкам и explore (score, issues, `distinct_exact`, `p_adjusted`);
- пограничные случаи: 0 строк, константная колонка, колонка целиком NULL,
  бесконечность, `sampleRows=3` (нет ложных «high» гипотез), сэмплированный
  quality-скан 100k из 1M (issue по доле просканированных, не по всем строкам);
- контракт: детерминированный пейджинг (10 страниц без дублей), `limit_too_large`,
  `where` с `from_date`/`selected`, PathGuard на `excel_reader` и `save`,
  самовосстановление битого Parquet-кэша.

## 4. Известные приближения (честно)

| Где | Приближение | Граница и как видно пользователю |
|---|---|---|
| Kendall | только асимптотическая нормальная аппроксимация с tie-коррекцией; exact permutation p не считается | `n` и p в ответе; при `n > 100 000` узел отказывает с подсказкой (`pearson`/`spearman`/`sample_rows`) |
| `distinct` в `/profile` | точно до 100 000 строк; выше — HLL-sketch, колонки с оценкой ≤ 10 000 (до 1M строк — ≤ 110 000) досчитываются точно | `docs/QUALITY.md` §2, политика в `DuckDbSession.readProfile` |
| `distinct` в `/explore` | то же; для > 100 000 строк включается sketch | поле `distinct_exact: false` |
| Quality-сканы explore | квартили/выбросы/дубликаты по первым 100 000 строкам | `quality_sampled`, `quality_scanned_rows`, доля считается по просканированным строкам |
| `sample_rows` в регрессии | детерминированный reservoir sample (`seed`) | `sampled_rows`/`total_rows` в predict |
| Графики по превью | агрегация по первым `limit` строкам | подпись `first N of M rows` на канве |
| PDF | шрифт WinAnsi без встраивания: не-WinAnsi символы → `?` | зафиксировано в `QUALITY.md` |
| Excel | shared strings до 8 МиБ в heap, больше — spool на диск; жёсткий лимит 5 млн строк/512 МиБ | понятная ошибка с подсказкой |

## 5. Что это не доказывает

- Эталоны — популярные реализации (scipy/lifelines/sklearn), а не первоисточники
  формул; расхождение с ними трактуется как дефект DAP.
- Производительность не является предметом этого документа: замеры — в
  `docs/BENCH.md` и `docs/BENCH-P6.md`.
- Численная устойчивость проверена на реалистичных диапазонах; для `|μ| ≫ σ`
  в моментах возможна потеря точности (см. `docs/NODES.md`, correlation), при
  необходимости используйте `regr_sxx`-путь или центрирование.
