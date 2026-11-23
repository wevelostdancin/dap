SHELL := /bin/bash

# JDK 21. Override with: make build-native DAP_JDK=/path/to/jdk-21
DAP_JDK ?= $(HOME)/Projects/tools/jdk-21.0.13+9/Contents/Home
export DAP_JDK

JAR := engine-native/target/dap-native.jar
REFPYTHON := $(if $(wildcard build/refvenv/bin/python),build/refvenv/bin/python,python3)

.PHONY: help build-native test-native run-native goldens goldens-validate coverage accuracy test-mcp smoke \
        mcp mcp-e2e mcp-recipe-e2e dist install-e2e extensions bench bench-full bench-gate image-native \
        run-native-image lint check soak clean-native

help:
	@echo "DAP 1.0.0 — native analytics backend for agents"
	@echo ""
	@echo "  make build-native   build $(JAR) (DuckDB + Arrow + Javalin)"
	@echo "  make test-native    run the JUnit suite"
	@echo "  make run-native     start the engine on 127.0.0.1:7800 (blocking)"
	@echo "  make goldens        run the native golden suite (starts/stops its own engine)"
	@echo "  make goldens-validate  structural check of the golden fixtures (no engine)"
	@echo "  make coverage       run the 69-task coverage suite (starts/stops its own engine)"
	@echo "  make accuracy       reference accuracy gate: 27 cases -> build/accuracy.json"
	@echo "  make smoke          black-box smoke against a running engine (DAP_BASE)"
	@echo "  make mcp            run the stdio MCP adapter (client-facing entrypoint)"
	@echo "  make mcp-e2e        MCP create/add/run/table e2e"
	@echo "  make mcp-recipe-e2e MCP resources/prompts/recipes/dap_sql e2e"
	@echo "  make dist           build the distributable dist/ (jar, adapter, CLI, checksums)"
	@echo "  make install-e2e    install from dist/ into a temp HOME and run the stdio preflight"
	@echo "  make extensions     install the DuckDB sqlite/postgres/httpfs extensions (network once)"
	@echo "  make bench          native 100k-row benchmark"
	@echo "  make bench-full     native 10M-row benchmark -> build/bench.json"
	@echo "  make bench-gate     P6 bench with accuracy/token CI gate"
	@echo "  make image-native   build the dap-native image (Docker host required)"
	@echo "  make lint           static checks (scripts/check_repo.py)"
	@echo "  make check          JUnit + goldens + validate + MCP launch/e2e + lint"
	@echo "  make soak           nightly soak: 200 x create/run/table/delete"
	@echo ""
	@echo "Env: DAP_PORT DAP_BIND DAP_HEAP DAP_JDK DAP_DUCKDB_MEMORY_LIMIT DAP_DUCKDB_TEMP_DIR"
	@echo "Docs: README.md, docs/ROADMAP.md, docs/ARCHITECTURE.md, docs/NODES.md"

build-native:
	cd engine-native && ./mvnw -q -DskipTests package

test-native:
	cd engine-native && ./mvnw -q test

run-native: build-native
	cd engine-native && "$(DAP_JDK)/bin/java" -jar target/dap-native.jar

goldens: build-native
	python3 tests/run_goldens.py

goldens-validate:
	python3 tests/goldens-native/runner.py --validate

accuracy: build-native
	@mkdir -p build
	$(REFPYTHON) tests/reference/validate_dap.py --jar $(JAR) --json build/accuracy.json --min-cases 27

coverage: build-native
	python3 tests/coverage/runner.py

test-mcp:
	python3 tests/test_mcp_launch.py

smoke:
	python3 tests/native_smoke.py --base ${DAP_BASE:-http://127.0.0.1:7800}

mcp: build-native
	./scripts/dap-mcp

mcp-e2e: build-native
	python3 tests/mcp_e2e.py

mcp-recipe-e2e: build-native
	python3 tests/mcp_recipe_e2e.py

DIST := dist

dist: build-native
	rm -rf $(DIST)
	mkdir -p $(DIST)/engine-native/target $(DIST)/adapters/mcp $(DIST)/scripts
	cp $(JAR) $(DIST)/engine-native/target/
	ln -sfn engine-native/target/dap-native.jar $(DIST)/dap-native.jar
	cp adapters/mcp/*.py adapters/mcp/README.md $(DIST)/adapters/mcp/
	cp scripts/dap-mcp $(DIST)/scripts/
	cp VERSION $(DIST)/
	@cd $(DIST) && if command -v shasum >/dev/null 2>&1; then \
		find . -type f ! -name SHA256SUMS | LC_ALL=C sort | xargs shasum -a 256 > SHA256SUMS; \
	else \
		find . -type f ! -name SHA256SUMS | LC_ALL=C sort | xargs sha256sum > SHA256SUMS; \
	fi
	@echo "dist: $(DIST)/"

install-e2e: dist
	python3 tests/install_e2e.py

extensions: build-native
	DAP_HOME="$(CURDIR)" "$(DAP_JDK)/bin/java" -jar $(JAR) --install-extensions sqlite postgres mysql httpfs

bench: build-native
	python3 tests/bench.py --jar $(JAR) --rows 100000

bench-full: build-native
	python3 tests/bench.py --jar $(JAR) --json build/bench.json --keep-data

bench-gate: build-native
	python3 tests/p6_bench.py \
		--manifest tests/p6_bench_manifest.example.json \
		--data-dir build/p6-bench/data \
		--out build/p6-bench/gate \
		--start \
		--reference tests/p6_bench_reference.json \
		--gate tests/p6_bench_gate.json \
		--java "$(DAP_JDK)/bin/java"

DAP_NATIVE_IMAGE ?= dap-native:local

image-native:
	docker build -f deploy/Dockerfile.native -t $(DAP_NATIVE_IMAGE) engine-native

run-native-image: image-native
	docker run --rm -p 7800:7800 $(DAP_NATIVE_IMAGE)

soak:
	cd engine-native && ./mvnw -q -Dgroups=soak test

lint:
	python3 scripts/check_repo.py

check: test-native goldens goldens-validate test-mcp mcp-e2e mcp-recipe-e2e lint

clean-native:
	rm -rf engine-native/target
	@echo "cleaned engine-native/target"
