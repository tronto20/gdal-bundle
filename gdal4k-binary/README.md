# 내장 GDAL 빌드와 배포

`gdal4k-runtime`은 공통 인터페이스, `gdal4k-binary`는 모든 타깃의 구현입니다. JVM 엔진은 `gdal4k-jvm-binaries` 모듈에서 플랫폼별 리소스 JAR로 별도 게시합니다. 소비 앱에는 GDAL, vcpkg, Emscripten, CMake가 필요하지 않습니다. 이 도구들은 라이브러리를 소스에서 만드는 producer에만 필요합니다.

## 고정된 소스

- GDAL 3.12.4 및 JPEG XL을 포함하는 `engine/vcpkg.json`과 `ports` overlay
- vcpkg `3cd21e48a75f67acfef289da52893ce79144aaa1`
- 웹: Emscripten 4.0.23 (`emsdk` commit `c0bb220cb6e6f4e0fabb6f6db9efd53390ef5e56`)
- Linux/Windows: Kotlin/Native 2.4.21에 포함된 sysroot / C++ ABI를 사용
- Android: NDK r28c, API 28 이상, 16 KiB 정렬
- Apple: macOS 11 / iOS 15 이상. Kotlin/Native 자체 지원 범위도 적용됩니다.

`tools/build-engine.py`는 고정 버전의 vcpkg를 준비하고 소스에서 GDAL과 정적 의존성을 빌드합니다. 시스템에 설치된 GDAL로 fallback하지 않습니다. `manifest.json`에 소스 fingerprint와 산출물 SHA-256을 기록하고 재사용 시 모두 검증합니다.

## Producer 로컬 실행

JDK 21, Python 3, Git, 플랫폼 SDK가 필요합니다.

```sh
python3 -m venv verification/tools/python
verification/tools/python/bin/pip install -r verification/requirements.txt
export PATH="$PWD/verification/tools/python/bin:$PATH"
./gradlew :gdal4k-runtime:compileKotlinMacosArm64
python3 gdal4k-binary/tools/build-engine.py macos-arm64
./gradlew :gdal4k:jvmTest :gdal4k:macosArm64Test
python3 verification/consumer.py --target macosArm64
```

호스트에 맞춰 `macosArm64` / `macos-arm64`를 바꿉니다. Linux ARM64는 x64 Linux 또는 macOS에서 교차 빌드하고 ARM64 호스트에서 실행합니다. Windows 바이너리는 Windows에서 Kotlin/Native의 MinGW 도구로 빌드합니다. Android producer는 `ANDROID_NDK_HOME`, Android Gradle은 `ANDROID_HOME`을 사용합니다. Android는 `arm64-v8a`만 지원하며 AAR도 이 ABI만 포함합니다. 다른 ABI를 지정하면 빌드 설정 단계에서 실패합니다.

웹은 `.github/workflows/library-tests.yml`과 같은 SDK를 준비·활성화합니다.

```sh
source verification/tools/emsdk/emsdk_env.sh
./gradlew :gdal4k:jsNodeTest :gdal4k:wasmJsNodeTest
CHROME_BIN=/path/to/chrome ./gradlew :gdal4k:jsBrowserTest :gdal4k:wasmJsBrowserTest
python3 verification/consumer.py --target js
python3 verification/consumer.py --target wasmJs
```

웹 엔진은 `gdal3.js`를 사용하지 않습니다. 자체 빌드한 `gdal4k.mjs`를 압축하여 JS/wasmJs KLIB에 똑같이 넣습니다. MIT 라이선스의 vendored `tiny-inflate` 디코더도 Worker 안에서만 실행합니다. 별도 npm 엔진 패키지, CDN, 엔진 URL 설정이 필요하지 않습니다.

## 산출물과 게시

- JVM 코드: `gdal4k-binary-jvm`에는 JNI 호출 코드만 포함하며 기본 바이너리 전이 의존성이 없습니다.
- JVM 리소스: `gdal4k-binary-linux-x64-gnu`, `gdal4k-binary-linux-arm64-gnu`, `gdal4k-binary-macos-x64`, `gdal4k-binary-macos-arm64`, `gdal4k-binary-windows-x64`. 각 JAR에는 해당 플랫폼의 GDAL·JXL JNI 라이브러리 하나만 포함합니다. Linux는 glibc용이며 Alpine/musl은 지원하지 않습니다.
- Native: `gdal4k-binary-<target>` KLIB 안에 GDAL과 모든 선택된 종속성의 정적 archive 포함
- Android: 플랫폼 Kotlin 아티팩트와 `gdal4k-android-runtime` AAR (`arm64-v8a`만 포함), Android 9 / API 28 이상
- 웹: `gdal4k-binary-js` / `gdal4k-binary-wasm-js`, 동일한 엔진 포함
- `gdal4k`와 `gdal4k-runtime`도 동일한 버전 `3.12.4-1.0.1-SNAPSHOT`으로 게시

Kotlin 소비자는 `gdal4k` 루트 좌표를 의존하며 Gradle이 Kotlin 타깃 아티팩트를 선택합니다. JVM에서는 별도로 필요한 플랫폼 리소스 좌표를 `runtimeOnly`로 지정해야 합니다([사용 예시](../README.md)). KMP `jvmMain.dependencies`에도 같은 설정을 사용합니다. 기본값이나 전체 플랫폼 묶음 JAR은 없습니다.

예를 들어 macOS ARM64 리소스만 로컬 게시하려면 `./gradlew :gdal4k-jvm-binaries:publishMacosArm64PublicationToMavenLocal`을 실행합니다. 다른 publication 이름은 `MacosX64`, `LinuxX64Gnu`, `LinuxArm64Gnu`, `WindowsX64`입니다. 선택한 작업만 해당 엔진을 빌드합니다. `verification/consumer.py --target jvm --jvm-binary macos-arm64`는 선택한 리소스와 코드 게시 후 외부 소비를 검증합니다. 테스트 도구에서 옵션을 생략하면 테스트 호스트를 선택하며 제품 의존성의 기본값으로 사용하지 않습니다.

CI는 타깃별 빌드·공통 테스트·독립 소비 테스트 후 아티팩트를 모읍니다. 코드 JAR에 바이너리가 없는지, POM/Gradle metadata에 기본 바이너리 의존성이 없는지, JVM 5종의 각 JAR에 정확히 해당 리소스만 있고 SHA-256이 맞는지 검사합니다. 미선택·잘못된 플랫폼·복수 플랫폼 의존성도 별도 JVM 소비 테스트로 확인합니다. Android AAR에 ARM64 이외의 ABI가 들어가면 실패합니다. 전체 Maven 좌표는 45개입니다. `verification/publish.py`는 합쳐진 **검증 완료 Maven 파일**을 재빌드 없이 게시합니다. PR에서는 게시하지 않으며 main push 또는 수동 publish 선택 시 모든 검증 성공 후 실행합니다. 실제 원격 게시 성공은 CI 검증 성공과 별도로 확인해야 합니다.

기존 Conda/SWIG producer 스크립트는 더 이상 배포 경로가 아닙니다. 드라이버 프로필과 비동기 API의 변경 사항은 [공통 API 문서](../gdal4k/README.md)를 참고하세요.
