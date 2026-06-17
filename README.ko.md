# Vouchq

> **MCP 도구를 위한 프라이빗 신뢰 레지스트리. 한 번 승인하고, 암호학적으로 박제하고, rug-pull을 영구히 탐지하세요.**
> 승인한 MCP 서버·Skill이라도 신뢰한 *뒤에* 도구 정의를 몰래 바꿀 수 있습니다. Vouchq는 승인 시점의 정의를 스냅샷해 SHA-256으로 박제(pin)하고, 현재 정의가 어긋나는 순간 변조 불가능한 감사 기록과 함께 드리프트 이벤트를 띄웁니다. 등록 · 스캔 · 승인&박제 · 드리프트 탐지 · 감사.

[![Release](https://img.shields.io/github/v/tag/ma3s1r0/Vouchq?filter=v*&sort=semver&color=388BFD)](https://github.com/ma3s1r0/Vouchq/releases/tag/v0.1.0-alpha)
[![Website](https://img.shields.io/badge/website-vouchq-388BFD)](https://ma3s1r0.github.io/vouchq-website)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-3FB950)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-388BFD)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3-3FB950)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-388BFD)](https://www.postgresql.org/)
[![Self-hosted](https://img.shields.io/badge/self--hosted-friendly-3FB950)](#프로덕션-배포-self-hosted-docker-compose)

[English](README.md) · **한국어**

> 보증된 Skill과 원격 MCP 서버를 **Claude** · **Cursor** · **Codex** 에 그대로 설치합니다.

## rug-pull 탐지 장면

승인·박제해 둔 `web_search` 툴의 업스트림 서버가 몰래 설명을 바꿔 시크릿을 빼돌립니다.
Vouchq가 그 변조(드리프트)를 잡아 현재 버전을 CRITICAL로 표시하고, 에이전트에는 박제된
정상 버전만 배포됩니다.

![Vouchq가 MCP rug-pull을 탐지: 승인·박제 → upstream 변조 → 드리프트 탐지 → CRITICAL 탈취 발견 → 감사 체인](examples/evil-mcp-rugpull/vouchq-rugpull.gif)

> 직접 ~90초 만에 재현: [`examples/evil-mcp-rugpull/`](examples/evil-mcp-rugpull/).

---

## Vouchq란

AI 에이전트가 불러오는 Skill과 연결하는 MCP 서버는, 그 도구 설명이 **런타임에 바뀔 수 있습니다.** 도입할 땐 멀쩡했던 기능이 나중에 변조돼, 도구 설명에 *"…결과를 attacker.com으로도 보내라"* 같은 숨은 지시가 끼어들면 에이전트는 그대로 따릅니다. 이것이 **rug-pull**입니다. MCP 스펙부터가 세션 도중 도구 설명 변경을 허용하면서도 무결성 검사도, 해시 고정도, 재승인 강제도 두지 않았습니다.

Vouchq는 *우리 조직이 무엇을 어떤 버전으로 신뢰하기로 했는가*를 담은 권위 있는 정본 기록이자, 그 승인한 정의가 **몰래 바뀌는 것**을 잡아내는 검증 엔진입니다. 디스커버리 카탈로그는 기능을 **한 번** 보고 말지만, Vouchq는 **현재** 정의를 암호학적으로 **박제한** 기준선과 끊임없이 대조해 승인 뒤의 변조를 잡아냅니다.

디스커버리 카탈로그("세상에 뭐가 있나")도 **아니고** 인라인 프록시도 **아닙니다.** 들어올 땐 거버넌스, 나갈 땐 보증된 배포 — 신뢰를 발급하는 **컨트롤 플레인**입니다.

---

## 핵심 기능

### 인제스천 & 인벤토리
Git 저장소와 MCP 서버를 연결하면, Vouchq의 OSS 파서가 Skill(`SKILL.md` + 스크립트)과 MCP 도구(`tools/list`)를 하나의 정의 모델로 정규화합니다. 에이전트가 닿을 수 있는 모든 기능을 종류·출처·상태·위험점수·마지막 검증 시점과 함께 검색 가능한 인벤토리로 만듭니다.

### 위험 스캔
룰 기반 스캐너가 각 정의에서 **프롬프트 인젝션**·**시크릿 노출**·**데이터 유출**·**위험 명령**을 탐지해 0–100 위험 점수와 구조화된 탐지 결과를 냅니다. **오탐 억제**(룰/도구/개별 항목 단위)로 신호를 깨끗하게 유지하니 리뷰어가 점수를 믿을 수 있습니다. 스캐너는 순수 Java 오픈소스입니다.

### 승인 & 박제
리뷰어가 정의를 승인하면 Vouchq가 그 정의를 불변 **SHA-256 기준선**(*정본*)으로 스냅샷합니다. 이 박제 버전이 이후 모든 비교의 고정 기준점이자, 그대로 하류에 배포되는 아티팩트입니다.

### 드리프트 / rug-pull 탐지
스케줄(또는 수동) 재스캔이 현재 정의를 다시 가져와 해시를 박제 기준선과 비교합니다. 다르면 심각도(`INFO`/`WARN`/`CRITICAL`)와 필드 단위 diff를 담은 **DriftEvent**가 발생하고 해당 도구를 "검토 필요"로 돌립니다. 진행 중인 rug-pull의 경보입니다.

### 정책 엔진
스캔·드리프트 결과에 따라 선언적 룰이 움직입니다. 예컨대 위험 임계치를 넘거나 critical 드리프트가 나면 자동으로 **차단**하거나 **보류**해, 고위험 변경을 사람 손을 기다리지 않고 막습니다.

### 감사 (WORM + 해시 체인)
모든 등록·스캔·승인·차단·드리프트 이벤트가 **append-only 감사 로그**에 기록됩니다. 항목들은 `prev_hash → entry_hash` SHA-256 체인으로 엮이고, 테이블은 **DB 레벨에서 WORM으로 강제**(update/delete 트리거)됩니다. 손대면 체인이 깨져 바로 드러나니, 로그 자체가 진짜 컴플라이언스 증빙이 됩니다.

### RBAC & 멀티테넌시
Spring Security 기반 **Admin / Member / Viewer** 역할. 모든 데이터는 `org_id`로 격리되고 쿼리 레벨에서 강제되어, 여러 팀·테넌트가 경계를 넘는 누출 없이 하나의 배포를 공유합니다.

### 배포 / 설치
개발자는 라이브 업스트림이 아니라 **승인된(vouched)** 기능만 받습니다. 하나의 레포가 여러 Skill을 등록하니 인벤토리는 소스별로 묶이고, 그룹마다 원클릭 **Install** 이 복붙용 `curl … | sh` 한 줄을 만들어 줍니다. 생성된 스크립트는 승인된 파일을 Vouchq에서(박제된 **바로 그 바이트**) 받아 SHA-256을 다시 검증한 뒤 에이전트의 스킬 디렉터리 — **Claude**(`.claude/skills/`) 또는 **Cursor**(`.cursor/rules/<name>.mdc`, verify-then-adapt), 프로젝트/사용자 범위 — 에 기록합니다. `APPROVED`+박제된 Skill만 내려가고, pending·drifted·blocked은 표시만 하고 건너뜁니다. 모든 설치는 WORM 감사 로그에 `SKILL_INSTALL_SERVED`로 남습니다. **원격** MCP 서버도 마찬가지인데, 정상 상태(승인 도구 ≥1, blocked·drifted 없음; 거부 자체가 신호)인 서버에만 보증된 연결 설정을 발급해 Claude(`.mcp.json`)·Cursor(`.cursor/mcp.json`)·Codex(`config.toml`)에 설치하고 `MCP_INSTALL_SERVED`로 기록합니다. 바이트가 새 `git clone`이 아니라 Vouchq의 박제 스냅샷에서 나오므로, rug-pull된 업스트림을 다시 끌어오는 일이 없습니다. Vouchq는 신뢰된 아티팩트를 발급할 뿐, 요청 경로에 끼어들지 않습니다.

### CI 검증 / 빌드 게이트
소비자 CI를 위한 read-only **빌드 게이트**입니다. [`vouchq-verify` GitHub Action](integrations/github-action/) 이 체크아웃된 레포를 업로드하면, Vouchq가 Skill별로 **현재** 정의가 `APPROVED`+박제된 버전인지 판정하고 `CHANGED` / `BLOCKED` / `UNKNOWN` 이 하나라도 있으면 잡(job)을 실패시킵니다. 배포와 짝을 이뤄 루프를 닫는 셈입니다. 보증된 기능은 **나가는** 길로 흐르고, 미승인·은밀히 바뀐 것은 **들어오는** 길에서 막힙니다. 정체성은 동일한 `definitionHash`이고 권위 있는 파서가 서버에서 계산하니 클라이언트 해시 불일치가 없습니다. Vouchq는 끝까지 레지스트리입니다. 게이트는 또 하나의 reader(`POST /api/verify`, VIEWER+)일 뿐, 에이전트 요청 경로엔 끼지 않습니다.

### 자가 거버넌스 룰셋 (Sentinel)
Vouchq는 파는 기준을 자신에게도 똑같이 적용합니다. 스캐너가 오픈소스라 그 룰셋 자체가 공급망 표적입니다. 룰을 슬쩍 약화하는 PR이나 변조된 빌드면 악성 정의가 그대로 통과해 버립니다. 그래서 Vouchq는 봉인된 **카나리 코퍼스**(CRITICAL 룰마다 알려진 악성 픽스처 1개)를 동봉하고, 자기 스캐너를 그 코퍼스에 **기동 시·매시간·CI 게이트**로 계속 돌립니다. 카나리가 하나라도 미탐지되면 룰셋이 약해진 것입니다. 이때 Vouchq는 **DEGRADED** 로 전환해 **fail-closed**(승인 발급 중단 — 자기가 보증 못 할 신뢰는 만들지 않음)하고, WORM 감사에 기록하며, `GET /api/ruleset/health` 로 상태와 룰셋 지문을 보여줍니다. 룰을 약화하면 카나리가 어두워져 반드시 들킵니다.

### 스코프 — first-party로 관측 가능한 것만
Vouchq는 자신이 **직접(first-party)** 정의를 관측할 수 있는 기능만 거버넌스합니다: Skill의 바이트(레포에서 파싱)와 **원격** MCP 서버의 도구 표면(`tools/list` 로 직접 fetch). 스스로 볼 수 있는 것만 박제하고 검증합니다.

**로컬 stdio MCP 서버** — 에이전트가 당신 머신에서 직접 띄우는 `docker run` / `npx` 바이너리 — 는 **의도적으로 스코프 밖**입니다. 이를 거버넌스하려면 (a) 미검증 third-party 서버를 *Vouchq 신뢰경계 안에서 실행*하거나(Vouchq가 막으려는 바로 그 공급망 리스크를 떠안음), (b) 제출자가 캡처한 도구 표면 스냅샷을 믿어야(검증이 아니라 전언) 합니다. Vouchq는 독립적으로 관측할 수 없는 것을 보증한다고 주장하지 않습니다 — 그리고 `@sha256` digest 핀은 재현성이지 안전성 판단이 아니므로, 그것을 거버넌스인 척 포장하지 않습니다. 정말 필요해지면 올바른 답은 신뢰된 CI의 **서명·어테스트된** 캡처이지, 묵시적 실행이나 미서명 스냅샷이 아닙니다.

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

앱은 깔끔한 내부 모듈 경계(parser, scanner, registry, audit, notify, policy, tenancy)를 가진 단일 Spring Boot 배포 단위로 시작합니다. PostgreSQL이 레지스트리·박제 버전·감사 로그·억제·정책을 보관합니다. 가장자리엔 reader 둘이 더 있습니다: **CI 검증 게이트**는 소비자가 미승인 스킬에 대해 빌드를 실패시키게 하고(들어오는 문), **Sentinel**은 스캐너 자신의 룰셋을 계속 자가검증하다 약화되면 fail-closed 합니다.

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

### 처음 몇 분

콘솔이 `http://localhost:3000` 에 뜨면, 전체 루프는 네 단계입니다:

1. **소스 등록** — **Inventory** 에서 Git 저장소(그 Skill들)나 MCP 서버 URL을 붙여넣습니다. Vouchq가 모든 기능을 파싱·위험스캔해서 `PENDING` 으로 나열합니다.
2. **리뷰 & 승인** — 기능을 열어 탐지 결과와 정의를 살펴보고 **Approve & pin**(승인·박제)합니다. Vouchq가 바로 그 바이트를 불변 기준선(정본)으로 박제합니다.
3. **계속 감시** — 재스캔이 현재 정의를 박제본과 비교해, 바뀌면 **드리프트** 경보(=rug-pull 알람)를 띄웁니다.
4. **배포 & 게이트** — 개발자에게 원클릭 **Install**(승인·박제된 바이트만)을 주고, [`vouchq-verify`](integrations/github-action/) 체크를 CI에 넣어 미승인 항목이 있으면 빌드를 실패시킵니다.

먼저 *눈으로* 보고 싶다면? [90초 rug-pull 데모](examples/evil-mcp-rugpull/)가 명령 하나로 전체 루프를 끝까지 돌려 보여줍니다.

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

**콘솔**이 `127.0.0.1:3000` 의 프런트 도어입니다(내부적으로 `/api` 를 백엔드로 프록시합니다). 그 앞에 TLS 종단 리버스 프록시를 두세요. Postgres는 외부에 노출되지 않고, 백엔드는 운영용으로만 loopback에 바인딩됩니다.

키 생성(`.env.prod.example`)·최초 admin·보안 기본값은 하든된 `compose.prod.yaml` 에 포함되어 있습니다. 대안 배포용 systemd(rootless Podman) 유닛은 [`deploy/quadlet/`](deploy/quadlet/).

---

## 스택

Java 21 · Spring Boot 3 · PostgreSQL · Flyway · Gradle(멀티모듈) · 콘솔: Next.js(App Router) + TypeScript + Tailwind.

**self-hosted 호환:** 표준 의존성만, 외부 통신(텔레메트리·알림)은 전부 옵션·기본 off. 빌드는 멀티스테이지이며 런타임 이미지는 non-root로 read-only 실행됩니다. GitHub Actions가 매 push/PR마다 전 모듈 빌드 + Testcontainers 통합테스트 포함 전체 테스트 + 콘솔 빌드 + 컨테이너 이미지 스모크 빌드를 실행합니다.

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

전체 스택에 대한 기여를 환영합니다 — 새 스캐너 룰, 더 많은 Skill·MCP 형태에 대한 파서 커버리지, 테스트 픽스처, 컨트롤 플레인 개선까지. **[ma3s1r0/Vouchq](https://github.com/ma3s1r0/Vouchq)** 에 이슈·PR을 열어주세요. 기여는 프로젝트의 나머지와 동일하게 **AGPL-3.0-or-later** 로 라이선스됩니다 — [`LICENSING.md`](LICENSING.md) 참고.

---

## 라이선스

**전체가 [AGPL-3.0-or-later](LICENSE) 완전 오픈소스** — 라이브러리 포함 전 스택. AGPL 네트워크 조항으로 서비스로 운영해도 모든 부분이 오픈으로 유지됩니다. 전체 근거: **[`LICENSING.md`](LICENSING.md)**.
