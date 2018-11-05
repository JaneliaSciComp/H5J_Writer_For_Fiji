#include <fstream>
#include "FFMpegEncoder.h"

int main()
{
	size_t width = 1024;
	size_t height = 1024;
	size_t depth = 258;

	unsigned char *stack = new unsigned char[width*height*depth];
    
	for (int z = 0; z < depth; z++) {
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				size_t id = z * width*height + y * width + x;
				stack[id] = (x+z) % 255;
			}
		}
	}
	
	FFMpegEncoder *enc = FFMpegEncoder_ctor(width, height, 8, "libx265", "crf=15:psy-rd=1.0");

	unsigned char *slice = stack;
	for (int z = 0; z < depth; z++) {
		FFMpegEncoder_SendSlice8(enc, (char *)slice);
		slice += width * height;
	}

	FFMpegEncoder_Encode(enc);

	int size = FFMpegEncoder_GetOutputSize(enc);
	char *output = (char *)FFMpegEncoder_GetOutput(enc);

	std::ofstream file("test.mp4", std::ios::binary);
	file.write(output, size);

	FFMpegEncoder_dtor(enc);
	
	delete[] stack;
}
