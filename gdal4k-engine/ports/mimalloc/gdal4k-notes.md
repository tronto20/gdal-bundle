This overlay retains vcpkg baseline3cd21e48a75f67acfef289da52893ce79144aaa1's mimalloc3.5.3 source SHA512, features and installation rules. Arrow[parquet] depends on it even without Arrow's optional allocator feature.

The one-line local patch gives the profiler flag exchange value its actual `size_t` type. GCC8.3's libstdc++ atomic_exchange_explicit deduces T from both arguments, so an `atomic<size_t>*` plus an int literal fails under the pinned Kotlin/Native Linux toolchain. No layout, allocation algorithm, memory order or enabled value changes. Default GDAL profiles do not depend on mimalloc; this does not add it to the default payload.

Observed full CI: optional profile run39057469075 / parquet Linux job117468287333, actual mimalloc3.5.3 compilation error `no matching function for call to atomic_exchange_explicit`. A local actual LLVM21/GCC8.3/glibc2.19 sysroot compile reproduces the original expression failure and accepts the cast. Complete profile build/consumer/closure CI is still required.

The next real full CI compile exposed a second instance in src/libc.c: storing int1 in atomic<uintptr_t>. All four mismatched assignments now use exactly the atomic value type. All mimalloc source translation units are checked with the actual pinned LLVM21/GCC8.3 Linux sysroot; version, allocator policy and memory ordering remain unchanged.
