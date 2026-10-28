# Experimental Compressor

Pure C implementation of research version image-compression

Repository: https://github.com/krashkanter/experimental-compressor

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

New `.extc` files use version 3, with an explicit media type and header size.
The decoder also accepts existing version 1 and 2 grayscale and RGB images. Unknown
versions, media types, flags, and header sizes are rejected before pixel allocation.
The image payload remains the same bitplane/block encoding.

| Offset | Size | EXTC v3 field |
| --- | --- | --- |
| 0 | 4 | ASCII `EXTC` magic |
| 4 | 1 | Container version: `3` |
| 5 | 1 | Media type: `1` for an image |
| 6 | 1 | Flags: `0` |
| 7 | 1 | Header size: `32` bytes |
| 8 | 4 | Image width, little-endian |
| 12 | 4 | Image height, little-endian |
| 16 | 4 | CRC-32 of decoded pixel bytes, little-endian |
| 20 | 1 | Block height |
| 21 | 1 | Block width |
| 22 | 1 | Channels: `1` grayscale or `3` RGB |
| 23 | 1 | Reserved: `0` |
| 24 | 8 | Original encoded input file size, little-endian; `0` if unavailable |
| 32 | variable | Image block payload |

Version 2 uses the first 24 bytes above with version `2` and header size `24`; it has no original-file size. Version 1 has a 20-byte header: byte 5 is block height, byte 6 is block width,
and byte 7 is `0`/`1` for grayscale or `3` for RGB. Its dimensions and CRC occupy
the same offsets as v2. No other media types are currently implemented. New
media codecs must define a media identifier and a supported version parser;
readers must never guess an unknown format from its payload.

Payload bits are most significant first, with zero padding in the final byte.
The decoder rejects truncated blocks, invalid cube counts, nonzero end padding,
trailing data, and checksum failures. Python bit arrays require the container
header and bit packing before use with this file decoder.

## Mobile apps

`android/` contains a native Kotlin/Compose Android app with a JNI wrapper around
this same C codec. It supports Android 6.0 (API 23) and newer, with ARMv7, ARM64,
and x86-64 builds. See [Android instructions](android/README.md).

`ios/` is initialized with design and integration notes only. No iOS app or
wrapper implementation has been written or built.

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

The product is named Experimental Compressor. EXTC remains the file format and CLI name. Android application ID `com.krashkanter.extc`, MIME identifiers, and the existing signing key are retained so installed apps and saved files remain compatible.

Stats distinguish the original encoded JPG/PNG/etc. file size, decoded grayscale/RGB pixel bytes, and the EXTC container size. The original-file size is preserved in v3 and reported as unavailable for v1/v2 or raw-buffer callers that do not provide it. It is never inferred from decoded pixels.
