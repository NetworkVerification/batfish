package org.batfish.role;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nonnull;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.Edge;
import org.batfish.datamodel.RoleEdge;
import org.batfish.datamodel.Topology;
import org.batfish.role.NodeRoleDimension.Type;

public final class InferRoles {
  private static class PreTokenizedString {
    @Nonnull private final String _string;
    @Nonnull private final PreToken _token;

    public PreTokenizedString(@Nonnull String string, @Nonnull PreToken token) {
      _string = string;
      _token = token;
    }

    @Nonnull
    public String getString() {
      return _string;
    }

    @Nonnull
    public PreToken getToken() {
      return _token;
    }
  }

  private static class TokenizedString {
    @Nonnull private final String _string;
    @Nonnull private final Token _token;

    public TokenizedString(@Nonnull String string, @Nonnull Token token) {
      _string = string;
      _token = token;
    }

    @Nonnull
    public String getString() {
      return _string;
    }

    @Nonnull
    public Token getToken() {
      return _token;
    }
  }

  private static class RegexScore {
    private final int _index;
    private final double _score;

    public RegexScore(int index, double score) {
      _index = index;
      _score = score;
    }

    public int getIndex() {
      return _index;
    }

    public double getScore() {
      return _score;
    }
  }

  private final Collection<String> _nodes;
  private final Topology _topology;

  // a tokenized version of _chosenNode
  private List<TokenizedString> _tokens;
  // the regex produced by generalizing from _tokens
  private List<String> _regex;
  // the list of nodes that match _regex
  private List<String> _matchingNodes;

  // should role names be case sensitive or not?
  private boolean _caseSensitive;
  // indicates whether to compile a pattern as case sensitive or not
  private int _patternFlags;

  // the percentage of nodes that must match a regex for it to be used as
  // the base for determining roles
  private static final double REGEX_THRESHOLD = 0.5;
  // the percentage of nodes that must have an alphabetic string at some position,
  // in order for that position to be considered as a possible role
  private static final double GROUP_THRESHOLD = 0.5;
  // the minimum role score for a candidate role regex to be chosen
  private static final double ROLE_THRESHOLD = 0.9;

  private static final String ALPHABETIC_REGEX = "\\p{Alpha}";
  private static final String ALPHANUMERIC_REGEX = "\\p{Alnum}";
  private static final String DIGIT_REGEX = "\\p{Digit}";

  public InferRoles(Collection<String> nodes, Topology topology, boolean caseSensitive) {
    _nodes = ImmutableSortedSet.copyOf(nodes);
    _topology = topology;
    _caseSensitive = caseSensitive;
    _patternFlags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
  }

  public InferRoles(Collection<String> nodes, Topology topology) {
    this(nodes, topology, false);
  }

  // A node's name is first parsed into a sequence of simple "pretokens",
  // and then these pretokens are combined to form tokens.
  public enum PreToken {
    ALPHA_PLUS, // sequence of alphabetic characters
    DELIMITER, // sequence of non-alphanumeric characters
    DIGIT_PLUS; // sequence of digits

    public static PreToken charToPreToken(char c) {
      if (Character.isAlphabetic(c)) {
        return ALPHA_PLUS;
      } else if (Character.isDigit(c)) {
        return DIGIT_PLUS;
      } else {
        return DELIMITER;
      }
    }
  }

  public enum Token {
    ALPHA_PLUS,
    ALPHA_PLUS_DIGIT_PLUS,
    ALNUM_PLUS,
    DELIMITER,
    DIGIT_PLUS;

    public String tokenToRegex(String s) {
      switch (this) {
        case ALPHA_PLUS:
          return plus(ALPHABETIC_REGEX);
        case ALPHA_PLUS_DIGIT_PLUS:
          return plus(ALPHABETIC_REGEX) + plus(DIGIT_REGEX);
        case ALNUM_PLUS:
          return plus(ALPHANUMERIC_REGEX);
        case DELIMITER:
          return Pattern.quote(s);
        case DIGIT_PLUS:
          return plus(DIGIT_REGEX);
        default:
          throw new BatfishException("this case should be unreachable");
      }
    }
  }

