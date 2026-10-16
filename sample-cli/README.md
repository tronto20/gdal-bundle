# JVM 소비 예제

별도 JVM 프로젝트가 게시된 `gdal4k-binary`와 공통 runtime 인터페이스를 사용하는 예제입니다. GDAL 작업은 `suspend`이며 CLI의 `suspend main`에서 호출합니다. GDAL 설치나 classifier 지정은 필요하지 않습니다.

로컬 검증 아티팩트를 준비한 뒤 실행합니다.

```sh
python3 verification/consumer.py --target jvm
./gradlew -p sample-cli -Pgdal4kPackagesUrl="$PWD/verification/build/consumer-maven" run --args='--help'
```

`verification/consumer.py`는 외부 API·JXL 왕복을 별도로 검사합니다. 전체 OS 배포용 JAR은 CI에서 모든 플랫폼 리소스를 합쳐 만듭니다. 이 예제의 jpackage installer는 해당 OS에서 별도로 검증해야 합니다.
