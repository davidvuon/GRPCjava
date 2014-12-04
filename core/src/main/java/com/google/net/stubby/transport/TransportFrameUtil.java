package com.google.net.stubby.transport;

import static com.google.common.base.Charsets.US_ASCII;

import com.google.common.io.BaseEncoding;
import com.google.net.stubby.Metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Utility functions for transport layer framing.
 *
 * <p>Within a given transport frame we reserve the first byte to indicate the type of compression
 * used for the contents of the transport frame.
 */
public final class TransportFrameUtil {

  private static final Logger logger = Logger.getLogger(TransportFrameUtil.class.getName());

  private static final byte[] binaryHeaderSuffixBytes =
      Metadata.BINARY_HEADER_SUFFIX.getBytes(US_ASCII);


  // Compression modes (lowest order 3 bits of frame flags)
  public static final byte NO_COMPRESS_FLAG = 0x0;
  public static final byte FLATE_FLAG = 0x1;
  public static final byte COMPRESSION_FLAG_MASK = 0x7;

  public static boolean isNotCompressed(int b) {
    return ((b & COMPRESSION_FLAG_MASK) == NO_COMPRESS_FLAG);
  }

  public static boolean isFlateCompressed(int b) {
    return ((b & COMPRESSION_FLAG_MASK) == FLATE_FLAG);
  }

  /**
   * Length of the compression type field.
   */
  public static final int COMPRESSION_TYPE_LENGTH = 1;

  /**
   * Length of the compression frame length field.
   */
  public static final int COMPRESSION_FRAME_LENGTH = 3;

  /**
   * Full length of the compression header.
   */
  public static final int COMPRESSION_HEADER_LENGTH =
      COMPRESSION_TYPE_LENGTH + COMPRESSION_FRAME_LENGTH;

  // Flags
  public static final byte PAYLOAD_FRAME = 0x0;
  public static final byte STATUS_FRAME = 0x3;

  // TODO(user): This needs proper namespacing support, this is currently just a hack
  /**
   * Converts the path from the HTTP request to the full qualified method name.
   *
   * @return null if the path is malformatted.
   */
  @Nullable
  public static String getFullMethodNameFromPath(String path) {
    if (!path.startsWith("/")) {
      return null;
    }
    return path;
  }

  /**
   * Transform the given headers to a format where only spec-compliant ASCII characters are allowed.
   * Binary header values are encoded by Base64 in the result.
   *
   * @return the interleaved keys and values.
   */
  public static byte[][] toHttp2Headers(Metadata headers) {
    byte[][] serializedHeaders = headers.serialize();
    ArrayList<byte[]> result = new ArrayList<byte[]>();
    for (int i = 0; i < serializedHeaders.length; i += 2) {
      byte[] key = serializedHeaders[i];
      byte[] value = serializedHeaders[i + 1];
      if (endsWith(key, binaryHeaderSuffixBytes)) {
        // Binary header.
        result.add(key);
        result.add(BaseEncoding.base64().encode(value).getBytes(US_ASCII));
      } else {
        // Non-binary header.
        // Filter out headers that contain non-spec-compliant ASCII characters.
        // TODO(user): only do such check in development mode since it's expensive
        if (isSpecCompliantAscii(value)) {
          result.add(key);
          result.add(value);
        } else {
          String keyString = new String(key, US_ASCII);
          logger.warning("Metadata key=" + keyString + ", value=" + Arrays.toString(value)
              + " contains invalid ASCII characters");
        }
      }
    }
    return result.toArray(new byte[result.size()][]);
  }

  /**
   * Transform HTTP/2-compliant headers to the raw serialized format which can be deserialized by
   * metadata marshallers. It decodes the Base64-encoded binary headers.
   */
  public static byte[][] toRawSerializedHeaders(byte[][] http2Headers) {
    byte[][] result = new byte[http2Headers.length][];
    for (int i = 0; i < http2Headers.length; i += 2) {
      byte[] key = http2Headers[i];
      byte[] value = http2Headers[i + 1];
      result[i] = key;
      if (endsWith(key, binaryHeaderSuffixBytes)) {
        // Binary header
        result[i + 1] = BaseEncoding.base64().decode(new String(value, US_ASCII));
      } else {
        // Non-binary header
        result[i + 1] = value;
      }
    }
    return result;
  }

  /**
   * Returns true if <b>subject</b> ends with <b>suffix</b>.
   */
  private static boolean endsWith(byte[] subject, byte[] suffix) {
    int start = subject.length - suffix.length;
    if (start < 0) {
      return false;
    }
    for (int i = start; i < subject.length; i++) {
      if (subject[i] != suffix[i - start]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if <b>subject</b> contains only bytes that are spec-compliant ASCII characters and
   * space.
   */
  private static boolean isSpecCompliantAscii(byte[] subject) {
    for (byte b : subject) {
      if (b < 32 || b > 126) {
        return false;
      }
    }
    return true;
  }

  private TransportFrameUtil() {}
}
