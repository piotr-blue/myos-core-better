package myos.better;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.coobird.thumbnailator.Thumbnails;

/**
 * Standard ImageIO decoding and Thumbnailator orientation/resize; output carries no input metadata.
 */
final class AvatarImages {
  static final int MAX_INPUT = 5 * 1024 * 1024;
  static final int MAX_OUTPUT = 2 * 1024 * 1024;
  static final long MAX_PIXELS = 16_000_000;

  private AvatarImages() {}

  static byte[] normalize(byte[] input) {
    check(
        input.length > 0 && input.length <= MAX_INPUT,
        413,
        "avatar_size",
        "Avatar must be between 1 byte and 5 MiB");
    try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
      var readers = ImageIO.getImageReaders(stream);
      check(readers.hasNext(), 422, "avatar_format", "Use a valid JPEG or PNG image");
      var reader = readers.next();
      try {
        check(
            Set.of("JPEG", "PNG").contains(reader.getFormatName().toUpperCase(Locale.ROOT)),
            422,
            "avatar_format",
            "Use JPEG or PNG");
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        check(
            width > 0 && height > 0 && (long) width * height <= MAX_PIXELS,
            413,
            "avatar_pixels",
            "Avatar must not exceed 16 million pixels");
      } finally {
        reader.dispose();
      }
      var output = new ByteArrayOutputStream();
      Thumbnails.of(new ByteArrayInputStream(input))
          .size(512, 512)
          .useExifOrientation(true)
          .outputFormat("png")
          .toOutputStream(output);
      byte[] normalized = output.toByteArray();
      check(normalized.length <= MAX_OUTPUT, 413, "avatar_size", "Normalized avatar is too large");
      return normalized;
    } catch (IOException | IllegalArgumentException invalid) {
      throw new ConsoleProblem(422, "avatar_format", "Use a valid JPEG or PNG image");
    }
  }

  private static void check(boolean condition, int status, String code, String message) {
    if (!condition) {
      throw new ConsoleProblem(status, code, message);
    }
  }
}
