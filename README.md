# Tailcat for Minecraft

> [!NOTE]
> * 공식 원본 저장소: [tailscale/tailcat-for-minecraft](https://github.com/tailscale/tailcat-for-minecraft)
> * 안내 사항: 해당 모드는 원본 프로토타입 기반으로 AI에 의해 포팅 및 작성 되었습니다. 네.. 전부요.
> * 본 프로젝트는 [Tailscale Tailcat](https://github.com/tailscale/tailcat)을 활용하여 복잡한 포트포워딩, 외부 가상 LAN 또는 VPN 설정 없이 간편하게 친구와 마인크래프트 월드를 P2P 및 DERP 릴레이로 안전하게 연결합니다.

별도의 Tailscale 앱 설치나 TUN 가상 어댑터 권한 없이, 모드/플러그인에 내장된 유저스페이스 Go 헬퍼 바이너리를 통해 P2P WireGuard 직접 연결(NAT Traversal) 및 DERP 릴레이 전송을 안전하게 수행합니다.

---

## 주요 기능

* 싱글플레이 LAN 월드 원클릭 공유: 일시정지 메뉴에서 Tailcat으로 공유를 누르면 고유 초대 코드(`mcl1_...`)가 생성됩니다.
* 손쉬운 멀티플레이 접속: 멀티플레이 화면에서 Tailcat으로 접속을 선택하고 전달받은 초대 코드만 입력하면 즉시 접속됩니다.
* 전용 서버(Dedicated Server) 및 Paper 플러그인 지원: 싱글플레이 호스팅뿐만 아니라 Fabric, Forge, NeoForge 전용 서버 및 Paper/Spigot 서버에서도 백그라운드 터널을 실행하여 서버를 손쉽게 외부에 공개할 수 있습니다.
* 완전한 한국어 UI 지원: 게임 내 버튼, 다이얼로그, 상태 메시지, 툴팁이 한국어로 제공됩니다.
* 클린 콘솔 및 프로세스 라이프사이클 관리: 게임이나 서버가 종료될 때 백그라운드 헬퍼 프로세스(`mclink-helper`)가 안전하게 종료되며, 콘솔 로깅이 정돈되어 불필요한 내부 통신 로그가 서버 출력을 방해하지 않습니다.

---

## 지원 환경 및 플랫폼 (v0.3.1)

빌드된 산출물 파일은 저장소의 [Releases](https://github.com/LeunoP/tailcat-for-minecraft/releases) 또는 프로젝트 루트의 `dist/` 디렉터리에서 확인할 수 있습니다.

### 모드 (Client & Dedicated Server)

| 마인크래프트 버전 | Fabric | Forge | NeoForge | 지원 Java 버전 |
|:---:|:---:|:---:|:---:|:---:|
| 1.21.11 | 지원 | 지원 | 지원 | Java 21+ |
| 1.20.1 | 지원 | 지원 | 지원 (LegacyForge) | Java 17+ |
| 1.21.1 | 지원 | 지원 | 지원 | Java 21+ |
| 26.2 | 지원 | 지원 | 지원 | Java 25+ |
| 26.3 | 지원 | 지원 | 지원 (베타) | Java 25+ |

### 서버 플러그인 (Paper / Purpur / Spigot)

| 구분 | 지원 버전 | 지원 Java 버전 | 산출물 파일명 |
|:---:|:---:|:---:|:---:|
| Paper Plugin | 1.17 ~ 26.2+ 호환 | Java 17+ | `tailcat-for-minecraft-paper-plugin-0.3.1.jar` |

---

## 사용 방법

### 1. 싱글플레이 월드 호스팅 (클라이언트)
1. 싱글플레이 월드에 접속합니다.
2. ESC 키를 눌러 일시정지 메뉴를 엽니다.
3. [Tailcat으로 공유] 버튼을 클릭합니다.
4. 생성된 `mcl1_...` 형식의 초대 코드를 복사하여 접속할 플레이어에게 전달합니다.

초대 코드는 여러 플레이어가 함께 사용할 수 있습니다. 호스트 프로그램을 별도 Disco 키를 지원하는 버전으로 업그레이드한 경우에는 기존 초대 코드 대신 호스트에서 새 코드를 발급받아 전달하십시오.

### 2. 게스트 접속 (클라이언트)
1. 메인 타이틀 화면에서 [멀티플레이]로 이동합니다.
2. 화면 하단의 [Tailcat으로 접속] 버튼을 클릭합니다.
3. 전달받은 초대 코드를 입력창에 붙여넣고 [접속] 버튼을 누릅니다.

### 3. 전용 서버(Dedicated Server) 및 Paper 플러그인 사용
1. 서버의 `mods` (모드 서버) 또는 `plugins` (Paper 서버) 폴더에 알맞은 JAR 파일을 넣고 서버를 구동합니다.
2. 기본 설정에서는 서버가 실행되면 Tailcat 터널이 자동으로 시작됩니다. 초대 코드는 서버 루트의 `tailcat_invite.txt` 또는 관리자용 `/tailcat invite`에서 확인합니다.
3. 클라이언트는 위 [2. 게스트 접속]과 동일하게 해당 코드로 접속할 수 있습니다.

---

## 명령어 안내 (서버 공통)

* `/tailcat invite` (관리자 전용): 현재 활성화된 서버의 초대 코드를 확인하고 채팅창에서 복사할 수 있습니다.
* `/tailcat start`, `/tailcat stop`, `/tailcat restart` (전용 서버·Paper 관리자 전용): 서버를 재부팅하지 않고 공유를 시작·중지·재시작합니다. 재시작은 이전 헬퍼의 종료를 기다립니다.
* `/tailcat status` (또는 `/tailcat`):
  * 관리자(OP): Tailcat 터널을 통해 접속한 유저 목록 및 레이턴시(ms)를 출력합니다.
  * 일반 플레이어: 본인이 Tailcat으로 연결된 상태인지 여부와 핑을 확인합니다.

---

## 서버 설정

전용 모드 서버는 `config/tailcat-server.json`, Paper는 `plugins/Tailcat/tailcat-server.json`을 처음 실행할 때 생성합니다.

```json
{
  "autoShare": true,
  "relayOnly": false,
  "tcpConnectionLimit": 256,
  "startupTimeoutSeconds": 20,
  "language": "en_us"
}
```

`autoShare`는 서버 시작 시 자동 공유 여부입니다. 실행 중인 터널의 나머지 설정을 변경한 뒤에는 `/tailcat restart`로 적용합니다. 언어는 `en_us`, `ko_kr`, `ja_jp`를 지원합니다. 잘못된 값은 경고와 함께 기본값을 사용하며, 원본 설정 파일을 덮어쓰지 않습니다.

`tcpConnectionLimit`는 서버 조회 등을 포함한 동시 TCP 연결의 자원 한도이며 플레이어 정원이 아닙니다. 전용 서버의 정원은 Minecraft의 `max-players`가 결정합니다. 싱글플레이 공유는 별도로 호스트 포함 2~64명을 유지합니다. 서버는 헬퍼가 사용하는 `127.0.0.1:<서버 포트>` 연결을 받을 수 있어야 합니다.

공유 화면은 실제 현재 서버 정원과 다음 공유에 사용할 정원을 구분합니다. 유효한 호스트 정원과 중계 전용 옵션은 이번 게임 실행 동안만 기억하며 파일에 저장하지 않습니다. 게임을 다시 실행하면 기본값인 8명·중계 전용 끔으로 돌아갑니다. 접속자가 없으면 연결 경로 대신 접속자 대기 상태를 표시합니다. 기존 일반 LAN이 열린 경우에는 Tailcat 공유를 중지해도 해당 LAN은 유지된다는 안내가 표시됩니다.

진단은 개발자 API로만 제공합니다. 일반 배포에는 진단 저장 버튼과 diagnose 명령이 없습니다. API를 명시적으로 호출한 경우에만 게임은 실행 폴더의 `tailcat-for-minecraft/diagnostics`, Paper는 `plugins/Tailcat/diagnostics`에 파일을 저장합니다. Minecraft 핑과 진단 파일의 터널 상태 조회 왕복 시간은 다른 측정값입니다. 측정하지 못한 경로·지역·지연은 추정값으로 채우지 않습니다.

---

## 빌드 방법 (개발자용)

전체 빌드 대상은 15개 모드와 Paper 총 16개이며 `scripts/support_matrix.json`에서 관리합니다. JDK 17, 21, 25와 Python 3.10 이상이 필요합니다. JDK 위치는 자동 탐지하거나 `--java-home`으로 지정합니다.

헬퍼를 수정했다면 먼저 Tailscale Go 1.26.5 도구 체인으로 `python scripts/build_natives.py --go <go 실행 파일>`을 실행합니다. 이 스크립트는 Go 테스트 후 6종의 내장 헬퍼와 체크섬을 함께 갱신합니다. 고정 Tailcat 소스의 보정은 `helper/third_party/tailcat/LOCAL_CHANGES.md`에 기록합니다.

```powershell
python scripts/build_all.py --list
python scripts/build_all.py --dry-run
python scripts/build_all.py
# 특정 모듈만 확인
python scripts/build_all.py --module mod-fabric-26.3
```

공통 계약 테스트와 각 모듈의 build 테스트를 생략하지 않습니다. JAR 메타데이터, 클래스 Java 버전과 내장 헬퍼 6종의 SHA-256을 검사하며, 선택한 대상이 모두 통과한 뒤 `dist/`를 갱신합니다. 빌드 로그는 `artifacts/build-<ID>/`, 전체 릴리스 목록은 `dist/manifests/release-manifest.json`에 남깁니다. 부분 빌드는 별도 목록을 만들고 전체 릴리스 목록을 덮어쓰지 않습니다. 빌드 성공과 인게임 호환 확인은 별도 항목입니다.

기존 Gradle 명령도 사용할 수 있습니다. 1.x Forge는 해당 모듈의 Gradle 8.8 래퍼를 사용하고, Forge 26.2는 루트 Gradle 9.5.1 래퍼를 사용합니다. 26.3 모듈은 각자의 Gradle 9.7.1 래퍼를 사용합니다.

```bash
# Fabric 모듈 빌드
./gradlew -p mod-fabric-1.20.1 build
./gradlew -p mod-fabric-1.21.1 build
./gradlew -p mod-fabric-1.21.11 build
./gradlew :mod-fabric-26.2:build
cd mod-fabric-26.3 && ./gradlew build && cd ..

# Forge 모듈 빌드
cd mod-forge-1.20.1 && ./gradlew build && cd ..
cd mod-forge-1.21.1 && ./gradlew build && cd ..
cd mod-forge-1.21.11 && ./gradlew build && cd ..
./gradlew -p mod-forge-26.2 build
cd mod-forge-26.3 && ./gradlew build && cd ..

# NeoForge 모듈 빌드
./gradlew -p mod-neoforge-1.20.1 build
./gradlew -p mod-neoforge-1.21.1 build
./gradlew -p mod-neoforge-1.21.11 build
./gradlew -p mod-neoforge-26.2 build
cd mod-neoforge-26.3 && ./gradlew build && cd ..

# Paper 플러그인 빌드
./gradlew -p plugin-paper build
```

### 격리된 Prism 시작 점검

`scripts/test_instances.py`는 명시한 원본 인스턴스의 구성·모드·설정만 새 `tailcat-test-*` 인스턴스로 복사합니다. 기존 월드와 로그는 복사하거나 비우지 않습니다. 게임 버전·로더가 정확히 맞고 전체 릴리스 목록과 해시가 일치하는 JAR만 사용합니다.

```powershell
# 원본 이름은 Prism instances 안의 실제 폴더명으로 바꿉니다.
python scripts/test_instances.py --source "26.3-fabric" --module mod-fabric-26.3
# 실행은 --launch를 명시한 경우에만 진행합니다. psutil이 필요합니다.
python scripts/test_instances.py --source "26.3-fabric" --module mod-fabric-26.3 --launch --close-owned
python -m unittest discover -s scripts -p test_preparation.py -v
```

종료 옵션은 이번 실행에서 생성됐고 작업 디렉터리까지 일치하는 게임 PID에만 적용합니다. 정상 창 닫기를 우선하며 강제 종료는 `--force-stop-owned`를 추가한 경우에만 허용합니다. 테스트 인스턴스와 로그·결과 JSON은 삭제하지 않습니다. 리소스 초기화 로그만으로 제목 화면·공유·접속 성공을 판정하지 않습니다.

---

## 라이선스

* 본 프로젝트는 BSD-3-Clause 라이선스를 따릅니다.
* Tailcat 및 Tailscale은 Tailscale Inc.의 상표 및 오픈소스 소프트웨어입니다.
* Minecraft는 Mojang AB 및 Microsoft의 등록 상표입니다.

