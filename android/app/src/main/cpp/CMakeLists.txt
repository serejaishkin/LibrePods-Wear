cmake_minimum_required(VERSION 3.22.1)

project("librepods_wear_native")
set(CMAKE_CXX_STANDARD 23)

add_library(bluetooth_socket SHARED
        bluetooth_socket.cpp
)

target_compile_options(bluetooth_socket PRIVATE
        -O2
        -fvisibility=hidden
)

target_link_options(bluetooth_socket PRIVATE
        -Wl,--strip-all
        -Wl,--gc-sections
)

target_link_libraries(bluetooth_socket
        android
        log
)
