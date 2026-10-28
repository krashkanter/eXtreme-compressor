#ifndef CODEC_H
#define CODEC_H

#include "espresso.h"
#include "file-handler.h"
#include "quadtree.h"

#define EXTC_FORMAT_VERSION 3
#define EXTC_MEDIA_IMAGE 1
#define EXTC_HEADER_SIZE 32

typedef struct {
    int version;
    int media_type;
    int width;
    int height;
    int channels;
    int block_width;
    int block_height;
    uint32_t crc;
    uint64_t source_bytes;
} ExtcHeader;

static int read_extc_header(FILE *file, ExtcHeader *header) {
    unsigned char bytes[8];
    uint32_t width, height;
    memset(header, 0, sizeof(*header));
    if(fread(bytes, 1, 8, file) != 8 || memcmp(bytes, "EXTC", 4)) {
        return 0;
    }
    header->version = bytes[4];
    if(header->version < 1 || header->version > EXTC_FORMAT_VERSION) {
        fprintf(stderr, "Unsupported EXTC version: %d.\n", header->version);
        return 0;
    }
    if(header->version >= 2 && (bytes[5] != EXTC_MEDIA_IMAGE || bytes[6] != 0 ||
       bytes[7] != (header->version == 2 ? 24 : EXTC_HEADER_SIZE))) {
        fprintf(stderr, "Unsupported EXTC media type, flags, or header size.\n");
        return 0;
    }
    if(!read_u32(file, &width) || !read_u32(file, &height) || !read_u32(file, &header->crc) ||
       width > 32768 || height > 32768 || !valid_shape((int)width, (int)height)) {
        return 0;
    }
    if(header->version == 1) {
        header->block_height = bytes[5];
        header->block_width = bytes[6];
        header->channels = bytes[7] == 0 ? 1 : bytes[7];
    } else {
        if(fread(bytes, 1, 4, file) != 4 || bytes[3] != 0) {
            return 0;
        }
        header->block_height = bytes[0];
        header->block_width = bytes[1];
        header->channels = bytes[2];
    }
    if(!header->block_height || !header->block_width ||
       header->block_height * header->block_width != 32 ||
       (header->channels != 1 && header->channels != 3)) {
        return 0;
    }
    if(header->version >= 3) {
        uint32_t low, high;
        if(!read_u32(file, &low) || !read_u32(file, &high) || (high & UINT32_C(0x80000000))) {
            return 0;
        }
        header->source_bytes = ((uint64_t)high << 32) | low;
    }
    header->media_type = EXTC_MEDIA_IMAGE;
    header->width = (int)width;
    header->height = (int)height;
    return 1;
}

typedef struct {
    FILE *file;
    unsigned int byte;
    int used;
    int error;
    size_t bits;
} BitStream;

typedef struct {
    size_t types[4];
    size_t cubes[5];
    size_t onset;
    size_t isolated[3];
    size_t bits;
} PlaneStats;

typedef struct {
    PlaneStats planes[24];
    int plane_count;
    size_t bytes;
} CompressionStats;

static void write_bits(BitStream *stream, unsigned int value, int count) {
    for(int i = count - 1; i >= 0; i--) {
        stream->byte = (stream->byte << 1) | ((value >> i) & 1);
        stream->used++;
        stream->bits++;
        if(stream->used == 8) {
            if(fputc((int)stream->byte, stream->file) == EOF) {
                stream->error = 1;
            }
            stream->byte = 0;
            stream->used = 0;
        }
    }
}

static void flush_bits(BitStream *stream) {
    if(stream->used && fputc((int)(stream->byte << (8 - stream->used)), stream->file) == EOF) {
        stream->error = 1;
    }
    stream->used = 0;
}

static unsigned int read_bits(BitStream *stream, int count) {
    unsigned int value = 0;
    for(int i = 0; i < count; i++) {
        if(!stream->used) {
            int byte = fgetc(stream->file);
            if(byte == EOF) {
                stream->error = 1;
                return 0;
            }
            stream->byte = (unsigned int)byte;
            stream->used = 8;
        }
        stream->used--;
        stream->bits++;
        value = (value << 1) | ((stream->byte >> stream->used) & 1);
    }
    return value;
}

static void symbol_order(int symbol, int order[3]) {
    order[0] = symbol;
    if(symbol == 0) {
        order[1] = 1;
        order[2] = 2;
    } else if(symbol == 1) {
        order[1] = 0;
        order[2] = 2;
    } else {
        order[1] = 0;
        order[2] = 1;
    }
}

