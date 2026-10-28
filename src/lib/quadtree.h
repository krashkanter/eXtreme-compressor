#ifndef QUADTREE_H
#define QUADTREE_H

#include <stddef.h>

static void read_block(const unsigned char *image, int width, int height,
                       int channels, int x, int y, int block_width, int block_height,
                       int channel, int plane, unsigned char block[32]) {
    for(int row = 0; row < block_height; row++) {
        for(int col = 0; col < block_width; col++) {
            int value = 0;
            if(y + row < height && x + col < width) {
                size_t pos = ((size_t)(y + row) * (size_t)width + (size_t)(x + col)) * (size_t)channels + (size_t)channel;
                value = image[pos];
            }
            block[row * block_width + col] = (unsigned char)((value >> plane) & 1);
        }
    }
}

static void write_block(unsigned char *image, int width, int height,
                        int channels, int x, int y, int block_width, int block_height,
                        int channel, int plane, const unsigned char block[32]) {
    for(int row = 0; row < block_height && y + row < height; row++) {
        for(int col = 0; col < block_width && x + col < width; col++) {
            size_t pos = ((size_t)(y + row) * (size_t)width + (size_t)(x + col)) * (size_t)channels + (size_t)channel;
            image[pos] |= (unsigned char)(block[row * block_width + col] << plane);
        }
    }
}

#endif
