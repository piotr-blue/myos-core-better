package myos.better;

import java.net.InetAddress;

/** Public destination policy shared by callback and provider transports. */
final class PublicAddress {
  private PublicAddress() {}

  static boolean allowed(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) return false;
    byte[] bytes = address.getAddress();
    int first = bytes[0] & 255, second = bytes[1] & 255;
    if (bytes.length == 16) return (first & 0xe0) == 0x20;
    return first != 0
        && first != 10
        && first != 127
        && first < 224
        && !(first == 100 && second >= 64 && second <= 127)
        && !(first == 169 && second == 254)
        && !(first == 172 && second >= 16 && second <= 31)
        && !(first == 192 && (second == 0 || second == 168))
        && !(first == 198 && (second == 18 || second == 19 || second == 51))
        && !(first == 203 && second == 0);
  }
}
