/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.transaction.jts;

import static com.sun.enterprise.config.serverbeans.ServerTags.KEYPOINT_INTERVAL;
import static com.sun.enterprise.config.serverbeans.ServerTags.RETRY_TIMEOUT_IN_SECONDS;
import static jakarta.transaction.Status.STATUS_NO_TRANSACTION;
import static java.util.Arrays.asList;
import static java.util.Collections.enumeration;
import static java.util.logging.Level.FINE;

import java.util.Hashtable;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.ServiceLocator;
import org.jvnet.hk2.annotations.Service;

import com.sun.enterprise.transaction.JavaEETransactionImpl;
import com.sun.enterprise.transaction.JavaEETransactionManagerSimplified;
import com.sun.enterprise.transaction.api.JavaEETransaction;
import com.sun.enterprise.transaction.api.JavaEETransactionManager;
import com.sun.enterprise.transaction.api.TransactionAdminBean;
import com.sun.enterprise.transaction.api.XAResourceWrapper;
import com.sun.enterprise.transaction.config.TransactionService;
import com.sun.enterprise.transaction.jts.recovery.GMSCallBack;
import com.sun.enterprise.transaction.jts.recovery.OracleXAResource;
import com.sun.enterprise.transaction.jts.recovery.SybaseXAResource;
import com.sun.enterprise.transaction.spi.JavaEETransactionManagerDelegate;
import com.sun.enterprise.transaction.spi.TransactionInternal;
import com.sun.enterprise.transaction.spi.TransactionalResource;
import com.sun.enterprise.util.i18n.StringManager;
import com.sun.jts.CosTransactions.Configuration;
import com.sun.jts.CosTransactions.DefaultTransactionService;
import com.sun.jts.CosTransactions.DelegatedRecoveryManager;
import com.sun.jts.CosTransactions.RWLock;
import com.sun.jts.CosTransactions.RecoveryManager;
import com.sun.jts.jta.TransactionManagerImpl;
import com.sun.jts.jta.TransactionServiceProperties;
import com.sun.logging.LogDomains;

import jakarta.inject.Inject;
import jakarta.resource.spi.XATerminator;
import jakarta.resource.spi.work.WorkException;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

/**
 ** Implementation of JavaEETransactionManagerDelegate that supports XA transactions with JTS.
 *
 * @author Marina Vatkina
 */
@Service
public class JavaEETransactionManagerJTSDelegate implements JavaEETransactionManagerDelegate, PostConstruct {

    // Use JavaEETransactionManagerSimplified logger and Sting Manager for Localization
    private static StringManager sm = StringManager.getManager(JavaEETransactionManagerSimplified.class);
    private final static ReadWriteLock lock = new ReadWriteLock();
    private static JavaEETransactionManagerJTSDelegate instance;

    @Inject
    private ServiceLocator serviceLocator;

    // An implementation of the JavaEETransactionManager that calls this object.
    private JavaEETransactionManager javaEETransactionManager;

    // An implementation of the JTA TransactionManager provided by JTS.
    private ThreadLocal<TransactionManager> transactionManagerLocal = new ThreadLocal<>();

    private Hashtable globalTransactions;
    private Hashtable<String, XAResourceWrapper> xaresourcewrappers = new Hashtable<String, XAResourceWrapper>();

    private Logger _logger;

    private boolean lao = true;

    private volatile TransactionManager transactionManagerImpl;
    private TransactionService transactionService;

    public JavaEETransactionManagerJTSDelegate() {
        globalTransactions = new Hashtable();
    }

    public void postConstruct() {
        if (javaEETransactionManager != null) {
            // JavaEETransactionManager has been already initialized
            javaEETransactionManager.setDelegate(this);
        }

        _logger = LogDomains.getLogger(JavaEETransactionManagerSimplified.class, LogDomains.JTA_LOGGER);
        initTransactionProperties();

        setInstance(this);
    }

    public boolean useLAO() {
        return lao;
    }

    public void setUseLAO(boolean b) {
        lao = b;
    }

