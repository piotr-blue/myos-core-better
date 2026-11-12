package myos.better;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Bounded Core request envelopes; authored numeric values must survive YAML and JSON unchanged. */
final class CoreRequestBody {
  private static final ObjectMapper JSON =
      Json.MAPPER.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private static final Pattern NUMBER =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
  private static final Set<Tag> TAGS =
      Set.of(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.NULL, Tag.TIMESTAMP, Tag.MAP, Tag.SEQ);

  private CoreRequestBody() {}

  static Map<String, Object> read(byte[] bytes, boolean yaml) {
    if (bytes.length == 0) return Map.of();
    return RuntimeConfiguration.object(parse(bytes, yaml), "request");
  }

  static Object parse(byte[] bytes, boolean yaml) {
    try {
      Object result;
      if (yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(100);
        options.setNestingDepthLimit(128);
        options.setCodePointLimit(1024 * 1024);
        var documents =
            new Yaml(options)
                .composeAll(
                    new StringReader(
                        StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString()))
                .iterator();
        if (!documents.hasNext())
          throw new IllegalArgumentException("A request object is required");
        result =
            value(
                documents.next(),
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new int[] {0},
                0);
        if (documents.hasNext())
          throw new IllegalArgumentException("A single YAML request is required");
      } else result = JSON.readValue(bytes, Object.class);
      return result;
    } catch (java.io.IOException | org.yaml.snakeyaml.error.YAMLException failure) {
      throw new IllegalArgumentException("Invalid request syntax", failure);
    }
  }

  // Scalar interpretation matches Blue authoring, rather than YAML 1.1 dates/yes/octal coercion.
  private static Object value(Node node, Set<Node> active, int[] count, int depth) {
    if (++count[0] > 100_000 || depth > 128)
      throw new IllegalArgumentException("Request exceeds structural limits");
    if (!TAGS.contains(node.getTag())) throw new IllegalArgumentException("Unsupported YAML tag");
    if (!active.add(node))
      throw new IllegalArgumentException("Recursive YAML aliases are not supported");
    try {
      if (node instanceof ScalarNode scalar) {
        String text = scalar.getValue();
        if (!scalar.isPlain()) return text;
        if (text.isEmpty() || text.equals("null")) return null;
        if (text.equals("true")) return true;
        if (text.equals("false")) return false;
        if (!NUMBER.matcher(text).matches()) return text;
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0)
          return new BigInteger(text);
        return new BigDecimal(text);
      }
      if (node instanceof SequenceNode sequence) {
        List<Object> values = new ArrayList<>();
        for (Node child : sequence.getValue()) values.add(value(child, active, count, depth + 1));
        return values;
      }
      if (!(node instanceof MappingNode mapping))
        throw new IllegalArgumentException("Unsupported YAML node");
      Map<String, Object> result = new LinkedHashMap<>();
      for (NodeTuple field : mapping.getValue()) {
        Object key = value(field.getKeyNode(), active, count, depth + 1);
        if (!(key instanceof String name) || name.equals("<<"))
          throw new IllegalArgumentException("Request keys must be literal text");
        if (result.containsKey(name))
          throw new IllegalArgumentException("Duplicate request field: " + name);
        result.put(name, value(field.getValueNode(), active, count, depth + 1));
      }
      return result;
    } finally {
      active.remove(node);
    }
  }
}