static void encode_block(BitStream *stream, const unsigned char block[32],
                         int width, PlaneStats *stats) {
    int ones = 0;
    for(int i = 0; i < 32; i++) {
        ones += block[i];
    }
    if(ones >= 1 && ones <= 3) {
        stats->isolated[ones - 1]++;
    }
    if(ones == 0 || ones == 32) {
        int type = ones == 32;
        write_bits(stream, (unsigned int)type, 2);
        stats->types[type]++;
        return;
    }
    Cover cover = minimize_block(block, width);
    int encoded_cost = 2 + 1 + (cover.symbol == 0 ? 1 : 2) + 3 + cover.cost;
    if(cover.count < 1 || cover.count >= 5 || encoded_cost >= 34) {
        write_bits(stream, 3, 2);
        for(int i = 0; i < 32; i++) {
            write_bits(stream, block[i], 1);
        }
        stats->types[3]++;
        return;
    }
    stats->types[2]++;
    stats->cubes[cover.count]++;
    stats->onset += (size_t)cover.onset;
    write_bits(stream, 2, 2);
    write_bits(stream, (unsigned int)cover.onset, 1);
    if(cover.symbol == 0) {
        write_bits(stream, 0, 1);
    } else {
        write_bits(stream, cover.symbol == 1 ? 2 : 3, 2);
    }
    write_bits(stream, (unsigned int)cover.count, 3);
    int order[3];
    symbol_order(cover.symbol, order);
    for(int i = 0; i < cover.count; i++) {
        for(unsigned int bit = 16; bit; bit >>= 1) {
            int symbol = !(cover.cubes[i].mask & bit) ? 2 :
                         (cover.cubes[i].value & bit) != 0;
            if(symbol == order[0]) {
                write_bits(stream, 0, 1);
            } else {
                write_bits(stream, symbol == order[1] ? 2 : 3, 2);
            }
        }
    }
}

static int decode_block(BitStream *stream, unsigned char block[32], int width) {
    unsigned int type = read_bits(stream, 2);
    if(type < 2) {
        memset(block, (int)type, 32);
    } else if(type == 3) {
        for(int i = 0; i < 32; i++) {
            block[i] = (unsigned char)read_bits(stream, 1);
        }
    } else {
        unsigned int onset = read_bits(stream, 1);
        int symbol = 0;
        if(read_bits(stream, 1)) {
            symbol = read_bits(stream, 1) ? 2 : 1;
        }
        int order[3];
        symbol_order(symbol, order);
        unsigned int count = read_bits(stream, 3);
        if(count < 1 || count > 4) {
            return 0;
        }
        Cube cubes[4] = {0};
        for(unsigned int i = 0; i < count; i++) {
            for(unsigned int bit = 16; bit; bit >>= 1) {
                int code = 0;
                if(read_bits(stream, 1)) {
                    code = read_bits(stream, 1) ? 2 : 1;
                }
                if(order[code] != 2) {
                    cubes[i].mask |= bit;
                    if(order[code] == 1) {
                        cubes[i].value |= bit;
                    }
                }
            }
        }
        for(int i = 0; i < 32; i++) {
            unsigned int gray = (unsigned int)(i ^ (i >> 1));
            int match = 0;
            for(unsigned int j = 0; j < count; j++) {
                if((gray & cubes[j].mask) == cubes[j].value) {
                    match = 1;
                    break;
                }
            }
            int row = i / width;
            int col = i % width;
            int pos = row * width + (row % 2 ? width - col - 1 : col);
            block[pos] = (unsigned char)(match ^ !onset);
        }
    }
    return !stream->error;
}

static int compress_image_controlled(FILE *file, const Image *image, int block_width,
                                     int block_height, CompressionStats *stats,
                                     int (*cancelled)(void *), void *context) {
    int channels = image->channels ? image->channels : 1;
    unsigned char header[8] = {'E', 'X', 'T', 'C', EXTC_FORMAT_VERSION,
                              EXTC_MEDIA_IMAGE, 0, EXTC_HEADER_SIZE};
    if(!valid_shape(image->width, image->height) || (channels != 1 && channels != 3) ||
       block_width < 1 || block_height < 1 || block_width > 32 || block_height > 32 ||
       block_width * block_height != 32) {
        return 0;
    }
    memset(stats, 0, sizeof(*stats));
    stats->plane_count = channels * 8;
    if(fwrite(header, 1, 8, file) != 8 ||
       !write_u32(file, (uint32_t)image->width) ||
       !write_u32(file, (uint32_t)image->height) || !write_u32(file, image_crc(image)) ||
       fputc(block_height, file) == EOF || fputc(block_width, file) == EOF ||
       fputc(channels, file) == EOF || fputc(0, file) == EOF ||
       !write_u32(file, (uint32_t)image->source_bytes) ||
       !write_u32(file, (uint32_t)(image->source_bytes >> 32))) {
        return 0;
    }
    BitStream stream = {0};
    stream.file = file;
    for(int channel = 0; channel < channels; channel++) {
        for(int plane = 0; plane < 8; plane++) {
            int idx = channel * 8 + plane;
            size_t start = stream.bits;
            for(int y = 0; y < image->height; y += block_height) {
                if(cancelled && cancelled(context)) {
                    return 0;
                }
                for(int x = 0; x < image->width; x += block_width) {
                    unsigned char block[32];
                    read_block(image->pixels, image->width, image->height, channels,
                               x, y, block_width, block_height, channel, plane, block);
                    encode_block(&stream, block, block_width, &stats->planes[idx]);
                }
            }
            stats->planes[idx].bits = stream.bits - start;
        }
    }
    flush_bits(&stream);
    stats->bytes = EXTC_HEADER_SIZE + (stream.bits + 7) / 8;
    return !stream.error && fflush(file) == 0;
}