    /**
     * An XA transaction commit
     */
    public void commitDistributedTransaction() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
            SecurityException, IllegalStateException, SystemException {
        _logger.log(FINE, "TM: commit");

        validateTransactionManager();
        TransactionManager transactionManager = transactionManagerLocal.get();
        Object obj = transactionManager.getTransaction(); // monitoring object

        JavaEETransactionManagerSimplified javaEETMS = (JavaEETransactionManagerSimplified) javaEETransactionManager;

        boolean success = false;
        if (javaEETMS.isInvocationStackEmpty()) {
            try {
                transactionManager.commit();
                success = true;
            } catch (HeuristicMixedException e) {
                success = true;
                throw e;
            } finally {
                javaEETMS.monitorTxCompleted(obj, success);
            }
        } else {
            try {
                javaEETMS.setTransactionCompeting(true);
                transactionManager.commit();
                success = true;
            } catch (HeuristicMixedException e) {
                success = true;
                throw e;
            } finally {
                javaEETMS.monitorTxCompleted(obj, success);
                javaEETMS.setTransactionCompeting(false);
            }
        }
    }

    /**
     * An XA transaction rollback
     */
    public void rollbackDistributedTransaction() throws IllegalStateException, SecurityException, SystemException {
        _logger.log(FINE, "TM: rollback");
        validateTransactionManager();

        TransactionManager transactionManager = transactionManagerLocal.get();
        Object obj = transactionManager.getTransaction(); // monitoring object

        JavaEETransactionManagerSimplified javaEETMS = (JavaEETransactionManagerSimplified) javaEETransactionManager;

        try {
            if (javaEETMS.isInvocationStackEmpty()) {
                transactionManager.rollback();
            } else {
                try {
                    javaEETMS.setTransactionCompeting(true);
                    transactionManager.rollback();
                } finally {
                    javaEETMS.setTransactionCompeting(false);
                }
            }
        } finally {
            javaEETMS.monitorTxCompleted(obj, false);
        }
    }

    public int getStatus() throws SystemException {
        JavaEETransaction tx = javaEETransactionManager.getCurrentTransaction();
        int status = STATUS_NO_TRANSACTION;

        TransactionManager transactionManager = transactionManagerLocal.get();
        if (tx != null) {
            status = tx.getStatus();
        } else if (transactionManager != null) {
            status = transactionManager.getStatus();
        }

        if (_logger.isLoggable(FINE)) {
            _logger.log(FINE, "TM: status: " + JavaEETransactionManagerSimplified.getStatusAsString(status));
        }

        return status;
    }

    public Transaction getTransaction() throws SystemException {
        JavaEETransaction javaEETransaction = javaEETransactionManager.getCurrentTransaction();
        if (_logger.isLoggable(FINE)) {
            _logger.log(FINE, "TM: getTransaction: tx=" + javaEETransaction + ", tm=" + transactionManagerLocal.get());
        }

        if (javaEETransaction != null) {
            return javaEETransaction;
        }

        // Check for a JTS imported tx
        TransactionInternal jtsTx = null;
        TransactionManager tm = transactionManagerLocal.get();
        if (tm != null) {
            jtsTx = (TransactionInternal) tm.getTransaction();
        }

        if (jtsTx == null) {
            return null;
        }

        // Check if this JTS Transaction was previously active in this JVM (possible for distributed loopbacks).
        javaEETransaction = (JavaEETransaction) globalTransactions.get(jtsTx);
        if (_logger.isLoggable(FINE)) {
            _logger.log(FINE, "TM: getTransaction: tx=" + javaEETransaction + ", jtsTx=" + jtsTx);
        }

        if (javaEETransaction == null) {
            javaEETransaction = ((JavaEETransactionManagerSimplified) javaEETransactionManager).createImportedTransaction(jtsTx);
            globalTransactions.put(jtsTx, javaEETransaction);
        }

        javaEETransactionManager.setCurrentTransaction(javaEETransaction); // associate tx with thread
        return javaEETransaction;
    }

    public JavaEETransaction getJavaEETransaction(Transaction transaction) {
        if (transaction instanceof JavaEETransaction) {
            return (JavaEETransaction) transaction;
        }

        return (JavaEETransaction) globalTransactions.get(transaction);
    }

    public boolean enlistDistributedNonXAResource(Transaction transaction, TransactionalResource transactionalResource)
            throws RollbackException, IllegalStateException, SystemException {
        if (useLAO()) {
            if (((JavaEETransactionManagerSimplified) javaEETransactionManager).resourceEnlistable(transactionalResource)) {
                XAResource res = transactionalResource.getXAResource();
                boolean result = transaction.enlistResource(res);
                if (!transactionalResource.isEnlisted()) {
                    transactionalResource.enlistedInTransaction(transaction);
                }

                return result;
            }

            return true;
        }

        throw new IllegalStateException(sm.getString("enterprise_distributedtx.nonxa_usein_jts"));
    }

