# 공통 인터페이스

`gdal4k`는 `GdalRuntime`, `Dataset`, `Band`, OGR·SRS 인터페이스와 공통 모델·편의 함수를 제공합니다. 플랫폼 구현과 GDAL 엔진에 의존하지 않습니다. Kotlin 패키지는 `dev.gdal4k.runtime` 및 `dev.gdal4k`를 유지합니다.

API에만 의존하는 라이브러리는 `dev.tronto.gdal4k:gdal4k:0.1.0-SNAPSHOT`을 사용합니다. GDAL을 실행하는 앱은 `dev.tronto.gdal4k:gdal4k-runtime:0.1.0-SNAPSHOT`과 필요한 `gdal4k-engine-*`를 추가합니다. runtime이 인터페이스를 전이 의존성으로 제공합니다.

[사용법과 플랫폼별 실행 방식](../gdal4k-runtime/README.md), [엔진 선택](../README.md)을 참고하세요.
