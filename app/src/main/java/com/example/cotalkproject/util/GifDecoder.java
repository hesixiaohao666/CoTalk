package com.example.cotalkproject.util;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java GIF 解码：LZW、调色板、透明色、帧处置与帧延迟。
 * 不依赖 Movie/ImageDecoder，供压缩与播放共用。
 */
public final class GifDecoder {

    private static final int MAX_CODES = 4096;

    public static final class GifFrame {
        public final int[] pixels;
        public final int delayCs;

        GifFrame(int[] pixels, int delayCs) {
            this.pixels = pixels;
            this.delayCs = delayCs;
        }
    }

    public static final class GifImage {
        public final int width;
        public final int height;
        public final List<GifFrame> frames;

        GifImage(int width, int height, List<GifFrame> frames) {
            this.width = width;
            this.height = height;
            this.frames = frames;
        }
    }

    private GifDecoder() {
    }

    public static GifImage decode(byte[] data, int maxFrames) {
        if (data == null || data.length < 13
                || data[0] != 'G' || data[1] != 'I' || data[2] != 'F') {
            return null;
        }
        int width = le16(data, 6);
        int height = le16(data, 8);
        if (width <= 0 || height <= 0) {
            return null;
        }
        int packed = data[10] & 0xFF;
        int pos = 13;
        int[] globalPalette = null;
        if ((packed & 0x80) != 0) {
            int size = 1 << ((packed & 7) + 1);
            globalPalette = readPalette(data, pos, size);
            pos += size * 3;
        }
        int[] canvas = new int[width * height];
        List<GifFrame> frames = new ArrayList<>();
        int limit = Math.max(1, maxFrames);

        int disposal = 0;
        int delayCs = 0;
        int transparent = -1;
        try {
            while (pos < data.length && frames.size() < limit) {
                int marker = data[pos++] & 0xFF;
                if (marker == 0x3B) {
                    break;
                }
                if (marker == 0x21) {
                    if (pos >= data.length) {
                        break;
                    }
                    int label = data[pos++] & 0xFF;
                    if (label == 0xF9 && pos < data.length) {
                        int blockSize = data[pos++] & 0xFF;
                        if (blockSize >= 4 && pos + blockSize <= data.length) {
                            int gce = data[pos] & 0xFF;
                            disposal = (gce >> 2) & 7;
                            delayCs = (data[pos + 1] & 0xFF)
                                    | ((data[pos + 2] & 0xFF) << 8);
                            transparent = (gce & 1) != 0
                                    ? data[pos + 3] & 0xFF : -1;
                        }
                        pos += blockSize;
                        if (pos < data.length && (data[pos] & 0xFF) == 0) {
                            pos++;
                        }
                    } else {
                        pos = skipSubBlocks(data, pos);
                        if (pos < 0) {
                            break;
                        }
                    }
                    continue;
                }
                if (marker != 0x2C || pos + 9 > data.length) {
                    break;
                }
                int left = le16(data, pos);
                int top = le16(data, pos + 2);
                int frameWidth = le16(data, pos + 4);
                int frameHeight = le16(data, pos + 6);
                int imagePacked = data[pos + 8] & 0xFF;
                pos += 9;
                int[] palette = globalPalette;
                if ((imagePacked & 0x80) != 0) {
                    int size = 1 << ((imagePacked & 7) + 1);
                    palette = readPalette(data, pos, size);
                    pos += size * 3;
                }
                if (palette == null || pos >= data.length) {
                    break;
                }
                int minCodeSize = data[pos++] & 0xFF;
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                pos = readSubBlocks(data, pos, compressed);
                if (pos < 0) {
                    break;
                }
                int[] indices = lzwDecode(compressed.toByteArray(),
                        minCodeSize, frameWidth * frameHeight);
                if (indices == null) {
                    break;
                }
                int[] saved = disposal == 3 ? canvas.clone() : null;
                drawFrame(canvas, width, height, indices,
                        frameWidth, frameHeight, left, top,
                        (imagePacked & 0x40) != 0, palette, transparent);
                frames.add(new GifFrame(canvas.clone(),
                        delayCs <= 0 ? 10 : delayCs));
                if (disposal == 2) {
                    clearRect(canvas, width, height,
                            left, top, frameWidth, frameHeight);
                } else if (disposal == 3 && saved != null) {
                    canvas = saved;
                }
                disposal = 0;
                delayCs = 0;
                transparent = -1;
            }
        } catch (RuntimeException e) {
            if (frames.isEmpty()) {
                return null;
            }
        }
        return frames.isEmpty() ? null : new GifImage(width, height, frames);
    }

