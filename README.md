# gdal4k

JVM, Android, Kotlin/Native, JS, wasmJs에서 GDAL을 사용하는 Kotlin Multiplatform 라이브러리입니다. WASI는 제외합니다.

- [공통 API와 플랫폼별 설치/검증](gdal4k/README.md)
- [기존 JVM 번들 빌드](gdal4k-binary/README.md)
- [초기 플랫폼 검증](verification/README.md)

공통 코드는 `dev.tronto.gdal4k:gdal4k`의 `dev.gdal4k.Gdal` 진입점을 사용합니다. 기존 JVM 객체 API는 별도 모듈에서 유지합니다.
