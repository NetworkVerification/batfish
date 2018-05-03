package org.batfish.datamodel.answers;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import org.batfish.datamodel.Ip;

/**
 * Return the output of {@link org.batfish.common.util.CommonUtil#computeIpOwners(Map, boolean)} as
 * an answer
 */
public class IpOwnersAnswerElement extends AnswerElement {

  private final SortedMap<Ip, SortedSet<String>> _ipOwners;

  /** Create new answer element from given ip owners. */
  public IpOwnersAnswerElement(Map<Ip, Set<String>> ipOwners) {
    // Sort Ips and the set of hostnames
    _ipOwners =
        ipOwners
            .entrySet()
            .stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ip::compareTo,
                    Entry::getKey,
                    e ->
                        e.getValue()
                            .stream()
                            .collect(ImmutableSortedSet.toImmutableSortedSet(String::compareTo))));
  }

  /** Return sorted Ip owners */
  public SortedMap<Ip, SortedSet<String>> getIpOwners() {
    return _ipOwners;
  }

  @Override
  public String prettyPrint() {
    StringBuilder sb = new StringBuilder();
    for (Entry<Ip, SortedSet<String>> e : _ipOwners.entrySet()) {
      sb.append(e.getKey().toString());
      sb.append(": ");
      sb.append(String.join(",", e.getValue()));
      sb.append("\n");
    }
    return sb.toString();
  }
}
