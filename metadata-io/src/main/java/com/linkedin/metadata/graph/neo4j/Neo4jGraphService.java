package com.linkedin.metadata.graph.neo4j;

import com.codahale.metrics.Timer;
import com.datahub.util.Statement;
import com.datahub.util.exception.RetryLimitReached;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.graph.Edge;
import com.linkedin.metadata.graph.EntityLineageResult;
import com.linkedin.metadata.graph.GraphFilters;
import com.linkedin.metadata.graph.GraphService;
import com.linkedin.metadata.graph.LineageDirection;
import com.linkedin.metadata.graph.LineageRelationship;
import com.linkedin.metadata.graph.LineageRelationshipArray;
import com.linkedin.metadata.graph.RelatedEntitiesResult;
import com.linkedin.metadata.graph.RelatedEntity;
import com.linkedin.metadata.key.SchemaFieldKey;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.RelationshipFieldSpec;
import com.linkedin.metadata.models.extractor.FieldExtractor;
import com.linkedin.metadata.models.registry.LineageRegistry;
import com.linkedin.metadata.query.filter.Condition;
import com.linkedin.metadata.query.filter.ConjunctiveCriterionArray;
import com.linkedin.metadata.query.filter.CriterionArray;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.query.filter.RelationshipDirection;
import com.linkedin.metadata.query.filter.RelationshipFilter;
import com.linkedin.metadata.utils.EntityKeyUtils;
import com.linkedin.metadata.utils.metrics.MetricUtils;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.time.StopWatch;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.internal.InternalRelationship;


@Slf4j
public class Neo4jGraphService implements GraphService {

  private static final int MAX_TRANSACTION_RETRY = 3;
  private final LineageRegistry _lineageRegistry;
  private final Driver _driver;
  private SessionConfig _sessionConfig;

  public Neo4jGraphService(@Nonnull LineageRegistry lineageRegistry, @Nonnull Driver driver) {
    this(lineageRegistry, driver, SessionConfig.defaultConfig());
  }

  public Neo4jGraphService(@Nonnull LineageRegistry lineageRegistry, @Nonnull Driver driver,
      @Nonnull SessionConfig sessionConfig) {
    this._lineageRegistry = lineageRegistry;
    this._driver = driver;
    this._sessionConfig = sessionConfig;
  }

  @Override
  public LineageRegistry getLineageRegistry() {
    return _lineageRegistry;
  }

  @Override
  public void addEdge(@Nonnull final Edge edge) {
    if (true) {
      return;
    }

    log.debug(
        String.format("Adding Edge source: %s, destination: %s, type: %s", edge.getSource(), edge.getDestination(),
            edge.getRelationshipType()));

    final String sourceType = edge.getSource().getEntityType();
    final String destinationType = edge.getDestination().getEntityType();

    final List<Statement> statements = new ArrayList<>();

    // Add/Update source & destination node first
    statements.addAll(getOrInsertNode(edge.getSource(), null, null));
    statements.addAll(getOrInsertNode(edge.getDestination(), null, null));

    // Add/Update relationship
    final String mergeRelationshipTemplate =
        "MATCH (source:%s {urn: $sourceUrn}),(destination:%s {urn: $destinationUrn}) MERGE (source)-[r:%s]->(destination) SET r = $properties";
    final String statement =
        String.format(mergeRelationshipTemplate, sourceType, destinationType, edge.getRelationshipType());

    final Map<String, Object> paramsMerge = new HashMap<>();
    paramsMerge.put("sourceUrn", edge.getSource().toString());
    paramsMerge.put("destinationUrn", edge.getDestination().toString());
    paramsMerge.put("properties", new HashMap<>());

    statements.add(buildStatement(statement, paramsMerge));

    executeStatements(statements);
  }

  @Override
  public void addEntity(@Nonnull Urn urn, @Nonnull RecordTemplate aspect, @Nonnull AspectSpec aspectSpec) {
    executeStatements(getOrInsertNode(urn, aspect, aspectSpec));
  }

