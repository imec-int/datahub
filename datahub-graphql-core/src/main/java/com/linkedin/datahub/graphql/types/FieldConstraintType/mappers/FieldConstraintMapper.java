package com.linkedin.datahub.graphql.types.FieldConstraintType.mappers;


import com.linkedin.common.DataPlatformInstance;
import com.linkedin.common.Deprecation;
import com.linkedin.common.Embed;
import com.linkedin.common.GlobalTags;
import com.linkedin.common.GlossaryTerms;
import com.linkedin.common.InstitutionalMemory;
import com.linkedin.common.Ownership;
import com.linkedin.common.Siblings;
import com.linkedin.common.Status;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.DataMap;
import com.linkedin.datahub.graphql.generated.Container;
import com.linkedin.datahub.graphql.generated.DataPlatform;
import com.linkedin.datahub.graphql.generated.Dataset;
import com.linkedin.datahub.graphql.generated.DatasetEditableProperties;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.generated.FabricType;
import com.linkedin.datahub.graphql.generated.FieldConstraint;
import com.linkedin.datahub.graphql.generated.GlossaryTerm;
import com.linkedin.datahub.graphql.types.common.mappers.DataPlatformInstanceAspectMapper;
import com.linkedin.datahub.graphql.types.common.mappers.DeprecationMapper;
import com.linkedin.datahub.graphql.types.common.mappers.EmbedMapper;
import com.linkedin.datahub.graphql.types.common.mappers.InstitutionalMemoryMapper;
import com.linkedin.datahub.graphql.types.common.mappers.OwnershipMapper;
import com.linkedin.datahub.graphql.types.common.mappers.SiblingsMapper;
import com.linkedin.datahub.graphql.types.common.mappers.StatusMapper;
import com.linkedin.datahub.graphql.types.common.mappers.CustomPropertiesMapper;
import com.linkedin.datahub.graphql.types.common.mappers.UpstreamLineagesMapper;
import com.linkedin.datahub.graphql.types.common.mappers.util.MappingHelper;
import com.linkedin.datahub.graphql.types.common.mappers.util.SystemMetadataUtils;
import com.linkedin.datahub.graphql.types.domain.DomainAssociationMapper;
import com.linkedin.datahub.graphql.types.glossary.GlossaryTermUtils;
import com.linkedin.datahub.graphql.types.glossary.mappers.GlossaryTermInfoMapper;
import com.linkedin.datahub.graphql.types.glossary.mappers.GlossaryTermMapper;
import com.linkedin.datahub.graphql.types.glossary.mappers.GlossaryTermPropertiesMapper;
import com.linkedin.datahub.graphql.types.glossary.mappers.GlossaryTermsMapper;
import com.linkedin.datahub.graphql.types.mappers.ModelMapper;
import com.linkedin.datahub.graphql.types.tag.mappers.GlobalTagsMapper;
import com.linkedin.dataset.DatasetDeprecation;
import com.linkedin.dataset.DatasetProperties;
import com.linkedin.dataset.EditableDatasetProperties;
import com.linkedin.dataset.UpstreamLineage;
import com.linkedin.dataset.ViewProperties;
import com.linkedin.domain.Domains;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.EnvelopedAspectMap;
import com.linkedin.glossary.GlossaryTermInfo;
import com.linkedin.metadata.key.DatasetKey;
import com.linkedin.metadata.key.GlossaryTermKey;
import com.linkedin.refinedtypes.PropertyShape;
import com.linkedin.schema.EditableSchemaMetadata;
import com.linkedin.schema.SchemaMetadata;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import com.linkedin.refinedtypes.FieldConstraintKey;

import static com.linkedin.metadata.Constants.*;
public class FieldConstraintMapper implements ModelMapper<EntityResponse, FieldConstraint> {

  public static final FieldConstraintMapper INSTANCE = new FieldConstraintMapper();

  public static FieldConstraint map(@Nonnull final EntityResponse entityResponse) {
    return INSTANCE.apply(entityResponse);
  }

  @Override
  public FieldConstraint apply(@Nonnull final EntityResponse entityResponse) {
    FieldConstraint result = new FieldConstraint();
//    Urn entityUrn = entityResponse.getUrn();

    result.setUrn(entityResponse.getUrn().toString());
    result.setType(EntityType.FIELD_CONSTRAINT);
    final String legacyName = GlossaryTermUtils.getGlossaryTermName(entityResponse.getUrn().getId());

    EnvelopedAspectMap aspectMap = entityResponse.getAspects();
    MappingHelper<FieldConstraint> mappingHelper = new MappingHelper<>(aspectMap, result);
    mappingHelper.mapToResult(FIELD_CONSTRAINT_KEY_ASPECT_NAME, this::mapFieldConstraintKey);
//    mappingHelper.mapToResult(FIELD_CONSTRAINT_SHAPE_ASPECT_NAME, (fieldConstraint, dataMap) ->
//        fieldConstra
//    );
//    mappingHelper.mapToResult(GLOSSARY_TERM_INFO_ASPECT_NAME, (glossaryTerm, dataMap) ->
//        glossaryTerm.setGlossaryTermInfo(GlossaryTermInfoMapper.map(new GlossaryTermInfo(dataMap), entityUrn)));
//    mappingHelper.mapToResult(GLOSSARY_TERM_INFO_ASPECT_NAME, (glossaryTerm, dataMap) ->
//        glossaryTerm.setProperties(GlossaryTermPropertiesMapper.map(new GlossaryTermInfo(dataMap), entityUrn)));
//    mappingHelper.mapToResult(OWNERSHIP_ASPECT_NAME, (glossaryTerm, dataMap) ->
//        glossaryTerm.setOwnership(OwnershipMapper.map(new Ownership(dataMap), entityUrn)));
//    mappingHelper.mapToResult(DOMAINS_ASPECT_NAME, this::mapDomains);
//    mappingHelper.mapToResult(DEPRECATION_ASPECT_NAME, (glossaryTerm, dataMap) ->
//        glossaryTerm.setDeprecation(DeprecationMapper.map(new Deprecation(dataMap))));
//    mappingHelper.mapToResult(INSTITUTIONAL_MEMORY_ASPECT_NAME, (dataset, dataMap) ->
//        dataset.setInstitutionalMemory(InstitutionalMemoryMapper.map(new InstitutionalMemory(dataMap))));

    // If there's no name property, resort to the legacy name computation.
//    if (result.getGlossaryTermInfo() != null && result.getGlossaryTermInfo().getName() == null) {
//      result.getGlossaryTermInfo().setName(legacyName);
//    }
//    if (result.getProperties() != null && result.getProperties().getName() == null) {
//      result.getProperties().setName(legacyName);
//    }
    return mappingHelper.getResult();
  }

  private void mapFieldConstraintKey(@Nonnull FieldConstraint fieldConstraint, @Nonnull DataMap dataMap) {
    FieldConstraintKey fieldConstraintKey = new FieldConstraintKey(dataMap);
    fieldConstraint.setName(getFieldConstraintName(fieldConstraintKey.getName()));
    fieldConstraint.setHierarchicalName(fieldConstraintKey.getName());
  }

  public static String getFieldConstraintName(String hierarchicalName) {
    if (hierarchicalName.contains(".")) {
      String[] nodes = hierarchicalName.split(Pattern.quote("."));
      return nodes[nodes.length - 1];
    }
    return hierarchicalName;
  }
}