  // some useful operations on regexes
  private static String plus(String s) {
    return s + "+";
  }

  private static String star(String s) {
    return s + "*";
  }

  private static String group(String s) {
    return "(" + s + ")";
  }

  public List<NodeRoleDimension> inferRoles() {

    if (_nodes.isEmpty()) {
      return ImmutableList.of();
    }

    boolean commonRegexFound = inferCommonRegex(_nodes);

    if (!commonRegexFound) {
      return ImmutableList.of();
    }

    // find the possible candidates that have a single role group
    List<List<String>> candidateRegexes = possibleRoleGroups();

    if (candidateRegexes.isEmpty()) {
      return ImmutableList.of();
    }

    // if there is at least one role group, let's fine the best role dimension according
    // to our metric, and also keep all the others.

    List<NodeRoleDimension> allDims;

    RegexScore bestRegexAndScore = findBestRegex(candidateRegexes);

    // select the regex of maximum score, if that score is above threshold
    Optional<NodeRoleDimension> optResult =
        toPrimaryNodeRoleDimensionIfAboveThreshold(bestRegexAndScore, candidateRegexes);
    boolean bestIsAboveThreshold = optResult.isPresent();
    if (bestIsAboveThreshold) {
      // remove the dimension that has been selected as the primary inferred role dimension,
      // so we do not duplicate it when creating the other role dimensions
      candidateRegexes.remove(bestRegexAndScore.getIndex());
    }

    // record the set of role "dimensions" for each node, which is a part of its name
    // that may indicate a useful grouping of nodes
    // (e.g., the node's function, location, device type, etc.)
    allDims = createRoleDimensions(candidateRegexes);

    if (bestIsAboveThreshold) {
      allDims.add(0, optResult.get());
    } else {

      // if no single role group is above threshold, we attempt to make the best role found so far
      // more specific.
      // NOTE: we could try to refine all possible roles we've considered, rather than
      // greedily only refining the best one, if the greedy approach fails often.

      // try adding a second group around any alphanumeric sequence in the regex;
      // now the role is a concatenation of the strings of both groups
      // NOTE: We could also consider just using the leading alphabetic portion of an alphanumeric
      // sequence as the second group, which would result in less specific groups and could
      // be appropriate for some naming schemes.

      candidateRegexes =
          possibleSecondRoleGroups(candidateRegexes.get(bestRegexAndScore.getIndex()));

      if (!candidateRegexes.isEmpty()) {
        // determine the best one according to our metric, even if it's below threshold
        allDims.add(
            0,
            toNodeRoleDimension(
                findBestRegex(candidateRegexes),
                candidateRegexes,
                NodeRoleDimension.AUTO_DIMENSION_PRIMARY));
      }
    }

    return allDims;
  }

  // try to identify a regex that most node names match
  private boolean inferCommonRegex(Collection<String> nodes) {
    for (int attempts = 0; attempts < 10; attempts++) {
      // pick a random node name, in order to find one with a common pattern
      // the node name that is used to infer a regex
      String chosenNode = Iterables.get(nodes, new Random().nextInt(nodes.size()));
      _tokens = tokenizeName(chosenNode);
      _regex =
          _tokens.stream()
              .map((p) -> p.getToken().tokenToRegex(p.getString()))
              .collect(Collectors.toList());
      Pattern p = Pattern.compile(String.join("", _regex), _patternFlags);
      _matchingNodes =
          nodes.stream().filter((node) -> p.matcher(node).matches()).collect(Collectors.toList());
      // keep this regex if it matches a sufficient fraction of node names; otherwise try again
      if ((double) _matchingNodes.size() / nodes.size() >= REGEX_THRESHOLD) {
        return true;
      }
    }
    return false;
  }