    public boolean enlistLAOResource(Transaction transaction, TransactionalResource transactionalResource)
            throws RollbackException, IllegalStateException, SystemException {
        if (transaction instanceof JavaEETransaction) {
            JavaEETransaction eeTransaction = (JavaEETransaction) transaction;
            ((JavaEETransactionManagerSimplified) javaEETransactionManager).startJTSTx(eeTransaction);

            // If transaction contains a NonXA and no LAO, convert the existing Non XA to LAO
            if (useLAO()) {
                if (transactionalResource != null && (eeTransaction.getLAOResource() == null)) {
                    eeTransaction.setLAOResource(transactionalResource);
                    if (transactionalResource.isTransactional()) {
                        return transaction.enlistResource(transactionalResource.getXAResource());
                    }
                }
            }
            return true;
        }

        // Should not be called
        return false;
    }

    public void setRollbackOnlyDistributedTransaction() throws IllegalStateException, SystemException {
        _logger.log(FINE, "TM: setRollbackOnly");

        validateTransactionManager();
        transactionManagerLocal.get().setRollbackOnly();
    }

    public Transaction suspend(JavaEETransaction tx) throws SystemException {
        if (tx != null) {
            if (!tx.isLocalTx()) {
                suspendXA();
            }

            javaEETransactionManager.setCurrentTransaction(null);
            return tx;
        }

        if (transactionManagerLocal.get() != null) {
            return suspendXA(); // probably a JTS imported tx
        }

        return null;
    }

    public void resume(Transaction tx) throws InvalidTransactionException, IllegalStateException, SystemException {
        _logger.log(FINE, "TM: resume");

        if (transactionManagerImpl != null) {
            setTransactionManager();
            transactionManagerLocal.get().resume(tx);
        }
    }

    public void removeTransaction(Transaction transaction) {
        globalTransactions.remove(transaction);
    }

    public int getOrder() {
        return 3;
    }

    public void setTransactionManager(JavaEETransactionManager eeTransactionManager) {
        javaEETransactionManager = eeTransactionManager;
        _logger = ((JavaEETransactionManagerSimplified) javaEETransactionManager).getLogger();
    }

    public TransactionInternal startJTSTx(JavaEETransaction transaction, boolean isAssociatedTimeout)
            throws RollbackException, IllegalStateException, SystemException {
        setTransactionManager();

        JavaEETransactionImpl eeTransactionImpl = (JavaEETransactionImpl) transaction;
        try {
            if (isAssociatedTimeout) {
                // calculate the timeout for the transaction, this is required as the local tx
                // is getting converted to a global transaction
                int timeout = eeTransactionImpl.cancelTimerTask();
                int newtimeout = (int) ((System.currentTimeMillis() - eeTransactionImpl.getStartTime()) / 1000);
                newtimeout = (timeout - newtimeout);
                beginJTS(newtimeout);
            } else {
                beginJTS(((JavaEETransactionManagerSimplified) javaEETransactionManager).getEffectiveTimeout());
            }
        } catch (NotSupportedException ex) {
            throw new RuntimeException(sm.getString("enterprise_distributedtx.lazy_transaction_notstarted"), ex);
        }

        TransactionInternal jtsTx = (TransactionInternal) transactionManagerLocal.get().getTransaction();
        globalTransactions.put(jtsTx, eeTransactionImpl);

        return jtsTx;
    }

    public void initRecovery(boolean force) {
        TransactionServiceProperties.initRecovery(force);
    }

    public void recover(XAResource[] resourceList) {
        setTransactionManager();
        TransactionManagerImpl.recover(enumeration(asList(resourceList)));
    }

    public void release(Xid xid) throws WorkException {
        setTransactionManager();
        TransactionManagerImpl.release(xid);
    }

    public void recreate(Xid xid, long timeout) throws WorkException {
        setTransactionManager();
        TransactionManagerImpl.recreate(xid, timeout);
    }

    public XATerminator getXATerminator() {
        setTransactionManager();
        return TransactionManagerImpl.getXATerminator();
    }

