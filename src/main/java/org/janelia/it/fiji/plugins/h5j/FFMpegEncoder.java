
package org.janelia.it.fiji.plugins.h5j;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.ShortPointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.avutil;

import ij.IJ;

import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.avdevice.avdevice_register_all;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avutil.*;
import static org.bytedeco.javacpp.swscale.*;

public class FFMpegEncoder {
    //typedef FFMpegVideo::Channel Channel;

    FFMpegEncoder(String file_name, int width, int height, int bdepth, String codec_name/* = AV_CODEC_ID_MPEG4*/, String options) {
    	picture_yuv = null;
        picture_rgb = null;
        container = null;
        use_buffer = false;
        _buffer_size = 0;
        _buffer = null;
        _frame_count = 0;
        _encoded_frames = 0;
        
        if (0 != (width % 2))
            IJ.log("WARNING: Video width is not a multiple of 2");
        if (0 != (height % 2))
            IJ.log("WARNING: Video height is not a multiple of 2");
        
        avcodec_register_all();
        avdevice_register_all();
        av_register_all();
        avformat_network_init();
        
        av_log_set_level(AV_LOG_TRACE);
        
        AVCodec codec = avcodec_find_encoder_by_name(codec_name);
        if (null == codec) {
        	IJ.log("Unable to find codec");
        	return;
        }
        //IJ.log("codec: "+codec.name().getString()+"  id: "+codec.id()+"    AV_CODEC_ID_HEVC:"+AV_CODEC_ID_HEVC);

        container = avformat_alloc_context();
        if (null == container) {
            IJ.log("Unable to allocate format context");
            return;
        }
        
        AVOutputFormat fmt = null;
        if ( file_name == null )
            if ( codec.id() == AV_CODEC_ID_FFV1 )
                fmt = av_guess_format( "mov", null, null );
            else
            	fmt = av_guess_format("mp4", null, null);
        else
        {
            fmt = av_guess_format(null, file_name, null);
        }
        if (fmt == null)
            fmt = av_guess_format("mpeg", null, null);
        if (fmt == null)
            IJ.log("Unable to deduce video format");

        container.oformat(fmt);

        fmt.video_codec(codec.id());
        // fmt->video_codec = AV_CODEC_ID_H264; // fails to write

        pCtx = avcodec_alloc_context3(codec);
        pCtx.width(width);
        pCtx.height(height);
        pCtx.bit_rate(width*height*4);
        pCtx.gop_size(12);
        pCtx.time_base(new AVRational().num(1).den(25));
        if ((fmt.flags() & AVFMT_GLOBALHEADER) > 0)
            pCtx.flags(AV_CODEC_FLAG_GLOBAL_HEADER);
        pCtx.strict_std_compliance(AVCodecContext.FF_COMPLIANCE_EXPERIMENTAL);
        if (bdepth == 8) {
        	pCtx.pix_fmt(AV_PIX_FMT_YUV444P);
        	_raw_format = AV_PIX_FMT_RGB24;
        }
        else {
        	pCtx.pix_fmt(AV_PIX_FMT_GRAY12);
        	_raw_format = AV_PIX_FMT_GRAY16;
        }
        
        AVDictionary codec_options = new AVDictionary();
        switch ( pCtx.codec_id() )
        {
            case AV_CODEC_ID_HEVC:
            {
            	av_dict_set( codec_options, "preset", "veryslow", 0 );
                av_dict_set( codec_options, "x265-params", options, 0 );
                IJ.log("CodecID: AV_CODEC_ID_HEVC");
                break;
            }
        }
        
        int err = 0;
        if ((err = avcodec_open2(pCtx, codec, codec_options)) < 0) {
        	IJ.log("Error opening codec: "+err);
        	return;
        }
        
        /* open the output file */
        ioc = new AVIOContext();
        if ((fmt.flags() & AVFMT_NOFILE) == 0)
        {
            if ( file_name == null )
            {
                use_buffer = true;
                _buffer = new BytePointer();
                if ( avio_open_dyn_buf( ioc ) != 0 ) {
                     IJ.log("Error opening memory buffer for encoding");
                     return;
                }
            }
            else if ( avio_open( ioc, file_name, AVIO_FLAG_WRITE ) < 0 ) {
            	IJ.log("Error opening output video file");
            	return;
            }
            container.pb(ioc);
        }
        
        video_st = avformat_new_stream(container, codec);
        if (video_st == null) {
        	IJ.log("Error creating stream");
        	return;
        }
        
        video_st.id(container.nb_streams()-1);
        //video_st.sample_aspect_ratio(pCtx.sample_aspect_ratio());
        video_st.time_base(pCtx.time_base());
       
        if (avcodec_parameters_from_context(video_st.codecpar(), pCtx) < 0) {
        	IJ.log("avcodec_parameters_from_context failed");
        	return;
        }
        
        /* Get framebuffers */
        if ( ( picture_yuv = av_frame_alloc() ) == null ) { // final frame format
            IJ.log(""); return;
        }
        if ( ( picture_rgb = av_frame_alloc() ) == null ) { // rgb version I can understand easily
        	IJ.log(""); return;
        }
        
        /* the image can be allocated by any means and av_image_alloc() is
             * just the most convenient way if av_malloc() is to be used */
        if ( av_image_alloc(picture_yuv.data(), picture_yuv.linesize(),
                           pCtx.width(), pCtx.height(), pCtx.pix_fmt(), 1) < 0 ) {
        	IJ.log("Error allocating YUV frame buffer"); return;
        }
        if ( av_image_alloc(picture_rgb.data(), picture_rgb.linesize(),
                       pCtx.width(), pCtx.height(), _raw_format, 1) < 0 ) {
        	IJ.log("Error allocating RGB frame buffer"); return;
        }
        
        //IJ.log("w: "+pCtx.width());
        //IJ.log("h: "+pCtx.height());
        //IJ.log("linesize: "+picture_rgb.linesize().get(0));

        // Fill in frame parameters
        picture_yuv.format(pCtx.pix_fmt());
        picture_yuv.width(pCtx.width());
        picture_yuv.height(pCtx.height());
        
        picture_rgb.format(_raw_format);
        picture_rgb.width(pCtx.width());
        picture_rgb.height(pCtx.height());

        /* Init scale & convert */
        if ((Sctx=sws_getContext(
                width,
                height,
                _raw_format,
                pCtx.width(),
                pCtx.height(),
                pCtx.pix_fmt(),
                SWS_BICUBIC,null,null,(DoublePointer)null)) == null ) {
            IJ.log( "Error in scaling" ); return;
        }

        avformat_write_header(container, codec_options);
    }
   