    private static int[] readPalette(byte[] data, int pos, int size) {
        if (pos < 0 || pos + size * 3 > data.length) {
            return null;
        }
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            int offset = pos + i * 3;
            palette[i] = 0xFF000000
                    | ((data[offset] & 0xFF) << 16)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | (data[offset + 2] & 0xFF);
        }
        return palette;
    }

    private static void drawFrame(int[] canvas, int canvasWidth,
                                  int canvasHeight, int[] indices,
                                  int frameWidth, int frameHeight,
                                  int left, int top, boolean interlaced,
                                  int[] palette, int transparent) {
        int source = 0;
        for (int band = 0; band < (interlaced ? 4 : 1); band++) {
            int start = interlaced
                    ? new int[]{0, 4, 2, 1}[band] : 0;
            int step = interlaced
                    ? new int[]{8, 8, 4, 2}[band] : 1;
            for (int y = start; y < frameHeight; y += step) {
                int canvasY = top + y;
                if (canvasY < 0 || canvasY >= canvasHeight) {
                    source += frameWidth;
                    continue;
                }
                for (int x = 0; x < frameWidth; x++) {
                    int index = source < indices.length
                            ? indices[source] : 0;
                    source++;
                    int canvasX = left + x;
                    if (canvasX < 0 || canvasX >= canvasWidth
                            || index == transparent) {
                        continue;
                    }
                    int color = index < palette.length
                            ? palette[index] : 0;
                    if ((color >>> 24) != 0) {
                        canvas[canvasY * canvasWidth + canvasX] = color;
                    }
                }
            }
        }
    }

    private static void clearRect(int[] canvas, int canvasWidth,
                                  int canvasHeight, int left, int top,
                                  int width, int height) {
        for (int y = Math.max(0, top);
             y < Math.min(canvasHeight, top + height); y++) {
            for (int x = Math.max(0, left);
                 x < Math.min(canvasWidth, left + width); x++) {
                canvas[y * canvasWidth + x] = 0;
            }
        }
    }

    private static int[] lzwDecode(byte[] data, int minCodeSize,
                                   int pixelCount) {
        if (minCodeSize < 1 || minCodeSize > 8 || pixelCount < 0) {
            return null;
        }
        int clear = 1 << minCodeSize;
        int end = clear + 1;
        int codeSize = minCodeSize + 1;
        int next = end + 1;
        int[] prefix = new int[MAX_CODES];
        int[] suffix = new int[MAX_CODES];
        int[] stack = new int[MAX_CODES];
        for (int i = 0; i < clear; i++) {
            prefix[i] = -1;
            suffix[i] = i;
        }
        int[] output = new int[pixelCount];
        int written = 0;
        int datum = 0;
        int bits = 0;
        int offset = 0;
        int old = -1;
        int first = 0;
        while (written < pixelCount) {
            while (bits < codeSize) {
                if (offset >= data.length) {
                    return written == pixelCount ? output : null;
                }
                datum |= (data[offset++] & 0xFF) << bits;
                bits += 8;
            }
            int code = datum & ((1 << codeSize) - 1);
            datum >>>= codeSize;
            bits -= codeSize;
            if (code == clear) {
                codeSize = minCodeSize + 1;
                next = end + 1;
                old = -1;
                continue;
            }
            if (code == end) {
                break;
            }
            if (old == -1) {
                if (code >= clear) {
                    return null;
                }
                output[written++] = suffix[code];
                first = suffix[code];
                old = code;
                continue;
            }
            int inCode = code;
            int stackSize = 0;
            if (code >= next) {
                stack[stackSize++] = first;
                code = old;
            }
            int guard = 0;
            while (code >= clear) {
                if (code >= next || guard++ > MAX_CODES) {
                    return null;
                }
                stack[stackSize++] = suffix[code];
                code = prefix[code];
            }
            first = suffix[code];
            stack[stackSize++] = first;
            while (stackSize > 0 && written < pixelCount) {
                output[written++] = stack[--stackSize];
            }
            if (next < MAX_CODES) {
                prefix[next] = old;
                suffix[next] = first;
                next++;
                if (next == (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
            }
            old = inCode;
        }
        return written == pixelCount ? output : null;
    }

    private static int readSubBlocks(byte[] data, int pos,
                                     ByteArrayOutputStream out) {
        while (pos >= 0 && pos < data.length) {
            int size = data[pos++] & 0xFF;
            if (size == 0) {
                return pos;
            }
            if (pos + size > data.length) {
                return -1;
            }
            out.write(data, pos, size);
            pos += size;
        }
        return -1;
    }

    private static int skipSubBlocks(byte[] data, int pos) {
        while (pos >= 0 && pos < data.length) {
            int size = data[pos++] & 0xFF;
            if (size == 0) {
                return pos;
            }
            pos += size;
            if (pos > data.length) {
                return -1;
            }
        }
        return -1;
    }

    private static int le16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
