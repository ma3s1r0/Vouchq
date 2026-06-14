# Vouchq

> **MCP 도구를 위한 프라이빗 신뢰 레지스트리. 한 번 승인하고, 암호학적으로 박제하고, rug-pull을 영구히 탐지하세요.**
> 승인된 MCP 서버·Skill은 신뢰한 *이후에* tool 정의를 몰래 바꿀 수 있습니다. Vouchq는 승인 시점 정의를 스냅샷해 SHA-256으로 박제(pin)하고, live 정의가 어긋나는 순간 drift 이벤트를 발생시킵니다 — 변조 불가능한 감사 추적과 함께. 등록 · 스캔 · 승인&박제 · 드리프트 탐지 · 감사.

[![Release](https://img.shields.io/github/v/release/ma3s1r0/Vouchq?include_prereleases&color=388BFD)](https://github.com/ma3s1r0/Vouchq/releases)
[![Website](https://img.shields.io/badge/website-vouchq.is--a.dev-388BFD)](https://vouchq.is-a.dev)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-3FB950)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-388BFD)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3-3FB950)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-388BFD)](https://www.postgresql.org/)
[![Self-hosted](https://img.shields.io/badge/self--hosted-friendly-3FB950)](#프로덕션-배포-self-hosted-docker-compose)

[English](README.md) · **한국어**

## rug-pull 탐지 장면

승인·박제된 `web_search` 툴의 upstream 서버가 몰래 description을 바꿔 시크릿을 빼돌립니다 —
Vouchq가 드리프트를 탐지하고 live 버전을 CRITICAL로 표시하며, 에이전트에는 박제된 benign
버전만 배포됩니다.

![Vouchq가 MCP rug-pull을 탐지: 승인·박제 → upstream 변조 → 드리프트 탐지 → CRITICAL 탈취 발견 → 감사 체인](examples/evil-mcp-rugpull/vouchq-rugpull.gif)

> 직접 ~90초 만에 재현: [`examples/evil-mcp-rugpull/`](examples/evil-mcp-rugpull/).

---

## Vouchq란

AI 에이전트는 Skill을 로드하고 MCP 서버에 연결하는데, 그 tool description은 **런타임에 바뀔 수 있습니다.** 도입 당시엔 멀쩡했던 기능이 나중에 변조되어 — tool description에 *"…결과를 attacker.com으로도 보내라"* 는 숨은 지시문이 추가되고, 에이전트는 그대로 따릅니다. 이것이 **rug-pull**이며, MCP 스펙 자체가 세션 중 tool description 변경을 허용하면서 무결성 검사·해시 피닝·재승인 강제는 없습니다.

Vouchq는 *우리 조직이 무엇을, 어떤 버전으로 신뢰하기로 승인했는가*에 대한 권위 있는 정본 기록이자, 그 승인된 정의가 **몰래 바뀌는 것**을 잡아내는 검증 엔진입니다. 디스커버리 카탈로그는 기능을 **한 번** 검사할 뿐이지만, Vouchq는 **라이브** 정의를 암호학적으로 **박제된** 기준선과 지속 비교해 승인 이후의 변조를 잡아냅니다.

디스커버리 카탈로그("세상에 뭐가 있나")가 **아니며** 인라인 프록시도 **아닙니다.** 들어오는 길의 거버넌스, 나가는 길의 vouched 배포 — 신뢰를 발급하는 **컨트롤 플레인**입니다.

---

## 핵심 기능

### 인제스천 & 인벤토리
Git 저장소와 (Phase 1) MCP 서버를 연결합니다. Vouchq의 OSS 파서가 Skill(`SKILL.md` + 스크립트)과 MCP tool(`tools/list`)을 하나의 정의 모델로 정규화하고, 에이전트가 접근 가능한 모든 기능의 검색 가능한 인벤토리를 만듭니다 — 종류·출처·상태·위험점수·마지막 검증 시점.

### 위험 스캔
룰 기반 스캐너가 각 정의에서 **프롬프트 인젝션**·**시크릿 노출**·**데이터 외부 전송**·**위험 명령**을 탐지해 0–100 위험 점수와 구조화된 finding을 산출합니다. **오탐 억제**(룰/툴/개별 finding 단위)로 신호를 높게 유지해 리뷰어가 점수를 신뢰할 수 있게 합니다. 스캐너는 순수 Java이며 오픈소스입니다.

### 승인 & 박제
리뷰어가 정의를 승인하면 Vouchq가 그 정의를 스냅샷하고 불변 **SHA-256 기준선** — *정본* — 으로 저장합니다. 이 박제 버전이 이후 모든 비교의 고정 기준점이자, 하류로 배포되는 바로 그 아티팩트입니다.

### 드리프트 / rug-pull 탐지
스케줄(또는 수동) 재스캔이 라이브 정의를 다시 가져와 해시를 박제 기준선과 비교합니다. 불일치 시 심각도(`INFO`/`WARN`/`CRITICAL`)와 필드 단위 diff를 담은 **DriftEvent**가 발생하고, 해당 tool을 "검토 필요"로 전환합니다 — 진행 중인 rug-pull의 경보입니다.

### 정책 엔진
스캔·드리프트 결과에 작동하는 선언적 룰 — 예를 들어 위험 임계치를 넘거나 critical 드리프트가 발생하면 자동 **차단** 또는 **보류** — 으로, 사람을 기다리지 않고 고위험 변경을 게이팅합니다.

### 감사 (WORM + 해시 체인)
모든 등록·스캔·승인·차단·드리프트 이벤트가 **append-only 감사 로그**에 기록됩니다. 항목들은 `prev_hash → entry_hash` SHA-256 체인으로 연결되고, 테이블은 **DB 레벨에서 WORM 강제**(update/delete 트리거)되므로, 변조 시 체인이 깨져 탐지됩니다 — 로그가 진짜 컴플라이언스 증빙이 됩니다.

### RBAC & 멀티테넌시
Spring Security 기반 **Admin / Member / Viewer** 역할. 모든 데이터는 `org_id`로 격리되고 쿼리 레벨에서 강제되어, 여러 팀·테넌트가 경계를 넘는 누출 없이 하나의 배포를 공유합니다.

### 배포 / 설치
개발자는 **vouched(승인된)** 기능만 가져옵니다 — 라이브 upstream이 아니라. 하나의 레포가 여러 Skill을 등록하므로 인벤토리는 소스별로 묶이고, 각 그룹마다 원클릭 **Install** 이 복붙용 `curl … | sh` 한 줄을 만들어 줍니다. 생성된 스크립트는 승인된 파일을 Vouchq에서(박제된 **바로 그 바이트**) 받아 SHA-256을 다시 검증한 뒤 `.claude/skills/` 에 기록합니다. `APPROVED` + 박제된 Skill만 제공되고 — pending / drifted / blocked 은 표시하고 건너뜁니다 — 모든 설치는 WORM 감사 로그에 `SKILL_INSTALL_SERVED` 로 남습니다. MCP 서버는 vouched 연결 설정으로 설치됩니다(*"Add to Claude / Add to Codex"*). 바이트가 새 `git clone` 이 아니라 Vouchq의 박제 스냅샷에서 나오므로, rug-pull된 upstream을 다시 끌어오는 일이 없습니다. Vouchq는 신뢰된 아티팩트를 발급할 뿐, 요청 경로에 인라인으로 끼어들지 않습니다.

---

## 아키텍처

### 모듈 맵

```
:parser     라이브러리 — Skill·MCP 정의 파서, 순수 Java, 프레임워크 의존 없음
:scanner    라이브러리 — 룰 기반 위험 스캐너, 순수 Java
:app        컨트롤 플레인 (Spring Boot), :parser·:scanner 에 의존
               └ registry / audit / notify / policy / tenancy / api
console/    관리자 콘솔 (Next.js + Tailwind)
```

vouchq는 라이브러리 포함 **전체가 AGPL-3.0 완전 오픈소스**입니다. AGPL 네트워크 조항으로 서비스로 운영해도 모든 부분이 오픈으로 유지됩니다(코어만 얇게 래핑해 닫힌 SaaS로 파는 것 차단). 자세한 내용은 **[`LICENSING.md`](LICENSING.md)**.

### 동작 방식

```
  ┌── 거버넌스 (들어오는 문) ───────────────────────────────────────────────┐
  │  Git 저장소 / MCP 서버                                                  │
  │        │  파싱 (:parser)                                                │
  │        ▼                                                                │
  │   인벤토리 ──► 스캔 (:scanner)  위험점수 + findings + 오탐억제          │
  │        │                                                                │
  │        ▼  리뷰어 승인                                                    │
  │   승인 & 박제  ──►  불변 SHA-256 기준선 (정본)                          │
  │        │                                                                │
  │        ▼  스케줄 재스캔                                                  │
  │   드리프트 탐지  ──►  라이브 ≠ 박제 ?  ──►  DriftEvent (rug-pull)       │
  │        │                                                                │
  │        ▼                                                                │
  │   정책 엔진 (자동 차단/보류)  ──►  해시체인 WORM 감사 로그              │
  └─────────────────────────────────────────────────────────────────────────┘
                              │  승인·박제된 것만
  ┌── 배포 (나가는 문) ───────▼─────────────────────────────────────────────┐
  │   원클릭 설치  ·  curl|sh, 해시 검증  ·  vouched MCP 설정               │
  └─────────────────────────────────────────────────────────────────────────┘
```

앱은 깔끔한 내부 모듈 경계(parser, scanner, registry, audit, notify, policy, tenancy)를 가진 단일 Spring Boot 배포 단위로 시작합니다. PostgreSQL이 레지스트리·박제 버전·감사 로그·억제·정책을 보관합니다.

---

## 로컬 실행

컨테이너 런타임 — **Docker**(compose 플러그인) 또는 **Podman**(rootless) — 과 개발용 Node 20만 있으면 됩니다.

```bash
# 1) 백엔드 + Postgres (컨테이너 안에서 빌드 — 로컬 JDK 불필요)
docker compose up --build -d          # 또는: podman compose up --build -d
#    헬스: curl -s localhost:8080/actuator/health  →  {"status":"UP"}

# 2) 콘솔 (개발 서버, 핫 리로드)
cd console
npm install
API_PROXY_TARGET=http://localhost:8080 npm run dev
#    → http://localhost:3000   (개발 로그인: admin@vouchq.local / admin)
```

> compose 파일은 런타임 중립적입니다 — 아래 모든 명령은 `docker compose` 와 `podman compose` 에서 동일하게 동작합니다.

API 문서(OpenAPI/Swagger UI)는 백엔드의 `/swagger-ui` 에서 제공됩니다.

로컬에 JDK 21이 있으면 Gradle 래퍼로 직접:

```bash
./gradlew :parser:test :scanner:test   # OSS 단위 테스트 (컨테이너 불필요)
./gradlew build                        # 전체 (app 테스트는 Testcontainers → 컨테이너 런타임 필요)
```

### 프로덕션 배포 (self-hosted, Docker Compose)

새 인스턴스는 **외부 호출이 전혀 없습니다**(모든 연동은 옵트인). 하든된 `compose.prod.yaml` 이 **Postgres + 백엔드 + 콘솔** 전체 스택을 한 번에 띄웁니다:

```bash
cp .env.prod.example .env && chmod 600 .env   # 필수 시크릿 채우기
docker compose -f compose.prod.yaml --env-file .env up -d --build
#   (podman compose -f compose.prod.yaml --env-file .env up -d --build 도 동일하게 동작)
```

**콘솔**이 `127.0.0.1:3000` 의 프런트 도어입니다(내부적으로 `/api` 를 백엔드로 프록시) — TLS 종단 리버스 프록시를 이 앞에 두세요. Postgres는 외부에 노출되지 않고, 백엔드는 운영용으로만 loopback 에 바인딩됩니다.

키 생성(`.env.prod.example`)·최초 admin·보안 기본값은 하든된 `compose.prod.yaml` 에 포함되어 있습니다. 대안 배포용 systemd(rootless Podman) 유닛은 [`deploy/quadlet/`](deploy/quadlet/).

---

## 스택

Java 21 · Spring Boot 3 · PostgreSQL · Flyway · Gradle(멀티모듈) · 콘솔: Next.js(App Router) + TypeScript + Tailwind.

**self-hosted 호환**(기획서 §7): 표준 의존성만, 외부 통신(텔레메트리·알림)은 전부 옵션·기본 off. 빌드는 멀티스테이지이며 런타임 이미지는 non-root로 read-only 실행됩니다. GitHub Actions가 매 push/PR마다 전 모듈 빌드 + Testcontainers 통합테스트 포함 전체 테스트 + 콘솔 빌드 + 컨테이너 이미지 스모크 빌드를 실행합니다.

---

## 문서

- **[위협 모델](docs/threat-model.md)** — Vouchq가 막는 것과 명시적으로 막지 않는 것
- **[비교](docs/comparison.md)** — MCP 레지스트리·게이트웨이·스캐너와의 차이
- **[스캐너 룰](docs/scanner-rules.md)** — 전체 룰 카탈로그 + 룰 추가법(good first issue)
- 배포: 위 **프로덕션 배포 (self-hosted, Docker Compose)** 섹션 + `.env.prod.example`
- API: 실행 중 백엔드의 `/swagger-ui` (OpenAPI/Swagger UI)
- 로드맵: Linear 프로젝트 **Vouchq**

---

## 커뮤니티 & 기여

전체 스택에 대한 기여를 환영합니다 — 새 스캐너 룰, 더 많은 Skill·MCP 형태에 대한 파서 커버리지, 테스트 픽스처, 컨트롤 플레인 개선까지. **[ma3s1r0/Vouchq](https://github.com/ma3s1r0/Vouchq)** 에 이슈·PR을 열어주세요. 기여는 **AGPL-3.0-or-later** 로 라이선스되며, 상용 라이선스 옵션 유지를 위해 가벼운 CLA/DCO 서명을 요청할 수 있습니다 — [`LICENSING.md`](LICENSING.md) 참고.

---

## 라이선스

**전체가 [AGPL-3.0-or-later](LICENSE) 완전 오픈소스** — 라이브러리 포함 전 스택. AGPL 네트워크 조항으로 서비스로 운영해도 모든 부분이 오픈으로 유지됩니다. AGPL을 수용할 수 없는 조직을 위한 별도 상용 라이선스도 제공합니다. 전체 근거·약관: **[`LICENSING.md`](LICENSING.md)**.
