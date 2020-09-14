/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.server.storage.file.lock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.netty.util.internal.ConcurrentSet;
import io.seata.common.exception.FrameworkException;
import io.seata.common.util.CollectionUtils;
import io.seata.core.exception.TransactionException;
import io.seata.core.lock.AbstractLocker;
import io.seata.core.lock.RowLock;
import io.seata.server.session.BranchSession;

/**
 * The type Memory locker.
 *
 * @author zhangsen
 */
public class FileLocker extends AbstractLocker {
    /**
     * BUCKET_PER_TABLE：用来定义每个 table 有多少个 bucket，目的是为了后续对同一个表加锁的时候减少竞争。
     */
    private static final int BUCKET_PER_TABLE = 128;
    /**
     * <pre>
     * resourceId tableName
     *            tableName bucketId
     *                      bucketId PK
     *                               PK transactionId
     *
     * LOCK_MAP：这个 Map 从定义上来看非常复杂，里里外外套了很多层 Map，这里用个表格具体说明一下：
     *
     * || 层数	              || key	                                         || value
     * -----------------------------------------------------------------------------------------
     * || 1- LOCK_MAP	      || resourceId（jdbcUrl）	                         || dbLockMap
     * || 2- dbLockMap	      || tableName （表名）	                             || tableLockMap
     * || 3- tableLockMap	  || PK.hashcode%Bucket （主键值的 hashcode%bucket）	 || bucketLockMap
     * || 4- bucketLockMap	  || PK	                                             || transactionId
     *
     *
     * 可以看见实际上的加锁在 bucketLockMap 这个 Map 中，这里具体的加锁方法比较简单就不作详细阐述，主要是逐步的找到 bucketLockMap ，
     * 然后将当前 transactionId 塞进去，如果这个主键当前有 TransactionId，那么比较是否是自己，如果不是则加锁失败。
     *
     * </pre>
     */
    private static final ConcurrentMap<String/* resourceId */
            , ConcurrentMap<String/* tableName */,
                    ConcurrentMap<Integer/* bucketId */, BucketLockMap>>>
        LOCK_MAP = new ConcurrentHashMap<>();

    /**
     * The Branch session.
     */
    protected BranchSession branchSession = null;

    /**
     * Instantiates a new Memory locker.
     *
     * @param branchSession the branch session
     */
    public FileLocker(BranchSession branchSession) {
        this.branchSession = branchSession;
    }

    @Override
    public boolean acquireLock(List<RowLock> rowLocks) {
        if (CollectionUtils.isEmpty(rowLocks)) {
            //no lock
            return true;
        }
        String resourceId = branchSession.getResourceId();
        long transactionId = branchSession.getTransactionId();

        ConcurrentMap<BucketLockMap, Set<String>> bucketHolder = branchSession.getLockHolder();
        ConcurrentMap<String, ConcurrentMap<Integer, BucketLockMap>> dbLockMap = LOCK_MAP.get(resourceId);
        if (dbLockMap == null) {
            LOCK_MAP.putIfAbsent(resourceId, new ConcurrentHashMap<>());
            dbLockMap = LOCK_MAP.get(resourceId);
        }

        for (RowLock lock : rowLocks) {
            String tableName = lock.getTableName();
            String pk = lock.getPk();
            ConcurrentMap<Integer, BucketLockMap> tableLockMap = dbLockMap.get(tableName);
            if (tableLockMap == null) {
                dbLockMap.putIfAbsent(tableName, new ConcurrentHashMap<>());
                tableLockMap = dbLockMap.get(tableName);
            }
            int bucketId = pk.hashCode() % BUCKET_PER_TABLE;
            BucketLockMap bucketLockMap = tableLockMap.get(bucketId);
            if (bucketLockMap == null) {
                tableLockMap.putIfAbsent(bucketId, new BucketLockMap());
                bucketLockMap = tableLockMap.get(bucketId);
            }
            Long previousLockTransactionId = bucketLockMap.get().putIfAbsent(pk, transactionId);
            if (previousLockTransactionId == null) {
                //No existing lock, and now locked by myself
                Set<String> keysInHolder = bucketHolder.get(bucketLockMap);
                if (keysInHolder == null) {
                    bucketHolder.putIfAbsent(bucketLockMap, new ConcurrentSet<>());
                    keysInHolder = bucketHolder.get(bucketLockMap);
                }
                keysInHolder.add(pk);
            } else if (previousLockTransactionId == transactionId) {
                // Locked by me before
                continue;
            } else {
                LOGGER.info("Global lock on [" + tableName + ":" + pk + "] is holding by " + previousLockTransactionId);
                try {
                    // Release all acquired locks.
                    branchSession.unlock();
                } catch (TransactionException e) {
                    throw new FrameworkException(e);
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean releaseLock(List<RowLock> rowLock) {
        if (CollectionUtils.isEmpty(rowLock)) {
            //no lock
            return true;
        }
        ConcurrentMap<BucketLockMap, Set<String>> lockHolder = branchSession.getLockHolder();
        if (lockHolder == null || lockHolder.size() == 0) {
            return true;
        }
        for (Map.Entry<BucketLockMap, Set<String>> entry : lockHolder.entrySet()) {
            BucketLockMap bucket = entry.getKey();
            Set<String> keys = entry.getValue();
            for (String key : keys) {
                // remove lock only if it locked by myself
                bucket.get().remove(key, branchSession.getTransactionId());
            }
        }
        lockHolder.clear();
        return true;
    }

    @Override
    public boolean isLockable(List<RowLock> rowLocks) {
        if (CollectionUtils.isEmpty(rowLocks)) {
            //no lock
            return true;
        }
        Long transactionId = rowLocks.get(0).getTransactionId();
        String resourceId = rowLocks.get(0).getResourceId();
        ConcurrentMap<String, ConcurrentMap<Integer, BucketLockMap>> dbLockMap = LOCK_MAP.get(resourceId);
        if (dbLockMap == null) {
            return true;
        }
        for (RowLock rowLock : rowLocks) {
            String xid = rowLock.getXid();
            String tableName = rowLock.getTableName();
            String pk = rowLock.getPk();

            ConcurrentMap<Integer, BucketLockMap> tableLockMap = dbLockMap.get(tableName);
            if (tableLockMap == null) {
                continue;
            }
            int bucketId = pk.hashCode() % BUCKET_PER_TABLE;
            BucketLockMap bucketLockMap = tableLockMap.get(bucketId);
            if (bucketLockMap == null) {
                continue;
            }
            Long lockingTransactionId = bucketLockMap.get().get(pk);
            if (lockingTransactionId == null || lockingTransactionId.longValue() == transactionId) {
                // Locked by me
                continue;
            } else {
                LOGGER.info("Global lock on [" + tableName + ":" + pk + "] is holding by " + lockingTransactionId);
                return false;
            }
        }
        return true;
    }

    @Override
    public void cleanAllLocks() {
        LOCK_MAP.clear();
    }

    /**
     * Because bucket lock map will be key of HashMap(lockHolder), however {@link ConcurrentHashMap} overwrites
     * {@link Object##hashCode()} and {@link Object##equals(Object)}, that leads to hash key conflict in lockHolder.
     * We define a {@link BucketLockMap} to hold the ConcurrentHashMap(bucketLockMap) and replace it as key of
     * HashMap(lockHolder).
     */
    public static class BucketLockMap {
        private final ConcurrentHashMap<String/* pk */, Long/* transactionId */> bucketLockMap
            = new ConcurrentHashMap<>();

        ConcurrentHashMap<String, Long> get() {
            return bucketLockMap;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }
    }
}