  @Override
  public void upsertEdge(final Edge edge) {
    throw new UnsupportedOperationException("Upsert edge not supported by Neo4JGraphService at this time.");
  }

  @Override
  public void removeEdge(final Edge edge) {
    throw new UnsupportedOperationException("Remove edge not supported by Neo4JGraphService at this time.");
  }

  @Nonnull
  @Override
  public EntityLineageResult getLineage(@Nonnull Urn entityUrn, @Nonnull LineageDirection direction,
      GraphFilters graphFilters, int offset, int count, int maxHops) {
    log.debug(String.format("Neo4j getLineage maxHops = %d", maxHops));

    final String statement = generateLineageStatement(entityUrn, direction, graphFilters, maxHops);

    List<Record> neo4jResult =
        statement != null ? runQuery(buildStatement(statement, new HashMap<>())).list() : new ArrayList<>();

    // It is possible to have more than 1 path from node A to node B in the graph and previous query returns all the paths.
    // We convert the List into Map with only the shortest paths. "item.get(i).size()" is the path size between two nodes in relation.
    // The key for mapping is the destination node as the source node is always the same, and it is defined by parameter.
    neo4jResult = neo4jResult.stream()
        .collect(Collectors.toMap(item -> item.values().get(2).asNode().get("urn").asString(), Function.identity(),
            (item1, item2) -> item1.get(1).size() < item2.get(1).size() ? item1 : item2))
        .values()
        .stream()
        .collect(Collectors.toList());

    LineageRelationshipArray relations = new LineageRelationshipArray();
    neo4jResult.stream().skip(offset).limit(count).forEach(item -> {
      String urn = item.values().get(2).asNode().get("urn").asString();
      String relationType = ((InternalRelationship) item.get(1).asList().get(0)).type();
      int numHops = item.get(1).size();
      try {
        relations.add(
            new LineageRelationship().setEntity(Urn.createFromString(urn)).setType(relationType).setDegree(numHops));
      } catch (URISyntaxException ignored) {
        log.warn(String.format("Can't convert urn = %s, Error = %s", urn, ignored.getMessage()));
      }
    });

    EntityLineageResult result = new EntityLineageResult().setStart(offset)
        .setCount(relations.size())
        .setRelationships(relations)
        .setTotal(neo4jResult.size());

    log.debug(String.format("Neo4j getLineage results = %s", result));
    return result;
  }

  private String generateLineageStatement(@Nonnull Urn entityUrn, @Nonnull LineageDirection direction,
      GraphFilters graphFilters, int maxHops) {
    final String multiHopTemplateDirect =
        "MATCH shortestPath((a {urn: '%s'})-[r:%s*1..%d]->(b)) WHERE (b:%s) AND b.urn <> '%s' RETURN a,r,b";
    final String multiHopTemplateIndirect =
        "MATCH shortestPath((a {urn: '%s'})<-[r:%s*1..%d]-(b)) WHERE (b:%s) AND b.urn <> '%s' RETURN a,r,b";

    List<LineageRegistry.EdgeInfo> edgesToFetch =
        getLineageRegistry().getLineageRelationships(entityUrn.getEntityType(), direction);

    String upstreamRel = edgesToFetch.stream()
        .filter(item -> item.getDirection() == RelationshipDirection.OUTGOING)
        .map(item -> item.getType())
        .collect(Collectors.joining("|"));
    String dowStreamRel = edgesToFetch.stream()
        .filter(item -> item.getDirection() == RelationshipDirection.INCOMING)
        .map(item -> item.getType())
        .collect(Collectors.joining("|"));

    final String allowedEntityTypes = String.join(" OR b:", graphFilters.getAllowedEntityTypes());

    final String statementDirect =
        String.format(multiHopTemplateDirect, entityUrn, upstreamRel, maxHops, allowedEntityTypes, entityUrn);
    final String statementIndirect =
        String.format(multiHopTemplateIndirect, entityUrn, dowStreamRel, maxHops, allowedEntityTypes, entityUrn);

    String statement = null;
    if (upstreamRel.length() > 0 && dowStreamRel.length() > 0) {
      statement = statementDirect + " UNION " + statementIndirect;
    } else if (upstreamRel.length() > 0) {
      statement = statementDirect;
    } else if (dowStreamRel.length() > 0) {
      statement = statementIndirect;
    }
    return statement;
  }

