#include <stdio.h>
#include "FFMpegEncoder.h"

extern "C" {
	#include <libavdevice/avdevice.h>
}

FFMpegEncoder::FFMpegEncoder(int width, int height, int bdepth, const char *codec_name, const char *options)
{
	picture_yuv = NULL;
	picture_rgb = NULL;
	container = NULL;
	use_buffer = false;
	_buffer_size = 0;
	_buffer = NULL;
	_frame_count = 0;
	_encoded_frames = 0;
	_frame_num = 0;
	_width = width;
	_height = height;
	_bdepth = bdepth;
	_allocated_slicenum = 100;
	_slices = (void **)malloc( _allocated_slicenum * (bdepth==8 ? sizeof(char*) : sizeof(unsigned short*)) );

	if (0 != (width % 2))
		fprintf(stderr, "WARNING: Video width is not a multiple of 2");
	if (0 != (height % 2))
		fprintf(stderr, "WARNING: Video height is not a multiple of 2");

	//avcodec_register_all();
	avdevice_register_all();
	//av_register_all();
	avformat_network_init();

	//av_log_set_level(AV_LOG_TRACE);

	AVCodec *codec = avcodec_find_encoder_by_name(codec_name);
	if (codec == NULL) {
		fprintf(stderr, "Unable to find codec");
		return;
	}
	//fprintf(stderr, "codec: "+codec.name().getString()+"  id: "+codec.id()+"    AV_CODEC_ID_HEVC:"+AV_CODEC_ID_HEVC);

	container = avformat_alloc_context();
	if (container == NULL) {
		fprintf(stderr, "Unable to allocate format context");
		return;
	}

	AVOutputFormat *fmt = NULL;
	if ( codec->id == AV_CODEC_ID_FFV1 )
		fmt = av_guess_format( "mov", NULL, NULL );
	else
		fmt = av_guess_format("mp4", NULL, NULL);
	if (fmt == NULL)
		fmt = av_guess_format("mpeg", NULL, NULL);
	if (fmt == NULL)
		fprintf(stderr, "Unable to deduce video format");

	container->oformat = fmt;

	fmt->video_codec = codec->id;
	
	pCtx = avcodec_alloc_context3(codec);
	pCtx->width = width;
	pCtx->height = height;
	pCtx->bit_rate = width*height*4;
	pCtx->gop_size = 12;
	pCtx->time_base.num = 1; pCtx->time_base.den = 25;
	if ((fmt->flags & AVFMT_GLOBALHEADER) > 0)
		pCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
	pCtx->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
	if (bdepth == 8) {
		pCtx->pix_fmt = AV_PIX_FMT_GRAY8;
		_raw_format = AV_PIX_FMT_GRAY8;
	}
	else {
		pCtx->pix_fmt = AV_PIX_FMT_GRAY12;
		_raw_format = AV_PIX_FMT_GRAY16;
	}

	AVDictionary *codec_options = NULL;
	switch ( pCtx->codec_id )
	{
	case AV_CODEC_ID_HEVC:
		{
			av_dict_set( &codec_options, "preset", "medium", 0 );
			av_dict_set( &codec_options, "x265-params", options, 0 );
			//fprintf(stderr, "CodecID: AV_CODEC_ID_HEVC");
			break;
		}
	}

	int err = 0;
	if ((err = avcodec_open2(pCtx, codec, &codec_options)) < 0) {
		fprintf(stderr, "Error opening codec: %d", err);
		return;
	}

	/* open the output file */
	ioc = new AVIOContext();
	if ((fmt->flags & AVFMT_NOFILE) == 0)
	{
		use_buffer = true;
		if ( avio_open_dyn_buf( &ioc ) != 0 ) {
			fprintf(stderr, "Error opening memory buffer for encoding");
			return;
		}
		container->pb = ioc;
	}

	video_st = avformat_new_stream(container, codec);
	if (video_st == NULL) {
		fprintf(stderr, "Error creating stream");
		return;
	}

	if (avcodec_parameters_from_context(video_st->codecpar, pCtx) < 0) {
		fprintf(stderr, "avcodec_parameters_from_context failed");
		return;
	}

	video_st->id = container->nb_streams-1;
	//video_st->sample_aspect_ratio = pCtx->sample_aspect_ratio;
	video_st->time_base = pCtx->time_base;


	/* Get framebuffers */
	if ( ( picture_yuv = av_frame_alloc() ) == NULL ) { // final frame format
		fprintf(stderr, "av_frame_alloc (picture_yuv) failed"); return;
	}
	if ( ( picture_rgb = av_frame_alloc() ) == NULL ) { // rgb version I can understand easily
		fprintf(stderr, "av_frame_alloc (picture_rgb) failed"); return;
	}

	/* the image can be allocated by any means and av_image_alloc() is
	* just the most convenient way if av_malloc() is to be used */
	if ( av_image_alloc(picture_yuv->data, picture_yuv->linesize,
		pCtx->width, pCtx->height, pCtx->pix_fmt, 4) < 0 ) {
			fprintf(stderr, "Error allocating YUV frame buffer"); return;
	}
	if ( av_image_alloc(picture_rgb->data, picture_rgb->linesize,
		pCtx->width, pCtx->height, _raw_format, 4) < 0 ) {
			fprintf(stderr, "Error allocating RGB frame buffer"); return;
	}

	// Fill in frame parameters
	picture_yuv->format = pCtx->pix_fmt;
	picture_yuv->width = pCtx->width;
	picture_yuv->height = pCtx->height;

	picture_rgb->format = _raw_format;
	picture_rgb->width = pCtx->width;
	picture_rgb->height = pCtx->height;

	/* Init scale & convert */
	if ((Sctx=sws_getContext(
		width,
		height,
		_raw_format,
		pCtx->width,
		pCtx->height,
		pCtx->pix_fmt,
		SWS_BICUBIC,NULL,NULL,NULL)) == NULL ) {
			fprintf(stderr, "Error in scaling" ); return;
	}

	avformat_write_header(container, &codec_options);
}

