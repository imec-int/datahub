package com.linkedin.datahub.graphql.types.FieldConstraintType;

import com.google.common.collect.ImmutableSet;
import com.linkedin.datahub.graphql.types.FieldConstraintType.mappers.FieldConstraintMapper;
import com.linkedin.entity.EntityResponse;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.template.StringArray;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.BrowsePath;
import com.linkedin.datahub.graphql.generated.BrowseResults;
import com.linkedin.datahub.graphql.generated.Entity;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.generated.FacetFilterInput;
import com.linkedin.datahub.graphql.generated.FieldConstraint;
import com.linkedin.datahub.graphql.resolvers.ResolverUtils;
import com.linkedin.datahub.graphql.types.BrowsableEntityType;
import com.linkedin.datahub.graphql.types.mappers.BrowsePathsMapper;
import com.linkedin.datahub.graphql.types.mappers.BrowseResultMapper;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.metadata.browse.BrowseResult;
import graphql.execution.DataFetcherResult;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.template.StringArray;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.AutoCompleteResults;
import com.linkedin.datahub.graphql.generated.BrowsePath;
import com.linkedin.datahub.graphql.generated.BrowseResults;
import com.linkedin.datahub.graphql.generated.Entity;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.generated.FacetFilterInput;
import com.linkedin.datahub.graphql.generated.GlossaryTerm;
import com.linkedin.datahub.graphql.generated.SearchResults;
import com.linkedin.datahub.graphql.resolvers.ResolverUtils;
import com.linkedin.datahub.graphql.types.BrowsableEntityType;
import com.linkedin.datahub.graphql.types.SearchableEntityType;
import com.linkedin.datahub.graphql.types.glossary.mappers.GlossaryTermMapper;
import com.linkedin.datahub.graphql.types.mappers.AutoCompleteResultsMapper;
import com.linkedin.datahub.graphql.types.mappers.BrowsePathsMapper;
import com.linkedin.datahub.graphql.types.mappers.BrowseResultMapper;
import com.linkedin.datahub.graphql.types.mappers.UrnSearchResultsMapper;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.metadata.browse.BrowseResult;
import com.linkedin.metadata.query.AutoCompleteResult;
import com.linkedin.metadata.search.SearchResult;
import graphql.execution.DataFetcherResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


import static com.linkedin.datahub.graphql.Constants.*;
import static com.linkedin.metadata.Constants.*;

public class FieldConstraintType implements BrowsableEntityType<FieldConstraint, String> {

  private final EntityClient _entityClient;

  private static final Set<String> FACET_FIELDS = ImmutableSet.of("");
  private static final Set<String> ASPECTS_TO_RESOLVE = ImmutableSet.of(
    FIELD_CONSTRAINT_KEY_ASPECT_NAME, FIELD_CONSTRAINT_SHAPE_ASPECT_NAME
  );

  public FieldConstraintType(EntityClient entityClient) {
    _entityClient = entityClient;
  }

  @Override
  public BrowseResults browse(@Nonnull List<String> path,
      @Nullable List<FacetFilterInput> filters,
      int start,
      int count,
      @Nonnull final QueryContext context) throws Exception {
    final Map<String, String> facetFilters = ResolverUtils.buildFacetFilters(filters, FACET_FIELDS);
    final String pathStr = path.size() > 0 ? BROWSE_PATH_DELIMITER + String.join(BROWSE_PATH_DELIMITER, path) : "";
    final BrowseResult result = _entityClient.browse(
        "fieldConstraint",
        pathStr,
        facetFilters,
        start,
        count,
        context.getAuthentication());
    return BrowseResultMapper.map(result);
  }

  @Nonnull
  @Override
  public List<BrowsePath> browsePaths(@Nonnull String urn, @Nonnull final QueryContext context) throws Exception {
    final StringArray result = _entityClient.getBrowsePaths(getFieldConstraintUrn(urn), context.getAuthentication());
    return BrowsePathsMapper.map(result);
  }

  static Urn getFieldConstraintUrn(String urnStr) {
    try {
      return Urn.createFromString(urnStr);
    } catch (URISyntaxException e) {
      throw new RuntimeException(String.format("Failed to retrieve glossary with urn %s, invalid urn", urnStr));
    }
  }

  @Override
  public EntityType type() {
    return EntityType.FIELD_CONSTRAINT;
  }

  @Override
  public Function<Entity, String> getKeyProvider() {
    return Entity::getUrn;
  }

  @Override
  public Class<FieldConstraint> objectClass() {
    return FieldConstraint.class;
  }

  @Override
  public List<DataFetcherResult<FieldConstraint>> batchLoad(final List<String> urns, final QueryContext context) {
    final List<Urn> glossaryTermUrns = urns.stream()
        .map(UrnUtils::getUrn)
        .collect(Collectors.toList());

//    return null;
    try {
      final Map<Urn, EntityResponse> glossaryTermMap = _entityClient.batchGetV2(FIELD_CONSTRAINT_ENTITY_NAME,
          new HashSet<>(glossaryTermUrns), ASPECTS_TO_RESOLVE, context.getAuthentication());

      final List<EntityResponse> gmsResults = new ArrayList<>();
      for (Urn urn : glossaryTermUrns) {
        gmsResults.add(glossaryTermMap.getOrDefault(urn, null));
      }
      return gmsResults.stream()
          .map(gmsGlossaryTerm ->
              gmsGlossaryTerm == null ? null
                  : DataFetcherResult.<FieldConstraint>newResult()
                      .data(FieldConstraintMapper.map(gmsGlossaryTerm))
                      .build())
          .collect(Collectors.toList());
    } catch (Exception e) {
      throw new RuntimeException("Failed to batch load GlossaryTerms", e);
    }
  }
}
