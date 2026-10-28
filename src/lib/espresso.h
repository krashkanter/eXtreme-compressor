#ifndef ESPRESSO_H
#define ESPRESSO_H

#include <stdint.h>
#include <string.h>

typedef struct {
    unsigned int value;
    unsigned int mask;
    uint32_t points;
} Cube;

typedef struct {
    Cube cubes[32];
    int count;
    int symbol;
    int cost;
    int onset;
} Cover;

static Cube cube_table[243];
static int cube_table_size;

static int count_bits(uint32_t value) {
    int count = 0;
    while(value) {
        value &= value - 1;
        count++;
    }
    return count;
}

static void prepare_cubes(void) {
    if(cube_table_size) {
        return;
    }
    for(unsigned int mask = 0; mask < 32; mask++) {
        for(unsigned int value = 0; value < 32; value++) {
            if(value & ~mask) {
                continue;
            }
            Cube cube = {value, mask, 0};
            for(unsigned int point = 0; point < 32; point++) {
                if((point & mask) == value) {
                    cube.points |= UINT32_C(1) << point;
                }
            }
            cube_table[cube_table_size++] = cube;
        }
    }
}

static Cover minimize_set(uint32_t points) {
    Cube primes[243];
    int prime_count = 0;
    Cover cover = {0};
    prepare_cubes();

    for(int i = 0; i < cube_table_size; i++) {
        Cube cube = cube_table[i];
        if(cube.points & ~points) {
            continue;
        }
        int prime = 1;
        for(unsigned int bit = 1; bit < 32; bit <<= 1) {
            if(!(cube.mask & bit)) {
                continue;
            }
            uint32_t expanded = cube.points;
            expanded |= (cube.value & bit) ? cube.points >> bit : cube.points << bit;
            if(!(expanded & ~points)) {
                prime = 0;
                break;
            }
        }
        if(prime) {
            primes[prime_count++] = cube;
        }
    }

    uint32_t remaining = points;
    while(remaining) {
        int best = -1;
        int best_count = 0;
        for(unsigned int point = 0; point < 32; point++) {
            uint32_t bit = UINT32_C(1) << point;
            if(!(remaining & bit)) {
                continue;
            }
            int only = -1;
            int choices = 0;
            for(int i = 0; i < prime_count; i++) {
                if(primes[i].points & bit) {
                    only = i;
                    choices++;
                }
            }
            if(choices == 1) {
                best = only;
                break;
            }
        }
        if(best < 0) {
            for(int i = 0; i < prime_count; i++) {
                int count = count_bits(primes[i].points & remaining);
                if(count > best_count || (count == best_count && best >= 0 &&
                   count_bits(primes[i].mask) < count_bits(primes[best].mask))) {
                    best = i;
                    best_count = count;
                }
            }
        }
        if(best < 0) {
            break;
        }
        cover.cubes[cover.count++] = primes[best];
        remaining &= ~primes[best].points;
    }

    for(int i = cover.count - 1; i >= 0; i--) {
        uint32_t other = 0;
        for(int j = 0; j < cover.count; j++) {
            if(i != j) {
                other |= cover.cubes[j].points;
            }
        }
        if(other == points) {
            memmove(&cover.cubes[i], &cover.cubes[i + 1],
                    (size_t)(cover.count - i - 1) * sizeof(Cube));
            cover.count--;
        }
    }

    int frequencies[3] = {0};
    for(int i = 0; i < cover.count; i++) {
        for(unsigned int bit = 16; bit; bit >>= 1) {
            int symbol = !(cover.cubes[i].mask & bit) ? 2 :
                         (cover.cubes[i].value & bit) != 0;
            frequencies[symbol]++;
        }
    }
    cover.symbol = 0;
    for(int i = 1; i < 3; i++) {
        if(frequencies[i] > frequencies[cover.symbol]) {
            cover.symbol = i;
        }
    }
    cover.cost = cover.count * 10 - frequencies[cover.symbol];
    return cover;
}

static Cover minimize_block(const unsigned char block[32], int width) {
    uint32_t points = 0;
    for(int i = 0; i < 32; i++) {
        int row = i / width;
        int col = i % width;
        int pos = row * width + (row % 2 ? width - col - 1 : col);
        if(block[pos]) {
            unsigned int gray = (unsigned int)(i ^ (i >> 1));
            points |= UINT32_C(1) << gray;
        }
    }
    Cover onset = minimize_set(points);
    Cover offset = minimize_set(~points);
    onset.onset = 1;
    offset.onset = 0;
    return onset.cost <= offset.cost ? onset : offset;
}

#endif