    void setPixelIntensity(int x, int y, int c, byte value) {
    	BytePointer bp = new BytePointer(picture_rgb.data().get(0));
    	bp.put( y * picture_rgb.linesize().get(0) + x * 3 + c, value );
    	bp.close();
    }
    void setPixelIntensity(int x, int y, short value) {
    	ShortPointer sp = new ShortPointer(picture_rgb.data().get(0));
    	sp.put( y * picture_rgb.linesize().get(0)/2 + x, value );
    	sp.close();
    }
    
    void write_frame() {
    	// convert from RGB24 to YUV
        sws_scale(Sctx,              // sws context
                  picture_rgb.data(),        // src slice
                  picture_rgb.linesize(),    // src stride
                  0,                      // src slice origin y
                  pCtx.height(),      // src slice height
                  picture_yuv.data(),        // dst
                  picture_yuv.linesize() );  // dst stride

        /* encode the image */
        // use non-deprecated avcodec_encode_video2(...)
        encode( picture_yuv );
    }
    
    void close() {
    	int result = av_write_frame(container, (AVPacket)null); // flush
        result = av_write_trailer(container);
        {
            if ( use_buffer )
                _buffer_size = avio_close_dyn_buf( container.pb(), _buffer );
            else
                avio_close(container.pb());
        }
    }
    
    int buffer_size() { return _buffer_size; }
    BytePointer buffer() { return _buffer; }
    void free_buffer() { if (_buffer_size > 0 ) { _buffer_size = 0; av_free( _buffer ); _buffer.close(); } }
    
    void encode( AVFrame picture ) {
    	AVPacket packet = new AVPacket();
        av_init_packet(packet);
        packet.data(null);
        packet.size(0);

        if ( pCtx.codec_id() == AV_CODEC_ID_HEVC && picture != null ) {
            picture.pts(_frame_count);
            _frame_count++;
        }
        
        int ret = 0;
        
        if (picture != null) {
        	ret = avcodec_send_frame(pCtx, picture);
        	if (ret < 0) {
        		BytePointer ep = new BytePointer(AV_ERROR_MAX_STRING_SIZE);
        		avutil.av_make_error_string(ep, AV_ERROR_MAX_STRING_SIZE, ret);
        		//System.out.println("Can not send frame:"+ep.getString());
        		//IJ.log("frame: "+ _encoded_frames +"    send_error: "+ret);
        		return;
        	}
        } else {
        	ret = avcodec_send_frame(pCtx, (AVFrame)null);
        }
        
        ret = avcodec_receive_packet(pCtx, packet);
        if (ret < 0) {
            BytePointer ep = new BytePointer(AV_ERROR_MAX_STRING_SIZE);
            avutil.av_make_error_string(ep, AV_ERROR_MAX_STRING_SIZE, ret);
            //System.out.println("Can not receive  packet:"+ep.getString());
            //IJ.log("frame: "+ _encoded_frames +"    receive_error: "+ret);
            return;
        }
        
        if (!packet.isNull()) {
        	//System.out.println("Succeed to encode one frame \tsize"+packet.size());
        	
            if ( pCtx.codec_id() == AV_CODEC_ID_HEVC )
            {
                if (packet.pts() == AV_NOPTS_VALUE && (pCtx.codec().capabilities() & AV_CODEC_CAP_DELAY) == 0) 
                    packet.pts(_encoded_frames);
                
                packet.stream_index(video_st.index());
                av_packet_rescale_ts(packet, pCtx.time_base(), video_st.time_base());
                packet.duration(packet.pts()-packet.dts());
            }
            _encoded_frames++;
            
            //IJ.log("frame: "+ _encoded_frames +"  packet size: "+packet.size()+"  pts: "+packet.pts()+"  dts: "+packet.dts()+"  d: "+packet.duration());
            int result = av_write_frame(container, packet);
            
            av_packet_unref(packet);
        }
    }
    
    int encoded_frames() { return _encoded_frames; }

    private AVFormatContext container;
    private AVCodecContext pCtx;
    private AVIOContext ioc;
    private AVStream video_st;
    private AVFrame picture_yuv;
    private AVFrame picture_rgb;
    private SwsContext Sctx;
    private boolean use_buffer;
    private int _buffer_size;
    private BytePointer _buffer;
    private int _frame_count;
    private int _encoded_frames;
    private int _raw_format;
};