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

## 지원 환경 및 플랫폼 (v0.2.1)

빌드된 산출물 파일은 저장소의 [Releases](https://github.com/LeunoP/tailcat-for-minecraft/releases) 또는 프로젝트 루트의 `dist/` 디렉터리에서 확인할 수 있습니다.

### 모드 (Client & Dedicated Server)

| 마인크래프트 버전 | Fabric | Forge | NeoForge | 지원 Java 버전 |
|:---:|:---:|:---:|:---:|:---:|
| 1.20.1 | 지원 | 지원 | 지원 (LegacyForge) | Java 17+ |
| 1.21.1 | 지원 | 지원 | 지원 | Java 21+ |
| 26.2 | 지원 | 지원 | 지원 | Java 21+ (빌드 25) |

### 서버 플러그인 (Paper / Purpur / Spigot)

| 구분 | 지원 버전 | 지원 Java 버전 | 산출물 파일명 |
|:---:|:---:|:---:|:---:|
| Paper Plugin | 1.17 ~ 26.2+ 호환 | Java 17+ | `tailcat-for-minecraft-paper-plugin-0.2.1.jar` |

---

## 사용 방법

### 1. 싱글플레이 월드 호스팅 (클라이언트)
1. 싱글플레이 월드에 접속합니다.
2. ESC 키를 눌러 일시정지 메뉴를 엽니다.
3. [Tailcat으로 공유] 버튼을 클릭합니다.
4. 생성된 `mcl1_...` 형식의 초대 코드를 복사하여 접속할 플레이어에게 전달합니다.

### 2. 게스트 접속 (클라이언트)
1. 메인 타이틀 화면에서 [멀티플레이]로 이동합니다.
2. 화면 하단의 [Tailcat으로 접속] 버튼을 클릭합니다.
3. 전달받은 초대 코드를 입력창에 붙여넣고 [접속] 버튼을 누릅니다.

### 3. 전용 서버(Dedicated Server) 및 Paper 플러그인 사용
1. 서버의 `mods` (모드 서버) 또는 `plugins` (Paper 서버) 폴더에 알맞은 JAR 파일을 넣고 서버를 구동합니다.
2. 서버가 실행되면 Tailcat 터널이 자동으로 시작되고 콘솔 및 서버 루트의 `tailcat_invite.txt` 파일에 초대 코드가 생성됩니다.
3. 클라이언트는 위 [2. 게스트 접속]과 동일하게 해당 코드로 접속할 수 있습니다.

---

## 명령어 안내 (서버 공통)

* `/tailcat invite` (관리자 전용): 현재 활성화된 서버의 초대 코드를 확인하고 채팅창에서 복사할 수 있습니다.
* `/tailcat status` (또는 `/tailcat`):
  * 관리자(OP): Tailcat 터널을 통해 접속한 유저 목록 및 레이턴시(ms)를 출력합니다.
  * 일반 플레이어: 본인이 Tailcat으로 연결된 상태인지 여부와 핑을 확인합니다.

---

## 빌드 방법 (개발자용)

저장소를 클론한 후 Gradle을 통해 각 모듈별 JAR를 빌드할 수 있습니다.

```bash
# Fabric 모듈 빌드
./gradlew -p mod-fabric-1.20.1 build
./gradlew -p mod-fabric-1.21.1 build
./gradlew :mod-fabric-26.2:build

# Forge 모듈 빌드
./gradlew -p mod-forge-1.20.1 build
./gradlew -p mod-forge-1.21.1 build
./gradlew -p mod-forge-26.2 build

# NeoForge 모듈 빌드
./gradlew -p mod-neoforge-1.20.1 build
./gradlew -p mod-neoforge-1.21.1 build
./gradlew -p mod-neoforge-26.2 build

# Paper 플러그인 빌드
./gradlew -p plugin-paper build
```

---

## 라이선스

* 본 프로젝트는 BSD-3-Clause 라이선스를 따릅니다.
* Tailcat 및 Tailscale은 Tailscale Inc.의 상표 및 오픈소스 소프트웨어입니다.
* Minecraft는 Mojang AB 및 Microsoft의 등록 상표입니다.

