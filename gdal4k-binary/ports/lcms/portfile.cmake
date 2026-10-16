if(VCPKG_LIBRARY_LINKAGE STREQUAL dynamic)
    set(SHARED_LIBRARY_PATCH "fix-shared-library.patch")
endif()

vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO mm2/Little-CMS
    REF "lcms${VERSION}"
    SHA512 1b2781ed8898e65f15be17cf0130a1500ec0bf5ca5159f871dff5692e387747be9526feef0bc7c370200656fc0aabe3036746041285a3978e90adec200d685f2
    HEAD_REF master
    PATCHES
        ${SHARED_LIBRARY_PATCH}
)

if(VCPKG_TARGET_IS_EMSCRIPTEN)
    # The web engine runs inside one Worker and must not require SharedArrayBuffer.
    vcpkg_replace_string("${SOURCE_PATH}/meson.build"
        "threads_dep = dependency('threads')" "threads_dep = []")
    vcpkg_replace_string("${SOURCE_PATH}/meson.build"
        "if cc.has_function('pthread_mutex_lock', dependencies: threads_dep)" "if false")
endif()

if("fastfloat" IN_LIST FEATURES)
    list(APPEND OPTIONS -Dfastfloat=true)
else()
    list(APPEND OPTIONS -Dfastfloat=false)
endif()
if("threaded" IN_LIST FEATURES)
    list(APPEND OPTIONS -Dthreaded=true)
else()
    list(APPEND OPTIONS -Dthreaded=false)
endif()
if("tools" IN_LIST FEATURES)
    list(APPEND OPTIONS -Dutils=true)
else()
    list(APPEND OPTIONS -Dutils=false)
endif()

if(VCPKG_TARGET_IS_LINUX AND CMAKE_HOST_APPLE)
    # The pinned Meson helper compares CPU families but misses a same-CPU OS change.
    # Preserve its cross-mode flag so it never executes Linux probes on macOS ARM64.
    set(IS_CROSS TRUE)
endif()

vcpkg_configure_meson(
    SOURCE_PATH "${SOURCE_PATH}"
    OPTIONS
        ${OPTIONS}
)
vcpkg_install_meson()
vcpkg_fixup_pkgconfig()

if("tools" IN_LIST FEATURES)
    vcpkg_copy_tools(
        TOOL_NAMES jpgicc linkicc psicc tificc transicc tifdiff
        AUTO_CLEAN
    )
endif()

file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/share/man")
file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/debug/share")

file(INSTALL "${CMAKE_CURRENT_LIST_DIR}/lcms-config.cmake" DESTINATION "${CURRENT_PACKAGES_DIR}/share/${PORT}")
file(INSTALL "${CMAKE_CURRENT_LIST_DIR}/lcms2-config.cmake" DESTINATION "${CURRENT_PACKAGES_DIR}/share/lcms2")
file(INSTALL "${CMAKE_CURRENT_LIST_DIR}/usage" DESTINATION "${CURRENT_PACKAGES_DIR}/share/${PORT}")

vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/LICENSE")