  // If delimiters (non-alphanumeric characters) are being used in the node names, we use them
  // to separate the different tokens.
  private static List<TokenizedString> preTokensToDelimitedTokens(
      List<PreTokenizedString> pretokens) {
    List<TokenizedString> tokens = new ArrayList<>();
    int size = pretokens.size();
    int i = 0;
    while (i < size) {
      StringBuilder chars = new StringBuilder(pretokens.get(i).getString());
      PreToken pt = pretokens.get(i).getToken();
      switch (pt) {
        case ALPHA_PLUS:
          // combine everything up to the next delimiter into a single alphanumeric token
          int next = i + 1;
          while (next < size && pretokens.get(next).getToken() != PreToken.DELIMITER) {
            chars.append(pretokens.get(next).getString());
            next++;
          }
          i = next - 1;
          tokens.add(new TokenizedString(chars.toString(), Token.ALNUM_PLUS));
          break;
        case DELIMITER:
          tokens.add(new TokenizedString(chars.toString(), Token.DELIMITER));
          break;
        case DIGIT_PLUS:
          tokens.add(new TokenizedString(chars.toString(), Token.DIGIT_PLUS));
          break;
        default:
          throw new BatfishException("Unknown pretoken " + pt);
      }
      i++;
    }
    return tokens;
  }

  // If delimiters (non-alphanumeric characters) are not being used in the node names, we treat
  // each consecutive string matching alpha+digit+ as a distinct token.
  private static List<TokenizedString> preTokensToUndelimitedTokens(
      List<PreTokenizedString> pretokens) {
    List<TokenizedString> tokens = new ArrayList<>();
    int size = pretokens.size();
    int i = 0;
    while (i < size) {
      String chars = pretokens.get(i).getString();
      PreToken pt = pretokens.get(i).getToken();
      switch (pt) {
        case ALPHA_PLUS:
          int next = i + 1;
          if (next >= size) {
            tokens.add(new TokenizedString(chars, Token.ALPHA_PLUS));
          } else {
            // the next token must be DIGIT_PLUS since we know there are no delimiters
            String bothChars = chars + pretokens.get(next).getString();
            tokens.add(new TokenizedString(bothChars, Token.ALPHA_PLUS_DIGIT_PLUS));
            i++;
          }
          break;
        case DIGIT_PLUS:
          tokens.add(new TokenizedString(chars, Token.DIGIT_PLUS));
          break;
        default:
          throw new BatfishException("Unexpected pretoken " + pt);
      }
      i++;
    }
    return tokens;
  }

  private static List<TokenizedString> tokenizeName(String name) {
    List<PreTokenizedString> pretokens = pretokenizeName(name);
    if (pretokens.stream().anyMatch((p) -> p.getToken() == PreToken.DELIMITER)) {
      return preTokensToDelimitedTokens(pretokens);
    } else {
      return preTokensToUndelimitedTokens(pretokens);
    }
  }

  // tokenizes a name into a sequence of pretokens defined by the PreToken enum above
  private static List<PreTokenizedString> pretokenizeName(String name) {
    List<PreTokenizedString> pattern = new ArrayList<>();
    char c = name.charAt(0);
    PreToken currPT = PreToken.charToPreToken(c);
    StringBuffer curr = new StringBuffer();
    curr.append(c);
    for (int i = 1; i < name.length(); i++) {
      c = name.charAt(i);
      PreToken newPT = PreToken.charToPreToken(c);
      if (newPT != currPT) {
        pattern.add(new PreTokenizedString(new String(curr), currPT));
        curr = new StringBuffer();
        currPT = newPT;
      }
      curr.append(c);
    }
    pattern.add(new PreTokenizedString(new String(curr), currPT));
    return pattern;
  }

  private static String regexTokensToRegex(List<String> tokens) {
    return String.join("", tokens);
  }

