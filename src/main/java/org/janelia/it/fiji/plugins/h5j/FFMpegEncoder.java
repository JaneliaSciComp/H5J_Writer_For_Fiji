
package org.janelia.it.fiji.plugins.h5j;

import ij.IJ;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.ShortPointer;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_CAP_DELAY;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_FFV1;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avcodec.FF_COMPLIANCE_EXPERIMENTAL;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.av_guess_format;
import static org.bytedeco.ffmpeg.global.avformat.av_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_network_init;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avformat.avio_close;
import static org.bytedeco.ffmpeg.global.avformat.avio_close_dyn_buf;
import static org.bytedeco.ffmpeg.global.avformat.avio_open;
import static org.bytedeco.ffmpeg.global.avformat.avio_open_dyn_buf;
import static org.bytedeco.ffmpeg.global.avutil.AV_ERROR_MAX_STRING_SIZE;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GRAY12;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GRAY16;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB24;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV444P;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_free;
import static org.bytedeco.ffmpeg.global.avutil.av_image_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_make_error_string;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BICUBIC;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

public class FFMpegEncoder {
    FFMpegEncoder(String file_name, int width, int height, int bdepth,
                  String codec_name/* = AV_CODEC_ID_MPEG4*/,
                  int codec_id,
                  String options) {
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

        // codecs/formats are registered automatically in FFmpeg 4.0+
        avdevice_register_all();
        avformat_network_init();

        AVCodec codec = avcodec_find_encoder_by_name(codec_name);
        if (codec == null && codec_id > 0) {
            // lookup by ID
            IJ.log("Unable to find codec by name: " + codec_name + " trying by ID: " + codec_id);
            codec = avcodec_find_encoder(codec_id);
        }

        if (codec == null) {
            IJ.log("Unable to find codec by name " + codec_name + " or by id " + codec_id);
            return;
        } else {
            IJ.log("Found codec " + codec_name);
        }

        container = avformat_alloc_context();
        if (null == container) {
            IJ.log("Unable to allocate format context");
            return;
        }

        AVOutputFormat fmt = null;
        if (file_name == null)
            if (codec.id() == AV_CODEC_ID_FFV1)
                fmt = av_guess_format("mov", null, null);
            else
                fmt = av_guess_format("mp4", null, null);
        else {
            fmt = av_guess_format(null, file_name, null);
        }
        if (fmt == null)
            fmt = av_guess_format("mpeg", null, null);
        if (fmt == null)
            IJ.log("Unable to deduce video format");

        container.oformat(fmt);

        // fmt.video_codec() removed - AVOutputFormat is opaque in FFmpeg 5.0+
        // The codec is specified directly via avcodec_alloc_context3

        pCtx = avcodec_alloc_context3(codec);
        pCtx.width(width);
        pCtx.height(height);
        pCtx.bit_rate(width * height * 4);
        pCtx.gop_size(12);
        pCtx.time_base(new AVRational().num(1).den(25));
        if ((fmt.flags() & AVFMT_GLOBALHEADER) > 0)
            pCtx.flags(AV_CODEC_FLAG_GLOBAL_HEADER);
        pCtx.strict_std_compliance(FF_COMPLIANCE_EXPERIMENTAL);
        if (bdepth == 8) {
            pCtx.pix_fmt(AV_PIX_FMT_YUV444P);
            _raw_format = AV_PIX_FMT_RGB24;
        } else {
            pCtx.pix_fmt(AV_PIX_FMT_GRAY12);
            _raw_format = AV_PIX_FMT_GRAY16;
        }

        AVDictionary codec_options = new AVDictionary();
        switch (pCtx.codec_id()) {
            case AV_CODEC_ID_HEVC: {
                av_dict_set(codec_options, "preset", "medium", 0);
                av_dict_set(codec_options, "x265-params", options, 0);
                break;
            }
        }

        int err = 0;
        if ((err = avcodec_open2(pCtx, codec, codec_options)) < 0) {
            IJ.log("Error opening codec: " + err);
            return;
        }

        /* open the output file */
        ioc = new AVIOContext();
        if ((fmt.flags() & AVFMT_NOFILE) == 0) {
            if (file_name == null) {
                use_buffer = true;
                _buffer = new BytePointer();
                if (avio_open_dyn_buf(ioc) != 0) {
                    IJ.log("Error opening memory buffer for encoding");
                    return;
                }
            } else if (avio_open(ioc, file_name, AVIO_FLAG_WRITE) < 0) {
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

        video_st.id(container.nb_streams() - 1);
        video_st.time_base(pCtx.time_base());

        if (avcodec_parameters_from_context(video_st.codecpar(), pCtx) < 0) {
            IJ.log("avcodec_parameters_from_context failed");
            return;
        }

        /* Get framebuffers */
        if ((picture_yuv = av_frame_alloc()) == null) { // final frame format
            IJ.log("Error allocating frame for YUV picture");
            return;
        }
        if ((picture_rgb = av_frame_alloc()) == null) { // rgb version I can understand easily
            IJ.log("Error allocating frame for RGB picture");
            return;
        }

        /* the image can be allocated by any means and av_image_alloc() is
         * just the most convenient way if av_malloc() is to be used */
        if (av_image_alloc(picture_yuv.data(), picture_yuv.linesize(),
                pCtx.width(), pCtx.height(), pCtx.pix_fmt(), 1) < 0) {
            IJ.log("Error allocating YUV frame buffer");
            return;
        }
        if (av_image_alloc(picture_rgb.data(), picture_rgb.linesize(),
                pCtx.width(), pCtx.height(), _raw_format, 1) < 0) {
            IJ.log("Error allocating RGB frame buffer");
            return;
        }

        // Fill in frame parameters
        picture_yuv.format(pCtx.pix_fmt());
        picture_yuv.width(pCtx.width());
        picture_yuv.height(pCtx.height());

        picture_rgb.format(_raw_format);
        picture_rgb.width(pCtx.width());
        picture_rgb.height(pCtx.height());

        /* Init scale & convert */
        if ((Sctx = sws_getContext(
                width,
                height,
                _raw_format,
                pCtx.width(),
                pCtx.height(),
                pCtx.pix_fmt(),
                SWS_BICUBIC, null, null, (DoublePointer) null)) == null) {
            IJ.log("Error in scaling");
            return;
        }

        avformat_write_header(container, codec_options);
    }

    void setPixelIntensity(int x, int y, int c, byte value) {
        BytePointer bp = new BytePointer(picture_rgb.data().get(0));
        bp.put(y * picture_rgb.linesize().get(0) + x * 3 + c, value);
        bp.close();
    }

    void setPixelIntensity(int x, int y, short value) {
        ShortPointer sp = new ShortPointer(picture_rgb.data().get(0));
        sp.put(y * picture_rgb.linesize().get(0) / 2 + x, value);
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
                picture_yuv.linesize());  // dst stride

        /* encode the image */
        // use non-deprecated avcodec_encode_video2(...)
        encode(picture_yuv);
    }

    void close() {
        int result = av_write_frame(container, (AVPacket) null); // flush
        result = av_write_trailer(container);
        {
            if (use_buffer)
                _buffer_size = avio_close_dyn_buf(container.pb(), _buffer);
            else
                avio_close(container.pb());
        }
    }

    int buffer_size() {
        return _buffer_size;
    }

    BytePointer buffer() {
        return _buffer;
    }

    void free_buffer() {
        if (_buffer_size > 0) {
            _buffer_size = 0;
            av_free(_buffer);
            _buffer.close();
        }
    }

    void encode(AVFrame picture) {
        // av_packet_alloc replaces deprecated av_init_packet (removed in FFmpeg 5.0)
        AVPacket packet = av_packet_alloc();
        if (packet == null) {
            IJ.log("Error allocating package");
            return;
        }

        if (pCtx.codec_id() == AV_CODEC_ID_HEVC && picture != null) {
            picture.pts(_frame_count);
            _frame_count++;
        }

        int ret = 0;

        if (picture != null) {
            ret = avcodec_send_frame(pCtx, picture);
            if (ret < 0) {
                BytePointer ep = new BytePointer(AV_ERROR_MAX_STRING_SIZE);
                av_make_error_string(ep, AV_ERROR_MAX_STRING_SIZE, ret);
                av_packet_unref(packet);
                return;
            }
        } else {
            ret = avcodec_send_frame(pCtx, (AVFrame) null);
        }

        ret = avcodec_receive_packet(pCtx, packet);
        if (ret < 0) {
            BytePointer ep = new BytePointer(AV_ERROR_MAX_STRING_SIZE);
            av_make_error_string(ep, AV_ERROR_MAX_STRING_SIZE, ret);
            av_packet_unref(packet);
            return;
        }

        if (!packet.isNull()) {
            if (pCtx.codec_id() == AV_CODEC_ID_HEVC) {
                if (packet.pts() == AV_NOPTS_VALUE && (pCtx.codec().capabilities() & AV_CODEC_CAP_DELAY) == 0)
                    packet.pts(_encoded_frames);

                packet.stream_index(video_st.index());
                av_packet_rescale_ts(packet, pCtx.time_base(), video_st.time_base());
            }
            _encoded_frames++;

            int result = av_write_frame(container, packet);

            av_packet_unref(packet);
        }
    }

    int encoded_frames() {
        return _encoded_frames;
    }

    boolean isReady() {
        return picture_rgb != null;
    }

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
