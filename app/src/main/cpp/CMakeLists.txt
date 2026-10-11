cmake_minimum_required(VERSION 3.22)
project(ba_jni CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Eigen (header-only) — expose it so ceres' find_package(Eigen3) succeeds
FetchContent_Declare(eigen
    GIT_REPOSITORY https://gitlab.com/libeigen/eigen.git
    GIT_TAG 3.4.0)
FetchContent_MakeAvailable(eigen)
set(Eigen3_DIR ${eigen_BINARY_DIR} CACHE INTERNAL "")

# Ceres 2.1.0 (fetches its own abseil if not found system-wide)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING  OFF CACHE BOOL "" FORCE)
FetchContent_Declare(ceres
    GIT_REPOSITORY https://github.com/ceres-solver/ceres-solver.git
    GIT_TAG 2.1.0)
FetchContent_MakeAvailable(ceres)

add_library(ba_jni SHARED ba.cpp)
find_library(log-lib log)
target_link_libraries(ba_jni ceres ${log-lib})