FFMpegEncoder::~FFMpegEncoder()
{
	Close();
	FreeBuffer();
}

void FFMpegEncoder::Close()
{
	// Close the video codec
	if (pCtx != NULL) {
		avcodec_free_context(&pCtx);
		pCtx = NULL;
	}

	// Close the video file
	if (container != NULL) {
		_buffer_size = avio_close_dyn_buf( container->pb, &_buffer );
		avformat_free_context(container);
		container = NULL;
	}

	if (Sctx != NULL) {
		sws_freeContext(Sctx);
		Sctx = NULL;
	}

	if (picture_rgb != NULL) {
		av_frame_free(&picture_rgb);
		picture_rgb = NULL;
	}
	if (picture_yuv != NULL) {
		av_frame_free(&picture_yuv);
		picture_yuv = NULL;
	}

	if (_slices != NULL) {
		free(_slices);
		_slices = NULL;
		_allocated_slicenum = 0;
	}
}

void FFMpegEncoder::FreeBuffer()
{
	if (_buffer != NULL ) { 
		_buffer_size = 0;
		av_free( _buffer );
		_buffer = NULL;
	}
}

void FFMpegEncoder::SendSlice(unsigned char *slice)
{
	if (_frame_num >= _allocated_slicenum) {
		_allocated_slicenum += 100;
		_slices = (void **)realloc( (void *)_slices,  _allocated_slicenum * sizeof(unsigned char*) );
	}
	_slices[_frame_num] = slice;
	_frame_num++;
}

void FFMpegEncoder::SendSlice(unsigned short *slice)
{
	if (_frame_num >= _allocated_slicenum) {
		_allocated_slicenum += 100;
		_slices = (void **)realloc( (void *)_slices,  _allocated_slicenum * sizeof(unsigned short*) );
	}
	_slices[_frame_num] = slice;
	_frame_num++;
}

