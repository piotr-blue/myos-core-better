package myos.better;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.fileupload2.core.AbstractFileUpload;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItemInputIterator;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.core.FileUploadSizeException;
import org.apache.commons.fileupload2.core.RequestContext;

/** HTTP exchange adapter only; Commons FileUpload owns multipart framing and limits. */
final class AvatarUpload {
  record Input(byte[] file, long expectedRevision) {}

  static Input read(HttpExchange exchange) throws IOException, InterruptedException {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    check(
        type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data;"),
        415,
        "unsupported_media_type",
        "Use multipart/form-data");
    HttpCancellation.SHARED.checkHealth();
    var upload = new Parser();
    upload.setMaxSize(AvatarImages.MAX_INPUT + 16 * 1024);
    upload.setMaxFileSize(AvatarImages.MAX_INPUT);
    upload.setMaxFileCount(2);
    upload.setMaxPartHeaderSize(4096);
    try (var deadline = new HttpDeadline(exchange, java.time.Duration.ofSeconds(15))) {
      deadline.attachThread();
      var iterator = upload.getItemIterator(exchange);
      byte[] file = null;
      Long revision = null;
      int count = 0;
      while (iterator.hasNext()) {
        var item = iterator.next();
        check(++count <= 2, 422, "invalid_avatar_input", "Provide only file and expectedRevision");
        try (var input = item.getInputStream()) {
          if ("file".equals(item.getFieldName())) {
            check(
                file == null && !item.isFormField(),
                422,
                "invalid_avatar_input",
                "Provide one file");
            file = input.readNBytes(AvatarImages.MAX_INPUT + 1);
            check(
                file.length <= AvatarImages.MAX_INPUT, 413, "avatar_size", "Avatar exceeds 5 MiB");
          } else if ("expectedRevision".equals(item.getFieldName())) {
            check(
                revision == null && item.isFormField(),
                422,
                "invalid_avatar_input",
                "Provide one expectedRevision");
            byte[] value = input.readNBytes(32);
            check(
                value.length > 0 && value.length < 32,
                422,
                "invalid_profile_revision",
                "Provide a nonnegative integer expectedRevision");
            String number = new String(value, StandardCharsets.UTF_8);
            check(
                number.matches("0|[1-9][0-9]{0,18}"),
                422,
                "invalid_profile_revision",
                "Provide a nonnegative integer expectedRevision");
            try {
              revision = Long.parseLong(number);
            } catch (NumberFormatException invalid) {
              throw new ConsoleProblem(422, "invalid_profile_revision", "Revision is too large");
            }
          } else {
            throw new ConsoleProblem(
                422, "invalid_avatar_input", "Provide only file and expectedRevision");
          }
        }
      }
      check(
          file != null && revision != null,
          422,
          "invalid_avatar_input",
          "Provide file and expectedRevision");
      return new Input(file, revision);
    } catch (FileUploadSizeException limit) {
      throw new ConsoleProblem(413, "avatar_size", "Avatar upload exceeds its limit");
    } catch (FileUploadException invalid) {
      throw new ConsoleProblem(422, "invalid_avatar_input", "Invalid multipart avatar upload");
    }
  }

  private static final class Parser
      extends AbstractFileUpload<HttpExchange, DiskFileItem, DiskFileItemFactory> {
    private RequestContext context(HttpExchange exchange) {
      return new RequestContext() {
        public String getCharacterEncoding() {
          return "UTF-8";
        }

        public long getContentLength() {
          String length = exchange.getRequestHeaders().getFirst("Content-Length");
          try {
            return length == null ? -1 : Long.parseLong(length);
          } catch (NumberFormatException invalid) {
            return -1;
          }
        }

        public String getContentType() {
          return exchange.getRequestHeaders().getFirst("Content-Type");
        }

        public InputStream getInputStream() {
          return exchange.getRequestBody();
        }

        public boolean isMultipartRelated() {
          return false;
        }
      };
    }

    @Override
    public FileItemInputIterator getItemIterator(HttpExchange request) throws IOException {
      return super.getItemIterator(context(request));
    }

    @Override
    public List<DiskFileItem> parseRequest(HttpExchange request) throws FileUploadException {
      return super.parseRequest(context(request));
    }

    @Override
    public Map<String, List<DiskFileItem>> parseParameterMap(HttpExchange request)
        throws FileUploadException {
      return super.parseParameterMap(context(request));
    }
  }

  private static void check(boolean condition, int status, String code, String message) {
    if (!condition) {
      throw new ConsoleProblem(status, code, message);
    }
  }
}