    private Transaction suspendXA() throws SystemException {
        _logger.log(FINE, "TM: suspend");

        validateTransactionManager();
        return transactionManagerLocal.get().suspend();
    }

    private void validateTransactionManager() throws IllegalStateException {
        if (transactionManagerLocal.get() == null) {
            throw new IllegalStateException(sm.getString("enterprise_distributedtx.transaction_notactive"));
        }
    }

    private void setTransactionManager() {
        if (_logger.isLoggable(FINE)) {
            _logger.log(FINE, "TM: setTransactionManager: tm=" + transactionManagerLocal.get());
        }

        if (transactionManagerImpl == null) {
            transactionManagerImpl = TransactionManagerImpl.getTransactionManagerImpl();
        }

        if (transactionManagerLocal.get() == null) {
            transactionManagerLocal.set(transactionManagerImpl);
        }
    }

    public XAResourceWrapper getXAResourceWrapper(String clName) {
        XAResourceWrapper xaResourceWrapper = xaresourcewrappers.get(clName);

        if (xaResourceWrapper == null) {
            return null;
        }

        return xaResourceWrapper.getInstance();
    }

    public void handlePropertyUpdate(String name, Object value) {
        if (name.equals(KEYPOINT_INTERVAL)) {
            Configuration.setKeypointTrigger(Integer.parseInt((String) value, 10));

        } else if (name.equals(RETRY_TIMEOUT_IN_SECONDS)) {
            Configuration.setCommitRetryVar((String) value);
        }
    }

    public boolean recoverIncompleteTx(boolean delegated, String logPath, XAResource[] xaresArray) throws Exception {
        if (!delegated) {
            RecoveryManager.recoverIncompleteTx(xaresArray);
            return true;
        }

        return DelegatedRecoveryManager.delegated_recover(logPath, xaresArray);
    }

    public void beginJTS(int timeout) throws NotSupportedException, SystemException {
        TransactionManagerImpl transactionManagerImpl = (TransactionManagerImpl) transactionManagerLocal.get();
        transactionManagerImpl.begin(timeout);

        ((JavaEETransactionManagerSimplified) javaEETransactionManager).monitorTxBegin(transactionManagerImpl.getTransaction());
    }

    public boolean supportsXAResource() {
        return true;
    }

    public void initTransactionProperties() {
        if (serviceLocator != null) {
            transactionService = serviceLocator.getService(TransactionService.class, ServerEnvironment.DEFAULT_INSTANCE_NAME);

            if (transactionService != null) {
                String value = transactionService.getPropertyValue("use-last-agent-optimization");
                if (value != null && "false".equals(value)) {
                    setUseLAO(false);
                    if (_logger.isLoggable(FINE))
                        _logger.log(FINE, "TM: LAO is disabled");
                }

                value = transactionService.getPropertyValue("oracle-xa-recovery-workaround");
                if (value == null || "true".equals(value)) {
                    xaresourcewrappers.put("oracle.jdbc.xa.client.OracleXADataSource", new OracleXAResource());
                }

                if (Boolean.parseBoolean(transactionService.getPropertyValue("sybase-xa-recovery-workaround"))) {
                    xaresourcewrappers.put("com.sybase.jdbc2.jdbc.SybXADataSource", new SybaseXAResource());
                }

                if (Boolean.parseBoolean(transactionService.getAutomaticRecovery())) {
                    // If recovery on server startup is set, initialize other properties as well
                    Properties props = TransactionServiceProperties.getJTSProperties(serviceLocator, false);
                    DefaultTransactionService.setServerName(props);

                    if (Boolean.parseBoolean(transactionService.getPropertyValue("delegated-recovery"))) {
                        // Register GMS notification callback
                        if (_logger.isLoggable(FINE))
                            _logger.log(FINE, "TM: Registering for GMS notification callback");

                        int waitTime = 60;
                        value = transactionService.getPropertyValue("wait-time-before-recovery-insec");
                        if (value != null) {
                            try {
                                waitTime = Integer.parseInt(value);
                            } catch (Exception e) {
                                _logger.log(Level.WARNING, "error_wait_time_before_recovery", e);
                            }
                        }
                        new GMSCallBack(waitTime, serviceLocator);
                    }
                }
            }
        }
    }

