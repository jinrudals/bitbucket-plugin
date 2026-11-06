# Bitbucket Cloud Jenkins Plugin — Design

## 1. Goal

Jenkins GitLab Plugin과 유사한 기능을 **Bitbucket Cloud(bitbucket.org)** 전용 Jenkins 플러그인으로 제공한다.
Workspace 단위 webhook까지 지원하는 것이 핵심 요구사항이다.

### Scope (MVP)

- Webhook 트리거: `repo:push`, `pullrequest:created`, `pullrequest:updated`
- 빌드 상태(commit status) 보고
- 지원 잡 유형: Freestyle, Pipeline(단일), Multibranch Pipeline
- 인증: Username/Password(App password·API token), Access Token(Bearer), OAuth consumer(Client Credentials)
- Webhook 서명 검증(`X-Hub-Signature`, HMAC-SHA256)
- 환경: Java 17, Maven, Jenkins LTS 2.452+

### Non-goals (YAGNI)

- PR 코멘트 트리거, 태그 push 트리거
- `bitbucketStatus` Pipeline 스텝, PR 승인/코멘트 스텝
- Bitbucket Data Center/Server
- Bitbucket Branch Source 플러그인 의존

## 2. Approach

자체 구현. scm-api, `Trigger`, `RunListener` 등 Jenkins 확장점을 사용하며 GitLab Plugin과 유사한 구조를 따른다.
Bitbucket Branch Source 위에 얹는 방식(Freestyle/단일 Pipeline 지원 약함)과 Generic Webhook Trigger 래핑(상태 보고·UX 제어 어려움)은 채택하지 않는다.

## 3. Architecture

패키지 루트: `io.jenkins.plugins.bitbucketcloud`

| 패키지 | 역할 |
|---|---|
| `webhook` | `/bitbucket-webhook/` 엔드포인트(`UnprotectedRootAction`). 서명 검증, payload 파싱, 이벤트 생성 |
| `event` | `PushEvent`, `PullRequestEvent` 등 불변 도메인 모델. Bitbucket JSON과의 경계 |
| `routing` | 이벤트의 저장소 URL로 잡을 찾는 `JobMatcher` |
| `trigger` | `BitbucketTrigger`(Freestyle, Pipeline `triggers{}`). 이벤트/브랜치 필터 |
| `status` | `RunListener` 기반 `StatusReporter`. commit status 전송 |
| `api` | Bitbucket Cloud REST 클라이언트. 인증 전략은 인터페이스 뒤에 은닉 |
| `config` | 글로벌 설정(기본 Credentials, webhook secret), Credentials 타입 |

원칙:

- Webhook은 서명 검증 후 즉시 202를 반환하고 잡 검색·트리거는 비동기로 처리한다(Bitbucket 타임아웃 회피).
- 저장소 매칭은 URL 정규화(`https`/`ssh`, `.git` 접미사, 대소문자) 후 `workspace/repo-slug`를 키로 비교한다.
- Multibranch는 scm-api의 `SCMHeadEvent`로 발행해 기존 인덱싱/브랜치 빌드 흐름을 따른다.
- 상태 보고는 빌드에 커밋 해시·저장소를 `Action`으로 기록하고 `RunListener`가 읽는다. 모든 잡 유형이 같은 경로를 쓴다.

## 4. Components

### 4.1 BitbucketTrigger

- 이벤트 선택: Push, PR Created, PR Updated
- 브랜치 필터: 소스/타겟 include/exclude (Ant 스타일 패턴)
- PR 옵션: 드래프트 PR 무시, fork PR 허용 여부
- 동일 커밋 중복 빌드 방지 옵션
- Pipeline: `triggers { bitbucketCloud(pushEvents: true, pullRequestEvents: ['created','updated']) }`
- Multibranch는 트리거 대신 scm-api 이벤트로 동작

### 4.2 Environment variables

`EnvironmentContributingAction`으로 주입:
`bitbucketSourceBranch`, `bitbucketTargetBranch`, `bitbucketPullRequestId`, `bitbucketRepoFullName`, `bitbucketCommitHash`, `bitbucketActor`

### 4.3 Authentication

- Credentials 3종: Username/Password, Secret text(Bearer), OAuth consumer(토큰 발급·캐시)
- `BitbucketAuth` 인터페이스는 `applyTo(HttpRequest)`만 노출하고 구현체 3개가 헤더를 구성한다. API 클라이언트는 인증 종류를 모른다.
- 선택 우선순위: 잡 설정 → 글로벌 기본값
- Webhook secret: 글로벌 Secret text Credential 1개로 `X-Hub-Signature` 검증. 서명 누락/불일치 시 401. secret 미설정 시 기본은 전체 거부이며 명시적 옵션으로만 허용한다.

### 4.4 StatusReporter

- 시작 `INPROGRESS`, 종료 `SUCCESSFUL`/`FAILED`/`STOPPED`
- `POST /2.0/repositories/{workspace}/{repo}/commit/{hash}/statuses/build`
- `key`=잡 풀네임, `name`=`Jenkins: <job>`, `url`=빌드 URL

### 4.5 JobMatcher

- Freestyle/Pipeline: 트리거가 설정된 잡 중 SCM remote URL이 이벤트 저장소와 일치하는 잡
- Multibranch: 기존 Git SCM 소스의 remote URL을 정규화해 비교(Bitbucket Branch Source 비의존)
- 매칭된 잡은 `ACL.SYSTEM`으로 트리거한다. 인증은 webhook 서명이 대신한다.

## 5. Data flow

```
Bitbucket ──POST──▶ /bitbucket-webhook/
  1. X-Hub-Signature 검증 (실패 → 401)
  2. X-Event-Key로 이벤트 종류 판별 (미지원 → 200 무시)
  3. payload → 도메인 이벤트 변환 (파싱 실패 → 400)
  4. 202 반환, 이후 비동기 처리
  5. JobMatcher: 저장소 URL로 후보 잡 검색
  6. 트리거 필터(이벤트/브랜치/드래프트) 확인
  7. 중복 방지 검사 후 빌드 스케줄 + Action(커밋/저장소/PR 정보) 첨부
  8. RunListener: 시작/종료 시 commit status POST
```

## 6. Error handling

- 서명 비교는 상수 시간(`MessageDigest.isEqual`). 로그에 secret/토큰을 남기지 않는다.
- 비동기 처리 중 예외는 잡별로 격리하고 WARNING으로 기록한다.
- 상태 보고 실패는 빌드 결과에 영향을 주지 않는다. 5xx/429는 지수 백오프로 최대 3회 재시도, 401/403은 재시도 없이 경고만 남긴다.
- OAuth 토큰은 만료 직전 갱신하고, 401 시 1회만 재발급 후 재시도한다.
- 매칭 잡이 없으면 정상(200) 처리하고 디버그 로그만 남긴다(워크스페이스 hook에는 무관한 저장소 이벤트도 온다).

## 7. Testing

- 단위: payload 파싱(실제 샘플 JSON fixture), URL 정규화, 서명 검증, 브랜치 필터, 중복 방지, 인증 3종 헤더
- API 클라이언트: WireMock으로 commit status 호출, 재시도, 401 재발급
- 통합: `JenkinsRule`로 Freestyle/Pipeline/Multibranch 각각에 webhook POST 후 트리거·환경 변수 확인
