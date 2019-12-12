package org.broadinstitute.ddp.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.NoSuchElementException;

import org.broadinstitute.ddp.TxnAwareBaseTest;
import org.broadinstitute.ddp.constants.SqlConstants.ActivityInstanceTable;
import org.broadinstitute.ddp.util.GuidUtils;
import org.junit.BeforeClass;
import org.junit.Test;

public class DBUtilsTest extends TxnAwareBaseTest {

    private static UserDao userDao;
    private static ActivityInstanceDao activityInstanceDao;

    @BeforeClass
    public static void setup() {
        userDao = UserDaoFactory.createFromSqlConfig(sqlConfig);
        activityInstanceDao = new ActivityInstanceDao(null);
    }

    @Test
    public void testMatchesCodePattern() {
        assertTrue(DBUtils.matchesCodePattern("STABLE"));
        assertTrue(DBUtils.matchesCodePattern("stable"));
        assertTrue(DBUtils.matchesCodePattern("123"));
        assertTrue(DBUtils.matchesCodePattern("stable_CODE_123"));
        assertTrue(DBUtils.matchesCodePattern("stable-CODE-123"));
        assertFalse(DBUtils.matchesCodePattern(null));
        assertFalse(DBUtils.matchesCodePattern(""));
        assertFalse(DBUtils.matchesCodePattern("stable code"));
        assertFalse(DBUtils.matchesCodePattern("stable.code"));
        assertFalse(DBUtils.matchesCodePattern("stable\tcode"));
        assertFalse(DBUtils.matchesCodePattern("</stable>#code@!"));
    }

    @Test
    public void testUniqueUserGuid() throws SQLException {
        TransactionWrapper.withTxn(handle -> {
            String guid = DBUtils.uniqueUserGuid(handle);

            assertNotNull(guid);
            assertEquals(GuidUtils.USER_GUID_LENGTH, guid.length());

            Long id = userDao.getUserIdByGuid(handle, guid);
            assertNull(id);

            return null;
        });
    }

    @Test
    public void testUniqueUserHruid() throws SQLException {
        TransactionWrapper.useTxn(handle -> {
            String hruid = DBUtils.uniqueUserHruid(handle);

            assertNotNull(hruid);
            assertEquals(GuidUtils.HRUID_PREFIX.length() + GuidUtils.USER_HRUID_RANDOM_PART_LENGTH, hruid.length());

            Long id = userDao.getUserIdByHruid(handle, hruid);
            assertNull(id);
        });
    }

    @Test
    public void testUniqueStandardGuid() {
        TransactionWrapper.withTxn(handle -> {
            String guid = DBUtils.uniqueStandardGuid(handle,
                    ActivityInstanceTable.TABLE_NAME, ActivityInstanceTable.GUID);

            assertNotNull(guid);
            assertEquals(GuidUtils.STANDARD_GUID_LENGTH, guid.length());

            try {
                activityInstanceDao.getActivityInstanceIdByGuid(handle, guid);
                fail("Querying by new activity instance guid should be unsuccessful");
            } catch (NoSuchElementException e) {
                // ignored
            }

            return null;
        });
    }
}
