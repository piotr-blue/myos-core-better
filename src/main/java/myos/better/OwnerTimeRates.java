package myos.better;

/** Durable host fairness limits; never changes a timer's logical timestamp or gas budget. */
final class OwnerTimeRates {
  private record Bucket(long tokens, long at) {}

  private static final long TOKEN = 1000;

  private OwnerTimeRates() {}

  /** Zero reserves one attempt; otherwise returns the earliest retry in epoch milliseconds. */
  static long reserve(OwnerMetadata.Transaction tx, String member, long now) {
    String accountKey = "time-rate\0account";
    String memberKey = "time-rate\0member\0" + member;
    Bucket account = refill(tx.get(accountKey), now, 100, 100);
    Bucket document = refill(tx.get(memberKey), now, 10, 16);
    long wait = Math.max(delay(account, 100, now), delay(document, 10, now));
    if (wait == 0) {
      account = new Bucket(account.tokens() - TOKEN, account.at());
      document = new Bucket(document.tokens() - TOKEN, document.at());
    }
    tx.put(accountKey, JsonText.of(account));
    tx.put(memberKey, JsonText.of(document));
    return wait;
  }

  private static Bucket refill(String stored, long now, long rate, long burst) {
    long capacity = burst * TOKEN;
    if (stored == null) return new Bucket(capacity, now);
    Bucket before =
        Json.MAPPER.convertValue(
            Json.object(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Bucket.class);
    long at = Math.max(before.at(), now), elapsed = at - before.at();
    long tokens =
        elapsed >= capacity / rate
            ? capacity
            : Math.min(capacity, before.tokens() + elapsed * rate);
    return new Bucket(tokens, at);
  }

  private static long delay(Bucket bucket, long rate, long now) {
    if (bucket.tokens() >= TOKEN) return 0;
    long millis = (TOKEN - bucket.tokens() + rate - 1) / rate;
    return Math.addExact(Math.max(now, bucket.at()), millis);
  }
}
