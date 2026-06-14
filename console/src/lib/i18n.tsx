"use client";

/**
 * Lightweight client-side i18n (MA3-101) — Korean / English.
 *
 * A LanguageProvider holds the active locale (persisted to localStorage) and a
 * `t(key)` lookup over a flat dictionary. Components call `useT()`. Keys missing
 * from a locale fall back to English, then to the key itself — so partial
 * translation degrades gracefully rather than throwing.
 *
 * Scope: navigational/structural UI chrome (nav, page headers, settings, login,
 * buttons, role labels). Dense technical data (finding text, hashes) stays as-is.
 */

import React, { createContext, useContext, useEffect, useState } from "react";

export type Lang = "en" | "ko";

const DICT: Record<string, { en: string; ko: string }> = {
  // nav
  "nav.dashboard": { en: "Dashboard", ko: "대시보드" },
  "nav.inventory": { en: "Inventory", ko: "인벤토리" },
  "nav.approvals": { en: "Approvals", ko: "승인" },
  "nav.drift": { en: "Drift", ko: "드리프트" },
  "nav.audit": { en: "Audit log", ko: "감사 로그" },
  "nav.settings": { en: "Settings", ko: "설정" },
  "shell.signOut": { en: "Sign out", ko: "로그아웃" },
  "shell.search": { en: "search assets, hashes…", ko: "자산·해시 검색…" },

  // page headers
  "page.dashboard.title": { en: "Organization overview", ko: "조직 현황" },
  "page.inventory.title": { en: "Asset inventory", ko: "자산 인벤토리" },
  "page.inventory.subtitle": {
    en: "Registered Skills, Tools and MCP servers · search and filter to triage.",
    ko: "등록된 Skill·Tool·MCP 서버 · 검색·필터로 분류하세요.",
  },
  "page.approvals.title": { en: "Approval queue", ko: "승인 대기열" },
  "page.drift.title": { en: "Drift alerts", ko: "드리프트 경보" },
  "page.audit.title": { en: "Audit log", ko: "감사 로그" },
  "page.settings.title": { en: "Settings", ko: "설정" },

  // dashboard widgets
  "dash.stat.assets": { en: "Registered assets", ko: "총 자산 수" },
  "dash.stat.approvals": { en: "Approval queue", ko: "승인 대기 큐" },
  "dash.stat.drift": { en: "Unresolved drift", ko: "미처리 드리프트" },
  "dash.stat.blocked": { en: "Blocked", ko: "차단됨" },
  "dash.riskDistribution": { en: "Risk distribution", ko: "위험 등급 분포" },
  "dash.recentActivity": { en: "Recent activity", ko: "최근 활동" },

  // login
  "login.title": { en: "Sign in to Vouchq", ko: "Vouchq 로그인" },
  "login.email": { en: "Email", ko: "이메일" },
  "login.password": { en: "Password", ko: "비밀번호" },
  "login.submit": { en: "Sign in", ko: "로그인" },
  "login.error": { en: "Invalid email or password.", ko: "이메일 또는 비밀번호가 올바르지 않습니다." },

  // settings tabs
  "settings.tab.sources": { en: "Sources", ko: "소스" },
  "settings.tab.channels": { en: "Notifications", ko: "알림" },
  "settings.tab.members": { en: "Members & roles", ko: "멤버 · 역할" },
  "settings.tab.policy": { en: "Policy rules", ko: "정책 규칙" },
  "settings.tab.suppressions": { en: "Suppressions", ko: "오탐 억제" },
  "settings.tab.account": { en: "Account", ko: "계정" },

  // roles
  "role.ADMIN": { en: "Admin", ko: "관리자" },
  "role.MEMBER": { en: "Member (approver)", ko: "멤버 (승인자)" },
  "role.VIEWER": { en: "Developer (install-only)", ko: "개발자 (설치 전용)" },

  // common actions
  "action.approve": { en: "Approve & pin", ko: "승인 · 박제" },
  "action.block": { en: "Block", ko: "차단" },
  "action.rescanAll": { en: "Rescan all", ko: "전체 재스캔" },
  "action.rescanning": { en: "Rescanning…", ko: "재스캔 중…" },
  "action.addToClaude": { en: "Add to Claude", ko: "Claude에 추가" },
  "action.addToCodex": { en: "Add to Codex", ko: "Codex에 추가" },
  "account.changePassword": { en: "Change password", ko: "비밀번호 변경" },

  // common UI
  "common.cancel": { en: "Cancel", ko: "취소" },
  "common.confirm": { en: "Confirm", ko: "확인" },
  "common.removed": { en: "Removed.", ko: "제거했습니다." },
  "common.deleted": { en: "Deleted.", ko: "삭제했습니다." },
  "common.saved": { en: "Saved.", ko: "저장했습니다." },
  "common.failed": { en: "Action failed. Please retry.", ko: "작업에 실패했습니다. 다시 시도하세요." },
  "error.title": { en: "Something went wrong", ko: "문제가 발생했습니다" },
  "error.desc": {
    en: "Couldn't load this page — the backend may be unreachable. This is an error, not an empty result.",
    ko: "이 페이지를 불러오지 못했습니다 — 백엔드에 연결하지 못했을 수 있습니다. 빈 결과가 아니라 오류입니다.",
  },
  "error.retry": { en: "Retry", ko: "다시 시도" },

  // first-run onboarding (empty state)
  "onboarding.title": { en: "No assets registered yet", ko: "아직 등록된 자산이 없습니다" },
  "onboarding.desc": {
    en: "Connect a Git repository or MCP server — vouchq inventories its Skills and tools, scans them for risk, and lets you approve & pin the versions you trust.",
    ko: "Git 저장소나 MCP 서버를 연결하세요 — vouchq가 그 Skill·도구를 인벤토리에 담고, 위험을 스캔하고, 신뢰하는 버전을 승인·박제하도록 도와줍니다.",
  },
  "onboarding.step1": { en: "Connect a source", ko: "소스 연결" },
  "onboarding.step2": { en: "Review the risk scan", ko: "위험 스캔 검토" },
  "onboarding.step3": { en: "Approve & pin", ko: "승인 · 박제" },
  "onboarding.cta": { en: "Connect your first source", ko: "첫 소스 연결하기" },
  "common.close": { en: "Close", ko: "닫기" },
  "common.delete": { en: "Delete", ko: "삭제" },
  "common.saving": { en: "Saving…", ko: "저장 중…" },
  "common.loading": { en: "Loading…", ko: "불러오는 중…" },
  "common.refresh": { en: "Refresh", ko: "새로 고침" },
  "common.copy": { en: "Copy", ko: "복사" },
  "common.copied": { en: "Copied ✓", ko: "복사됨 ✓" },
  "common.noMatch": { en: "No results match the current filters.", ko: "현재 필터에 맞는 결과가 없습니다." },
  "pager.prev": { en: "Prev", ko: "이전" },
  "pager.next": { en: "Next", ko: "다음" },
  "pager.of": { en: "of", ko: "/" },

  // page subtitles
  "page.approvals.subtitle": {
    en: "pending · review findings, then approve & pin or block.",
    ko: "건 대기 중 · 점검 후 승인·박제 또는 차단하세요.",
  },
  "page.drift.subtitle": {
    en: "drifted · observed sha256 no longer matches the pinned version. Review the diff, then re-approve & pin or block.",
    ko: "건 드리프트 · 관측된 sha256이 박제 버전과 다릅니다. 변경 내용을 확인한 후 재승인·박제하거나 차단하세요.",
  },
  "page.settings.subtitle": {
    en: "Sources, notifications, members & roles, policy rules, and suppressions.",
    ko: "소스, 알림, 멤버·역할, 정책 규칙, 오탐 억제.",
  },
  "page.audit.subtitle": {
    en: "Tamper-evident, hash-chained record of every decision. Filter, then export the selection.",
    ko: "모든 결정을 변조 방지 해시 체인으로 기록합니다. 필터링 후 선택 항목을 내보내세요.",
  },

  // inventory
  "inventory.search": { en: "search name, sha256, source…", ko: "이름·sha256·소스 검색…" },
  "inventory.filter.kind": { en: "Kind", ko: "유형" },
  "inventory.filter.status": { en: "Status", ko: "상태" },
  "inventory.filter.risk": { en: "Risk", ko: "위험" },
  "inventory.col.name": { en: "Name", ko: "이름" },
  "inventory.col.kind": { en: "Kind", ko: "유형" },
  "inventory.col.source": { en: "Source", ko: "소스" },
  "inventory.col.status": { en: "Status", ko: "상태" },
  "inventory.col.risk": { en: "Risk", ko: "위험" },
  "inventory.col.lastVerified": { en: "Last verified", ko: "마지막 검증" },
  "inventory.assets": { en: "assets", ko: "개 자산" },
  "inventory.sources": { en: "sources", ko: "개 소스" },
  "inventory.empty": { en: "No assets match the current filters.", ko: "현재 필터에 맞는 자산이 없습니다." },

  // status badge labels
  "status.APPROVED": { en: "APPROVED", ko: "승인됨" },
  "status.PENDING": { en: "PENDING", ko: "대기 중" },
  "status.DRIFTED": { en: "DRIFTED", ko: "드리프트" },
  "status.BLOCKED": { en: "BLOCKED", ko: "차단됨" },

  // approvals
  "approvals.selectAll": { en: "Select all", ko: "전체 선택" },
  "approvals.selected": { en: "selected", ko: "건 선택" },
  "approvals.pending": { en: "pending", ko: "건 대기" },
  "approvals.approveSelected": { en: "Approve selected", ko: "선택 항목 승인" },
  "approvals.blockSelected": { en: "Block selected", ko: "선택 항목 차단" },
  "approvals.approvePinBtn": { en: "Approve & pin", ko: "승인 · 박제" },
  "approvals.defer": { en: "Defer", ko: "연기" },
  "approvals.undo": { en: "undo", ko: "취소" },
  "approvals.viewDiff": { en: "View details & findings →", ko: "상세 · 발견 항목 보기 →" },
  "approvals.riskScore": { en: "Risk score", ko: "위험 점수" },
  "approvals.findings": { en: "findings", ko: "건 발견" },
  "approvals.result.approved": { en: "approved & pinned", ko: "승인 · 박제됨" },
  "approvals.result.blocked": { en: "blocked", ko: "차단됨" },
  "approvals.result.deferred": { en: "deferred", ko: "연기됨" },
  "approvals.empty": { en: "Queue clear — no pending approvals.", ko: "대기열이 비어 있습니다 — 승인 대기 항목이 없습니다." },
  "approvals.error": { en: "Action failed — nothing was changed. Please retry.", ko: "작업에 실패했습니다 — 변경된 내용이 없습니다. 다시 시도하세요." },
  "approvals.confirm.block": { en: "asset(s)? They can no longer be invoked until re-approved.", ko: "건을 차단하시겠습니까? 재승인 전까지 호출할 수 없습니다." },

  // drift
  "drift.reapprove": { en: "Re-approve & pin", ko: "재승인 · 박제" },
  "drift.toast.reapproved": { en: "Re-approved & re-pinned to the observed version.", ko: "관측 버전으로 재승인·재박제했습니다." },
  "drift.toast.blocked": { en: "Blocked.", ko: "차단했습니다." },
  "drift.result.reapproved": { en: "re-approved & pinned @", ko: "재승인 · 박제됨 @" },
  "drift.result.blocked": { en: "blocked", ko: "차단됨" },
  "drift.viewManifest": { en: "View manifest & findings →", ko: "매니페스트 · 발견 항목 보기 →" },
  "drift.empty": { en: "No active drift — every pinned asset matches its observed version.", ko: "드리프트 없음 — 모든 박제 자산이 관측 버전과 일치합니다." },
  "drift.error": { en: "Action failed — nothing was changed. Please retry.", ko: "작업에 실패했습니다 — 변경된 내용이 없습니다. 다시 시도하세요." },
  "drift.confirm.block": { en: "? It can no longer be invoked until re-approved.", ko: "을(를) 차단하시겠습니까? 재승인 전까지 호출할 수 없습니다." },
  "drift.detected": { en: "DRIFT DETECTED", ko: "드리프트 감지됨" },
  "drift.pinned": { en: "pinned", ko: "박제" },
  "drift.observed": { en: "observed", ko: "관측" },

  // audit log
  "audit.filter.action": { en: "Action", ko: "작업" },
  "audit.filter.actor": { en: "Actor", ko: "수행자" },
  "audit.filter.since": { en: "Since", ko: "이후" },
  "audit.export.csv": { en: "View → CSV", ko: "보기 → CSV" },
  "audit.export.json": { en: "View → JSON", ko: "보기 → JSON" },
  "audit.export.viewHint": { en: "Exports the current page only", ko: "현재 페이지만 내보냅니다" },
  "audit.export.full": { en: "Download full log", ko: "전체 로그 다운로드" },
  "audit.export.fullJson": { en: "Full log (JSON)", ko: "전체 로그 (JSON)" },
  "audit.export.fullCsv": { en: "Full log (CSV)", ko: "전체 로그 (CSV)" },
  "audit.allActions": { en: "All actions", ko: "전체 작업" },
  "audit.allActors": { en: "All actors", ko: "전체 수행자" },
  "audit.loadError": { en: "Could not load the audit log.", ko: "감사 로그를 불러오지 못했습니다." },
  "audit.header": { en: "Audit log", ko: "감사 로그" },
  "audit.chain.verifying": { en: "verifying chain…", ko: "체인 검증 중…" },
  "audit.chain.verified": { en: "chain verified", ko: "체인 검증됨" },
  "audit.chain.entries": { en: "entries", ko: "개 항목" },
  "audit.chain.broken": { en: "chain BROKEN", ko: "체인 손상됨" },
  "audit.chain.at": { en: "at", ko: "위치" },
  "audit.chain.hashed": { en: "hash-chained", ko: "해시 체인 적용됨" },
  "audit.by": { en: "by", ko: "수행자:" },
  "audit.empty": { en: "No entries match the current filters.", ko: "현재 필터에 맞는 항목이 없습니다." },
  "audit.prev": { en: "prev", ko: "이전" },
  "audit.entry": { en: "entry", ko: "항목" },

  // findings panel
  "findings.riskScore": { en: "RISK SCORE", ko: "위험 점수" },
  "findings.riskScoreEffective": { en: "RISK SCORE (effective)", ko: "위험 점수 (유효)" },
  "findings.findings": { en: "findings", ko: "건 발견" },
  "findings.suppressed": { en: "suppressed", ko: "건 억제됨" },
  "findings.suppressedChip": { en: "Suppressed", ko: "억제됨" },
  "findings.suppress": { en: "Suppress", ko: "억제" },
  "findings.suppressRule": { en: "Suppress rule", ko: "규칙 억제" },
  "findings.none": { en: "No findings — this version is clean.", ko: "발견 항목 없음 — 이 버전은 안전합니다." },
  "findings.scan": { en: "scan", ko: "스캔" },
  "findings.raw": { en: "raw", ko: "원본" },
  "findings.critical": { en: "critical", ko: "심각" },
  "findings.warn": { en: "warn", ko: "경고" },
  "findings.info": { en: "info", ko: "정보" },
  "findings.none.summary": { en: "none", ko: "없음" },

  // decision actions
  "decision.approvePinDrifted": { en: "Re-approve & re-pin", ko: "재승인 · 재박제" },
  "decision.approvePin": { en: "Approve & pin", ko: "승인 · 박제" },
  "decision.confirm.block": { en: "Block this asset? It will no longer be invocable until re-approved.", ko: "이 자산을 차단하시겠습니까? 재승인 전까지 호출할 수 없습니다." },
  "decision.result.reapproved": { en: "Re-approved & re-pinned to the observed version.", ko: "관측 버전으로 재승인·재박제되었습니다." },
  "decision.result.approved": { en: "Approved & pinned at the current version.", ko: "현재 버전으로 승인·박제되었습니다." },
  "decision.result.blocked": { en: "Blocked. This asset can no longer be invoked.", ko: "차단되었습니다. 이 자산은 더 이상 호출할 수 없습니다." },
  "decision.roleGate": { en: "Approving or blocking requires the Member or Admin role.", ko: "승인 또는 차단은 멤버(승인자) 또는 관리자 역할이 필요합니다." },
  "decision.error.approve": { en: "Couldn't pin — the action failed. Nothing was changed; please retry.", ko: "박제에 실패했습니다. 변경된 내용이 없습니다. 다시 시도하세요." },
  "decision.error.block": { en: "Couldn't block — the action failed. Nothing was changed; please retry.", ko: "차단에 실패했습니다. 변경된 내용이 없습니다. 다시 시도하세요." },

  // add to claude / skill install
  "install.skill.title": { en: "Install approved version", ko: "승인된 버전 설치" },
  "install.skill.loading": { en: "Loading approved version…", ko: "승인된 버전을 불러오는 중…" },
  "install.skill.error": { en: "Could not load the approved version.", ko: "승인된 버전을 불러올 수 없습니다." },
  "install.skill.copyError": { en: "Copy unavailable here — select the command above to copy manually.", ko: "여기서는 복사할 수 없습니다 — 위 명령어를 직접 선택하여 복사하세요." },
  "install.skill.installsTo": { en: "Installs the", ko: "다음 위치에" },
  "install.skill.approved": { en: "approved (pinned)", ko: "승인(박제)된" },
  "install.skill.versionInto": { en: "version into", ko: "버전을 설치합니다:" },
  "install.skill.notLive": { en: "— not the live upstream. sha256", ko: "— 최신 소스가 아닙니다. sha256" },
  "install.skill.download": { en: "Download SKILL.md", ko: "SKILL.md 다운로드" },

  // mcp install
  "install.mcp.title": { en: "Install approved MCP server", ko: "승인된 MCP 서버 설치" },
  "install.mcp.loading": { en: "Loading vouched config…", ko: "검증된 설정을 불러오는 중…" },
  "install.mcp.error": { en: "This MCP server isn't installable yet — it must have at least one approved tool and no blocked or drifted tools.", ko: "이 MCP 서버는 아직 설치할 수 없습니다. 승인된 도구가 하나 이상 있어야 하며, 차단되거나 드리프트된 도구가 없어야 합니다." },
  "install.mcp.copyError": { en: "Copy unavailable here — select the config above to copy manually.", ko: "여기서는 복사할 수 없습니다 — 위 설정을 직접 선택하여 복사하세요." },
  "install.mcp.vouchedFor": { en: "Vouched config for", ko: "검증된 설정 —" },
  "install.mcp.withheldTitle": { en: "Config withheld — server not vouched", ko: "설정 발급 보류 — 미검증 서버" },
  "install.mcp.withheldDesc": {
    en: "vouchq only issues an install config for a vouched MCP server (approved, no blocked or drifted tools). This is the governance signal, not an error:",
    ko: "vouchq는 vouched된 MCP 서버(승인됨, 차단·드리프트 도구 없음)에만 설치 설정을 발급합니다. 오류가 아니라 거버넌스 신호입니다:",
  },
  "install.copyToast": { en: "Copied to clipboard.", ko: "클립보드에 복사했습니다." },
  "install.copyFallback": { en: "Couldn't copy automatically — the text is selected; press ⌘/Ctrl-C.", ko: "자동 복사가 안 됩니다 — 텍스트를 선택했으니 ⌘/Ctrl-C로 복사하세요." },
  "install.mcp.toolsApproved": { en: "tools approved", ko: "개 도구 승인됨" },
  "install.mcp.addNote": { en: "Add it to your agent; set your own token where shown.", ko: "에이전트에 추가하고, 표시된 위치에 토큰을 설정하세요." },
  "install.mcp.vouchedNote": { en: "(vouchq vouches the server as of issuance; it isn't in the connection path — live drift is caught separately by re-scan.)", ko: "(vouchq는 발급 시점 기준으로 서버를 보증합니다. 연결 경로에는 개입하지 않으며, 실시간 드리프트는 재스캔으로 별도로 탐지합니다.)" },

  // version history
  "versionHistory.title": { en: "Version history", ko: "버전 히스토리" },

  // settings — sources
  "settings.sources.heading": { en: "Connected sources", ko: "연결된 소스" },
  "settings.sources.connect": { en: "Connect source", ko: "소스 연결" },
  "settings.sources.close": { en: "Close", ko: "닫기" },
  "settings.sources.branch": { en: "branch", ko: "브랜치" },
  "settings.sources.assets": { en: "assets", ko: "개 자산" },
  "settings.sources.scanned": { en: "scanned", ko: "스캔됨" },
  "settings.sources.rescan": { en: "Rescan", ko: "재스캔" },
  "settings.sources.scanning": { en: "Scanning…", ko: "스캔 중…" },
  "settings.sources.remove": { en: "Remove", ko: "제거" },
  "settings.sources.toast.rescanned": { en: "Re-scan complete.", ko: "재스캔 완료." },
  "settings.sources.toast.connected": { en: "Source connected & scanned.", ko: "소스를 연결·스캔했습니다." },
  "settings.sources.toast.connectFailed": { en: "Couldn't connect — check the URL and access token.", ko: "연결하지 못했습니다 — URL과 액세스 토큰을 확인하세요." },
  "settings.sources.removing": { en: "Removing…", ko: "제거 중…" },
  "settings.sources.confirm.delete": {
    en: "Remove this source and everything ingested from it (tools, versions, scans, approvals, drift)? This cannot be undone.",
    ko: "이 소스와 여기서 인제스천된 모든 것(도구·버전·스캔·승인·드리프트)을 제거하시겠습니까? 되돌릴 수 없습니다.",
  },
  "settings.sources.form.title": { en: "Connect a Git source", ko: "Git 소스 연결" },
  "settings.sources.form.subtitle": { en: "Scan repository for MCP servers, Skills & Tools.", ko: "저장소에서 MCP 서버·스킬·도구를 스캔합니다." },
  "settings.sources.form.url": { en: "Repository URL", ko: "저장소 URL" },
  "settings.sources.form.token": { en: "Access token", ko: "액세스 토큰" },
  "settings.sources.form.branch": { en: "Branch", ko: "브랜치" },
  "settings.sources.form.note": { en: "Token is encrypted at rest", ko: "토큰은 저장 시 암호화됩니다" },
  "settings.sources.form.noteDetail": { en: "(AES-256) and used read-only to clone & scan. It is never logged or shown again after save.", ko: "(AES-256) 읽기 전용으로 클론·스캔에만 사용됩니다. 저장 후에는 다시 표시되지 않습니다." },
  "settings.sources.form.submit": { en: "Connect & scan", ko: "연결 · 스캔" },
  "settings.sources.form.submitting": { en: "Connecting…", ko: "연결 중…" },

  // settings — channels
  "settings.channels.heading": { en: "Notification channels", ko: "알림 채널" },
  "settings.channels.selfHostedNote": { en: "Self-hosted: all channels are", ko: "자체 호스팅: 모든 채널은" },
  "settings.channels.optIn": { en: "opt-in and off by default", ko: "기본적으로 비활성화 상태입니다" },
  "settings.channels.optInNote": { en: ". Nothing leaves your network until you enable and test a destination.", ko: ". 대상을 활성화하고 테스트하기 전까지는 네트워크 외부로 데이터가 나가지 않습니다." },
  "settings.channels.add": { en: "Add channel", ko: "채널 추가" },
  "settings.channels.empty": { en: "No notification channels configured. Add one above.", ko: "알림 채널이 설정되어 있지 않습니다. 위에서 추가하세요." },
  "settings.channels.test": { en: "Test", ko: "테스트" },
  "settings.channels.testing": { en: "Testing…", ko: "테스트 중…" },
  "settings.channels.testOk": { en: "Test sent", ko: "테스트 전송됨" },
  "settings.channels.testFail": { en: "Test failed", ko: "테스트 실패" },
  "settings.channels.confirm.delete": { en: "Delete this notification channel?", ko: "이 알림 채널을 삭제하시겠습니까?" },
  "settings.channels.form.title": { en: "Add notification channel", ko: "알림 채널 추가" },
  "settings.channels.form.subtitle": { en: "New channels are off by default — enable after testing.", ko: "새 채널은 기본적으로 비활성화 상태입니다 — 테스트 후 활성화하세요." },
  "settings.channels.form.type": { en: "Type", ko: "유형" },
  "settings.channels.form.name": { en: "Name", ko: "이름" },
  "settings.channels.form.target": { en: "Target (address / URL / channel)", ko: "대상 (주소 / URL / 채널)" },
  "settings.channels.form.submit": { en: "Add channel", ko: "채널 추가" },

  // settings — members
  "settings.members.heading": { en: "Members", ko: "멤버" },
  "settings.members.invite": { en: "Invite member", ko: "멤버 초대" },
  "settings.members.loading": { en: "Loading members…", ko: "멤버 목록을 불러오는 중…" },
  "settings.members.error": { en: "Could not load members — check backend.", ko: "멤버를 불러올 수 없습니다 — 백엔드를 확인하세요." },
  "settings.members.empty": { en: "No active members found.", ko: "활성 멤버가 없습니다." },
  "settings.members.col.member": { en: "Member", ko: "멤버" },
  "settings.members.col.role": { en: "Role", ko: "역할" },
  "settings.members.col.joined": { en: "Joined", ko: "가입일" },
  "settings.members.col.actions": { en: "Actions", ko: "작업" },
  "settings.members.deactivate": { en: "Deactivate", ko: "비활성화" },
  "settings.members.cannotSelf": { en: "Cannot deactivate yourself", ko: "본인은 비활성화할 수 없습니다" },
  "settings.members.confirm.deactivate": { en: "They will lose access.", ko: "해당 사용자는 접근 권한을 잃게 됩니다." },
  "settings.members.form.title": { en: "Invite new member", ko: "새 멤버 초대" },
  "settings.members.form.email": { en: "Email", ko: "이메일" },
  "settings.members.form.displayName": { en: "Display name", ko: "표시 이름" },
  "settings.members.form.tempPassword": { en: "Temporary password", ko: "임시 비밀번호" },
  "settings.members.form.role": { en: "Role", ko: "역할" },
  "settings.members.form.submit": { en: "Create member", ko: "멤버 생성" },
  "settings.members.form.creating": { en: "Creating…", ko: "생성 중…" },
  "settings.members.form.error": { en: "Failed to create member.", ko: "멤버 생성에 실패했습니다." },

  // settings — policy
  "settings.policy.heading": { en: "Policy rules", ko: "정책 규칙" },
  "settings.policy.subtitle": { en: "Automated enforcement rules applied during ingestion and drift scanning.", ko: "수집 및 드리프트 스캔 시 자동으로 적용되는 규칙입니다." },
  "settings.policy.addRule": { en: "Add rule", ko: "규칙 추가" },
  "settings.policy.empty": { en: "No policy rules configured. Add one above.", ko: "정책 규칙이 없습니다. 위에서 추가하세요." },
  "settings.policy.action": { en: "Action:", ko: "작업:" },
  "settings.policy.severity": { en: "severity", ko: "심각도" },
  "settings.policy.risk": { en: "risk ≥", ko: "위험 ≥" },
  "settings.policy.category": { en: "category", ko: "카테고리" },
  "settings.policy.nameMatches": { en: "name matches", ko: "이름 일치" },
  "settings.policy.confirm.delete": { en: "Delete this policy rule?", ko: "이 정책 규칙을 삭제하시겠습니까?" },
  "settings.policy.form.title": { en: "Add policy rule", ko: "정책 규칙 추가" },
  "settings.policy.form.subtitle": { en: "Rules are evaluated in priority order (lowest number first).", ko: "규칙은 우선순위 순서로 적용됩니다 (낮은 번호 우선)." },
  "settings.policy.form.name": { en: "Rule name", ko: "규칙 이름" },
  "settings.policy.form.priority": { en: "Priority", ko: "우선순위" },
  "settings.policy.form.action": { en: "Action", ko: "작업" },
  "settings.policy.form.severityFilter": { en: "Severity filter (optional)", ko: "심각도 필터 (선택)" },
  "settings.policy.form.minRisk": { en: "Min risk score (optional)", ko: "최소 위험 점수 (선택)" },
  "settings.policy.form.submit": { en: "Add rule", ko: "규칙 추가" },
  "settings.policy.action.autoBlock": { en: "Auto-block", ko: "자동 차단" },
  "settings.policy.action.hold": { en: "Hold", ko: "보류" },

  // settings — suppressions
  "settings.suppress.heading": { en: "Scanner suppressions", ko: "스캐너 억제 설정" },
  "settings.suppress.subtitle": { en: "Suppressions exclude specific findings from the effective risk score. Suppressed findings remain visible (transparency) but are de-emphasised. To suppress a finding, use the \"Suppress\" button in the tool detail view.", ko: "억제 설정은 특정 발견 항목을 유효 위험 점수에서 제외합니다. 억제된 항목은 투명성을 위해 표시되지만 강조되지 않습니다. 발견 항목 억제는 도구 상세 화면의 \"억제\" 버튼을 사용하세요." },
  "settings.suppress.add": { en: "Add suppression", ko: "억제 추가" },
  "settings.suppress.empty": { en: "No active suppressions. Use the \"Suppress\" action in a tool's findings panel to add one.", ko: "활성 억제 설정이 없습니다. 도구 발견 항목 패널의 \"억제\" 작업을 사용하세요." },
  "settings.suppress.col.ruleId": { en: "Rule ID", ko: "규칙 ID" },
  "settings.suppress.col.scope": { en: "Scope", ko: "범위" },
  "settings.suppress.col.reason": { en: "Reason", ko: "사유" },
  "settings.suppress.col.createdBy": { en: "Created by", ko: "생성자" },
  "settings.suppress.col.createdAt": { en: "Created at", ko: "생성 일시" },
  "settings.suppress.scope.orgWide": { en: "org-wide", ko: "조직 전체" },
  "settings.suppress.scope.wholeRule": { en: "whole rule", ko: "전체 규칙" },
  "settings.suppress.scope.fingerprint": { en: "fingerprint", ko: "핑거프린트" },
  "settings.suppress.scope.tool": { en: "tool:", ko: "도구:" },
  "settings.suppress.confirm.delete": { en: "Remove this suppression? Its findings will count toward risk again.", ko: "이 억제 설정을 삭제하시겠습니까? 해당 발견 항목이 다시 위험 점수에 반영됩니다." },
  "settings.suppress.form.title": { en: "Add suppression", ko: "억제 추가" },
  "settings.suppress.form.subtitle": { en: "Leave Tool ID blank for org-wide scope. Leave Fingerprint blank to suppress the whole rule.", ko: "조직 전체 범위로 설정하려면 도구 ID를 비워두세요. 규칙 전체를 억제하려면 핑거프린트를 비워두세요." },
  "settings.suppress.form.ruleId": { en: "Rule ID *", ko: "규칙 ID *" },
  "settings.suppress.form.reason": { en: "Reason (optional)", ko: "사유 (선택)" },
  "settings.suppress.form.toolId": { en: "Tool ID (blank = org-wide)", ko: "도구 ID (비우면 조직 전체)" },
  "settings.suppress.form.fingerprint": { en: "Fingerprint (blank = whole rule)", ko: "핑거프린트 (비우면 전체 규칙)" },
  "settings.suppress.form.submit": { en: "Add suppression", ko: "억제 추가" },
  "settings.suppress.form.error": { en: "Failed to create suppression.", ko: "억제 생성에 실패했습니다." },

  // settings — account
  "settings.account.heading": { en: "Account", ko: "계정" },
  "settings.account.signedInAs": { en: "Signed in as", ko: "로그인 계정:" },
  "settings.account.curPassword": { en: "Current password", ko: "현재 비밀번호" },
  "settings.account.newPassword": { en: "New password (min 8)", ko: "새 비밀번호 (최소 8자)" },
  "settings.account.confirmPassword": { en: "Confirm new password", ko: "새 비밀번호 확인" },
  "settings.account.changing": { en: "Changing…", ko: "변경 중…" },
  "settings.account.err.minLength": { en: "New password must be at least 8 characters.", ko: "새 비밀번호는 8자 이상이어야 합니다." },
  "settings.account.err.mismatch": { en: "New passwords do not match.", ko: "새 비밀번호가 일치하지 않습니다." },
  "settings.account.err.failed": { en: "Could not change password — check your current password.", ko: "비밀번호를 변경할 수 없습니다 — 현재 비밀번호를 확인하세요." },
  "settings.account.success": { en: "Password changed.", ko: "비밀번호가 변경되었습니다." },
};

interface I18nState {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: (key: string) => string;
}

const I18nContext = createContext<I18nState>({
  lang: "en",
  setLang: () => {},
  t: (k) => k,
});

const STORAGE_KEY = "vouchq-lang";

export function LanguageProvider({ children }: { children: React.ReactNode }) {
  const [lang, setLangState] = useState<Lang>("en");

  // Hydrate from localStorage on mount (avoids SSR/client mismatch).
  useEffect(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === "ko" || saved === "en") setLangState(saved);
  }, []);

  // Keep <html lang> in sync so AT/pronunciation/hyphenation match (WCAG 3.1.1).
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  const setLang = (l: Lang) => {
    setLangState(l);
    localStorage.setItem(STORAGE_KEY, l);
  };

  const t = (key: string): string => {
    const entry = DICT[key];
    if (!entry) return key;
    return entry[lang] || entry.en || key;
  };

  return (
    <I18nContext.Provider value={{ lang, setLang, t }}>
      {children}
    </I18nContext.Provider>
  );
}

export function useT(): I18nState {
  return useContext(I18nContext);
}
