datahub put --urn "urn:li:dataset:(urn:li:dataPlatform:hive,default.anpr_tf_devs,PROD)" --aspect customDataQualityRules --aspect-data scripts/data/dq_rule.json

datahub put --urn "urn:li:dataset:(urn:li:dataPlatform:hive,default.anpr_tf_devs,PROD)" --aspect dataContract --aspect-data scripts/data/data_contract.json