  @Nonnull
  public RelatedEntitiesResult findRelatedEntities(@Nullable final List<String> sourceTypes,
      @Nonnull final Filter sourceEntityFilter, @Nullable final List<String> destinationTypes,
      @Nonnull final Filter destinationEntityFilter, @Nonnull final List<String> relationshipTypes,
      @Nonnull final RelationshipFilter relationshipFilter, final int offset, final int count) {

    log.debug(String.format("Finding related Neo4j nodes sourceType: %s, sourceEntityFilter: %s, destinationType: %s, ",
        sourceTypes, sourceEntityFilter, destinationTypes) + String.format(
        "destinationEntityFilter: %s, relationshipTypes: %s, relationshipFilter: %s, ", destinationEntityFilter,
        relationshipTypes, relationshipFilter) + String.format("offset: %s, count: %s", offset, count));

    if (sourceTypes != null && sourceTypes.isEmpty() || destinationTypes != null && destinationTypes.isEmpty()) {
      return new RelatedEntitiesResult(offset, 0, 0, Collections.emptyList());
    }

    final String srcCriteria = filterToCriteria(sourceEntityFilter).trim();
    final String destCriteria = filterToCriteria(destinationEntityFilter).trim();
    final String edgeCriteria = relationshipFilterToCriteria(relationshipFilter);

    final RelationshipDirection relationshipDirection = relationshipFilter.getDirection();

    String matchTemplate = "MATCH (src %s)-[r%s %s]-(dest %s)%s";
    if (relationshipDirection == RelationshipDirection.INCOMING) {
      matchTemplate = "MATCH (src %s)<-[r%s %s]-(dest %s)%s";
    } else if (relationshipDirection == RelationshipDirection.OUTGOING) {
      matchTemplate = "MATCH (src %s)-[r%s %s]->(dest %s)%s";
    }

    final String returnNodes =
        String.format("RETURN dest, type(r)"); // Return both related entity and the relationship type.
    final String returnCount = "RETURN count(*)"; // For getting the total results.

    String relationshipTypeFilter = "";
    if (relationshipTypes.size() > 0) {
      relationshipTypeFilter = ":" + StringUtils.join(relationshipTypes, "|");
    }

    String whereClause = computeEntityTypeWhereClause(sourceTypes, destinationTypes);

    // Build Statement strings
    String baseStatementString =
        String.format(matchTemplate, srcCriteria, relationshipTypeFilter, edgeCriteria, destCriteria, whereClause);

    log.info(baseStatementString);

    final String resultStatementString =
        String.format("%s %s SKIP $offset LIMIT $count", baseStatementString, returnNodes);
    final String countStatementString = String.format("%s %s", baseStatementString, returnCount);

    // Build Statements
    final Statement resultStatement =
        new Statement(resultStatementString, ImmutableMap.of("offset", offset, "count", count));
    final Statement countStatement = new Statement(countStatementString, Collections.emptyMap());

    // Execute Queries
    final List<RelatedEntity> relatedEntities = runQuery(resultStatement).list(
        record -> new RelatedEntity(record.values().get(1).asString(), // Relationship Type
            record.values().get(0).asNode().get("urn").asString())); // Urn TODO: Validate this works against Neo4j.
    final int totalCount = runQuery(countStatement).single().get(0).asInt();
    return new RelatedEntitiesResult(offset, relatedEntities.size(), totalCount, relatedEntities);
  }