  // If for enough node names matching the identified regex,
  // a particular alphanumeric token starts with one or more alphabetic characters,
  // the string of initial alphabetic characters is considered a candidate for the role name.
  // This method returns all such candidates, each represented as a regex
  // with a single group indicating the role name; the regex is returned as a sequence
  // of tokens.
  private List<List<String>> possibleRoleGroups() {
    int numAll = _matchingNodes.size();
    List<List<String>> candidateRegexes = new ArrayList<>();
    for (int i = 0; i < _tokens.size(); i++) {
      switch (_tokens.get(i).getToken()) {
        case ALNUM_PLUS:
          List<String> regexCopy = new ArrayList<>(_regex);
          regexCopy.set(i, group(plus(ALPHABETIC_REGEX)) + star(ALPHANUMERIC_REGEX));
          Pattern newp = Pattern.compile(regexTokensToRegex(regexCopy), _patternFlags);
          int numMatches = 0;
          for (String node : _matchingNodes) {
            Matcher newm = newp.matcher(node);
            if (newm.matches()) {
              numMatches++;
            }
          }
          if ((double) numMatches / numAll >= GROUP_THRESHOLD) {
            candidateRegexes.add(regexCopy);
          }
          break;
        case ALPHA_PLUS_DIGIT_PLUS:
          List<String> regexCopy2 = new ArrayList<>(_regex);
          regexCopy2.set(i, group(plus(ALPHABETIC_REGEX)) + plus(DIGIT_REGEX));
          candidateRegexes.add(regexCopy2);
          break;
        default:
          break;
      }
    }
    return candidateRegexes;
  }

  private List<NodeRoleDimension> createRoleDimensions(List<List<String>> regexes) {

    List<NodeRoleDimension> result = new LinkedList<>();
    for (int i = 0; i < regexes.size(); i++) {
      String dimName = NodeRoleDimension.AUTO_DIMENSION_PREFIX + (i + 1);
      String regex = regexTokensToRegex(regexes.get(i));
      result.add(regexToNodeRoleDimension(regex, dimName));
    }
    return result;
  }

