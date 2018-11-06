# CMakeList.txt : CMake project for FFMpegEncoder, include source and define
# project specific logic here.
#
cmake_minimum_required(VERSION 2.6)

project(FFMpegEncoder)

set(CMAKE_CXX_STANDARD 11)

find_path(AVCODEC_INCLUDE_DIR libavcodec/avcodec.h)
find_path(AVFORMAT_INCLUDE_DIR libavformat/avformat.h)
find_path(AVDEVICE_INCLUDE_DIR libavdevice/avdevice.h)
find_path(AVSWS_INCLUDE_DIR libswscale/swscale.h)
find_path(AVPIXFMT_INCLUDE_DIR libavutil/pixfmt.h)
find_path(AVOPT_INCLUDE_DIR libavutil/opt.h)
find_path(AVIMGUTILS_INCLUDE_DIR libavutil/imgutils.h)

find_library(AVCODEC_LIBRARY avcodec)
find_library(AVDEVICE_LIBRARY avdevice)
find_library(AVFILTER_LIBRARY avfilter)
find_library(AVFORMAT_LIBRARY avformat)
find_library(AVUTIL_LIBRARY avutil)
find_library(AVPP_LIBRARY postproc)
find_library(AVSWR_LIBRARY swresample)
find_library(AVSWS_LIBRARY swscale)
find_library(X265_LIBRARY x265)


if(AVCODEC_INCLUDE_DIR AND
	AVFORMAT_INCLUDE_DIR AND
	AVDEVICE_INCLUDE_DIR AND
	AVSWS_INCLUDE_DIR AND
	AVPIXFMT_INCLUDE_DIR AND
	AVOPT_INCLUDE_DIR AND
	AVIMGUTILS_INCLUDE_DIR)

	set(FFMPEG_FOUND "YES")

	set(FFMPEG_INCLUDE_DIR
		${AVCODEC_INCLUDE_DIR}
		${AVFORMAT_INCLUDE_DIR}
		${AVDEVICE_INCLUDE_DIR}
		${AVSWS_INCLUDE_DIR}
		${AVPIXFMT_INCLUDE_DIR}
		${AVOPT_INCLUDE_DIR}
		${AVIMGUTILS_INCLUDE_DIR})
	set(FFMPEG_LIBRARIES
		${AVCODEC_LIBRARY}
		${AVDEVICE_LIBRARY}
		${AVFILTER_LIBRARY}
		${AVFORMAT_LIBRARY}
		${AVUTIL_LIBRARY}
		${AVPP_LIBRARY}
		${AVSWR_LIBRARY}
		${AVSWS_LIBRARY}
		${X265_LIBRARY})

endif(AVCODEC_INCLUDE_DIR AND
	AVFORMAT_INCLUDE_DIR AND
	AVDEVICE_INCLUDE_DIR AND
	AVSWS_INCLUDE_DIR AND
	AVPIXFMT_INCLUDE_DIR AND
	AVOPT_INCLUDE_DIR AND
	AVIMGUTILS_INCLUDE_DIR)

if(NOT ${FFMPEG_FOUND} MATCHES "YES")
	message(STATUS "ffmpeg not found")
endif()

if(${CMAKE_SYSTEM_NAME} MATCHES "Windows")
	include_directories(${FFMpegEncoder_SOURCE_DIR}/ffmpeg/include)
	file(GLOB FFMPEG_LIBRARIES ${FFMpegEncoder_SOURCE_DIR}/ffmpeg/lib/*.lib)

	add_library (FFMpegEncoder SHARED "FFMpegEncoder.cpp" "Wrapper.cpp")
	add_executable(FFMpegTest "FFMpegTest.cpp")
	set (CMAKE_SHARED_LINKER_FLAGS ${CMAKE_SHARED_LINKER_FLAGS} "-Wl,--version-script=${CMAKE_SOURCE_DIR}/FFMpegEncoder.version")

	target_link_libraries(FFMpegEncoder PRIVATE ${FFMPEG_LIBRARIES})
	target_link_libraries(FFMpegEncoder PRIVATE -static)
	target_link_libraries(FFMpegTest FFMpegEncoder)
	target_link_libraries(FFMpegTest -static)
elseif(${CMAKE_SYSTEM_NAME} MATCHES "Linux")
	include_directories(${FFMpegEncoder_SOURCE_DIR}/ffmpeg/include)
	file(GLOB FFMPEG_LIBRARIES ${FFMpegEncoder_SOURCE_DIR}/ffmpeg/lib/*.so)

	add_library (FFMpegEncoder SHARED "FFMpegEncoder.cpp" "Wrapper.cpp")
	add_executable(FFMpegTest "FFMpegTest.cpp")
	set (CMAKE_SHARED_LINKER_FLAGS ${CMAKE_SHARED_LINKER_FLAGS} "-Wl,--version-script=${CMAKE_SOURCE_DIR}/FFMpegEncoder.version")

	target_link_libraries(FFMpegEncoder PRIVATE ${FFMPEG_LIBRARIES})
	#target_link_libraries(FFMpegEncoder PRIVATE -static)
	target_link_libraries(FFMpegTest FFMpegEncoder)
	#target_link_libraries(FFMpegTest -static)
elseif(${CMAKE_SYSTEM_NAME} MATCHES "Darwin")
	include_directories(${FFMpegEncoder_SOURCE_DIR}/ffmpeg/include)
	file(GLOB FFMPEG_LIBRARIES ${FFMpegEncoder_SOURCE_DIR}/ffmpeg/lib/*.dylib)

	add_library (FFMpegEncoder SHARED "FFMpegEncoder.cpp" "Wrapper.cpp")
	add_executable(FFMpegTest "FFMpegTest.cpp")
    #set (CMAKE_SHARED_LINKER_FLAGS ${CMAKE_SHARED_LINKER_FLAGS} "-Wl,--version-script=${CMAKE_SOURCE_DIR}/FFMpegEncoder.version")

	target_link_libraries(FFMpegEncoder PRIVATE ${FFMPEG_LIBRARIES})
	#target_link_libraries(FFMpegEncoder PRIVATE -static)
	target_link_libraries(FFMpegTest FFMpegEncoder)
	#target_link_libraries(FFMpegTest -static)
endif()