  private String computeEntityTypeWhereClause(@Nonnull final List<String> sourceTypes,
      @Nonnull final List<String> destinationTypes) {
    String whereClause = "";

    Boolean hasSourceTypes = sourceTypes != null && !sourceTypes.isEmpty();
    Boolean hasDestTypes = destinationTypes != null && !destinationTypes.isEmpty();
    if (hasSourceTypes && hasDestTypes) {
      whereClause = String.format(" WHERE %s AND %s",
          sourceTypes.stream().map(type -> "src:" + type).collect(Collectors.joining(" OR ")),
          destinationTypes.stream().map(type -> "dest:" + type).collect(Collectors.joining(" OR ")));
    } else if (hasSourceTypes) {
      whereClause = String.format(" WHERE %s",
          sourceTypes.stream().map(type -> "src:" + type).collect(Collectors.joining(" OR ")));
    } else if (hasDestTypes) {
      whereClause = String.format(" WHERE %s",
          destinationTypes.stream().map(type -> "dest:" + type).collect(Collectors.joining(" OR ")));
    }
    return whereClause;
  }

  public void removeNode(@Nonnull final Urn urn) {

    log.debug(String.format("Removing Neo4j node with urn: %s", urn));

    // also delete any relationship going to or from it
    final String matchTemplate = "MATCH (node {urn: $urn}) DETACH DELETE node";
    final String statement = String.format(matchTemplate);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    runQuery(buildStatement(statement, params)).consume();
  }

  public void removeEdgesFromNode(@Nonnull final Urn urn, @Nonnull final List<String> relationshipTypes,
      @Nonnull final RelationshipFilter relationshipFilter) {

    log.debug(
        String.format("Removing Neo4j edge types from node with urn: %s, types: %s, filter: %s", urn, relationshipTypes,
            relationshipFilter));

    // also delete any relationship going to or from it
    final RelationshipDirection relationshipDirection = relationshipFilter.getDirection();

    String matchTemplate = "MATCH (src {urn: $urn})-[r%s]-(dest) DELETE r";
    if (relationshipDirection == RelationshipDirection.INCOMING) {
      matchTemplate = "MATCH (src {urn: $urn})<-[r%s]-(dest) DELETE r";
    } else if (relationshipDirection == RelationshipDirection.OUTGOING) {
      matchTemplate = "MATCH (src {urn: $urn})-[r%s]->(dest) DELETE r";
    }

    String relationshipTypeFilter = "";
    if (relationshipTypes.size() > 0) {
      relationshipTypeFilter = ":" + StringUtils.join(relationshipTypes, "|");
    }
    final String statement = String.format(matchTemplate, relationshipTypeFilter);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    runQuery(buildStatement(statement, params)).consume();
  }

  public void removeNodesMatchingLabel(@Nonnull String labelPattern) {
    log.debug(String.format("Removing Neo4j nodes matching label %s", labelPattern));
    final String matchTemplate = "MATCH (n) WHERE any(l IN labels(n) WHERE l=~'%s') DETACH DELETE n";
    final String statement = String.format(matchTemplate, labelPattern);

    final Map<String, Object> params = new HashMap<>();

    runQuery(buildStatement(statement, params)).consume();
  }

  @Override
  public void configure() {
    // Do nothing
  }

  @Override
  public void clear() {
    removeNodesMatchingLabel(".*");
  }

  @VisibleForTesting
  public void wipe() {
    runQuery(new Statement("MATCH (n) DETACH DELETE n", Map.of())).consume();
  }

