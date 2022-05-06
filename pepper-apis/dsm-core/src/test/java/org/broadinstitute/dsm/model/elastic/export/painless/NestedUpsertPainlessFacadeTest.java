package org.broadinstitute.dsm.model.elastic.export.painless;

import static org.junit.Assert.*;

import org.apache.lucene.search.join.ScoreMode;
import org.broadinstitute.dsm.db.KitRequestShipping;
import org.broadinstitute.dsm.db.dto.ddp.instance.DDPInstanceDto;
import org.broadinstitute.dsm.statics.DBConstants;
import org.broadinstitute.dsm.statics.ESObjectConstants;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.junit.Before;
import org.junit.Test;

public class NestedUpsertPainlessFacadeTest {
    UpsertPainlessFacade upsertPainlessFacade;

    @Before
    public void setUp() {
        KitRequestShipping kitRequestShipping = new KitRequestShipping();
        kitRequestShipping.setKitLabel("KIT_LABEL");
        DDPInstanceDto ddpInstanceDto = new DDPInstanceDto.Builder()
                .build();
        upsertPainlessFacade = UpsertPainlessFacade.of(
                DBConstants.DDP_KIT_REQUEST_ALIAS, kitRequestShipping, ddpInstanceDto,
                ESObjectConstants.KIT_LABEL, ESObjectConstants.KIT_LABEL,"KIT_LABEL"
                );
    }

    @Test
    public void buildScriptBuilder() {
        assertTrue(upsertPainlessFacade.buildScriptBuilder() instanceof NestedScriptBuilder);
    }

    @Test
    public void buildQueryBuilder() {
        QueryBuilder queryBuilder = upsertPainlessFacade.buildQueryBuilder();
        String path = "dsm.kitRequestShipping";
        String fieldName = String.join(DBConstants.ALIAS_DELIMITER, path, "kitLabel");
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        TermQueryBuilder kitLabelTerm = new TermQueryBuilder(fieldName, "KIT_LABEL");
        boolQueryBuilder.must(kitLabelTerm);
        NestedQueryBuilder expected = new NestedQueryBuilder(path, boolQueryBuilder, ScoreMode.Avg);
        assertEquals(expected, queryBuilder);
    }
}