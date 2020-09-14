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
package io.seata.server.lock;

import io.seata.core.exception.TransactionException;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;

/**
 * The interface Lock manager.
 * 大家知道数据库实现隔离级别主要是通过锁来实现的，同样的再分布式事务框架 Seata 中要实现隔离级别也需要通过锁。
 * 一般在数据库中数据库的隔离级别一共有四种：读未提交、读已提交、可重复读、串行化。
 * 在 Seata 中可以保证写的互斥，而读的隔离级别一般是未提交，但是提供了达到读已提交隔离的手段。
 * Lock 模块也就是 Seata 实现隔离级别的核心模块。在 Lock 模块中提供了一个接口用于管理锁：
 * @author sharajava
 */
public interface LockManager {

    /**
     * Acquire lock boolean.
     * acquireLock：
     *     用于对 BranchSession 加锁，这里虽然是传的分支事务 Session，实际上是对分支事务的资源加锁，成功返回 true。
     * @param branchSession the branch session
     * @return the boolean
     * @throws TransactionException the transaction exception
     */
    boolean acquireLock(BranchSession branchSession) throws TransactionException;

    /**
     * Un lock boolean.
     *
     * @param branchSession the branch session
     * @return the boolean
     * @throws TransactionException the transaction exception
     */
    boolean releaseLock(BranchSession branchSession) throws TransactionException;

    /**
     * Un lock boolean.
     *
     * @param globalSession the global session
     * @return the boolean
     * @throws TransactionException the transaction exception
     */
    boolean releaseGlobalSessionLock(GlobalSession globalSession) throws TransactionException;

    /**
     * Is lockable boolean.
     * isLockable：根据事务 ID，资源 ID，锁住的 Key 来查询是否已经加锁。
     * @param xid        the xid
     * @param resourceId the resource id
     * @param lockKey    the lock key
     * @return the boolean
     * @throws TransactionException the transaction exception
     */
    boolean isLockable(String xid, String resourceId, String lockKey) throws TransactionException;

    /**
     * Clean all locks.
     * • cleanAllLocks：清除所有的锁。
     * @throws TransactionException the transaction exception
     */
    void cleanAllLocks() throws TransactionException;

}
