#pragma once
#ifndef _FFMPEGENC_H
#define _FFMPEGENC_H

#if defined(WIN32)
#ifdef FFMPEGENC_DLL_EXPORTS
#define EXPORT_API __declspec(dllexport)
#define EXTERN_EXPORT_API extern "C" __declspec(dllexport)
#else
#define EXPORT_API __declspec(dllimport)
#define EXTERN_EXPORT_API extern "C"
#endif
#else
#define EXPORT_API
#define EXTERN_EXPORT_API extern "C"
#endif

extern "C" {
	#include <libavcodec/avcodec.h>
	#include <libavformat/avformat.h>
	#include <libswscale/swscale.h>
	#include <libavutil/pixfmt.h>
	#include <libavutil/opt.h>
	#include <libavutil/imgutils.h>
}

class FFMpegEncoder {
private:
	AVFormatContext *container;
	AVCodecContext *pCtx;
	AVIOContext *ioc;
	AVStream *video_st;
	AVFrame *picture_yuv;
	AVFrame *picture_rgb;
	SwsContext *Sctx;
	bool use_buffer;
	size_t _buffer_size;
	unsigned char *_buffer;
	int _frame_count;
	int _frame_num;
	int _encoded_frames;
	AVPixelFormat _raw_format;

	int _width;
	int _height;
	int _bdepth;
	int _allocated_slicenum;
	void **_slices;

public:
	FFMpegEncoder(int width, int height, int bdepth, const char *codec_name, const char *options);
	~FFMpegEncoder();
	void Close();
	void FreeBuffer();
	void SendSlice(unsigned char *slice);
	void SendSlice(unsigned short *slice);
	void Encode();
	int GetOutputSize();
	unsigned char *GetOutput();
};


EXTERN_EXPORT_API FFMpegEncoder *FFMpegEncoder_ctor(int width, int height, int bdepth, const char *codec_name, const char *options);

EXTERN_EXPORT_API void FFMpegEncoder_SendSlice8(FFMpegEncoder *self, char *slice);

EXTERN_EXPORT_API void FFMpegEncoder_SendSlice16(FFMpegEncoder *self, short *slice);

EXTERN_EXPORT_API void FFMpegEncoder_Encode(FFMpegEncoder *self);

EXTERN_EXPORT_API int FFMpegEncoder_GetOutputSize(FFMpegEncoder *self);

EXTERN_EXPORT_API unsigned char *FFMpegEncoder_GetOutput(FFMpegEncoder *self);

EXTERN_EXPORT_API void FFMpegEncoder_dtor(FFMpegEncoder *self);

#endif