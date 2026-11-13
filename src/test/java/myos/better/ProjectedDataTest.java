package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import blue.Bex;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectedDataTest {
  @Test
  void absenceNullEmptyObjectsAndEscapedPropertyNamesHaveDistinctPortableRepresentations() {
    Object source =
        Map.of(
            "a/b~c",
            Arrays.asList(
                null,
                Bex.undefined(),
                Map.of(),
                Map.of("missing", Bex.undefined()),
                BigInteger.TEN));
    ProjectedData encoded = ProjectedData.encode(source);
    assertEquals(2, encoded.undefinedPaths().size());
    Object transported = Json.read(Json.bytes(encoded.data()), Object.class);
    Map<?, ?> restored = (Map<?, ?>) ProjectedData.decode(transported, encoded.undefinedPaths());
    List<?> items = (List<?>) restored.get("a/b~c");
    assertNull(items.get(0));
    assertSame(Bex.undefined(), items.get(1));
    assertEquals(Map.of(), items.get(2));
    assertSame(Bex.undefined(), ((Map<?, ?>) items.get(3)).get("missing"));
    assertEquals(BigInteger.TEN, items.get(4));
    assertNull(((List<?>) ((Map<?, ?>) transported).get("a/b~c")).get(1));
    assertNull(ProjectedData.encode(Bex.undefined()).data());
    assertSame(Bex.undefined(), ProjectedData.decode(null, List.of("")));
  }
}
