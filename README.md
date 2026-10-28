# eXtreme-compressor

Pure C implementation of research version image-compression

Standalone C11 port of `image-compression`'s newest branch by commit date,
`fixed-8x4`, at `85cfc646fc47e18bd68f59013df4da7d582398e9`.
The default-branch experiments with 8x8 blocks, BDDs, predictive XOR,
and quadtrees are not part of this branch's algorithm.

The executable needs only the C standard library. There is no Python, OpenCV,
PyEDA, Espresso executable, zlib installation, or runtime download. The image
decoder is bundled directly in `src/lib/file-handler.h`. Build tools are needed
only to compile the program.

## Build

Using GCC directly:

```sh
gcc -std=c11 -O2 src/main.c -o extc
```

Using CMake:

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```

For MinGW on Windows, add `-G "MinGW Makefiles"` to the configure command.
With a Bash shell, `bash build.sh -G "MinGW Makefiles"` also configures and builds.
The commands below use `extc`; use `build/extc.exe` for a MinGW CMake build.

## Usage

Compress, reconstruct, and verify with the reference CLI's image options:

```sh
extc -i input.png -o reconstructed.png -b 4x8 -s 256x256
```

Save the compressed file and statistics as well:

```sh
extc -i input.jpeg -o reconstructed.png --dump-bin image.extc --dump-stats stats.txt
```

Compress and decompress in separate runs:

```sh
extc compress input.png image.extc -b 4x8
extc decompress image.extc reconstructed.png
```

Input formats are PNG, JPEG, BMP, and binary PGM/PPM (grayscale or full 24-bit RGB color).
Output is an 8-bit grayscale or 24-bit RGB PNG or binary PGM/PPM. PNG output uses stored
DEFLATE blocks, prioritizing a small writer over PNG file size. Compression measurements
concern the `.extc` file, not the reconstructed PNG.

Block shapes use `heightxwidth`, defaulting to `4x8`. A block must contain exactly
32 pixels, matching the reference decoder's five Boolean variables. `8x4`,
`2x16`, `16x2`, `1x32`, and `32x1` are also supported. An optional `--shape`
resizes the image with bilinear interpolation across all channels; without it the
input dimensions are preserved. Partial edge blocks are zero-padded and cropped on
reconstruction. Images are limited to 32768 pixels per axis and 256 Mi pixels.

Every compression run decodes its result and compares all pixels before reporting
success. Lossless reconstruction applies after optional resizing.

## Encoding

The port keeps the reference's row-wise zigzag traversal, Gray-code positions,
ON/OFF selection, frequency-based symbol codes, and block tags: `00` for black,
`01` for white, `10` for a Boolean cover, and `11` for raw pixels. Covers with five
or more cubes fall back to raw pixels, as in the reference, even when a smaller cube
count is not a net saving in bits.

For grayscale images, 8 bitplanes are encoded. For 24-bit RGB color images, 24
bitplanes (8 per color channel: Red, Green, Blue) are encoded sequentially.

`espresso.h` implements a small replacement for Espresso: enumerate the 243
possible five-variable cubes, identify prime implicants, select essential
implicants, greedily cover remaining points, and remove redundant cubes. Both
ON and OFF sets are tried. This preserves the Boolean function but is not the
full Espresso heuristic; cube choices, compressed sizes, and statistics can
differ. The block payload can be decoded by the reference Python decoder.

An `.extc` file adds a 20-byte header before that payload: `EXTC`, version byte
`1`, block height and width bytes, channel count byte (`0` or `1` for grayscale, `3`
for RGB color), then little-endian 32-bit image width, image height, and CRC-32 of
the pixels. Payload bits are most significant first, with zero padding in the final
byte. The decoder rejects invalid headers, truncated blocks, invalid cube counts,
nonzero end padding, trailing data, and checksum failures.

## Source layout

The original file structure is retained. `main.c` handles arguments and the
compression/verification flow, `codec.h` handles bitstreams and statistics,
`espresso.h` handles minimization, and `quadtree.h` holds block traversal helpers
under its existing filename. This reference branch uses fixed blocks rather
than a quadtree.

`file-handler.h` contains image handling, a small PNG writer (supporting grayscale
and truecolor RGB), and an embedded copy of stb_image v2.30 from commit
`003501f6d7a8668ce8b6d875e34dd97ad0d5d5b5`. The bundled reader accounts for most
of that header's size. Its comments were removed to match the requested code
style; its public-domain dedication is preserved in `LICENSE`. Only PNG, JPEG,
BMP, and PNM decoding are enabled; SIMD and floating-point image modes are
disabled.