  private static List<List<String>> possibleSecondRoleGroups(List<String> tokens) {
    List<List<String>> candidateRegexes = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      // skip the token if it's a delimiter or the primary group
      if (token.startsWith("\\Q") || token.startsWith("(")) {
        continue;
      }
      List<String> regexCopy = new ArrayList<>(tokens);
      regexCopy.set(i, group(token));
      candidateRegexes.add(regexCopy);
    }
    return candidateRegexes;
  }

  private double computeRoleScore(String regex) {

    SortedMap<String, SortedSet<String>> nodeRolesMap = regexToNodeRolesMap(regex, _nodes);

    // produce a role-level topology and the list of nodes in each edge's source role
    // that have an edge to some node in the edge's target role
    SortedMap<RoleEdge, SortedSet<String>> roleEdges = new TreeMap<>();
    for (Edge e : _topology.getEdges()) {
      String n1 = e.getNode1();
      String n2 = e.getNode2();
      SortedSet<String> roles1 = nodeRolesMap.get(n1);
      SortedSet<String> roles2 = nodeRolesMap.get(n2);
      if (roles1 != null && roles2 != null && roles1.size() == 1 && roles2.size() == 1) {
        String role1 = roles1.first();
        String role2 = roles2.first();
        // ignore self-edges
        if (role1.equals(role2)) {
          continue;
        }
        RoleEdge redge = new RoleEdge(role1, role2);
        SortedSet<String> roleEdgeNodes = roleEdges.getOrDefault(redge, new TreeSet<>());
        roleEdgeNodes.add(n1);
        roleEdges.put(redge, roleEdgeNodes);
      }
    }

    int numEdges = roleEdges.size();
    if (numEdges == 0) {
      return 0.0;
    }

    // compute the "support" of each edge in the role-level topology:
    // the percentage of nodes playing the source role that have an edge
    // to a node in the target role.
    // the score of this regex is then the average support across all role edges
    SortedMap<String, SortedSet<String>> roleNodesMap = regexToRoleNodesMap(regex, _nodes);

    double supportSum = 0.0;
    for (Map.Entry<RoleEdge, SortedSet<String>> roleEdgeCount : roleEdges.entrySet()) {
      RoleEdge redge = roleEdgeCount.getKey();
      int count = roleEdgeCount.getValue().size();
      supportSum += (double) count / roleNodesMap.get(redge.getRole1()).size();
    }
    return supportSum / numEdges;
  }

  private SortedMap<String, SortedSet<String>> regexToRoleNodesMap(
      String regex, Collection<String> nodes) {
    SortedMap<String, SortedSet<String>> roleNodesMap = new TreeMap<>();
    Pattern pattern;
    try {
      pattern = Pattern.compile(regex, _patternFlags);
    } catch (PatternSyntaxException e) {
      throw new BatfishException("Supplied regex is not a valid Java regex: \"" + regex + "\"", e);
    }
    for (String node : nodes) {
      Matcher matcher = pattern.matcher(node);
      int numGroups = matcher.groupCount();
      if (matcher.matches()) {
        try {
          List<String> roleParts =
              IntStream.range(1, numGroups + 1)
                  .mapToObj(matcher::group)
                  .collect(Collectors.toList());
          String role = String.join("-", roleParts);
          if (!_caseSensitive) {
            role = role.toLowerCase();
          }
          SortedSet<String> currNodes = roleNodesMap.computeIfAbsent(role, k -> new TreeSet<>());
          currNodes.add(node);
        } catch (IndexOutOfBoundsException e) {
          throw new BatfishException(
              "Supplied regex does not contain "
                  + numGroups
                  + "groups: \""
                  + pattern.pattern()
                  + "\"",
              e);
        }
      }
    }
    return roleNodesMap;
  }

  // return a map from each node name to the set of roles that it plays
  private SortedMap<String, SortedSet<String>> regexToNodeRolesMap(
      String regex, Collection<String> allNodes) {

    SortedMap<String, SortedSet<String>> roleNodesMap = regexToRoleNodesMap(regex, allNodes);

    // invert the map from roles to nodes, to create a map from nodes to roles
    SortedMap<String, SortedSet<String>> nodeRolesMap = new TreeMap<>();

    roleNodesMap.forEach(
        (role, nodes) -> {
          for (String node : nodes) {
            SortedSet<String> nodeRoles = nodeRolesMap.computeIfAbsent(node, k -> new TreeSet<>());
            nodeRoles.add(role);
          }
        });

    return nodeRolesMap;
  }

  // the list of candidates must have at least one element
  private RegexScore findBestRegex(final List<List<String>> candidates) {
    // choose the candidate role regex with the maximal "role score"
    return IntStream.range(0, candidates.size())
        .mapToObj(i -> new RegexScore(i, computeRoleScore(regexTokensToRegex(candidates.get(i)))))
        .max(Comparator.comparingDouble(RegexScore::getScore))
        .orElseThrow(() -> new BatfishException("this exception should not be reachable"));
  }

  // the list of candidates must have at least one element
  private Optional<NodeRoleDimension> toPrimaryNodeRoleDimensionIfAboveThreshold(
      RegexScore bestRegexAndScore, List<List<String>> candidates) {
    if (bestRegexAndScore.getScore() >= ROLE_THRESHOLD) {
      NodeRoleDimension bestNRD =
          toNodeRoleDimension(
              bestRegexAndScore, candidates, NodeRoleDimension.AUTO_DIMENSION_PRIMARY);
      return Optional.of(bestNRD);
    } else {
      return Optional.empty();
    }
  }

  // the list of candidates must have at least one element
  private NodeRoleDimension toNodeRoleDimension(
      RegexScore bestRegexAndScore, List<List<String>> candidates, String dimName) {
    List<String> bestRegexTokens = candidates.get(bestRegexAndScore.getIndex());
    String bestRegex = regexTokensToRegex(bestRegexTokens);
    return regexToNodeRoleDimension(bestRegex, dimName);
  }

  // converts a regex containing one or more groups indicating roles into a NodeRoleDimension
  private NodeRoleDimension regexToNodeRoleDimension(String regex, String dimName) {
    return NodeRoleDimension.builder()
        .setName(dimName)
        .setType(Type.AUTO)
        .setRoleDimensionMappings(
            ImmutableList.of(new RoleDimensionMapping(regex, null, null, _caseSensitive)))
        .build();
  }
}
