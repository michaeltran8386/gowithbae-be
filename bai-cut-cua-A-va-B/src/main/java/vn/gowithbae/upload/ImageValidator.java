package vn.gowithbae.upload;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ImageValidator {
    private ImageValidator() {}

    static String validate(byte[] bytes, String declared) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                String mime = switch (format.toLowerCase(java.util.Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    default -> throw new IOException();
                };
                if (!mime.equalsIgnoreCase(declared)) throw new IOException();
                if (mime.equals("image/png")) checkPng(bytes);
                else if (bytes.length < 4 || bytes[bytes.length - 2] != (byte) 0xff
                        || bytes[bytes.length - 1] != (byte) 0xd9) throw new IOException();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192
                        || (long) width * height > 16_000_000) throw new IOException();
                boolean[] warned = {false};
                reader.addIIOReadWarningListener((source, warning) -> warned[0] = true);
                if (reader.read(0) == null || warned[0]) throw new IOException();
                return mime;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A complete PNG or JPEG matching its MIME type is required");
        }
    }

    // ImageIO tolerates missing PNG trailers and CRC errors; require complete checked chunks too.
    private static void checkPng(byte[] bytes) throws IOException {
        int offset = 8;
        while (offset <= bytes.length - 12) {
            int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
            if (length < 0 || length > bytes.length - offset - 12) throw new IOException();
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + 4, length + 4);
            if ((int) crc.getValue() != ByteBuffer.wrap(bytes, offset + 8 + length, 4).getInt()) throw new IOException();
            boolean end = Arrays.equals(Arrays.copyOfRange(bytes, offset + 4, offset + 8), new byte[]{73, 69, 78, 68});
            offset += length + 12;
            if (end) {
                if (length != 0 || offset != bytes.length) throw new IOException();
                return;
            }
        }
        throw new IOException();
    }
}
