SHELL := /bin/bash

# JDK 21. Override with: make run DAP_JDK=/path/to/jdk-21
DAP_JDK ?= $(HOME)/Projects/tools/jdk-21.0.13+9/Contents/Home
export DAP_JDK

.PHONY: help build-engine build-catalog build-native test-native run-native clean-native run test image lint fetch-runtime runtime-archive clean \
        bench bench-full mcp-e2e image-native run-native-image

help:
	@echo "DAP 1.0.0"
	@echo ""
	@echo "  make build-engine   compile com.dap.engine against runtime/knime"
	@echo "  make build-native   build engine-native/target/dap-native.jar (DuckDB + Arrow + Javalin)"
	@echo "  make test-native    run the dap-native JUnit suite"
	@echo "  make run-native     start dap-native on 127.0.0.1:7800 (blocking)"
	@echo "  make bench          dap-native 100k-row benchmark"
	@echo "  make bench-full     dap-native 10M-row benchmark -> build/bench.json"
	@echo "  make mcp-e2e        MCP create/add/run/table e2e against dap-native"
	@echo "  make image-native   build the dap-native image (Docker host required)"
	@echo "  make build-catalog  rebuild catalog/catalog.json and catalog/schemas/*.json"
	@echo "  make run            start HTTP /v1 on 127.0.0.1:7800 (blocking)"
	@echo "  make test           run python3 tests/run_all.py"
	@echo "  make lint           static checks (scripts/check_repo.py)"
	@echo "  make fetch-runtime  download runtime from DAP_RUNTIME_URL"
	@echo "  make runtime-archive create build/dap-runtime-1.0.0.tar.zst"
	@echo "  make image          build OCI image via scripts/package-oci.sh"
	@echo "  make clean          remove build/classes and build/*.tar"
	@echo ""
	@echo "Env: DAP_PORT DAP_BIND DAP_DATA DAP_HEAP DAP_JOB_TIMEOUT DAP_MAX_WORKFLOWS DAP_JDK"
	@echo "Docs: README.md, docs/OPERATIONS.md, docs/API.md"

build-engine:
	./scripts/build-engine.sh

build-catalog:
	./scripts/build-catalog.sh

build-native:
	cd engine-native && ./mvnw -q -DskipTests package

test-native:
	cd engine-native && ./mvnw -q test

run-native: build-native
	cd engine-native && "$(DAP_JDK)/bin/java" -jar target/dap-native.jar

clean-native:
	rm -rf engine-native/target
	@echo "cleaned engine-native/target"

# --- native Phase 3 targets -------------------------------------------------
bench: build-native
	python3 tests/bench.py --jar engine-native/target/dap-native.jar --rows 100000

bench-full: build-native
	python3 tests/bench.py --jar engine-native/target/dap-native.jar \
		--json build/bench.json --keep-data

mcp-e2e: build-native
	python3 tests/mcp_e2e.py

DAP_NATIVE_IMAGE ?= dap-native:local

image-native:
	docker build -f deploy/Dockerfile.native -t $(DAP_NATIVE_IMAGE) engine-native

run-native-image: image-native
	docker run --rm -p 7800:7800 $(DAP_NATIVE_IMAGE)

run:
	./scripts/run.sh

test:
	python3 tests/run_all.py

lint:
	python3 scripts/check_repo.py

fetch-runtime:
	./scripts/fetch-runtime.sh

runtime-archive:
	./scripts/make-runtime-archive.sh

image:
	./scripts/package-oci.sh

clean:
	rm -rf build/classes
	rm -f build/*.tar
	@echo "cleaned build/classes and build/*.tar; runtime/ and catalog/ untouched"