    /**
     * Return true if a "null transaction context" was received from the client. See EJB2.0 spec section 19.6.2.1. A null tx
     * context has no Coordinator objref. It indicates that the client had an active tx but the client container did not
     * support tx interop.
     */
    public boolean isNullTransaction() {
        try {
            return com.sun.jts.pi.InterceptorImpl.isTxCtxtNull();
        } catch (Exception ex) {
            // sometimes JTS throws an EmptyStackException if isTxCtxtNull
            // is called outside of any CORBA invocation.
            return false;
        }
    }

    public TransactionAdminBean getTransactionAdminBean(Transaction t) throws jakarta.transaction.SystemException {
        TransactionAdminBean tBean = null;
        if (t instanceof com.sun.jts.jta.TransactionImpl) {
            String id = ((com.sun.jts.jta.TransactionImpl) t).getTransactionId();
            long startTime = ((com.sun.jts.jta.TransactionImpl) t).getStartTime();
            long elapsedTime = System.currentTimeMillis() - startTime;
            String status = JavaEETransactionManagerSimplified.getStatusAsString(t.getStatus());

            JavaEETransactionImpl tran = (JavaEETransactionImpl) globalTransactions.get(t);
            if (tran != null) {
                tBean = ((JavaEETransactionManagerSimplified) javaEETransactionManager).getTransactionAdminBean(tran);

                // Override with JTS values
                tBean.setIdentifier(t);
                tBean.setId(id);
                tBean.setStatus(status);
                tBean.setElapsedTime(elapsedTime);
                if (tBean.getComponentName() == null) {
                    tBean.setComponentName("unknown");
                }
            } else {
                tBean = new TransactionAdminBean(t, id, status, elapsedTime, "unknown", null);
            }
        } else {
            tBean = ((JavaEETransactionManagerSimplified) javaEETransactionManager).getTransactionAdminBean(t);
        }
        return tBean;
    }

    /**
     * {@inheritDoc}
     */
    public String getTxLogLocation() {
        if (Configuration.getServerName() == null) {
            // If server name is null, the properties were not fully initialized
            Properties props = TransactionServiceProperties.getJTSProperties(serviceLocator, false);
            DefaultTransactionService.setServerName(props);
        }

        return Configuration.getDirectory(Configuration.LOG_DIRECTORY, Configuration.JTS_SUBDIRECTORY, new int[1]);
    }

    /**
     * {@inheritDoc}
     */
    public void registerRecoveryResourceHandler(XAResource xaResource) {
        ResourceRecoveryManagerImpl.registerRecoveryResourceHandler(xaResource);
    }

    public Lock getReadLock() {
        return lock;
    }

    public void acquireWriteLock() {
        if (com.sun.jts.CosTransactions.AdminUtil.isFrozenAll()) {
            // multiple freezes will hang this thread, therefore just return
            return;
        }
        com.sun.jts.CosTransactions.AdminUtil.freezeAll();

        /**
         * XXX Do we need to check twice? XXX ** if(lock.isWriteLocked()){ //multiple freezes will hang this thread, therefore
         * just return return; } XXX Do we need to check twice? XXX
         **/

        lock.acquireWriteLock();
    }

    public void releaseWriteLock() {
        if (com.sun.jts.CosTransactions.AdminUtil.isFrozenAll()) {
            com.sun.jts.CosTransactions.AdminUtil.unfreezeAll();
        }

        /**
         * XXX Do we need to check twice? XXX ** if(lock.isWriteLocked()){ lock.releaseWriteLock(); } XXX Do we need to check
         * twice? XXX
         **/

        lock.releaseWriteLock();
    }

    public boolean isWriteLocked() {
        return com.sun.jts.CosTransactions.AdminUtil.isFrozenAll();
    }

    public static JavaEETransactionManagerJTSDelegate getInstance() {
        return instance;
    }

    private static void setInstance(JavaEETransactionManagerJTSDelegate new_instance) {
        if (instance == null) {
            instance = new_instance;
        }
    }

    public void initXA() {
        setTransactionManager();
    }

    private static class ReadWriteLock implements Lock {
        private static final RWLock freezeLock = new RWLock();

        public void lock() {
            freezeLock.acquireReadLock();
        }

        public void unlock() {
            freezeLock.releaseReadLock();
        }

        private void acquireWriteLock() {
            freezeLock.acquireWriteLock();
        }

        private void releaseWriteLock() {
            freezeLock.releaseWriteLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public boolean tryLock() {
            throw new UnsupportedOperationException();
        }

        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }
}