static int compress_image(FILE *file, const Image *image, int block_width,
                           int block_height, CompressionStats *stats) {
    return compress_image_controlled(file, image, block_width, block_height, stats, NULL, NULL);
}

static int decompress_image_controlled(FILE *file, Image *image,
                                       int (*cancelled)(void *), void *context) {
    ExtcHeader header;
    if(!read_extc_header(file, &header)) {
        fprintf(stderr, "Invalid or unsupported EXTC image header.\n");
        return 0;
    }
    int channels = header.channels;
    image->width = header.width;
    image->height = header.height;
    image->channels = channels;
    image->source_bytes = header.source_bytes;
    image->pixels = calloc((size_t)image->width * (size_t)image->height * (size_t)channels, 1);
    if(!image->pixels) {
        return 0;
    }
    BitStream stream = {0};
    stream.file = file;
    for(int channel = 0; channel < channels; channel++) {
        for(int plane = 0; plane < 8; plane++) {
            for(int y = 0; y < image->height; y += header.block_height) {
                if(cancelled && cancelled(context)) {
                    free(image->pixels);
                    image->pixels = NULL;
                    return 0;
                }
                for(int x = 0; x < image->width; x += header.block_width) {
                    unsigned char block[32];
                    if(!decode_block(&stream, block, header.block_width)) {
                        fprintf(stderr, "Truncated or invalid compressed blocks.\n");
                        free(image->pixels);
                        image->pixels = NULL;
                        return 0;
                    }
                    write_block(image->pixels, image->width, image->height, channels,
                                x, y, header.block_width, header.block_height, channel, plane, block);
                }
            }
        }
    }
    unsigned int padding_mask = (1U << stream.used) - 1;
    if((stream.byte & padding_mask) || fgetc(file) != EOF || ferror(file) ||
       image_crc(image) != header.crc) {
        fprintf(stderr, "Compressed file failed integrity verification.\n");
        free(image->pixels);
        image->pixels = NULL;
        return 0;
    }
    return 1;
}

static int decompress_image(FILE *file, Image *image) {
    return decompress_image_controlled(file, image, NULL, NULL);
}

static int print_stats(FILE *file, const Image *image, const CompressionStats *stats) {
    int channels = image->channels ? image->channels : 1;
    size_t original = (size_t)image->width * (size_t)image->height * (size_t)channels;
    const char *channel_names[] = {"Red / Gray", "Green", "Blue"};
    for(int i = 0; i < stats->plane_count; i++) {
        const PlaneStats *plane = &stats->planes[i];
        int ch = i / 8;
        int bp = i % 8;
        if(channels > 1 && bp == 0) {
            fprintf(file, "=== Channel %d (%s) ===\n", ch, channel_names[ch]);
        }
        fprintf(file, "Bitplane %d\n", bp);
        fprintf(file, "All black: %zu\nAll white: %zu\nCompressible: %zu\nIncompressible: %zu\n",
                plane->types[0], plane->types[1], plane->types[2], plane->types[3]);
        fprintf(file, "ON-set: %zu\nOFF-set: %zu\n", plane->onset,
                plane->types[2] - plane->onset);
        for(int count = 1; count <= 4; count++) {
            fprintf(file, "%d cube blocks: %zu\n", count, plane->cubes[count]);
        }
        for(int count = 1; count <= 3; count++) {
            fprintf(file, "%d isolated pixels: %zu\n", count, plane->isolated[count - 1]);
        }
        fprintf(file, "Compressed bits: %zu\n\n", plane->bits);
    }
    if(image->source_bytes) {
        fprintf(file, "Original file size: %llu bytes\n", (unsigned long long)image->source_bytes);
    } else {
        fprintf(file, "Original file size: unavailable\n");
    }
    fprintf(file, "Decoded pixel size: %zu bytes\n", original);
    fprintf(file, "Compressed file size: %zu bytes (32-byte versioned header included)\n", stats->bytes);
    fprintf(file, "Compression ratio (decoded/EXTC): %.4f\n", (double)original / (double)stats->bytes);
    fprintf(file, "Space savings (vs decoded): %.2f%%\n", (1 - (double)stats->bytes / (double)original) * 100);
    return !ferror(file);
}

#endif
