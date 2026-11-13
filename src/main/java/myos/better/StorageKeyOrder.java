package myos.better;

/** Unicode scalar order for canonical graph names and DynamoDB UTF-8 string ranges. */
final class StorageKeyOrder {
  private StorageKeyOrder() {}

  static int compare(String left, String right) {
    int a = 0;
    int b = 0;
    while (a < left.length() && b < right.length()) {
      int first = left.codePointAt(a);
      int second = right.codePointAt(b);
      if (first != second) {
        return Integer.compare(first, second);
      }
      a += Character.charCount(first);
      b += Character.charCount(second);
    }
    return Integer.compare(left.length() - a, right.length() - b);
  }
}
