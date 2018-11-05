#include "FFMpegEncoder.h"

FFMpegEncoder *FFMpegEncoder_ctor(int width, int height, int bdepth, const char *codec_name, const char *options)
{
    return new FFMpegEncoder(width, height, bdepth, codec_name, options);
}

void FFMpegEncoder_SendSlice8(FFMpegEncoder *self, char *slice)
{
    self->SendSlice((unsigned char*)slice);
}

void FFMpegEncoder_SendSlice16(FFMpegEncoder *self, short *slice)
{
    self->SendSlice((unsigned short*)slice);
}

void FFMpegEncoder_Encode(FFMpegEncoder *self)
{
    self->Encode();
}

int FFMpegEncoder_GetOutputSize(FFMpegEncoder *self)
{
    return self->GetOutputSize();
}

unsigned char *FFMpegEncoder_GetOutput(FFMpegEncoder *self)
{
	return self->GetOutput();
}

void FFMpegEncoder_dtor(FFMpegEncoder *self)
{
	delete self;
}