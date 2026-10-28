#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "lib/codec.h"

static void usage(const char *program) {
    printf("Usage:\n");
    printf("  %s -i input -o reconstructed.png [-b 4x8] [-s heightxwidth]\n", program);
    printf("       [--dump-bin compressed.extc] [--dump-stats stats.txt]\n");
    printf("  %s compress input compressed.extc [-b 4x8] [-s heightxwidth]\n", program);
    printf("       [--dump-stats stats.txt]\n");
    printf("  %s decompress compressed.extc reconstructed.png\n", program);
    printf("Input: PNG, JPEG, BMP, binary PGM/PPM (grayscale or color). Output: PNG, PPM, or PGM.\n");
}

static int parse_shape(const char *text, int *height, int *width) {
    char *end;
    long h = strtol(text, &end, 10);
    if(end == text || *end != 'x' || h < 1 || h > 32768) {
        return 0;
    }
    const char *next = end + 1;
    long w = strtol(next, &end, 10);
    if(end == next || *end || w < 1 || w > 32768 || !valid_shape((int)w, (int)h)) {
        return 0;
    }
    *height = (int)h;
    *width = (int)w;
    return 1;
}

int main(int argc, char *argv[]) {
    const char *input = NULL;
    const char *output = NULL;
    const char *binary_path = NULL;
    const char *stats_path = NULL;
    int block_height = 4;
    int block_width = 8;
    int height = 0;
    int width = 0;
    int mode = 0;
    int start = 1;

    if(argc == 2 && (!strcmp(argv[1], "--help") || !strcmp(argv[1], "-h"))) {
        usage(argv[0]);
        return 0;
    }
    if(argc > 1 && (!strcmp(argv[1], "compress") || !strcmp(argv[1], "decompress"))) {
        if(argc < 4) {
            usage(argv[0]);
            return 1;
        }
        mode = !strcmp(argv[1], "compress") ? 1 : 2;
        input = argv[2];
        output = argv[3];
        start = 4;
    }
    for(int i = start; i < argc; i++) {
        const char *option = argv[i];
        if(mode == 2 || i + 1 >= argc) {
            fprintf(stderr, "Unexpected or incomplete option: %s\n", option);
            return 1;
        }
        const char *value = argv[++i];
        if(!strcmp(option, "-i") || !strcmp(option, "--input")) {
            input = value;
        } else if(!strcmp(option, "-o") || !strcmp(option, "--output")) {
            output = value;
        } else if(!strcmp(option, "-b") || !strcmp(option, "--block")) {
            if(!parse_shape(value, &block_height, &block_width) || block_height * block_width != 32) {
                fprintf(stderr, "Block shape must contain exactly 32 pixels, such as 4x8 or 8x4.\n");
                return 1;
            }
        } else if(!strcmp(option, "-s") || !strcmp(option, "--shape")) {
            if(!parse_shape(value, &height, &width)) {
                fprintf(stderr, "Invalid image shape. Use heightxwidth.\n");
                return 1;
            }
        } else if(!strcmp(option, "--dump-bin") && mode == 0) {
            binary_path = value;
        } else if(!strcmp(option, "--dump-stats")) {
            stats_path = value;
        } else {
            fprintf(stderr, "Unknown option: %s\n", option);
            return 1;
        }
    }
    if(!input || !output) {
        usage(argv[0]);
        return 1;
    }
    const char *paths[] = {input, output, binary_path, stats_path};
    for(int i = 0; i < 4; i++) {
        for(int j = i + 1; j < 4; j++) {
            if(paths[i] && paths[j] && !strcmp(paths[i], paths[j])) {
                fprintf(stderr, "Input and output paths must be different.\n");
                return 1;
            }
        }
    }

    Image image = {0};
    Image reconstructed = {0};
    CompressionStats stats = {0};
    FILE *compressed = NULL;
    FILE *stats_file = NULL;
    int result = 1;
    clock_t started = clock();

    if(mode == 2) {
        compressed = fopen(input, "rb");
        if(!compressed || !decompress_image(compressed, &reconstructed)) {
            fprintf(stderr, "Decompression failed.\n");
            goto cleanup;
        }
        if(!save_image(output, &reconstructed)) {
            fprintf(stderr, "Cannot save reconstructed image.\n");
            goto cleanup;
        }
        printf("Reconstructed image saved to %s\n", output);
        result = 0;
        goto cleanup;
    }

    if(!load_image(input, &image) || (width && !resize_image(&image, width, height))) {
        fprintf(stderr, "Cannot load or resize input image.\n");
        goto cleanup;
    }
    compressed = mode == 1 ? fopen(output, "w+b") :
                 binary_path ? fopen(binary_path, "w+b") : tmpfile();
    if(!compressed || !compress_image(compressed, &image, block_width, block_height, &stats)) {
        fprintf(stderr, "Compression failed.\n");
        goto cleanup;
    }
    if(fseek(compressed, 0, SEEK_SET) || !decompress_image(compressed, &reconstructed)) {
        fprintf(stderr, "Verification failed during decompression.\n");
        goto cleanup;
    }
    if(image.width != reconstructed.width || image.height != reconstructed.height ||
       image.channels != reconstructed.channels ||
       memcmp(image.pixels, reconstructed.pixels,
              (size_t)image.width * (size_t)image.height * (size_t)image.channels)) {
        fprintf(stderr, "Verification failed: reconstructed pixels differ.\n");
        goto cleanup;
    }
    if(mode == 0 && !save_image(output, &reconstructed)) {
        fprintf(stderr, "Cannot save reconstructed image.\n");
        goto cleanup;
    }
    if(!print_stats(stdout, &image, &stats)) {
        goto cleanup;
    }
    if(stats_path) {
        stats_file = fopen(stats_path, "w");
        if(!stats_file || !print_stats(stats_file, &image, &stats)) {
            fprintf(stderr, "Cannot write statistics.\n");
            goto cleanup;
        }
    }
    printf("Verification successful: reconstructed %s pixels match.\n",
           image.channels == 3 ? "color" : "grayscale");
    printf("Finished in %.3f seconds.\n", (double)(clock() - started) / CLOCKS_PER_SEC);
    result = 0;

cleanup:
    if(stats_file && fclose(stats_file) != 0) {
        result = 1;
    }
    if(compressed && fclose(compressed) != 0) {
        result = 1;
    }
    free(image.pixels);
    free(reconstructed.pixels);
    return result;
}