void FFMpegEncoder::Encode()
{
	AVPacket packet;

	for (int i = 0; i < _frame_num; i++) {

		if (_bdepth == 8) {
			for (int x = 0; x < _width; x++)
			for (int y = 0; y < _height; y++)
				picture_rgb->data[0][ y*picture_rgb->linesize[0] + x ] = ((unsigned char**)_slices)[i][y*_width+x];
		} else {
			int linesize = picture_rgb->linesize[0]/2;
			for (int x = 0; x < _width; x++)
			for (int y = 0; y < _height; y++)
				((unsigned short *)picture_rgb->data[0])[ y*linesize + x ] = ((unsigned short**)_slices)[i][y*_width+x];
		}

		sws_scale(Sctx,              // sws context
                  picture_rgb->data,        // src slice
                  picture_rgb->linesize,    // src stride
                  0,                      // src slice origin y
                  pCtx->height,      // src slice height
                  picture_yuv->data,        // dst
                  picture_yuv->linesize );  // dst stride


		//encode a frame
		AVPacket packet;
        av_init_packet(&packet);
        packet.data = NULL;
        packet.size = 0;

        if ( pCtx->codec_id == AV_CODEC_ID_HEVC && picture_yuv != NULL ) {
            picture_yuv->pts = _frame_count;
            _frame_count++;
        }
        
        int ret = 0;
        
        if (picture_yuv != NULL) {
        	ret = avcodec_send_frame(pCtx, picture_yuv);
        	if (ret < 0) {
        		char ep[AV_ERROR_MAX_STRING_SIZE];
        		av_make_error_string(ep, AV_ERROR_MAX_STRING_SIZE, ret);
        		fprintf(stderr, "Can not receive packet: %s", ep);
        		return;
        	}
        } else {
        	ret = avcodec_send_frame(pCtx, NULL);
        }

		av_init_packet(&packet);
		packet.data = NULL;
		packet.size = 0;
		while (avcodec_receive_packet(pCtx, &packet) == 0) {
			if (packet.data != NULL) {
				if ( pCtx->codec_id == AV_CODEC_ID_HEVC )
				{
					if (packet.pts == AV_NOPTS_VALUE && (pCtx->codec->capabilities & AV_CODEC_CAP_DELAY) == 0) 
						packet.pts = _encoded_frames;

					packet.stream_index = video_st->index;
					packet.duration = 1;
					av_packet_rescale_ts(&packet, pCtx->time_base, video_st->time_base);
				}
				_encoded_frames++;
				int result = av_interleaved_write_frame(container, &packet);
				av_packet_unref(&packet);
			}
		}

	}

	// flush encoder
	if (avcodec_send_frame(pCtx, NULL) != 0) {
		fprintf(stderr, "avcodec_send_frame failed\n");
	}
	av_init_packet(&packet);
	packet.data = NULL;
	packet.size = 0;
	while (avcodec_receive_packet(pCtx, &packet) == 0) {
		if ( pCtx->codec_id == AV_CODEC_ID_HEVC )
		{
			if (packet.pts == AV_NOPTS_VALUE && (pCtx->codec->capabilities & AV_CODEC_CAP_DELAY) == 0) 
				packet.pts = _encoded_frames;

			packet.stream_index = video_st->index;
			packet.duration = 1;
			av_packet_rescale_ts(&packet, pCtx->time_base, video_st->time_base);
		}
		_encoded_frames++;
		int result = av_interleaved_write_frame(container, &packet);
		av_packet_unref(&packet);
	}
	
	int result = av_write_frame(container, NULL);
	if (av_write_trailer(container) != 0) {
		fprintf(stderr, "av_write_trailer failed\n");
	}

	Close();
}

int FFMpegEncoder::GetOutputSize()
{
	return (int)_buffer_size;
}

unsigned char *FFMpegEncoder::GetOutput()
{
	return (unsigned char*)_buffer;
}