  // visible for testing
  @Nonnull
  Statement buildStatement(@Nonnull String queryTemplate, @Nonnull Map<String, Object> params) {
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      String k = entry.getKey();
      Object v = entry.getValue();
      params.put(k, toPropertyValue(v));
    }
    return new Statement(queryTemplate, params);
  }

  @Nonnull
  private Object toPropertyValue(@Nonnull Object obj) {
    if (obj instanceof Urn) {
      return obj.toString();
    }
    return obj;
  }

  @AllArgsConstructor
  @Data
  private static final class ExecutionResult {
    private long tookMs;
    private int retries;
  }

  /**
   * Executes a list of statements with parameters in one transaction.
   *
   * @param statements List of statements with parameters to be executed in order
   */
  private synchronized ExecutionResult executeStatements(@Nonnull List<Statement> statements) {
    int retry = 0;
    final StopWatch stopWatch = new StopWatch();
    stopWatch.start();
    Exception lastException;
    try (final Session session = _driver.session(_sessionConfig)) {
      do {
        try {
          session.writeTransaction(tx -> {
            for (Statement statement : statements) {
              tx.run(statement.getCommandText(), statement.getParams());
            }
            return 0;
          });
          lastException = null;
          break;
        } catch (Neo4jException e) {
          lastException = e;
        }
      } while (++retry <= MAX_TRANSACTION_RETRY);
    }

    if (lastException != null) {
      throw new RetryLimitReached(
          "Failed to execute Neo4j write transaction after " + MAX_TRANSACTION_RETRY + " retries", lastException);
    }

    stopWatch.stop();
    return new ExecutionResult(stopWatch.getTime(), retry);
  }

  /**
   * Runs a query statement with parameters and return StatementResult.
   *
   * @param statement a statement with parameters to be executed
   * @return list of elements in the query result
   */
  @Nonnull
  private Result runQuery(@Nonnull Statement statement) {
    log.debug(String.format("Running Neo4j query %s", statement.toString()));
    try (Timer.Context ignored = MetricUtils.timer(this.getClass(), "runQuery").time()) {
      return _driver.session(_sessionConfig).run(statement.getCommandText(), statement.getParams());
    }
  }

  @Nonnull
  private static String toCriterionWhereString(@Nonnull String key, @Nonnull Object value) {
    if (ClassUtils.isPrimitiveOrWrapper(value.getClass())) {
      return key + " = " + value;
    }

    return key + " = \"" + value.toString() + "\"";
  }

  // Returns "key:value" String, if value is not primitive, then use toString() and double quote it
  @Nonnull
  private static String toCriterionString(@Nonnull String key, @Nonnull Object value) {
    if (ClassUtils.isPrimitiveOrWrapper(value.getClass())) {
      return key + ":" + value;
    }

    return key + ":\"" + value.toString() + "\"";
  }

  /**
   * Converts {@link RelationshipFilter} to neo4j query criteria, filter criterion condition requires to be EQUAL.
   *
   * @param filter Query relationship filter
   * @return Neo4j criteria string
   */
  @Nonnull
  private static String relationshipFilterToCriteria(@Nonnull RelationshipFilter filter) {
    return disjunctionToCriteria(filter.getOr());
  }

  /**
   * Converts {@link Filter} to neo4j query criteria, filter criterion condition requires to be EQUAL.
   *
   * @param filter Query Filter
   * @return Neo4j criteria string
   */
  @Nonnull
  private static String filterToCriteria(@Nonnull Filter filter) {
    return disjunctionToCriteria(filter.getOr());
  }

  private static String disjunctionToCriteria(final ConjunctiveCriterionArray disjunction) {
    if (disjunction.size() > 1) {
      // TODO: Support disjunctions (ORs).
      throw new UnsupportedOperationException("Neo4j query filter only supports 1 set of conjunction criteria");
    }
    final CriterionArray criterionArray = disjunction.size() > 0 ? disjunction.get(0).getAnd() : new CriterionArray();
    return criterionToString(criterionArray);
  }

  /**
   * Converts {@link CriterionArray} to neo4j query string.
   *
   * @param criterionArray CriterionArray in a Filter
   * @return Neo4j criteria string
   */
  @Nonnull
  private static String criterionToString(@Nonnull CriterionArray criterionArray) {
    if (!criterionArray.stream().allMatch(criterion -> Condition.EQUAL.equals(criterion.getCondition()))) {
      throw new RuntimeException("Neo4j query filter only support EQUAL condition " + criterionArray);
    }

    final StringJoiner joiner = new StringJoiner(",", "{", "}");

    criterionArray.forEach(criterion -> joiner.add(toCriterionString(criterion.getField(), criterion.getValue())));

    return joiner.length() <= 2 ? "" : joiner.toString();
  }

  /**
   * Gets Node based on Urn, if not exist, creates placeholder node.
   */
  @Nonnull
  private List<Statement> getOrInsertNode(@Nonnull Urn urn, RecordTemplate aspect, AspectSpec aspectSpec) {
    List<Statement> statements = new ArrayList<>();
    final String nodeType = urn.getEntityType();

    final String mergeTemplate = "MERGE (node:%s {urn: $urn})\n";
    final String statement = String.format(mergeTemplate, nodeType);
    StringBuilder ingestQuery = new StringBuilder(statement);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    Map<String, Object> relationshipMap = null;
    if (aspect != null) {
      if (aspectSpec != null) {
        Map<RelationshipFieldSpec, List<Object>> extractedFields =
            FieldExtractor.extractFields(aspect, aspectSpec.getRelationshipFieldSpecs());
        // make a nested map based on the path segments of the key of extractdFields
        relationshipMap = new HashMap<>();
        for (Map.Entry<RelationshipFieldSpec, List<Object>> entry : extractedFields.entrySet()) {
          RelationshipFieldSpec relationshipFieldSpec = entry.getKey();
          List<String> pathSegments = relationshipFieldSpec.getPath().getPathComponents();
          Map<String, Object> currentMap = relationshipMap;
          for (int i = 0; i < pathSegments.size() - 1; i += 1) {
            String pathSegment = pathSegments.get(i);
            if (!currentMap.containsKey(pathSegment)) {
              currentMap.put(pathSegment, new HashMap<String, Object>());
            }
            currentMap = (Map<String, Object>) currentMap.get(pathSegment);
          }
          currentMap.put(pathSegments.get(pathSegments.size() - 1), entry);
        }
      }
      Map<String, Object> dataMap = aspect.data();
      if (dataMap.containsKey("fields")) {
        for (Map<String, Object> entry : (List<Map<String, Object>>) dataMap.get("fields")) {
          String path = (String) entry.get("fieldPath");
          final SchemaFieldKey key = new SchemaFieldKey().setParent(urn).setFieldPath(path);
          Urn fieldUrn = EntityKeyUtils.convertEntityKeyToUrn(key, Constants.SCHEMA_FIELD_ENTITY_NAME);
          entry.put("urn", fieldUrn.toString());
        }
      }

      if (aspect.schema().getName().equals("PropertyShape")) {
        rdfInit();
        String shape = (String) dataMap.get("shape");
        Map<String, Object> opts = new HashMap<>();
        opts.put("shape", shape);
        Statement s = new Statement("CALL n10s.rdf.import.inline($shape, \"Turtle\")", opts);
        statements.add(s);
      } else {
        params.putAll(buildMergeQuery(ingestQuery, dataMap, relationshipMap, aspect.schema().getName(), "node", true, 0));
      }
    }
    ingestQuery.append("RETURN node\n");
    statements.add( new Statement(ingestQuery.toString(), params));
    return statements;
  }

  private boolean rdfReady = false;
  private void rdfInit() {
    if (rdfReady) {
      return;
    }
//    statements.add(new Statement("CALL n10s.graphconfig.drop;", Collections.emptyMap()));
//    statements.add(new Statement("CALL n10s.graphconfig.init();", Collections.emptyMap()));
//    statements.add(new Statement("CREATE CONSTRAINT n10s_unique_uri IF NOT EXISTS ON (r:Resource)\n" + "ASSERT r.uri IS UNIQUE;", Collections.emptyMap()));
    Exception lastException;
    int retry = 0;
    try (final Session session = _driver.session(_sessionConfig)) {
      do {
        try {
          session.run("CALL n10s.graphconfig.init()");
          session.run("CREATE CONSTRAINT n10s_unique_uri IF NOT EXISTS ON (r:Resource)\n" + "ASSERT r.uri IS UNIQUE;");
          lastException = null;
          break;
        } catch (Neo4jException e) {
          lastException = e;
        }
      } while (++retry <= MAX_TRANSACTION_RETRY);
    }

    if (lastException != null) {
      throw new RetryLimitReached(
          "Failed to execute Neo4j write transaction after " + MAX_TRANSACTION_RETRY + " retries", lastException);
    }
    rdfReady = true;
  }

  private static void debugPrintMap(String label, Map<String, Object> map) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(baos);
    MapUtils.debugPrint(ps, label, map);
    log.warn(baos.toString());
  }

  // TODO: This is a temporary solution to support nested aspect ingestion. We should use a better way to handle nested aspect ingestion.
  // TODO: all the nested fields that were not updated should be removed from the graph to ensure the graph is consistent with the data.
  // TODO: add outgoing relationship from the nested aspect to the relevant entity

  /**
   * Builds a query that either constructs a LPG sub-graph or updates an existing it. The query is build recursively on
   * the basis of MERGE statements
   * @param ingestQuery A stringbuilder which will be used to construct the query, must contain the query that inserts the root node of the LPG
   * @param dataMap the map containing the aspect data
   * @param relationshipMap the map containing the relationship data
   * @param propertyName the name of the property that is being processed
   * @param parentNodeIdentifier the identifier of the parent node
   * @param root whether the property is a root property
   * @param seq the sequence number of the property in case it is part of a list
   * @return a map containing the parameters that need to be passed to the query when it is executed
   */
  private static Map<String, Object> buildMergeQuery(StringBuilder ingestQuery, Map<String, Object> dataMap,
      Map<String, Object> relationshipMap, String propertyName, String parentNodeIdentifier, boolean root, int seq) {

    String nodeIdentifier = parentNodeIdentifier + "_" + propertyName.replace('.', '_') + "_" + seq;
    final String mergeTemplate;
    if (root) {
      mergeTemplate = "MERGE (%s)<-[:ASPECT_OF { name: $name_%s, seq: %d}]-(%s:%s) SET %s = $data_%s\n";
    } else {
      mergeTemplate = "MERGE (%s)<-[:PROPERTY_OF { name: $name_%s, seq: %d}]-(%s:%s) SET %s = $data_%s\n";
    }
    final String statement = String.format(mergeTemplate, parentNodeIdentifier, nodeIdentifier, seq, nodeIdentifier,
        propertyName.replace(".", "_"), nodeIdentifier, nodeIdentifier);
    ingestQuery.append(statement);
    // split dataMap into two maps, one for primitives or list of primitives and the other one for objects
    Map<String, Object> primitiveMap = dataMap.entrySet()
        .stream()
        .filter(entry -> isPrimitiveOrListOfPrimitives(entry.getValue()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    Map<String, Object> objectMap = dataMap.entrySet()
        .stream()
        .filter(entry -> !isPrimitiveOrListOfPrimitives(entry.getValue()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    Map<String, Object> otherParams = objectMap.entrySet()
        .stream()
        .flatMap(entry -> buildMergeQueryRecurse(ingestQuery, relationshipMap, nodeIdentifier, entry))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    Iterator<Map.Entry<String, Object>> iter = primitiveMap.entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry<String, Object> entry = iter.next();
      String key = entry.getKey();
      Object primitive = entry.getValue();
      if (relationshipMap == null || !relationshipMap.containsKey(key)) {
        continue;
      }
      iter.remove();
      if (primitive instanceof List) {
        List<Object> list = (List<Object>) primitive;
        for (int i = 0; i < list.size(); i += 1) {
          Map<String, Object> subMap = (Map<String, Object>) relationshipMap.get(key);
          Map.Entry<RelationshipFieldSpec, List<Object>> relationshipSpec =
              (Map.Entry<RelationshipFieldSpec, List<Object>>) subMap.get(FieldExtractor.getArrayWildcard());
          establishEdge(ingestQuery, otherParams, nodeIdentifier, (String) list.get(i), relationshipSpec,
              key + "_" + i);
        }
      } else {
        Map.Entry<RelationshipFieldSpec, List<Object>> relationshipSpec =
            (Map.Entry<RelationshipFieldSpec, List<Object>>) relationshipMap.get(key);
        establishEdge(ingestQuery, otherParams, nodeIdentifier, (String) primitive, relationshipSpec, key + "_" + 0);
      }
    }

    otherParams.put("name_" + nodeIdentifier, propertyName);
    otherParams.put("data_" + nodeIdentifier, primitiveMap);

    return otherParams;
  }

  /**
   * This function is constructed in such a way that, if the endpoint of the relationship already exists (i.e. it is an
   * already ingested entity) then that node is retrieved, otherwise it is constructed.
   */
  private static void establishEdge(StringBuilder ingestQuery, Map<String, Object> otherParams,
      String nodeIdentifier, String endpointUrn, Map.Entry<RelationshipFieldSpec, List<Object>> relationshipEntry,
      String seq) {
    final String relationshipEndpointIdentifier = "rel_end_urn_" + nodeIdentifier + "_" + seq;
    final String relationshipEndpointStatement =
        String.format("MERGE (%s {urn: $%s})\n", relationshipEndpointIdentifier, relationshipEndpointIdentifier);
    ingestQuery.append(relationshipEndpointStatement);
    otherParams.put(relationshipEndpointIdentifier, endpointUrn);

    final String relationshipStatement =
        String.format("MERGE (%s)-[:%s]->(%s)\n", nodeIdentifier, relationshipEntry.getKey().getRelationshipName(),
            relationshipEndpointIdentifier);
    ingestQuery.append(relationshipStatement);
  }

  /**
   * This is a helper function which helps buildMergeQuery to recurse into the objectMap
   * @return a map containing the parameters that need to be passed to the query when it is executed
   */
  private static Stream<Map.Entry<String, Object>> buildMergeQueryRecurse(StringBuilder ingestQuery,
      Map<String, Object> relationshipMap, String nodeIdentifier, Map.Entry<String, Object> entry) {
    String key = entry.getKey();
    Object value = entry.getValue();
    Map<String, Object> relationship = null;
    if (value instanceof List) {
      if (relationshipMap != null && relationshipMap.containsKey(key)) {
        Map<String, Object> temp = (Map<String, Object>) relationshipMap.get(key);
        if (temp.containsKey(FieldExtractor.getArrayWildcard())) {
          relationship = (Map<String, Object>) temp.get(FieldExtractor.getArrayWildcard());
        }
      }
      List<Map<String, Object>> valueList = (List<Map<String, Object>>) value;
      Map<String, Object> temp = new HashMap<>();
      for (int i = 0; i < valueList.size(); i++) {
        temp.putAll(buildMergeQuery(ingestQuery, valueList.get(i), relationship, key, nodeIdentifier, false, i));
      }
      return temp.entrySet().stream();
    } else {
      if (relationshipMap != null && relationshipMap.containsKey(key)) {
        relationship = (Map<String, Object>) relationshipMap.get(key);
      }
      return buildMergeQuery(ingestQuery, (Map<String, Object>) value, relationship, key, nodeIdentifier, false,
          0).entrySet().stream();
    }
  }

  /**
   * This function checks if the object is a primitive or a list of primitives. It is used to determine if the object
   * can be stored as in the neo4j database or if it needs to be further processed.
   */
  private static boolean isPrimitiveOrListOfPrimitives(Object object) {
    if (ClassUtils.isPrimitiveOrWrapper(object.getClass())) {
      return true;
    }
    if (object instanceof String) {
      return true;
    }
    if (!(object instanceof List)) {
      return false;
    }
    return ((List) object).stream().allMatch(e -> !(e instanceof List) && isPrimitiveOrListOfPrimitives(e));
  }

  @Override
  public boolean supportsMultiHop() {
    return true;
  }
}
