# Tailcat for Minecraft (테일캣 마인크래프트 모드)

> [!NOTE]
> * **공식 원본 저장소**: [tailscale/tailcat-for-minecraft](https://github.com/tailscale/tailcat-for-minecraft)
> * **안내 사항**: 해당 모드는 원본 프로토타입 기반으로 AI에 의해 포팅 및 작성 되었습니다.
> * 본 프로젝트는 [Tailscale Tailcat](https://github.com/tailscale/tailcat)을 활용하여 복잡한 포트포워딩, 하마치 설치, VPN 세팅 없이 간편하게 친구와 마인크래프트 싱글플레이 월드를 공유하고 함께 플레이할 수 있도록 제작된 클라이언트 모드입니다.

별도의 Tailscale 앱 설치나 TUN 가상 네트워크 어댑터 생성 없이, 모드에 내장된 유저스페이스(Go) 엔진을 통해 **P2P WireGuard 직접 연결(NAT Traversal) 및 릴레이(DERP)** 전송을 안전하게 수행합니다.

---

## 주요 기능

* **클릭 한 번으로 LAN 월드 공유**: 싱글플레이 일시정지 메뉴에서 **Tailcat으로 공유**를 누르면 초대 코드가 즉시 생성됩니다.
* **손쉬운 접속**: 멀티플레이 화면에서 **Tailcat으로 접속**을 누르고 받은 초대 코드(mcl1_...)를 붙여넣기만 하면 바로 접속됩니다.
* **완전한 한국어 지원**: 게임 내 모든 버튼, 안내문, 툴팁, 상태 메시지가 한국어로 깔끔하게 표시됩니다.
* **다중 모드로더 및 마인크래프트 버전 지원**:
  * **1.20.1** (Fabric / Forge / NeoForge)
  * **1.21.1** (Fabric / Forge / NeoForge)
  * **26.2** (Fabric / Forge / NeoForge)
* **안전한 프로세스 라이프사이클 관리**: 마인크래프트 게임이 종료되면 백그라운드 헬퍼 프로세스(mclink-helper.exe)도 자동으로 감지되어 깔끔하게 정리됩니다.
* **방화벽 및 UAC 승격 제거**: 불필요한 관리자 권한(UAC) 요청 없이 일반 사용자 권한으로 안전하게 구동됩니다.

---

## 지원 플랫폼 및 다운로드

모든 빌드 파일은 저장소의 **[Releases](https://github.com/LeunoP/tailcat-for-minecraft/releases)** 또는 로컬 빌드의 dist/ 폴더에서 확인하실 수 있습니다.

| 마인크래프트 버전 | Fabric | Forge | NeoForge | 대상 Java 버전 |
|:---:|:---:|:---:|:---:|:---:|
| **1.20.1** | ✅ 지원 | ✅ 지원 | ✅ 지원 (LegacyForge) | Java 17 |
| **1.21.1** | ✅ 지원 | ✅ 지원 | ✅ 지원 | Java 21 |
| **26.2** | ✅ 지원 | ✅ 지원 | ✅ 지원 | Java 25 (런타임 21+) |

---

## 사용 방법

### 1. 호스트 (월드를 여는 사람)
1. 싱글플레이 월드에 접속합니다.
2. ESC 키를 눌러 일시정지 메뉴를 엽니다.
3. **Tailcat으로 공유** 버튼을 클릭합니다.
4. 화면에 생성된 mcl1_... 형식의 초대 코드를 복사하여 친구에게 전달합니다.

### 2. 게스트 (접속하는 사람)
1. 마인크래프트 타이틀 화면에서 **멀티플레이**로 이동합니다.
2. 화면 하단의 **Tailcat으로 접속** 버튼을 클릭합니다.
3. 친구에게 받은 초대 코드를 입력창에 붙여넣고 **접속** 버튼을 누릅니다.

---

## 빌드 방법 (개발자용)

저장소를 클론한 후 각 서브프로젝트 디렉토리에서 Gradle을 통해 빌드할 수 있습니다:

# Fabric 26.2 빌드
./gradlew :mod:build

# Fabric 1.20.1 / 1.21.1 빌드
./gradlew -p mod-1.20.1 build
./gradlew -p mod-1.21.1 build

# Forge 빌드 (1.20.1, 1.21.1, 26.2)
./gradlew -p mod-forge-1.20.1 build
./gradlew -p mod-forge-1.21.1 build
./gradlew -p mod-forge-26.2 build

# NeoForge 빌드 (1.20.1, 1.21.1, 26.2)
./gradlew -p mod-neoforge-1.20.1 build
./gradlew -p mod-neoforge-1.21.1 build
./gradlew -p mod-neoforge-26.2 build
`

---

## 📄 라이선스

* 본 프로젝트는 **BSD-3-Clause** 라이선스를 따릅니다.
* Tailcat 및 Tailscale은 Tailscale Inc.의 상표 및 오픈소스 소프트웨어입니다.
* Minecraft는 Mojang AB 및 Microsoft의 등록 상표입니다.
