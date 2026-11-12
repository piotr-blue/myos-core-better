package myos.better;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cold request admission over a protected publication, with varying immutable history depth. */
public final class PublishedAdmissionProbe {
  private PublishedAdmissionProbe() {}

  public static void main(String[] args) {
    var results = new ArrayList<Object>();
    Language base = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    Contracts contracts = Contracts.newBuilder().language(base).build();
    for (int length : List.of(8, 64, 128)) {
      Language issuer = base;
      LanguageDocument history = issuer.readYaml("sequence: 0");
      for (int i = 1; i < length; i++) {
        issuer = issuer.toBuilder().content(history).build();
        history =
            issuer.readYaml("sequence: " + i + "\nprevious: {blueId: '" + history.blueId() + "'}");
      }
      issuer = issuer.toBuilder().content(history).build();
      LanguageDocument source =
          issuer.readYaml("history: {blueId: '" + history.blueId() + "'}\ncount: 0");
      var store = new MeasuredStore(new MemoryStore());
      String page, member;
      BlueId target;
      try (var session = Contracts.newBuilder().language(issuer).build().open(source)) {
        session.initialize().requireApplied();
        member = session.rootMember();
        target = session.member(member).blueId();
        page =
            new CanonicalSnapshots(store).save("probe", null, session.snapshot(), session::member);
      }
      var request =
          Map.of(
              "type",
              "Contracts/Operation Request",
              "document",
              ProviderSigning.ref(target),
              "channel",
              "owner",
              "operation",
              "increment",
              "requireExactDocumentVersion",
              true,
              "request",
              Map.of("delta", 1));
      var samples = new ArrayList<Object>();
      BlueId messageId = null;
      Contracts.OperationRequest expected = null;
      for (int i = 0; i < 5; i++) {
        var pair = new LinkedHashMap<String, Object>();
        for (String mode :
            i % 2 == 0 ? List.of("ordinary", "checkpoint") : List.of("checkpoint", "ordinary")) {
          var pages = new CanonicalSnapshots(store);
          var contents = new CoreContents(store, base, PublicPackages.EMPTY);
          long reads = store.statistics().get("content.calls");
          long started = System.nanoTime();
          LanguageDocument[] evidence =
              mode.equals("checkpoint")
                  ? new LanguageDocument[] {pages.reviewedMember("probe", page, member, base)}
                  : new LanguageDocument[0];
          LanguageContent prepared =
              contents.prepare(
                  "probe",
                  request,
                  "source",
                  "definition",
                  pages.storedRecords("probe", page),
                  evidence);
          var parsed = contracts.operationRequest(prepared, evidence).orElseThrow();
          double millis = (System.nanoTime() - started) / 1e6;
          if (messageId != null
              && (!messageId.equals(prepared.blueId()) || !expected.equals(parsed))) {
            throw new AssertionError("Admission identity or operation grammar changed");
          }
          messageId = prepared.blueId();
          expected = parsed;
          pair.put(
              mode,
              Map.of(
                  "millis",
                  millis,
                  "contentCalls",
                  store.statistics().get("content.calls") - reads));
        }
        pair.put("warmup", i < 2);
        samples.add(pair);
      }
      results.add(
          Map.of(
              "historyLength",
              length,
              "messageId",
              messageId.value(),
              "targetId",
              target.value(),
              "samples",
              samples));
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "local MemoryStore; cold request admission and recognition only; no publication,"
                    + " execution, HTTP or AWS",
                "results",
                results)));
  }
}
